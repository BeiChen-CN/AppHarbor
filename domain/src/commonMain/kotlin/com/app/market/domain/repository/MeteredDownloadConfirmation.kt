package com.app.market.domain.repository

import com.app.market.domain.model.download.DownloadMeta
import kotlinx.coroutines.flow.StateFlow

/** 计费网络下等待用户确认的下载；确认只授权当前批次。 */
data class MeteredDownloadRequest(val meta: DownloadMeta, val installAfterDownload: Boolean)

interface MeteredDownloadConfirmation {
    val pendingDownloads: StateFlow<List<MeteredDownloadRequest>>
    fun confirmDownloads()
    fun dismissDownloads()
}
