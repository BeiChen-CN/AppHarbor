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
            supportsHistoricalVersions = true,
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
            supportsComments = true,
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
    ),
    APPCHINA(
        "appchina",
        SourceCapabilities(
            supportsComments = true,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            // 无原生批量接口，逐包搜索应用汇搜索结果中的 versionCode 做更新比对
            supportsUpdates = true,
            supportsHistoricalVersions = true,
        ),
    ),
    TENCENT(
        "tencent",
        SourceCapabilities(
            supportsComments = true,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            supportsUpdates = true,
        ),
    ),
    COOLAPK(
        "coolapk",
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
    BOX7723(
        "7723",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = true,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            supportsUpdates = false,
        ),
    ),
    FDROID(
        "fdroid",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            // P2 起基于仓库索引（index-v2）做本地更新比对；仅推签名指纹兼容的条目
            supportsUpdates = true,
            supportsHistoricalVersions = true,
        ),
    ),
    IZZYONDROID(
        "izzyondroid",
        SourceCapabilities(
            supportsComments = false, supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false, reportsDeltaSize = false,
            supportsTodayFeed = false, todayFullCoverOverlay = false,
            supportsUpdates = true, supportsHistoricalVersions = true,
        ),
    ),
    FDROID_TUNA(
        "fdroid_tuna",
        SourceCapabilities(
            supportsComments = false, supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false, reportsDeltaSize = false,
            supportsTodayFeed = false, todayFullCoverOverlay = false,
            supportsUpdates = true, supportsHistoricalVersions = true,
        ),
    ),
    GITHUB(
        "github",
        SourceCapabilities(
            supportsComments = false, supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false, reportsDeltaSize = false,
            supportsTodayFeed = false, todayFullCoverOverlay = false, supportsUpdates = true,
        ),
    ),
    GITLAB(
        "gitlab",
        SourceCapabilities(
            supportsComments = false, supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false, reportsDeltaSize = false,
            supportsTodayFeed = false, todayFullCoverOverlay = false, supportsUpdates = true,
        ),
    ),
    LSPOSED(
        "lsposed",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            // 以 Xposed Module Repository 的正式版 Release 做更新比对
            supportsUpdates = true,
        ),
    ),

    // 360 手机助手网页端：移动版搜索 JSON + 详情页 HTML + 评论 JSON
    X360(
        "360",
        SourceCapabilities(
            // 移动版 /message/index 返回结构化评论数组；分页参数未暴露，单次取首屏
            supportsComments = true,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            // 无批量更新协议，仅作为搜索/下载/评论来源
            supportsUpdates = false,
        ),
    ),

    // 手机乐园：网页端站内搜索 / 详情 / 下载直链
    SHOUDJI(
        "shouji",
        SourceCapabilities(
            // 评论接口位于 tj.shouji.com.cn，详情页未注入 objType/objID，评论不可用
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            supportsUpdates = false,
        ),
    ),

    // 安卓盒 apk.hais.pw：APK 归档站，多来源变体 + 官方历史版本直链，无评论
    HAIS(
        "hais",
        SourceCapabilities(
            supportsComments = false,
            supportsSameDeveloperApps = false,
            prefersOpenLinkLaunch = false,
            reportsDeltaSize = false,
            supportsTodayFeed = false,
            todayFullCoverOverlay = false,
            supportsUpdates = false,
            supportsHistoricalVersions = true,
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
