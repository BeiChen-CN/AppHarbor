package com.app.market.data.repository

import com.app.market.data.remote.coolapk.CoolapkApi
import com.app.market.domain.exception.AppNotListedException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.model.update.ManualUpdateStatus
import com.app.market.domain.repository.CoolapkRepository
import com.app.market.domain.repository.InstalledPackagesRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal class CoolapkRepositoryImpl(
    private val api: CoolapkApi,
    private val installedPackages: InstalledPackagesRepository,
) : CoolapkRepository {
    override suspend fun search(keyword: String, page: Int): SearchPage = api.search(keyword, page)
    override suspend fun appDetail(appId: Long, packageName: String): AppDetail = api.detail(packageName, appId).detail
    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = api.downloadMeta(app)

    override suspend fun checkUpdates(): List<MarketAppInfo> = coroutineScope {
        val installed = installedPackages.installed()
            .filter { it.packageName.isNotBlank() && it.versionCode > 0 }
            .distinctBy { it.packageName.lowercase() }
        // 没有依赖未经验证的批量协议；限制并发，未收录包跳过，认证/网络错误继续向 UI 传播。
        installed.chunked(4).flatMap { batch ->
            batch.map { local -> async {
                val remote = findApp(local.packageName) ?: return@async null
                if (remote.versionCode <= local.versionCode || remote.downloadBlockReason.isNotBlank()) null
                else remote.withInstalled(local)
            } }.awaitAll().filterNotNull()
        }
    }

    override suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult {
        val remote = findApp(request.packageName) ?: return ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
        if (remote.versionCode <= request.versionCode || remote.downloadBlockReason.isNotBlank()) {
            return ManualUpdateResult(ManualUpdateStatus.RECOGNIZED_NO_UPDATE)
        }
        val local = InstalledPackage(
            packageName = request.packageName,
            versionCode = request.versionCode,
            versionName = request.versionName,
            isSystemApp = request.isSystemApp,
            installedBy = request.installedBy,
            splits = request.splits,
            oldApkHash = request.oldApkHash,
            apkSource = request.apkSource,
        )
        return ManualUpdateResult(ManualUpdateStatus.UPDATE_AVAILABLE, remote.withInstalled(local))
    }

    private suspend fun findApp(packageName: String): MarketAppInfo? = try {
        api.detail(packageName).detail.app
    } catch (_: AppNotListedException) {
        null
    }
}

private fun MarketAppInfo.withInstalled(local: InstalledPackage): MarketAppInfo = copy(
    isSystemApp = local.isSystemApp,
    installedVersionName = local.versionName,
    installedVersionCode = local.versionCode,
    installedOldApkHash = local.oldApkHash,
    installedBaseApkPath = local.baseApkPath,
    installedSplits = local.splits,
)
