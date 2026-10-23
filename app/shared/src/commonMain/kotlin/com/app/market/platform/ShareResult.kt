package com.app.market.platform

/** 平台只报结果，文案由 UI 侧走 Compose Resources 决定。 */
enum class ShareResult {
    /** 已拉起系统分享面板。 */
    Shared,

    /** 无系统分享面板，内容已复制到剪贴板。 */
    Copied,
    Failed,
    Unsupported,
}
