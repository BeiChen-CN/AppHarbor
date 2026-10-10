package com.app.market.data.remote.releases

internal data class ReleaseApkIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val size: Long,
)

internal interface ReleaseApkInspector {
    suspend fun inspect(url: String, size: Long = 0L): ReleaseApkIdentity
}
