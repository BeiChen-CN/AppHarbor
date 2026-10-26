package com.app.market.domain.repository

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage

/** 360 手机助手移动网页匿名只读接入：搜索、详情、评论与下载直链。 */
interface X360Repository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage
    suspend fun appDetail(appId: Long, packageName: String): AppDetail
    suspend fun appComments(app: MarketAppInfo): AppComments
    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta
}
