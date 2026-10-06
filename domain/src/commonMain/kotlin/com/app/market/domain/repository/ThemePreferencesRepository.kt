package com.app.market.domain.repository

import kotlinx.coroutines.flow.StateFlow

/** 配色模式的持久化 token，与 UI 层的 miuix ColorSchemeMode 解耦。 */
object ColorSchemeModeTokens {
    const val System = "system"
    const val Light = "light"
    const val Dark = "dark"
    const val MonetSystem = "monet_system"
    const val MonetLight = "monet_light"
    const val MonetDark = "monet_dark"

    /** 默认动态取色：Android 12+ 跟随壁纸，其余平台回退基线种子色。 */
    const val Default = MonetSystem

    val All: List<String> = listOf(System, Light, Dark, MonetSystem, MonetLight, MonetDark)

    fun fromToken(raw: String?): String = All.firstOrNull { it.equals(raw, ignoreCase = true) } ?: Default
}

/** Persisted appearance and navigation preferences shared by all UI targets. */
interface ThemePreferencesRepository {
    val initialized: StateFlow<Boolean>
    val colorSchemeMode: StateFlow<String>
    val enableBlur: StateFlow<Boolean>
    val enableFloatingBottomBar: StateFlow<Boolean>
    val enableFloatingBottomBarBlur: StateFlow<Boolean>
    val enableNavigationBadge: StateFlow<Boolean>
    val navRailExpanded: StateFlow<Boolean>
    val enablePredictiveBack: StateFlow<Boolean>
    val pageScale: StateFlow<Float>

    suspend fun setColorSchemeMode(value: String)
    suspend fun setEnableBlur(value: Boolean)
    suspend fun setEnableFloatingBottomBar(value: Boolean)
    suspend fun setEnableFloatingBottomBarBlur(value: Boolean)
    suspend fun setEnableNavigationBadge(value: Boolean)
    suspend fun setNavRailExpanded(value: Boolean)
    suspend fun setEnablePredictiveBack(value: Boolean)
    suspend fun setPageScale(value: Float)
}
