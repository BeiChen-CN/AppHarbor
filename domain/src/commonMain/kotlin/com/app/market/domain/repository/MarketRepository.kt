package com.app.market.domain.repository

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import kotlinx.coroutines.flow.Flow

/**
 * Search, detail, download-meta and update-check operations against the Xiaomi App Store APIs.
 * Implementations live in the platform layer and resolve the device profile + account cookie internally.
 */
interface MarketRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage
    suspend fun categoryApps(categoryId: Int, page: Int = 0): SearchPage =
        SearchPage(emptyList(), hasMore = false)

    /** Loads only the data required to render the detail page's primary content. */
    suspend fun appDetail(appId: Long, packageName: String, externalQuery: String? = null): AppDetail

    /** Loads the optional comment section after the primary content has been published. */
    suspend fun appComments(appId: Long, versionCode: Long): AppComments

    /** Loads the optional same-developer section after the primary content has been published. */
    suspend fun sameDeveloperApps(appId: Long): List<MarketAppInfo>
    suspend fun downloadMeta(app: MarketAppInfo, keyword: String): DownloadMeta
    suspend fun downloadUpdateMeta(app: MarketAppInfo): DownloadMeta
    suspend fun loadReconciledCachedUpdates(): List<MarketAppInfo>
    suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult
    fun checkUpdatesFlow(): Flow<List<MarketAppInfo>>
}
