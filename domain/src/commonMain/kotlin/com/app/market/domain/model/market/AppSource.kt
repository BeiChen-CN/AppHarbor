package com.app.market.domain.model.market

/** 搜索结果来源。[MarketAppInfo.appId] 语义随来源而变，均为对应商店的站内应用 id。 */
enum class AppSource(val token: String, val capabilities: SourceCapabilities) {
    XIAOMI(
        "xiaomi",
        SourceCapabilities(
            supportsComments = true,
            supportsSameDeveloperApps = true,
            prefersOpenLinkLaunch = true,
            reportsDeltaSize = true,
            supportsTodayFeed = true,
            todayFullCoverOverlay = true,
            supportsUpdates = true,
            supportsSearchAdsFilter = true,
            supportsQuickAppFilter = true,
            supportsReservationFilter = true,
            supportsPromotions = true,
        ),
    ),
    VIVO(
        "vivo",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = true,
            reportsDeltaSize = true,
            supportsTodayFeed = true,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
            supportsSearchAdsFilter = true,
        ),
    ),
    WANDOUJIA(
        "wandoujia",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = true,
            reportsDeltaSize = true,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            // 豌豆荚无原生更新元数据协议，更新下载与手动更新均回退小米，不提供独立更新来源
            supportsUpdates = false,
        ),
    ),
    OPPO(
        "oppo",
        SourceCapabilities(
            supportsComments = true,
            supportsSameDeveloperApps = true,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = true,
            supportsTodayFeed = true,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
            supportsSearchAdsFilter = true,
        ),
    ),
    SAMSUNG(
        "samsung",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
        ),
    ),
    HONOR(
        "honor",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = true,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
            supportsSearchAdsFilter = true,
        ),
    ),
    HUAWEI(
        "huawei",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = true,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
            supportsSearchAdsFilter = true,
        ),
    ),
    TAPTAP(
        "taptap",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsDeltaUpdates = true,
            supportsTodayFeed = true,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
        ),
    ),
    KUAIBAO(
        "kuaibao",
        SourceCapabilities(
            supportsComments = true,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            // 网页渠道不暴露版本号，无独立更新协议，更新下载与手动更新均回退小米
            supportsUpdates = false,
        ),
    );

    companion object {
        val Default: Set<AppSource> = setOf(WANDOUJIA)

        /** 今日页内容来源默认值；须为声明了 [SourceCapabilities.supportsTodayFeed] 的源。 */
        val DefaultTodaySource: AppSource = XIAOMI

        /** 更新来源默认值；须为声明了 [SourceCapabilities.supportsUpdates] 的源。 */
        val DefaultUpdateSource: AppSource = XIAOMI

        fun parse(raw: String?): Set<AppSource> {
            if (raw.isNullOrBlank()) return Default
            // 旧版本允许多选；设置页改为单选后按枚举顺序保留第一个有效来源。
            val selected = entries.firstOrNull { source ->
                raw.split(',').any { it.trim() == source.token }
            }
            return selected?.let(::setOf) ?: Default
        }

        fun serialize(sources: Set<AppSource>): String =
            entries.filter { it in sources }.joinToString(",") { it.token }

        fun fromToken(raw: String?): AppSource? =
            entries.firstOrNull { it.token.equals(raw, ignoreCase = true) }
    }
}
