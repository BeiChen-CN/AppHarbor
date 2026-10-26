package com.app.market.domain.repository

/**
 * 底栏类别标识。用户可逐个隐藏/显示，设置持久化在 [FeaturePreferences.hiddenMainTabs]。
 * 桌面端不含「更新」页签，平台层按能力裁剪，此枚举只描述用户可配置的部分。
 */
enum class MainTabId(val token: String) {
    RECOMMENDATIONS("recommendations"),
    CATEGORY("category"),
    UPDATES("updates"),
    SEARCH("search"),
    SETTINGS("settings");

    companion object {
        /** 设置页至少保留一个页签，避免底栏为空。 */
        val Configurable: List<MainTabId> = listOf(RECOMMENDATIONS, CATEGORY, UPDATES, SEARCH, SETTINGS)

        fun fromToken(raw: String?): MainTabId? =
            entries.firstOrNull { it.token.equals(raw, ignoreCase = true) }

        fun parse(raw: String?): Set<MainTabId> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.split(',').mapNotNull(::fromToken).toSet()
        }

        fun serialize(tabs: Set<MainTabId>): String =
            entries.filter { it in tabs }.joinToString(",") { it.token }
    }
}
