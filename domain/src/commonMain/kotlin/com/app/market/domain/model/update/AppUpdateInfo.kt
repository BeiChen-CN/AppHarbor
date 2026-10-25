package com.app.market.domain.model.update

/** A newer Trove build published on GitHub Releases. */
data class AppUpdateInfo(
    val versionName: String,
    val versionCode: Long,
    val changelog: String,
    val releaseUrl: String,
    val assetName: String,
    val downloadUrl: String,
    val size: Long,
)
