package com.app.market.domain.repository

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult

/**
 * F-Droid 匿名只读接入：搜索、完整详情、下载、历史版本与更新检查（P2 起为独立更新来源）。
 * F-Droid 无站内数字 id，全部以 [MarketAppInfo.packageName] 为真实键，
 * [MarketAppInfo.appId] 为包名的稳定哈希（与 Samsung 源同策略）。
 */
interface FdroidRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage
    suspend fun appDetail(packageName: String): AppDetail
    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta
    suspend fun historicalVersions(packageName: String, offset: Int = 0): HistoricalVersionPage
    suspend fun historicalDownloadMeta(version: HistoricalVersion): DownloadMeta
    suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult

    /**
     * 与本地安装列表比对的可更新应用。仅推送签名指纹兼容的条目：
     * F-Droid 默认重签 APK，与其他渠道签名不兼容的覆盖安装必然失败。
     */
    suspend fun checkUpdates(): List<MarketAppInfo>
}
