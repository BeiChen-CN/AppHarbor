package com.app.market.data.repository

import com.app.market.data.remote.appchina.AppchinaApi
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.repository.AppchinaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class AppchinaRepositoryImpl(
    private val api: AppchinaApi,
) : AppchinaRepository {
    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        api.search(keyword, page)
    }

    override suspend fun appDetail(appId: Long, packageName: String): AppDetail = withContext(Dispatchers.Default) {
        api.appDetail(appId, packageName)
    }

    override suspend fun appComments(app: MarketAppInfo): AppComments = withContext(Dispatchers.Default) {
        api.appComments(app)
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        api.downloadMeta(app)
    }

    override suspend fun historicalVersions(appId: Long, packageName: String, offset: Int): HistoricalVersionPage =
        withContext(Dispatchers.Default) {
            api.historicalVersions(appId, packageName, offset)
        }

    override fun historicalDownloadMeta(version: HistoricalVersion): DownloadMeta =
        api.historicalDownloadMeta(version)
}
