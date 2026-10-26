package com.app.market.domain.repository

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage

/**
 * HAIS（apk.hais.pw）APK 归档站匿名只读接入：搜索、详情、直链下载与历史版本。
 *
 * HAIS 无站内数字 id，主条目以**包名**充当标识（与 F-Droid 源同策略），
 * 因此 [MarketAppInfo.appId] 恒为 0，全部按 [MarketAppInfo.packageName] 取数。
 * 历史版本用短 ID（base62 风格字符串）定位，短 ID 无法无损放进 [HistoricalVersion.versionId]，
 * 故在 [historicalVersions] 阶段就把下载直链写进 [HistoricalVersion.downloadUrl]。
 *
 * 该站没有评论接口，故不提供 appComments。
 */
interface HaisRepository {
    suspend fun search(keyword: String, page: Int = 0): SearchPage

    suspend fun appDetail(packageName: String): AppDetail

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta

    /** 历史版本分页；[offset] 为已加载条数，实现内部换算为 1-based 页码。 */
    suspend fun historicalVersions(packageName: String, offset: Int = 0): HistoricalVersionPage

    suspend fun historicalDownloadMeta(version: HistoricalVersion): DownloadMeta
}
