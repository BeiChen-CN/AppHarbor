package com.app.market.data.repository

import com.app.market.data.remote.tencent.TencentApi
import com.app.market.domain.exception.AppNotListedException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.model.update.ManualUpdateStatus
import com.app.market.domain.repository.InstalledPackagesRepository
import com.app.market.domain.repository.TencentRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal class TencentRepositoryImpl(private val api: TencentApi, private val installed: InstalledPackagesRepository) : TencentRepository {
    override suspend fun search(keyword: String, page: Int): SearchPage = coroutineScope {
        val result = api.search(keyword, page)
        val packageName = keyword.trim()
        val exact = if (page == 0 && packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+"))) {
            findApp(packageName)
        } else null
        val localNames = installed.installed().map { it.packageName }.toSet()
        // 仅为已安装结果补充真实版本号，搜索即可正确显示“更新”；其它结果点击详情时再加载。
        val items = result.items.chunked(4).flatMap { batch ->
            batch.map { app -> async {
                if (app.packageName == exact?.packageName) exact
                else if (app.packageName in localNames) findApp(app.packageName) ?: app
                else app
            } }.awaitAll()
        }
        result.copy(items = (listOfNotNull(exact) + items).distinctBy { it.packageName.lowercase() })
    }
    override suspend fun appDetail(packageName: String): AppDetail = api.detail(packageName).detail
    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = api.downloadMeta(app)
    override suspend fun checkUpdates(): List<MarketAppInfo> = coroutineScope {
        installed.installed().filter { it.packageName.isNotBlank() && it.versionCode > 0 }
            .distinctBy { it.packageName.lowercase() }.chunked(4).flatMap { batch ->
                batch.map { local -> async {
                    val remote = findApp(local.packageName) ?: return@async null
                    if (remote.versionCode > local.versionCode && remote.downloadBlockReason.isBlank()) remote.withLocal(local) else null
                } }.awaitAll().filterNotNull()
            }
    }
    override suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult {
        val remote = findApp(request.packageName) ?: return ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
        if (remote.versionCode <= request.versionCode || remote.downloadBlockReason.isNotBlank()) {
            return ManualUpdateResult(ManualUpdateStatus.RECOGNIZED_NO_UPDATE)
        }
        return ManualUpdateResult(ManualUpdateStatus.UPDATE_AVAILABLE, remote.withLocal(InstalledPackage(
            packageName = request.packageName, versionCode = request.versionCode, versionName = request.versionName,
            isSystemApp = request.isSystemApp, installedBy = request.installedBy, splits = request.splits,
            oldApkHash = request.oldApkHash, apkSource = request.apkSource,
        )))
    }
    private suspend fun findApp(packageName: String): MarketAppInfo? = try { api.detail(packageName).detail.app }
        catch (_: AppNotListedException) { null }
}

private fun MarketAppInfo.withLocal(local: InstalledPackage): MarketAppInfo = copy(
    isSystemApp = local.isSystemApp, installedVersionName = local.versionName, installedVersionCode = local.versionCode,
    installedBaseApkPath = local.baseApkPath, installedOldApkHash = local.oldApkHash, installedSplits = local.splits,
)
