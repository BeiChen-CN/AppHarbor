package com.app.market.data.repository

import com.app.market.data.remote.kuaibao.KuaibaoApi
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.repository.KuaibaoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class KuaibaoRepositoryImpl(
    private val api: KuaibaoApi,
) : KuaibaoRepository {
    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        api.search(keyword, page)
    }

    override suspend fun appDetail(appId: Long): AppDetail = withContext(Dispatchers.Default) {
        api.appDetail(appId)
    }

    override suspend fun appComments(app: MarketAppInfo): AppComments = withContext(Dispatchers.Default) {
        api.appComments(app)
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        api.downloadMeta(app)
    }
}
