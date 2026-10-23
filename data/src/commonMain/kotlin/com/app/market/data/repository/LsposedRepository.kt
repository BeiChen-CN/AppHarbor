package com.app.market.data.repository

import com.app.market.data.remote.fdroid.fdroidStableId
import com.app.market.data.remote.lsposed.LsposedApi
import com.app.market.data.remote.lsposed.LsposedAsset
import com.app.market.data.remote.lsposed.LsposedModule
import com.app.market.data.remote.lsposed.LsposedRelease
import com.app.market.data.remote.lsposed.LsposedSearchEntry
import com.app.market.data.remote.lsposed.latestStableRelease
import com.app.market.data.remote.releases.ReleaseApkInspector
import com.app.market.data.remote.releases.ReleaseAsset
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPart
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppScreenshot
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.ScreenshotOrientation
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.model.update.ManualUpdateStatus
import com.app.market.domain.repository.InstalledPackagesRepository
import io.ktor.http.Url
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock

/**
 * Xposed Module Repository source.
 *
 * Search uses the site's compact index; search results are intentionally lightweight. Opening a
 * module resolves its latest stable release and reads the APK manifest through
 * [ReleaseApkInspector], so update checks compare real versionCode values rather than tag strings.
 */
internal class LsposedRepository(
    private val api: LsposedApi,
    private val inspector: ReleaseApkInspector,
    private val installed: InstalledPackagesRepository,
    private val cpuArchitecture: String = "arm64-v8a,armeabi-v7a",
) {
    private data class Resolved(val detail: AppDetail, val asset: LsposedAsset, val fetchedAt: Long)

    private val mutex = Mutex()
    private val cache = mutableMapOf<String, Resolved>()

    suspend fun search(keyword: String, page: Int): SearchPage {
        val needle = keyword.trim().lowercase()
        if (needle.isEmpty()) return SearchPage(emptyList(), hasMore = false)
        val matches = api.searchIndex().mapNotNull { entry ->
            val haystack = listOf(entry.name, entry.description, entry.summary, entry.readmeExcerpt, entry.authors)
                .joinToString(" ").lowercase()
            if (needle !in haystack) return@mapNotNull null
            val score = when {
                entry.name.lowercase() == needle -> 4
                entry.name.lowercase().contains(needle) -> 3
                entry.description.lowercase().contains(needle) -> 2
                entry.summary.lowercase().contains(needle) -> 1
                else -> 0
            }
            entry to score
        }.sortedWith(compareByDescending<Pair<LsposedSearchEntry, Int>> { it.second }.thenBy { it.first.name })
        val start = (page * PAGE_SIZE).coerceAtLeast(0)
        if (start >= matches.size) return SearchPage(emptyList(), hasMore = false)
        val end = (start + PAGE_SIZE).coerceAtMost(matches.size)
        return SearchPage(matches.subList(start, end).map { searchApp(it.first) }, hasMore = end < matches.size)
    }

    suspend fun appDetail(packageName: String): AppDetail {
        val resolved = resolve(packageName) ?: throw MarketException("LSPosed 模块仓库未收录该模块的正式版 APK")
        return resolved.detail
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val resolved = resolve(app.packageName) ?: throw MarketException("LSPosed 模块仓库未收录该模块的正式版 APK")
        val latest = resolved.detail.app
        if (app.versionCode > 0L && app.versionCode != latest.versionCode) {
            throw MarketException("模块版本已变化，请刷新详情后重试")
        }
        val asset = resolved.asset
        return DownloadMeta(
            appId = app.appId,
            packageName = latest.packageName,
            displayName = latest.displayName,
            versionName = latest.versionName,
            versionCode = latest.versionCode,
            url = asset.preferredUrl,
            size = latest.apkSize,
            parts = listOf(DownloadPart(asset.name, "base", asset.preferredUrl, latest.apkSize)),
            icon = latest.icon,
            changeLog = resolved.detail.changeLog,
            source = AppSource.LSPOSED,
        )
    }

    suspend fun checkUpdates(): List<MarketAppInfo> {
        val packages = installed.installed()
            .filter { it.packageName.isNotBlank() && it.versionCode > 0L }
        if (packages.isEmpty()) return emptyList()
        val repositoryPackages = api.searchIndex().mapTo(HashSet()) { it.name }
        val modules = packages.filter { it.packageName in repositoryPackages }
        if (modules.isEmpty()) return emptyList()
        val results = parallel(modules) { local ->
            val app = resolve(local.packageName, refresh = true)?.detail?.app ?: return@parallel null
            app.takeIf { it.versionCode > local.versionCode }?.copy(
                installedVersionCode = local.versionCode,
                installedVersionName = local.versionName,
                installedSplits = local.splits,
                isSystemApp = local.isSystemApp,
            )
        }
        if (results.isNotEmpty() && results.none { it.isSuccess }) results.first().getOrThrow()
        return results.mapNotNull { it.getOrNull() }
    }

    suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult {
        val app = resolve(request.packageName, refresh = true)?.detail?.app
            ?: return ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
        if (app.versionCode <= request.versionCode) {
            return ManualUpdateResult(ManualUpdateStatus.RECOGNIZED_NO_UPDATE)
        }
        return ManualUpdateResult(
            ManualUpdateStatus.UPDATE_AVAILABLE,
            app.copy(
                installedVersionCode = request.versionCode,
                installedVersionName = request.versionName,
                installedSplits = request.splits,
                isSystemApp = request.isSystemApp,
            ),
        )
    }

    private suspend fun resolve(packageName: String, refresh: Boolean = false): Resolved? {
        val now = Clock.System.now().toEpochMilliseconds()
        mutex.withLock { cache[packageName] }?.takeIf {
            !refresh && now - it.fetchedAt < CACHE_TTL_MILLIS && it.detail.app.packageName == packageName
        }?.let { return it }

        val module = api.module(packageName) ?: return null
        val release = module.latestStableRelease() ?: return null
        val assets = release.releaseAssets.filter { it.isApk && it.preferredUrl.isSafeAssetUrl() }
        if (assets.isEmpty()) return null

        val candidates = preferredReleaseAssets(
            assets.map { ReleaseAsset(it.name, it.preferredUrl, it.size) },
            cpuArchitecture,
        ).mapNotNull { preferred ->
            assets.firstOrNull { it.name == preferred.name && it.preferredUrl == preferred.url }
        }
        var firstFailure: Exception? = null
        for (asset in candidates) {
            val identity = try {
                inspector.inspect(asset.preferredUrl, asset.size)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (firstFailure == null) firstFailure = error
                continue
            }
            if (identity.packageName != packageName) continue
            val app = module.toApp(release, asset, identity.packageName, identity.versionName, identity.versionCode, identity.size)
            val detail = AppDetail(
                app = app,
                brief = module.summary.ifBlank { module.description },
                introduction = htmlToText(module.readmeHTML).ifBlank { module.summary.ifBlank { module.description } },
                changeLog = htmlToText(release.descriptionHTML),
                category = "Xposed",
                ageClassification = "",
                downloadCount = 0L,
                registrationNum = "",
                privacyUrl = "",
                screenshots = extractScreenshots(module.readmeHTML),
                comments = emptyList(),
                sameDeveloperApps = emptyList(),
            )
            val resolved = Resolved(detail, asset, now)
            mutex.withLock { cache[packageName] = resolved }
            return resolved
        }
        firstFailure?.let { throw MarketException("无法读取模块 APK 信息：${it.message}") }
        return null
    }

    private fun searchApp(entry: LsposedSearchEntry): MarketAppInfo = MarketAppInfo(
        appId = fdroidStableId("lsposed:${entry.name}"),
        packageName = entry.name,
        displayName = entry.description.ifBlank { entry.name },
        publisherName = entry.authors,
        versionName = "",
        versionCode = 0L,
        icon = avatarForAuthor(entry.authors),
        apkSize = 0L,
        ratingScore = 0.0,
        openLink = "$SITE/module/${entry.name}/",
        source = AppSource.LSPOSED,
        category = "Xposed",
    )

    private fun LsposedModule.toApp(
        release: LsposedRelease,
        asset: LsposedAsset,
        packageName: String,
        versionName: String,
        versionCode: Long,
        size: Long,
    ): MarketAppInfo {
        val publisher = collaborators.joinToString(", ") { it.name.ifBlank { it.login } }
            .ifBlank { homepageOwner().orEmpty() }
        return MarketAppInfo(
            appId = fdroidStableId("lsposed:$packageName"),
            packageName = packageName,
            displayName = description.ifBlank { name },
            publisherName = publisher,
            versionName = versionName.ifBlank { release.versionFromTag() },
            versionCode = versionCode,
            icon = avatarForHomepage(homepageUrl) ?: avatarForAuthor(publisher),
            apkSize = size.takeIf { it > 0L } ?: asset.size,
            ratingScore = 0.0,
            changeLog = htmlToText(release.descriptionHTML),
            openLink = "$SITE/module/$name/",
            source = AppSource.LSPOSED,
            category = "Xposed",
        )
    }

    private fun LsposedRelease.versionFromTag(): String =
        tagName.substringAfter('-', tagName).ifBlank { tagName }

    private fun LsposedModule.homepageOwner(): String? =
        homepageUrl?.let { GITHUB_OWNER.find(it)?.groupValues?.getOrNull(1) }

    private fun avatarForHomepage(url: String?): String? {
        val owner = url?.let { GITHUB_OWNER.find(it)?.groupValues?.getOrNull(1) } ?: return null
        return "https://github.com/$owner.png?size=200"
    }

    private fun avatarForAuthor(authors: String): String {
        val first = authors.substringBefore(',').substringBefore(" (").trim()
        return if (first.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})"))) {
            "https://github.com/$first.png?size=200"
        } else {
            "https://github.com/LSPosed.png?size=200"
        }
    }

    private suspend fun <T, R> parallel(items: List<T>, block: suspend (T) -> R): List<Result<R>> = coroutineScope {
        items.chunked(4).flatMap { batch ->
            batch.map { item ->
                async {
                    try {
                        Result.success(block(item))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            }.map { it.await() }
        }
    }

    private companion object {
        const val SITE = "https://modules.lsposed.org"
        const val PAGE_SIZE = 20
        const val CACHE_TTL_MILLIS = 5 * 60_000L
        val GITHUB_OWNER = Regex("^https://github\\.com/([A-Za-z0-9_.-]+)(?:/|$)")
    }
}

private fun String.isSafeAssetUrl(): Boolean = runCatching {
    val url = Url(this)
    url.protocol.name == "https" &&
        url.user.isNullOrEmpty() &&
        url.password.isNullOrEmpty() &&
        (url.host == "github.com" || url.host == "assets.lsposed.org")
}.getOrDefault(false)

internal fun htmlToText(raw: String): String = raw
    .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
    .replace(Regex("(?i)<br\\s*/?>"), "\n")
    .replace(Regex("(?i)</(p|div|li|h[1-6]|tr)>"), "\n")
    .replace(Regex("<[^>]+>"), "")
    .let(::decodeHtmlEntities)
    .replace(Regex("[ \t]+\n"), "\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()
    .take(MAX_DESCRIPTION_CHARS)

private fun extractScreenshots(html: String): List<AppScreenshot> =
    Regex("(?i)<img[^>]+src=[\"']([^\"']+)[\"']").findAll(html)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .filter { it.startsWith("https://", ignoreCase = true) }
        .filterNot { "shields.io" in it || "badge" in it || "avatar" in it }
        .distinct()
        .take(8)
        .map { AppScreenshot(it, ScreenshotOrientation.PORTRAIT) }
        .toList()

private fun decodeHtmlEntities(value: String): String {
    val named = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
    return Regex("&#(x?)([0-9A-Fa-f]+);").replace(named) { match ->
        val code = match.groupValues[2].toIntOrNull(if (match.groupValues[1].isEmpty()) 10 else 16)
        code?.takeIf { Character.isValidCodePoint(it) }?.let { String(Character.toChars(it)) } ?: match.value
    }
}

private const val MAX_DESCRIPTION_CHARS = 30_000
