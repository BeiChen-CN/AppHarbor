package com.app.market.domain.model.download

import com.app.market.domain.model.market.AppSource

data class DownloadState(
    val appId: Long,
    val packageName: String,
    val displayName: String,
    val progress: Int?,
    val phase: DownloadPhase = DownloadPhase.QUEUED,
    val savedPackageId: String? = null,
    val errorMessage: String = "",
    val icon: String = "",
    val versionName: String = "",
    val versionCode: Long = 0L,
    val source: AppSource? = null,
    val waitingForUnmetered: Boolean = false,
) {
    val isComplete: Boolean get() = phase == DownloadPhase.DOWNLOADED && savedPackageId != null
    val isPaused: Boolean get() = phase == DownloadPhase.PAUSED
}

enum class DownloadPhase {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    INSTALLING,
    AWAITING_USER_ACTION,
    DOWNLOADED,
    FAILED,
}
