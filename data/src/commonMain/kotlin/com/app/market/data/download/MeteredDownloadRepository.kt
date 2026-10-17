package com.app.market.data.download

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPhase
import com.app.market.domain.model.download.taskKey
import com.app.market.domain.repository.DownloadRepository
import com.app.market.domain.repository.MeteredDownloadConfirmation
import com.app.market.domain.repository.MeteredDownloadRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update

/** 在任务入队与记录更新历史之前确认，取消不会创建下载或待安装历史。 */
internal class MeteredDownloadRepository(
    private val delegate: DownloadRepository,
    private val network: NetworkMetering,
) : DownloadRepository by delegate, MeteredDownloadConfirmation {
    private val requests = MutableStateFlow<List<MeteredDownloadRequest>>(emptyList())
    override val pendingDownloads: StateFlow<List<MeteredDownloadRequest>> = requests.asStateFlow()

    override fun start(meta: DownloadMeta, installAfterDownload: Boolean) {
        val existing = taskStates.value[meta.taskKey]
        if (existing?.phase in setOf(DownloadPhase.QUEUED, DownloadPhase.DOWNLOADING,
                DownloadPhase.INSTALLING, DownloadPhase.AWAITING_USER_ACTION)) return
        if (!network.isMetered()) {
            removePending(meta.packageName, meta.versionCode)
            delegate.start(meta, installAfterDownload)
            return
        }
        requests.update { pending ->
            pending.filterNot { it.meta.taskKey == meta.taskKey } + MeteredDownloadRequest(meta, installAfterDownload)
        }
    }

    override fun confirmDownloads() {
        val approved = requests.getAndUpdate { emptyList() }
        approved.forEach { delegate.start(it.meta, it.installAfterDownload) }
    }

    override fun dismissDownloads() {
        requests.value = emptyList()
    }

    override fun cancel(packageName: String) {
        removePending(packageName)
        delegate.cancel(packageName)
    }

    override fun cancel(packageName: String, versionCode: Long) {
        removePending(packageName, versionCode)
        delegate.cancel(packageName, versionCode)
    }

    override fun clear(packageName: String) {
        removePending(packageName)
        delegate.clear(packageName)
    }

    private fun removePending(packageName: String, versionCode: Long? = null) {
        requests.update { pending -> pending.filterNot {
            it.meta.packageName == packageName && (versionCode == null || it.meta.versionCode == versionCode)
        } }
    }
}
