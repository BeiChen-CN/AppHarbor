package com.app.market.data.repository

import com.app.market.data.remote.xiaomi.XiaomiApi
import com.app.market.domain.exception.InstalledPackagesUnavailableException
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.market.hasInstalledSplits
import com.app.market.domain.model.market.isReservation
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.repository.AccountRepository
import com.app.market.domain.repository.InstalledApkHashRepository
import com.app.market.domain.repository.InstalledPackagesRepository
import com.app.market.domain.repository.InstallerPreferencesRepository
import com.app.market.domain.repository.MarketRepository
import com.app.market.domain.repository.ProfileRepository
import com.app.market.domain.repository.UpdatePreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Platform-agnostic market repository: all networking/parsing lives in commonMain ([XiaomiApi]).
 * The platform supplies the account [cookies] (empty = anonymous on desktop) and the locally
 * [installedPackages] used only for update checking (empty on desktop).
 */
internal class MarketRepositoryImpl(
    private val api: XiaomiApi,
    private val profileStore: ProfileRepository,
    private val updatePrefs: UpdatePreferencesRepository,
    private val cookies: AccountRepository,
    private val installedPackages: InstalledPackagesRepository,
    private val installedApkHash: InstalledApkHashRepository,
    private val installerController: InstallerPreferencesRepository,
) : MarketRepository {

    // 单次 downloadMeta 因版本协商最多串行发 4 个签名请求；「全部更新」不限并发会直接踩到服务端风控
    private val metadataSlots = Semaphore(MAX_CONCURRENT_METADATA_REQUESTS)

    override suspend fun categoryApps(categoryId: Int, page: Int): SearchPage = withContext(Dispatchers.Default) {
        api.categoryApps(categoryId, page)
    }

    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        var result = api.search(keyword, page, profileStore.load(), cookies.cookie(), updatePrefs.currentRemoveSearchAds())
        if (updatePrefs.currentFilterQuickGames()) {
            result = result.copy(items = result.items.filterNot { it.type.equals("quickGame", ignoreCase = true) })
        }
        if (updatePrefs.currentFilterReservationApps()) {
            result = result.copy(items = result.items.filterNot { it.isReservation() })
        }
        result
    }

    override suspend fun appDetail(
        appId: Long,
        packageName: String,
        externalQuery: String?,
    ): AppDetail = withContext(Dispatchers.Default) {
        val installed = runCatching {
            installedPackages.installedPackage(packageName)
        }.getOrElse { error ->
            if (error is InstalledPackagesUnavailableException) null else throw error
        }
        val detail = api.appDetail(
            appId = appId,
            packageName = packageName,
            installedVersionCode = installed?.versionCode ?: 0L,
            profile = profileStore.load(),
            cookie = cookies.cookie(),
            externalQuery = externalQuery,
        )
        if (installed == null) {
            detail
        } else {
            detail.copy(
                app = detail.app.withInstalled(installed)
            )
        }
    }

    override suspend fun appComments(appId: Long, versionCode: Long) = withContext(Dispatchers.Default) {
        api.appComments(appId, versionCode, profileStore.load(), cookies.cookie())
    }

    override suspend fun sameDeveloperApps(appId: Long) = withContext(Dispatchers.Default) {
        api.sameDeveloperApps(appId, profileStore.load(), cookies.cookie())
    }

    override suspend fun downloadMeta(app: MarketAppInfo, keyword: String): DownloadMeta = withContext(Dispatchers.Default) {
        metadataSlots.withPermit {
            api.downloadMeta(app.withOldApkHash(deltaUpdatesEnabled()), keyword, profileStore.load(), cookies.cookie())
        }
    }

    override suspend fun downloadUpdateMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        metadataSlots.withPermit {
            val installed = installedPackages.installedPackage(app.packageName)
            if (installed != null && app.versionCode > 0L && installed.versionCode >= app.versionCode) {
                throw MarketException("当前已安装版本不低于商店版本")
            }
            val currentApp = installed?.let { app.withInstalled(it) } ?: app
            api.downloadUpdateMeta(
                currentApp.withOldApkHash(deltaUpdatesEnabled()),
                profileStore.load(),
                cookies.cookie(),
            )
        }
    }

    override fun checkUpdatesFlow(): Flow<List<MarketAppInfo>> =
        flow {
            emitAll(
                api.checkUpdatesFlow(
                    installed = installedPackages.installed(),
                    profile = profileStore.load(),
                    cookie = cookies.cookie(),
                    security = cookies.security(),
                    resolveOldApkHashes = ::resolveOldApkHashes,
                    deltaUpdatesEnabled = deltaUpdatesEnabled(),
                )
            )
        }.flowOn(Dispatchers.Default)

    override suspend fun loadReconciledCachedUpdates(): List<MarketAppInfo> = withContext(Dispatchers.Default) {
        val cached = updatePrefs.loadCachedUpdates()
        if (cached.isEmpty()) return@withContext cached
        // null = scan failed / no permission: nothing to reconcile against, so show the cache as-is.
        val installed = runCatching { installedPackages.installed() }.getOrNull()
            ?: return@withContext cached
        reconcileCachedUpdates(cached, installed)
    }

    override suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult = withContext(Dispatchers.Default) {
        api.checkManualUpdate(request, profileStore.load(), cookies.cookie(), cookies.security())
    }

    private suspend fun deltaUpdatesEnabled(): Boolean = installerController.deltaUpdateEnabled()

    // 开关读一次即可；哈希是对整个 base APK 做 MD5，逐个串行会把检查更新拖成分钟级
    private suspend fun resolveOldApkHashes(apps: List<MarketAppInfo>): List<MarketAppInfo> = coroutineScope {
        val deltaEnabled = deltaUpdatesEnabled()
        val hashSlots = Semaphore(MAX_CONCURRENT_HASHES)
        apps.map { app -> async { hashSlots.withPermit { app.withOldApkHash(deltaEnabled) } } }.awaitAll()
    }

    private suspend fun MarketAppInfo.withOldApkHash(deltaUpdatesEnabled: Boolean): MarketAppInfo =
        if (!deltaUpdatesEnabled || hasInstalledSplits()) {
            copy(installedOldApkHash = "0", deltaSize = 0L)
        } else if (installedOldApkHash.isNotBlank() && installedOldApkHash != "0") {
            this
        } else if (installedBaseApkPath.isBlank()) {
            copy(installedOldApkHash = "0")
        } else {
            copy(installedOldApkHash = installedApkHash.md5(installedBaseApkPath))
        }

    private fun MarketAppInfo.withInstalled(installed: InstalledPackage): MarketAppInfo = copy(
        installedVersionName = installed.versionName,
        installedVersionCode = installed.versionCode,
        installedOldApkHash = installed.oldApkHash,
        installedBaseApkPath = installed.baseApkPath,
        installedSplits = installed.splits,
        isSystemApp = installed.isSystemApp,
    )

    private companion object {
        const val MAX_CONCURRENT_METADATA_REQUESTS = 3
        const val MAX_CONCURRENT_HASHES = 3
    }
}

