package com.app.market.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPart
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.update.AppUpdateInfo
import com.app.market.domain.repository.AppUpdateRepository
import com.app.market.domain.repository.DownloadRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import misc.VersionInfo

data class AppUpdateUiState(
    val checking: Boolean = false,
    val dialogVisible: Boolean = false,
    val update: AppUpdateInfo? = null,
    val upToDate: Boolean = false,
    val error: String = "",
    val started: Boolean = false,
)

/** Checks the app's own GitHub release and starts a self-update download. */
class AppUpdateViewModel(
    private val repository: AppUpdateRepository,
    private val downloads: DownloadRepository,
) : ViewModel() {
    private var state = AppUpdateUiState()
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(state)
    val uiState = mutableState

    fun check() {
        if (state.checking) return
        state = state.copy(checking = true, dialogVisible = false, update = null, upToDate = false, error = "", started = false)
        mutableState.value = state
        viewModelScope.launch {
            try {
                val info = repository.latest()
                val hasUpdate = info != null && info.versionCode > VersionInfo.VERSION_CODE
                state = state.copy(
                    checking = false,
                    dialogVisible = true,
                    update = info.takeIf { hasUpdate },
                    upToDate = !hasUpdate,
                )
                mutableState.value = state
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                state = state.copy(
                    checking = false,
                    dialogVisible = true,
                    error = error.message.orEmpty().ifBlank { "检查更新失败" },
                )
                mutableState.value = state
            }
        }
    }

    fun dismiss() {
        state = state.copy(dialogVisible = false)
        mutableState.value = state
    }

    fun startUpdate() {
        val info = state.update ?: return
        val meta = DownloadMeta(
            appId = 0L,
            packageName = VersionInfo.PACKAGE_NAME,
            displayName = VersionInfo.APP_NAME,
            versionName = info.versionName,
            versionCode = info.versionCode,
            url = info.downloadUrl,
            size = info.size,
            parts = listOf(DownloadPart(info.assetName, "base", info.downloadUrl, info.size)),
            changeLog = info.changelog,
            source = AppSource.GITHUB,
        )
        downloads.start(meta, installAfterDownload = true)
        state = state.copy(dialogVisible = false, started = true)
        mutableState.value = state
    }

    fun consumeStarted() {
        state = state.copy(started = false)
        mutableState.value = state
    }
}
