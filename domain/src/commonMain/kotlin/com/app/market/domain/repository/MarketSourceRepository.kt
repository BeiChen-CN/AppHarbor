package com.app.market.domain.repository

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.today.TodayArticle
import com.app.market.domain.model.today.TodayFeedPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import kotlinx.coroutines.flow.Flow

/**
 * Unified market entry point used by the UI.
 *
 * A source is selected once here, rather than in every screen. Implementations may fall back to
 * Xiaomi for an operation that a third-party store does not expose; the returned app keeps the
 * source that actually owns its download URL.
 */
interface MarketSourceRepository {
    suspend fun search(source: AppSource, keyword: String, page: Int = 0): SearchPage

    suspend fun appDetail(
        source: AppSource,
        appId: Long,
        packageName: String,
        externalQuery: String? = null,
    ): AppDetail

    suspend fun appComments(source: AppSource, app: MarketAppInfo): AppComments

    suspend fun sameDeveloperApps(source: AppSource, app: MarketAppInfo): List<MarketAppInfo>

    suspend fun downloadMeta(source: AppSource, app: MarketAppInfo, keyword: String = app.displayName): DownloadMeta

    suspend fun downloadUpdateMeta(source: AppSource, app: MarketAppInfo): DownloadMeta

    suspend fun loadReconciledCachedUpdates(): List<MarketAppInfo>

    fun checkUpdatesFlow(source: AppSource): Flow<List<MarketAppInfo>>

    suspend fun checkManualUpdate(source: AppSource, request: ManualUpdateRequest): ManualUpdateResult

    suspend fun goldMiFeed(source: AppSource, page: Int = 0, pageSize: Int = 9): TodayFeedPage

    suspend fun todayArticle(source: AppSource, rId: String): TodayArticle

    /** 历史版本列表；仅声明了 [com.app.market.domain.model.market.SourceCapabilities.supportsHistoricalVersions] 的源有数据。 */
    suspend fun historicalVersions(
        source: AppSource,
        appId: Long,
        packageName: String,
        offset: Int = 0,
    ): HistoricalVersionPage

    suspend fun historicalDownloadMeta(source: AppSource, version: HistoricalVersion): DownloadMeta
}
