package com.app.market.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.market.domain.model.download.DownloadState
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.repository.DownloadRepository
import com.app.market.domain.repository.MarketSourceRepository
import com.app.market.domain.repository.UpdatePreferencesRepository
import com.app.market.platform.UiPlatform
import com.app.market.resources.Res
import com.app.market.resources.download_failed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

class DownloadingAppsViewModel(
    private val sources: MarketSourceRepository,
    private val prefs: UpdatePreferencesRepository,
    private val downloads: DownloadRepository,
    private val uiPlatform: UiPlatform,
) : ViewModel() {

    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states
    private val pendingResumes = mutableSetOf<String>()

    /** 任务记录里的下载地址可能已过期，恢复前重新拉取元数据；同版本任务会复用暂存数据续传。 */
    fun resume(state: DownloadState) {
        if (!pendingResumes.add(state.packageName)) return
        viewModelScope.launch {
            try {
                val source = state.source
                    ?: prefs.searchSources.value.firstOrNull()
                    ?: AppSource.Default.first()
                runCatchingCancellable {
                    sources.downloadMeta(source, state.toAppInfo(source), state.displayName)
                }
                    .onSuccess { downloads.start(it) }
                    .onFailure { error ->
                        uiPlatform.showToast(error.message ?: getString(Res.string.download_failed))
                    }
            } finally {
                pendingResumes.remove(state.packageName)
            }
        }
    }

    fun installDownloaded(packageName: String) = downloads.install(packageName)
    fun pause(packageName: String) = downloads.cancel(packageName)

    /** 取消会结束任务并清理暂存文件，与仅暂停的 [pause] 不同。 */
    fun cancel(packageName: String) = downloads.clear(packageName)

    private fun DownloadState.toAppInfo(source: AppSource) = MarketAppInfo(
        appId = appId,
        packageName = packageName,
        displayName = displayName,
        publisherName = "",
        versionName = "",
        versionCode = 0L,
        icon = icon,
        apkSize = 0L,
        ratingScore = 0.0,
        source = source,
    )
}
