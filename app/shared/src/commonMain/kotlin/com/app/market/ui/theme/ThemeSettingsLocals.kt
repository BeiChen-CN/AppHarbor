package com.app.market.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import com.app.market.domain.repository.ColorSchemeModeTokens
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

val LocalEnableBlur = staticCompositionLocalOf { false }
val LocalEnableFloatingBottomBar = staticCompositionLocalOf { false }
val LocalEnableFloatingBottomBarBlur = staticCompositionLocalOf { false }
val LocalEnableNavigationBadge = staticCompositionLocalOf { true }

/** 持久化 token → miuix 配色模式；未知 token 回退到默认动态取色。 */
fun parseColorSchemeMode(token: String?): ColorSchemeMode = when (ColorSchemeModeTokens.fromToken(token)) {
    ColorSchemeModeTokens.Light -> ColorSchemeMode.Light
    ColorSchemeModeTokens.Dark -> ColorSchemeMode.Dark
    ColorSchemeModeTokens.MonetSystem -> ColorSchemeMode.MonetSystem
    ColorSchemeModeTokens.MonetLight -> ColorSchemeMode.MonetLight
    ColorSchemeModeTokens.MonetDark -> ColorSchemeMode.MonetDark
    else -> ColorSchemeMode.System
}
