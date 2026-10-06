package com.app.market.data.local.preferences

import com.app.market.data.local.BooleanPreferenceKey
import com.app.market.data.local.StringPreferenceKey

object ThemePreferenceKeys {
    private const val NS = "theme_preferences"
    val ColorSchemeMode = StringPreferenceKey(NS, "color_scheme_mode")
    // 参考 KernelSU：毛玻璃 / 悬浮底栏 / 液态玻璃默认开启（低版本系统有降级守卫）
    val EnableBlur = BooleanPreferenceKey(NS, "enable_blur", true)
    val EnableFloatingBottomBar = BooleanPreferenceKey(NS, "enable_floating_bottom_bar", true)
    val EnableFloatingBottomBarBlur = BooleanPreferenceKey(NS, "enable_floating_bottom_bar_blur", true)
    val EnableNavigationBadge = BooleanPreferenceKey(NS, "enable_navigation_badge", true)
    val NavRailExpanded = BooleanPreferenceKey(NS, "nav_rail_expanded")
    val EnablePredictiveBack = BooleanPreferenceKey(NS, "enable_predictive_back")
    val PageScale = StringPreferenceKey(NS, "page_scale")
}
