package com.app.market.domain.model.download

import com.app.market.domain.model.market.AppSource

data class DownloadMeta(
    val appId: Long,
    val packageName: String,
    val displayName: String,
    val versionName: String,
    val versionCode: Long,
    val url: String,
    val size: Long,
    val parts: List<DownloadPart> = listOf(DownloadPart("", "base", url, size)),
    val installedBaseApkPath: String = "",
    val icon: String = "",
    val changeLog: String = "",
    val requestHeaders: Map<String, String> = emptyMap(),
    val source: AppSource = AppSource.XIAOMI,
    /**
     * 源站声明的安装包内原生库 ABI 列表；空表示源站未提供该信息。
     *
     * 用于识别「源站只托管了 32 位包」的情况：此时换一个按设备架构派发安装包的来源重取。
     */
    val abis: List<String> = emptyList(),
) {
    /** 已知只含 32 位原生库；无 ABI 信息时返回 false（无从判断，按原样使用）。 */
    val is32BitOnly: Boolean
        get() = abis.isNotEmpty() && abis.none { it == Arm64Abi }
}

/** 判断安装包是否具备 64 位原生库时使用的 ABI 名称。 */
const val Arm64Abi: String = "arm64-v8a"
