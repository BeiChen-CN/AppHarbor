package com.app.market.domain.repository

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage

/** 好游快爆（3839.com）网页渠道匿名只读接入：搜索、详情、评论、当前版本下载。 */
interface KuaibaoRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage
    suspend fun appDetail(appId: Long): AppDetail
    suspend fun appComments(app: MarketAppInfo): AppComments
    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta
}
