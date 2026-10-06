package com.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.market.domain.repository.ColorSchemeModeTokens
import com.app.market.domain.repository.ThemePreferencesRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class ThemeSettingsUiState(
    val colorSchemeMode: String = ColorSchemeModeTokens.Default,
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
    private val appearance = combine(
        preferences.colorSchemeMode,
        preferences.enableBlur,
        preferences.enableFloatingBottomBar,
    ) { mode, blur, floating ->
        ThemeAppearance(mode, blur, floating)
    }

    val uiState: StateFlow<ThemeSettingsUiState> = combine(
        appearance,
        preferences.enableFloatingBottomBarBlur,
        preferences.enableNavigationBadge,
        preferences.enablePredictiveBack,
        preferences.pageScale,
    ) { appearance, glass, badge, predictiveBack, scale ->
        ThemeSettingsUiState(
            colorSchemeMode = appearance.colorSchemeMode,
            enableBlur = appearance.enableBlur,
            enableFloatingBottomBar = appearance.enableFloatingBottomBar,
            enableFloatingBottomBarBlur = glass,
            enableNavigationBadge = badge,
            enablePredictiveBack = predictiveBack,
            pageScale = scale,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ThemeSettingsUiState())

    private data class ThemeAppearance(
        val colorSchemeMode: String,
        val enableBlur: Boolean,
        val enableFloatingBottomBar: Boolean,
    )

    fun setColorSchemeMode(value: String) = persist { preferences.setColorSchemeMode(value) }
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
