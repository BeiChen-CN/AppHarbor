package com.app.market.domain.model.market

/**
 * 各来源对通用能力的声明。聚合层与 UI 层据此决定回退行为，
 * 新增来源只需补充声明，不需要再维护各处的负向判断列表。
 */
data class SourceCapabilities(
    /** 是否支持应用评论；不支持时聚合层返回空列表。 */
    val supportsComments: Boolean,
    /** 是否支持同开发者应用列表；不支持时返回空列表。 */
    val supportsSameDeveloperApps: Boolean,
    /** 打开应用时是否优先使用 openLink 深链；为 false 时先尝试已安装应用，深链仅作回退。 */
    val prefersOpenLinkLaunch: Boolean,
    /** 是否报告可信的 delta 大小；为 false 时更新合并阶段强制清零 deltaSize。 */
    val reportsDeltaSize: Boolean,
    /** 是否提供独立的今日页内容；为 false 的源不出现在今日来源选项中。 */
    val supportsTodayFeed: Boolean,
    /** 今日卡片是否使用完全覆盖式（应用行也叠加在封面上）；为 false 时应用行附加在封面下方。 */
    val todayFullCoverOverlay: Boolean,
    /** 是否提供独立的更新检查与更新下载；为 false 的源不出现在更新来源选项中。 */
    val supportsUpdates: Boolean,
    /** 是否支持历史版本列表与历史版本下载；为 false 时详情页不展示历史版本入口。 */
    val supportsHistoricalVersions: Boolean = false,
    /** 搜索结果是否带推广标记，并可按设置过滤。 */
    val supportsSearchAdsFilter: Boolean = false,
    /** 搜索结果是否带快应用标记，并可按设置过滤。 */
    val supportsQuickAppFilter: Boolean = false,
    /** 搜索结果是否带预约状态，并可按设置过滤。 */
    val supportsReservationFilter: Boolean = false,
    /** 详情页是否提供优惠活动内容。 */
    val supportsPromotions: Boolean = false,
    /** 是否能在下载时协商增量补丁；与更新列表是否提前报告大小独立。 */
    val supportsDeltaUpdates: Boolean = reportsDeltaSize,
)
