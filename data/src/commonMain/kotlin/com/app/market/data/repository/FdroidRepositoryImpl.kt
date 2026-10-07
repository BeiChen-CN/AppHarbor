package com.app.market.data.repository

import com.app.market.data.remote.fdroid.FdroidApi
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.repository.FdroidRepository
import com.app.market.domain.repository.InstalledPackagesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class FdroidRepositoryImpl(
    private val api: FdroidApi,
    private val installedPackages: InstalledPackagesRepository,
) : FdroidRepository {
    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        api.search(keyword, page)
    }

    override suspend fun appDetail(packageName: String): AppDetail = withContext(Dispatchers.Default) {
        api.appDetail(packageName)
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        api.downloadMeta(app)
    }

    override suspend fun historicalVersions(packageName: String, offset: Int): HistoricalVersionPage =
        withContext(Dispatchers.Default) {
            api.historicalVersions(packageName, offset)
        }

    override suspend fun historicalDownloadMeta(version: HistoricalVersion): DownloadMeta =
        withContext(Dispatchers.Default) {
            api.historicalDownloadMeta(version)
        }

    override suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult =
        withContext(Dispatchers.Default) {
            // 手动更新比对时补充本地签名指纹：已知的签名不兼容按"无可用更新"处理，
            // 避免 UI 给出必然失败的覆盖安装选项（F-Droid 默认重签 APK）
            val installed = runCatching { installedPackages.installedPackage(request.packageName) }.getOrNull()
            val signers = buildList {
                installed?.signerSha256?.takeIf { it.isNotBlank() }?.let(::add)
                addAll(installed?.signerSha256List.orEmpty())
            }
            api.checkManualUpdate(request, signers)
        }

    override suspend fun checkUpdates(): List<MarketAppInfo> = withContext(Dispatchers.IO) {
        val installed = installedPackages.installed()
            .filter { it.packageName.isNotBlank() && it.versionCode > 0L }
        if (installed.isEmpty()) emptyList() else api.checkUpdates(installed)
    }
}
