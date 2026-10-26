package com.app.market.domain.repository

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage

/** 手机乐园网页匿名只读接入：站内搜索、详情与下载直链。 */
interface ShoudjiRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage
    suspend fun appDetail(appId: Long, packageName: String): AppDetail
    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta
}
