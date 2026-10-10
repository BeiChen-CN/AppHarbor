package com.app.market.data.repository

import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.StringPreferenceKey
import com.app.market.data.remote.fdroid.fdroidStableId
import com.app.market.data.remote.releases.ReleaseApi
import com.app.market.data.remote.releases.ReleaseApkInspector
import com.app.market.data.remote.releases.ReleaseAsset
import com.app.market.data.remote.releases.ReleaseRepositoryRef
import com.app.market.data.remote.releases.parseReleaseRepository
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPart
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.model.update.ManualUpdateStatus
import com.app.market.domain.repository.InstalledPackagesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Public release sources; package/repository associations survive process death. */
internal class ReleaseSourceRepository(
    private val api: ReleaseApi,
    private val inspector: ReleaseApkInspector,
    private val preferences: PreferencesDataSource,
    private val installed: InstalledPackagesRepository,
    private val cpuArchitecture: String = "arm64-v8a,armeabi-v7a",
) {
    private data class Resolved(val detail: AppDetail, val asset: ReleaseAsset, val fetchedAt: Long)
    private val mutex = Mutex()
    private val cache = mutableMapOf<ReleaseRepositoryRef, Resolved>()
    private val refsById = mutableMapOf<Pair<AppSource, Long>, ReleaseRepositoryRef>()

    suspend fun search(source: AppSource, keyword: String, page: Int): SearchPage {
        if (keyword.isBlank()) return SearchPage(emptyList(), false)
        val direct = parseReleaseRepository(source, keyword)
        if (keyword.trim().contains("://") && direct == null) throw MarketException("请填写该来源的公开仓库链接")
        val (refs, hasMore) = if (direct != null) {
            if (page > 0) return SearchPage(emptyList(), false)
            listOf(direct) to false
        } else api.search(source, keyword, page)
        val results = parallel(refs) { ref -> resolve(ref) }
        val resolved = results.mapNotNull { it.getOrNull() }
        if (resolved.isEmpty()) results.firstOrNull { it.isFailure }?.getOrThrow()
        if (direct != null && resolved.isEmpty()) throw MarketException("该仓库没有可用的正式版 APK Release")
        return SearchPage(resolved.map { it.detail.app }, hasMore)
    }

    suspend fun appDetail(source: AppSource, appId: Long, packageName: String): AppDetail {
        val ref = mutex.withLock { refsById[source to appId] } ?: storedRef(source, packageName)
            ?: throw MarketException("请先搜索应用或仓库链接以建立来源关联")
        val detail = resolve(ref, expectedPackage = packageName)?.detail ?: throw MarketException("该仓库没有对应应用的 APK")
        preferences.put(associationKey(source, packageName), ref.link)
        return detail
    }

    suspend fun downloadMeta(source: AppSource, app: MarketAppInfo): DownloadMeta {
        val ref = parseReleaseRepository(source, app.openLink) ?: storedRef(source, app.packageName)
            ?: throw MarketException("应用仓库关联已失效，请重新搜索")
        val resolved = resolve(ref, expectedPackage = app.packageName) ?: throw MarketException("该仓库没有对应应用的 APK")
        if (resolved.detail.app.versionCode != app.versionCode) throw MarketException("Release 版本已变化，请刷新应用详情")
        preferences.put(associationKey(source, app.packageName), ref.link)
        val asset = resolved.asset
        return DownloadMeta(
            appId = app.appId, packageName = app.packageName, displayName = app.displayName,
            versionName = resolved.detail.app.versionName, versionCode = resolved.detail.app.versionCode,
            url = asset.url, size = resolved.detail.app.apkSize,
            parts = listOf(DownloadPart(asset.name, "base", asset.url, resolved.detail.app.apkSize, asset.sha256)),
            icon = app.icon, changeLog = resolved.detail.changeLog, source = source,
        )
    }

    suspend fun checkUpdates(source: AppSource): List<MarketAppInfo> {
        val packages = installed.installed().filter { it.packageName.isNotBlank() && it.versionCode > 0L }
        val tracked = packages.mapNotNull { local -> storedRef(source, local.packageName)?.let { local to it } }
        val results = parallel(tracked) { (local, ref) ->
            val app = resolve(ref, local.packageName, refresh = true)?.detail?.app ?: return@parallel null
            app.takeIf { it.versionCode > local.versionCode }?.copy(
                installedVersionCode = local.versionCode, installedVersionName = local.versionName,
                installedSplits = local.splits, isSystemApp = local.isSystemApp,
            )
        }
        if (results.isNotEmpty() && results.none { it.isSuccess }) results.first().getOrThrow()
        return results.mapNotNull { it.getOrNull() }
    }

    suspend fun checkManualUpdate(source: AppSource, request: ManualUpdateRequest): ManualUpdateResult {
        val ref = storedRef(source, request.packageName) ?: return ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
        val app = resolve(ref, request.packageName, refresh = true)?.detail?.app
            ?: return ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
        if (app.versionCode <= request.versionCode) return ManualUpdateResult(ManualUpdateStatus.RECOGNIZED_NO_UPDATE)
        return ManualUpdateResult(ManualUpdateStatus.UPDATE_AVAILABLE, app.copy(
            installedVersionCode = request.versionCode, installedVersionName = request.versionName, installedSplits = request.splits,
        ))
    }

    private suspend fun resolve(ref: ReleaseRepositoryRef, expectedPackage: String? = null, refresh: Boolean = false): Resolved? {
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val cached = mutex.withLock { cache[ref] }
        if (!refresh && cached != null && now - cached.fetchedAt < 5 * 60_000 &&
            (expectedPackage == null || expectedPackage == cached.detail.app.packageName)) return cached
        val release = api.latest(ref) ?: return null
        var firstFailure: Exception? = null
        for (asset in preferredReleaseAssets(release.assets, cpuArchitecture)) {
            val identity = try { inspector.inspect(asset.url, asset.size) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { if (firstFailure == null) firstFailure = error; continue }
            if (expectedPackage != null && identity.packageName != expectedPackage) continue
            val app = MarketAppInfo(
                appId = fdroidStableId(ref.link + "#" + identity.packageName), packageName = identity.packageName,
                displayName = ref.path.substringAfterLast('/'), publisherName = ref.path.substringBeforeLast('/'),
                versionName = identity.versionName, versionCode = identity.versionCode,
                icon = release.icon, apkSize = identity.size.takeIf { it > 0 } ?: asset.size,
                ratingScore = 0.0, changeLog = release.notes, openLink = ref.link, source = ref.source,
            )
            val detail = AppDetail(
                app = app, brief = release.description, introduction = release.description,
                changeLog = release.notes, category = "", ageClassification = "", downloadCount = 0L,
                registrationNum = "", privacyUrl = "", screenshots = emptyList(), comments = emptyList(), sameDeveloperApps = emptyList(),
            )
            val resolved = Resolved(detail, asset, now)
            mutex.withLock {
                cache[ref] = resolved
                refsById[ref.source to app.appId] = ref
            }
            return resolved
        }
        firstFailure?.let { throw MarketException("无法读取 Release APK 信息：${it.message}") }
        return null
    }

    private suspend fun storedRef(source: AppSource, packageName: String): ReleaseRepositoryRef? =
        preferences.read(associationKey(source, packageName))?.let { parseReleaseRepository(source, it) }

    private fun associationKey(source: AppSource, packageName: String) =
        StringPreferenceKey("release_sources", "${source.token}.$packageName")

    private suspend fun <T, R> parallel(items: List<T>, block: suspend (T) -> R): List<Result<R>> = coroutineScope {
        items.chunked(4).flatMap { batch ->
            batch.map { item -> async {
                try { Result.success(block(item)) }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { Result.failure(error) }
            } }.map { it.await() }
        }
    }
}

internal fun preferredReleaseAssets(assets: List<ReleaseAsset>, cpuArchitecture: String = "arm64-v8a,armeabi-v7a"): List<ReleaseAsset> = assets
    .filterNot { Regex("(?i)(debug|unsigned|unaligned)").containsMatchIn(it.name) }
    .filter { asset ->
        val name = asset.name.lowercase()
        if (name.contains("universal")) true
        else when {
            name.contains("arm64") || name.contains("aarch64") -> cpuArchitecture.contains("arm64") || cpuArchitecture.contains("aarch64")
            name.contains("armeabi") || name.contains("armv7") -> cpuArchitecture.contains("armeabi") || cpuArchitecture.contains("armv7")
            name.contains("x86_64") -> cpuArchitecture.contains("x86_64") || cpuArchitecture.contains("amd64")
            name.contains("x86") -> cpuArchitecture.contains("x86") || cpuArchitecture.contains("amd64")
            else -> true
        }
    }
    .sortedBy { asset ->
        val name = asset.name.lowercase()
        val architectureScore = when {
            name.contains("universal") -> 0
            name.contains("arm64") || name.contains("aarch64") -> 2
            name.contains("armeabi") || name.contains("armv7") -> 3
            name.contains("x86") -> 4
            else -> 1
        }
        architectureScore + if (name.contains("fdroid") || name.contains("f-droid")) 10 else 0
    }
