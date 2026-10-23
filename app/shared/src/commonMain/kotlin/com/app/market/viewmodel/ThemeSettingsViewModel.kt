package com.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.market.domain.model.theme.ThemeColorMode
import com.app.market.domain.model.theme.ThemeColorSource
import com.app.market.domain.model.theme.ThemeColorSpec
import com.app.market.domain.model.theme.ThemePaletteStyle
import com.app.market.domain.repository.ColorSchemeModeTokens
import com.app.market.domain.repository.ThemePreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ThemeSettingsUiState(
    val colorSchemeMode: String = ColorSchemeModeTokens.Default,
    val colorMode: ThemeColorMode = ThemeColorMode.SYSTEM,
    val colorSource: ThemeColorSource = ThemeColorSource.DEFAULT,
    val seedColor: Long? = null,
    val paletteStyle: ThemePaletteStyle = ThemePaletteStyle.TONAL_SPOT,
    val colorSpec: ThemeColorSpec = ThemeColorSpec.SPEC_2021,
    val amoledDark: Boolean = false,
    val enableBlur: Boolean = true,
    val enableFloatingBottomBar: Boolean = true,
    val enableFloatingBottomBarBlur: Boolean = true,
    val enableNavigationBadge: Boolean = true,
    val enablePredictiveBack: Boolean = false,
    val pageScale: Float = 1f,
)

class ThemeSettingsViewModel(
    private val preferences: ThemePreferencesRepository,
) : ViewModel() {
    private val appearance: Flow<ThemeSettingsUiState> = combine(
        preferences.colorSchemeMode,
        preferences.enableBlur,
        preferences.enableFloatingBottomBar,
    ) { mode, blur, floating ->
        ThemeSettingsUiState(
            colorSchemeMode = mode,
            enableBlur = blur,
            enableFloatingBottomBar = floating,
        )
    }

    private val colors: Flow<ThemeSettingsUiState> = combine(
        preferences.colorMode,
        preferences.colorSource,
        preferences.seedColor,
        preferences.paletteStyle,
        preferences.colorSpec,
    ) { mode, source, seed, style, spec ->
        ThemeSettingsUiState(
            colorMode = mode,
            colorSource = source,
            seedColor = seed,
            paletteStyle = style,
            colorSpec = spec,
        )
    }

    val uiState: StateFlow<ThemeSettingsUiState> = combine(
        appearance,
        colors,
        preferences.amoledDark,
        preferences.enableFloatingBottomBarBlur,
        preferences.enableNavigationBadge,
        preferences.enablePredictiveBack,
        preferences.pageScale,
    ) { values ->
        val appearance = values[0] as ThemeSettingsUiState
        val colors = values[1] as ThemeSettingsUiState
        appearance.copy(
            colorMode = colors.colorMode,
            colorSource = colors.colorSource,
            seedColor = colors.seedColor,
            paletteStyle = colors.paletteStyle,
            colorSpec = colors.colorSpec,
            amoledDark = values[2] as Boolean,
            enableFloatingBottomBarBlur = values[3] as Boolean,
            enableNavigationBadge = values[4] as Boolean,
            enablePredictiveBack = values[5] as Boolean,
            pageScale = values[6] as Float,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeSettingsUiState())

    fun setColorSchemeMode(value: String) = persist { preferences.setColorSchemeMode(value) }
    fun setColorMode(value: ThemeColorMode) = persist { preferences.setColorMode(value) }
    fun setColorSource(value: ThemeColorSource) = persist { preferences.setColorSource(value) }
    fun setSeedColor(value: Long?) = persist { preferences.setSeedColor(value) }
    fun setPaletteStyle(value: ThemePaletteStyle) = persist { preferences.setPaletteStyle(value) }
    fun setColorSpec(value: ThemeColorSpec) = persist { preferences.setColorSpec(value) }
    fun setAmoledDark(value: Boolean) = persist { preferences.setAmoledDark(value) }
    fun setEnableBlur(value: Boolean) = persist { preferences.setEnableBlur(value) }
    fun setEnableFloatingBottomBar(value: Boolean) = persist { preferences.setEnableFloatingBottomBar(value) }
    fun setEnableFloatingBottomBarBlur(value: Boolean) = persist { preferences.setEnableFloatingBottomBarBlur(value) }
    fun setEnableNavigationBadge(value: Boolean) = persist { preferences.setEnableNavigationBadge(value) }
    fun setEnablePredictiveBack(value: Boolean) = persist { preferences.setEnablePredictiveBack(value) }
    fun setPageScale(value: Float) = persist { preferences.setPageScale(value) }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