/**
 * Drops cached rows that no longer apply to [installed] (uninstalled, or already at/past the target)
 * and refreshes each survivor's installed metadata; a moved installed version resets the cached delta
 * size / old-apk hash so the live check recomputes them. Empty [installed] returns the cache unchanged.
 */
internal fun reconcileCachedUpdates(
    cached: List<MarketAppInfo>,
    installed: List<InstalledPackage>,
): List<MarketAppInfo> {
    if (cached.isEmpty() || installed.isEmpty()) return cached
    val installedByPackage = installed.associateBy(InstalledPackage::packageName)
    return cached.mapNotNull { app ->
        val local = installedByPackage[app.packageName] ?: return@mapNotNull null
        if (local.versionCode >= app.versionCode) return@mapNotNull null
        val installedChanged = local.versionCode != app.installedVersionCode
        app.copy(
            installedVersionCode = local.versionCode,
            installedVersionName = local.versionName,
            installedBaseApkPath = local.baseApkPath,
            installedSplits = local.splits,
            isSystemApp = local.isSystemApp,
            installedOldApkHash = if (installedChanged) "0" else app.installedOldApkHash,
            deltaSize = if (installedChanged) 0L else app.deltaSize,
        )
    }
}

internal class AnonymousAccountRepositoryImpl : AccountRepository {
    override fun cookie(): String = ""
}
