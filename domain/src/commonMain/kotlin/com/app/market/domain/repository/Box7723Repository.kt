package com.app.market.domain.repository

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage

/** 7723游戏盒匿名只读接入：搜索、应用详情与官方 APK 下载（排除 UP 资源）。 */
interface Box7723Repository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage
    suspend fun appDetail(appId: Long): AppDetail
    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta
}
