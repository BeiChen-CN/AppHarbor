package com.app.market.data.store

import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.preferences.ThemePreferenceKeys
import com.app.market.data.platform.ThemePlatformPreferences
import com.app.market.data.platform.debugLog
import com.app.market.domain.repository.ColorSchemeModeTokens
import com.app.market.domain.model.theme.ThemeColorMode
import com.app.market.domain.model.theme.ThemeColorSource
import com.app.market.domain.model.theme.ThemeColorSpec
import com.app.market.domain.model.theme.ThemePaletteStyle
import com.app.market.domain.repository.ThemePreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class ThemePreferencesRepositoryImpl(
    private val preferences: PreferencesDataSource,
    private val scope: CoroutineScope,
    private val platformPreferences: ThemePlatformPreferences,
) : ThemePreferencesRepository {
    private val _initialized = MutableStateFlow(false)
    override val initialized: StateFlow<Boolean> = _initialized.asStateFlow()
    private val _colorSchemeMode = MutableStateFlow(ColorSchemeModeTokens.Default)
    override val colorSchemeMode: StateFlow<String> = _colorSchemeMode.asStateFlow()
    private val _colorMode = MutableStateFlow(ThemeColorMode.SYSTEM)
    override val colorMode: StateFlow<ThemeColorMode> = _colorMode.asStateFlow()
    private val _colorSource = MutableStateFlow(ThemeColorSource.DEFAULT)
    override val colorSource: StateFlow<ThemeColorSource> = _colorSource.asStateFlow()
    private val _seedColor = MutableStateFlow<Long?>(null)
    override val seedColor: StateFlow<Long?> = _seedColor.asStateFlow()
    private val _paletteStyle = MutableStateFlow(ThemePaletteStyle.TONAL_SPOT)
    override val paletteStyle: StateFlow<ThemePaletteStyle> = _paletteStyle.asStateFlow()
    private val _colorSpec = MutableStateFlow(ThemeColorSpec.SPEC_2021)
    override val colorSpec: StateFlow<ThemeColorSpec> = _colorSpec.asStateFlow()
    private val _amoledDark = MutableStateFlow(false)
    override val amoledDark: StateFlow<Boolean> = _amoledDark.asStateFlow()
    private val _enableBlur = MutableStateFlow(ThemePreferenceKeys.EnableBlur.default)
    override val enableBlur: StateFlow<Boolean> = _enableBlur.asStateFlow()
    private val _enableFloatingBottomBar = MutableStateFlow(ThemePreferenceKeys.EnableFloatingBottomBar.default)
    override val enableFloatingBottomBar: StateFlow<Boolean> = _enableFloatingBottomBar.asStateFlow()
    private val _enableFloatingBottomBarBlur = MutableStateFlow(ThemePreferenceKeys.EnableFloatingBottomBarBlur.default)
    override val enableFloatingBottomBarBlur: StateFlow<Boolean> = _enableFloatingBottomBarBlur.asStateFlow()
    private val _enableNavigationBadge = MutableStateFlow(ThemePreferenceKeys.EnableNavigationBadge.default)
    override val enableNavigationBadge: StateFlow<Boolean> = _enableNavigationBadge.asStateFlow()
    private val _navRailExpanded = MutableStateFlow(ThemePreferenceKeys.NavRailExpanded.default)
    override val navRailExpanded: StateFlow<Boolean> = _navRailExpanded.asStateFlow()
    private val _enablePredictiveBack = MutableStateFlow(false)
    override val enablePredictiveBack: StateFlow<Boolean> = _enablePredictiveBack.asStateFlow()
    private val _pageScale = MutableStateFlow(1f)
    override val pageScale: StateFlow<Float> = _pageScale.asStateFlow()

    init {
        val arrivals = listOf(
            observe("colorSchemeMode", preferences.observe(ThemePreferenceKeys.ColorSchemeMode)) {
                _colorSchemeMode.value = ColorSchemeModeTokens.fromToken(it)
            },
            observe("colorMode", preferences.observe(ThemePreferenceKeys.ColorMode)) {
                _colorMode.value = ThemeColorMode.fromKey(it)
            },
            observe("colorSource", preferences.observe(ThemePreferenceKeys.ColorSource)) {
                _colorSource.value = ThemeColorSource.fromKey(it)
            },
            observe("seedColor", preferences.observe(ThemePreferenceKeys.SeedColor)) {
                _seedColor.value = it?.toLongOrNull()
            },
            observe("paletteStyle", preferences.observe(ThemePreferenceKeys.PaletteStyle)) {
                _paletteStyle.value = ThemePaletteStyle.fromKey(it)
            },
            observe("colorSpec", preferences.observe(ThemePreferenceKeys.ColorSpec)) {
                _colorSpec.value = ThemeColorSpec.fromKey(it)
            },
            observe("amoledDark", preferences.observe(ThemePreferenceKeys.AmoledDark)) {
                _amoledDark.value = it
            },
            observe("enableBlur", preferences.observe(ThemePreferenceKeys.EnableBlur)) { _enableBlur.value = it },
            observe("enableFloatingBottomBar", preferences.observe(ThemePreferenceKeys.EnableFloatingBottomBar)) {
                _enableFloatingBottomBar.value = it
            },
            observe("enableFloatingBottomBarBlur", preferences.observe(ThemePreferenceKeys.EnableFloatingBottomBarBlur)) {
                _enableFloatingBottomBarBlur.value = it
            },
            observe("enableNavigationBadge", preferences.observe(ThemePreferenceKeys.EnableNavigationBadge)) {
                _enableNavigationBadge.value = it
            },
            observe("navRailExpanded", preferences.observe(ThemePreferenceKeys.NavRailExpanded)) {
                _navRailExpanded.value = it
            },
            observe("enablePredictiveBack", preferences.observe(ThemePreferenceKeys.EnablePredictiveBack)) {
                _enablePredictiveBack.value = it
                platformPreferences.setPredictiveBackEnabled(it)
            },
            observe("pageScale", preferences.observe(ThemePreferenceKeys.PageScale)) {
                _pageScale.value = it?.toFloatOrNull()?.coerceIn(0.8f, 1.1f) ?: 1f
            },
        )
        scope.launch {
            arrivals.forEach { it.await() }
            _initialized.value = true
        }
    }

    private fun <T> observe(
        name: String,
        flow: Flow<T>,
        update: (T) -> Unit,
    ): CompletableDeferred<Unit> {
        val firstValue = CompletableDeferred<Unit>()
        scope.launch {
            try {
                flow.collect {
                    update(it)
                    firstValue.complete(Unit)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                debugLog("ThemePreferencesRepository") { "observe $name failed: $error" }
            } finally {
                firstValue.complete(Unit)
            }
        }
        return firstValue
    }

    override suspend fun setColorSchemeMode(value: String) =
        preferences.put(ThemePreferenceKeys.ColorSchemeMode, ColorSchemeModeTokens.fromToken(value))

    override suspend fun setColorMode(value: ThemeColorMode) =
        preferences.put(ThemePreferenceKeys.ColorMode, value.key)

    override suspend fun setColorSource(value: ThemeColorSource) =
        preferences.put(ThemePreferenceKeys.ColorSource, value.key)

    override suspend fun setSeedColor(value: Long?) =
        preferences.put(ThemePreferenceKeys.SeedColor, value?.toString().orEmpty())

    override suspend fun setPaletteStyle(value: ThemePaletteStyle) =
        preferences.put(ThemePreferenceKeys.PaletteStyle, value.key)

    override suspend fun setColorSpec(value: ThemeColorSpec) =
        preferences.put(ThemePreferenceKeys.ColorSpec, value.key)

    override suspend fun setAmoledDark(value: Boolean) =
        preferences.put(ThemePreferenceKeys.AmoledDark, value)

    override suspend fun setEnableBlur(value: Boolean) = preferences.put(ThemePreferenceKeys.EnableBlur, value)
    override suspend fun setEnableFloatingBottomBar(value: Boolean) = preferences.put(ThemePreferenceKeys.EnableFloatingBottomBar, value)

    override suspend fun setEnableFloatingBottomBarBlur(value: Boolean) =
        preferences.put(ThemePreferenceKeys.EnableFloatingBottomBarBlur, value)

    override suspend fun setEnableNavigationBadge(value: Boolean) = preferences.put(ThemePreferenceKeys.EnableNavigationBadge, value)
    override suspend fun setNavRailExpanded(value: Boolean) = preferences.put(ThemePreferenceKeys.NavRailExpanded, value)
    override suspend fun setEnablePredictiveBack(value: Boolean) {
        platformPreferences.setPredictiveBackEnabled(value)
        preferences.put(ThemePreferenceKeys.EnablePredictiveBack, value)
    }

    override suspend fun setPageScale(value: Float) =
        preferences.put(ThemePreferenceKeys.PageScale, value.coerceIn(0.8f, 1.1f).toString())
}
