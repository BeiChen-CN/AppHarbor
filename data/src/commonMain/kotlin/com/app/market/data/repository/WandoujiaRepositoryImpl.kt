package com.app.market.data.repository

import com.app.market.data.remote.wandoujia.WandoujiaApi
import com.app.market.data.remote.wandoujia.wandoujiaDownloadMeta
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.repository.InstalledPackagesRepository
import com.app.market.domain.repository.WandoujiaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class WandoujiaRepositoryImpl(
    private val api: WandoujiaApi,
    private val installedPackages: InstalledPackagesRepository,
) : WandoujiaRepository {
    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        api.search(keyword, page)
    }

    override suspend fun appDetail(appId: Long): AppDetail = withContext(Dispatchers.Default) {
        api.appDetail(appId)
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        api.downloadMeta(app)
    }

    override suspend fun checkUpdates(): List<MarketAppInfo> = withContext(Dispatchers.Default) {
        api.checkUpdates(installedPackages.installed())
    }

    override suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult =
        withContext(Dispatchers.Default) {
            api.checkManualUpdate(request)
        }

    override suspend fun historicalVersions(
        appId: Long,
        packageName: String,
        offset: Int,
    ): HistoricalVersionPage = withContext(Dispatchers.Default) {
        api.historicalVersions(appId, packageName, offset)
    }

    override fun historicalDownloadMeta(version: HistoricalVersion): DownloadMeta =
        wandoujiaDownloadMeta(
            appId = version.appId,
            packageName = version.packageName,
            displayName = version.displayName,
            versionName = version.versionName,
            versionCode = version.versionCode,
            icon = version.icon,
            changeLog = "",
            downloadUrl = version.downloadUrl,
            fallbackSize = version.size,
        )
}
