package com.app.market.domain.repository

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult

/** 应用汇网页匿名只读接入：搜索、详情、当前版本与历史版本下载。 */
interface AppchinaRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage
    suspend fun appDetail(appId: Long, packageName: String): AppDetail
    suspend fun appComments(app: MarketAppInfo): AppComments
    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta
    suspend fun historicalVersions(appId: Long, packageName: String, offset: Int = 0): HistoricalVersionPage
    fun historicalDownloadMeta(version: HistoricalVersion): DownloadMeta
    suspend fun checkUpdates(): List<MarketAppInfo>
    suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult
}
