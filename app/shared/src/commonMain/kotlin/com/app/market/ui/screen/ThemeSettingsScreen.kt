package com.app.market.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.app.market.domain.model.theme.ThemeColorMode
import com.app.market.domain.model.theme.ThemeColorSource
import com.app.market.domain.model.theme.ThemeColorSpec
import com.app.market.domain.model.theme.ThemePaletteStyle
import com.app.market.resources.theme_accent_color
import com.app.market.resources.theme_accent_default
import com.app.market.resources.theme_amoled
import com.app.market.resources.theme_color_spec
import com.app.market.resources.theme_color_spec_2021
import com.app.market.resources.theme_color_spec_2025
import com.app.market.resources.theme_color_spec_unsupported
import com.app.market.resources.theme_custom_color
import com.app.market.resources.theme_dark_mode
import com.app.market.resources.theme_dark_mode_dark
import com.app.market.resources.theme_dark_mode_light
import com.app.market.resources.theme_dark_mode_system
import com.app.market.resources.theme_palette_content
import com.app.market.resources.theme_palette_expressive
import com.app.market.resources.theme_palette_fidelity
import com.app.market.resources.theme_palette_fruit_salad
import com.app.market.resources.theme_palette_monochrome
import com.app.market.resources.theme_palette_neutral
import com.app.market.resources.theme_palette_rainbow
import com.app.market.resources.theme_palette_style
import com.app.market.resources.theme_palette_tonal_spot
import com.app.market.resources.theme_palette_vibrant
import com.app.market.resources.theme_use_monet
import com.app.market.ui.component.ColorPickerDialog
import org.jetbrains.compose.resources.StringResource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.market.platform.isBlurSettingSupported
import com.app.market.platform.isPredictiveBackSupported
import com.app.market.domain.repository.ColorSchemeModeTokens
import com.app.market.resources.Res
import com.app.market.resources.theme
import com.app.market.resources.theme_color_scheme
import com.app.market.resources.theme_color_scheme_summary
import com.app.market.resources.theme_color_scheme_dark
import com.app.market.resources.theme_color_scheme_light
import com.app.market.resources.theme_color_scheme_monet_dark
import com.app.market.resources.theme_color_scheme_monet_light
import com.app.market.resources.theme_color_scheme_monet_system
import com.app.market.resources.theme_color_scheme_system
import com.app.market.resources.theme_enable_blur
import com.app.market.resources.theme_enable_blur_summary
import com.app.market.resources.theme_enable_glass
import com.app.market.resources.theme_enable_glass_summary
import com.app.market.resources.theme_floating_bottom_bar
import com.app.market.resources.theme_floating_bottom_bar_summary
import com.app.market.resources.theme_navigation_badge
import com.app.market.resources.theme_navigation_badge_summary
import com.app.market.resources.theme_page_scale
import com.app.market.resources.theme_page_scale_summary
import com.app.market.resources.theme_predictive_back
import com.app.market.resources.theme_predictive_back_summary
import com.app.market.ui.component.CardSegmentContainer
import com.app.market.ui.component.MarketScaffold
import com.app.market.ui.component.PageVerticalPadding
import com.app.market.ui.component.ScaleDialog
import com.app.market.viewmodel.ThemeSettingsUiState
import com.app.market.viewmodel.ThemeSettingsViewModel
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SliderDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

private const val DEFAULT_SEED_COLOR = 0xFF5B7CFFL

internal fun paletteStyleRes(style: ThemePaletteStyle): StringResource = when (style) {
    ThemePaletteStyle.TONAL_SPOT -> Res.string.theme_palette_tonal_spot
    ThemePaletteStyle.NEUTRAL -> Res.string.theme_palette_neutral
    ThemePaletteStyle.VIBRANT -> Res.string.theme_palette_vibrant
    ThemePaletteStyle.EXPRESSIVE -> Res.string.theme_palette_expressive
    ThemePaletteStyle.RAINBOW -> Res.string.theme_palette_rainbow
    ThemePaletteStyle.FRUIT_SALAD -> Res.string.theme_palette_fruit_salad
    ThemePaletteStyle.MONOCHROME -> Res.string.theme_palette_monochrome
    ThemePaletteStyle.FIDELITY -> Res.string.theme_palette_fidelity
    ThemePaletteStyle.CONTENT -> Res.string.theme_palette_content
}

/** 配色模式 token → 设置项文案。 */
@Composable
private fun colorSchemeModeLabel(token: String): String = when (token) {
    ColorSchemeModeTokens.Light -> stringResource(Res.string.theme_color_scheme_light)
    ColorSchemeModeTokens.Dark -> stringResource(Res.string.theme_color_scheme_dark)
    ColorSchemeModeTokens.MonetSystem -> stringResource(Res.string.theme_color_scheme_monet_system)
    ColorSchemeModeTokens.MonetLight -> stringResource(Res.string.theme_color_scheme_monet_light)
    ColorSchemeModeTokens.MonetDark -> stringResource(Res.string.theme_color_scheme_monet_dark)
    else -> stringResource(Res.string.theme_color_scheme_system)
}

@Composable
fun ThemeSettingsScreen(
    viewModel: ThemeSettingsViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ThemeSettingsContent(
        state = state,
        onBack = onBack,
        onColorSchemeMode = viewModel::setColorSchemeMode,
        onColorMode = viewModel::setColorMode,
        onColorSource = viewModel::setColorSource,
        onSeedColor = viewModel::setSeedColor,
        onPaletteStyle = viewModel::setPaletteStyle,
        onColorSpec = viewModel::setColorSpec,
        onAmoledDark = viewModel::setAmoledDark,
        onEnableBlur = viewModel::setEnableBlur,
        onEnableFloatingBottomBar = viewModel::setEnableFloatingBottomBar,
        onEnableFloatingBottomBarBlur = viewModel::setEnableFloatingBottomBarBlur,
        onEnableNavigationBadge = viewModel::setEnableNavigationBadge,
        onEnablePredictiveBack = viewModel::setEnablePredictiveBack,
        onPageScale = viewModel::setPageScale,
        blurSupported = isBlurSettingSupported(),
        predictiveBackSupported = isPredictiveBackSupported(),
        modifier = modifier,
    )
}

@Composable
private fun ThemeSettingsContent(
    state: ThemeSettingsUiState,
    onBack: () -> Unit,
    onColorSchemeMode: (String) -> Unit,
    onColorMode: (ThemeColorMode) -> Unit,
    onColorSource: (ThemeColorSource) -> Unit,
    onSeedColor: (Long?) -> Unit,
    onPaletteStyle: (ThemePaletteStyle) -> Unit,
    onColorSpec: (ThemeColorSpec) -> Unit,
    onAmoledDark: (Boolean) -> Unit,
    onEnableBlur: (Boolean) -> Unit,
    onEnableFloatingBottomBar: (Boolean) -> Unit,
    onEnableFloatingBottomBarBlur: (Boolean) -> Unit,
    onEnableNavigationBadge: (Boolean) -> Unit,
    onEnablePredictiveBack: (Boolean) -> Unit,
    onPageScale: (Float) -> Unit,
    blurSupported: Boolean,
    predictiveBackSupported: Boolean,
    modifier: Modifier = Modifier,
) {
    val layoutDirection = LocalLayoutDirection.current
    var sliderValue by remember(state.pageScale) { mutableFloatStateOf(state.pageScale) }
    var showScaleDialog by rememberSaveable { mutableStateOf(false) }
    var showColorPicker by rememberSaveable { mutableStateOf(false) }

    MarketScaffold(
        title = stringResource(Res.string.theme),
        onBack = onBack,
        modifier = modifier,
    ) { innerPadding, backdropModifier, scrollBehavior ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(backdropModifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                start = innerPadding.calculateStartPadding(layoutDirection) + 12.dp,
                end = innerPadding.calculateEndPadding(layoutDirection) + 12.dp,
                top = innerPadding.calculateTopPadding() + PageVerticalPadding,
                bottom = innerPadding.calculateBottomPadding() + PageVerticalPadding,
            ),
        ) {
            val monetEnabled = state.colorSource != ThemeColorSource.DEFAULT
            item(key = "dark-mode") {
                CardSegmentContainer(
                    isFirst = true,
                    isLast = false,
                    horizontalPadding = 0.dp,
                ) {
                    WindowDropdownPreference(
                        title = stringResource(Res.string.theme_dark_mode),
                        items = listOf(
                            stringResource(Res.string.theme_dark_mode_system),
                            stringResource(Res.string.theme_dark_mode_light),
                            stringResource(Res.string.theme_dark_mode_dark),
                        ),
                        selectedIndex = ThemeColorMode.entries.indexOf(state.colorMode),
                        onSelectedIndexChange = { index ->
                            onColorMode(ThemeColorMode.entries[index])
                        },
                    )
                }
            }
            item(key = "amoled") {
                CardSegmentContainer(isFirst = false, isLast = false, horizontalPadding = 0.dp) {
                    SwitchPreference(
                        title = stringResource(Res.string.theme_amoled),
                        checked = state.amoledDark,
                        onCheckedChange = onAmoledDark,
                    )
                }
            }
            item(key = "use-monet") {
                CardSegmentContainer(
                    isFirst = false,
                    isLast = !monetEnabled,
                    horizontalPadding = 0.dp,
                ) {
                    SwitchPreference(
                        title = stringResource(Res.string.theme_use_monet),
                        checked = monetEnabled,
                        onCheckedChange = { on ->
                            onColorSource(
                                if (on) {
                                    if (state.seedColor != null) ThemeColorSource.CUSTOM else ThemeColorSource.MONET
                                } else {
                                    ThemeColorSource.DEFAULT
                                },
                            )
                        },
                    )
                }
            }
            if (monetEnabled) {
                item(key = "palette-style") {
                    CardSegmentContainer(isFirst = false, isLast = false, horizontalPadding = 0.dp) {
                        WindowDropdownPreference(
                            title = stringResource(Res.string.theme_palette_style),
                            items = ThemePaletteStyle.entries.map { stringResource(paletteStyleRes(it)) },
                            selectedIndex = ThemePaletteStyle.entries.indexOf(state.paletteStyle),
                            onSelectedIndexChange = { index ->
                                onPaletteStyle(ThemePaletteStyle.entries[index])
                            },
                        )
                    }
                }
                item(key = "color-spec") {
                    CardSegmentContainer(isFirst = false, isLast = false, horizontalPadding = 0.dp) {
                        WindowDropdownPreference(
                            title = stringResource(Res.string.theme_color_spec),
                            summary = if (state.paletteStyle.supportsSpec2025) null
                            else stringResource(Res.string.theme_color_spec_unsupported),
                            items = listOf(
                                stringResource(Res.string.theme_color_spec_2021),
                                stringResource(Res.string.theme_color_spec_2025),
                            ),
                            selectedIndex = ThemeColorSpec.entries.indexOf(state.colorSpec),
                            enabled = state.paletteStyle.supportsSpec2025,
                            onSelectedIndexChange = { index ->
                                onColorSpec(ThemeColorSpec.entries[index])
                            },
                        )
                    }
                }
                item(key = "accent-color") {
                    CardSegmentContainer(isFirst = false, isLast = true, horizontalPadding = 0.dp) {
                        ArrowPreference(
                            title = stringResource(Res.string.theme_accent_color),
                            endActions = {
                                Text(
                                    text = state.seedColor?.let { "#%06X".format((it and 0xFFFFFF).toInt()) }
                                        ?: stringResource(Res.string.theme_accent_default),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                                )
                            },
                            onClick = { showColorPicker = true },
                        )
                    }
                }
            }
            if (blurSupported) {
                item(key = "blur") {
                    CardSegmentContainer(
                        isFirst = true,
                        isLast = false,
                        horizontalPadding = 0.dp,
                        modifier = Modifier.padding(top = PageVerticalPadding),
                    ) {
                        SwitchPreference(
                            title = stringResource(Res.string.theme_enable_blur),
                            summary = stringResource(Res.string.theme_enable_blur_summary),
                            checked = state.enableBlur,
                            onCheckedChange = onEnableBlur,
                        )
                    }
                }
            }
            item(key = "floating-bottom-bar") {
                CardSegmentContainer(
                    isFirst = !blurSupported,
                    isLast = false,
                    horizontalPadding = 0.dp,
                    modifier = if (!blurSupported) Modifier.padding(top = PageVerticalPadding) else Modifier,
                ) {
                    SwitchPreference(
                        title = stringResource(Res.string.theme_floating_bottom_bar),
                        summary = stringResource(Res.string.theme_floating_bottom_bar_summary),
                        checked = state.enableFloatingBottomBar,
                        onCheckedChange = onEnableFloatingBottomBar,
                    )
                }
            }
            if (blurSupported) {
                item(key = "floating-bottom-bar-glass") {
                    AnimatedVisibility(
                        visible = state.enableFloatingBottomBar,
                        enter = expandVertically(),
                        exit = shrinkVertically(),
                    ) {
                        CardSegmentContainer(
                            isFirst = false,
                            isLast = false,
                            horizontalPadding = 0.dp,
                        ) {
                            SwitchPreference(
                                title = stringResource(Res.string.theme_enable_glass),
                                summary = stringResource(Res.string.theme_enable_glass_summary),
                                checked = state.enableFloatingBottomBarBlur,
                                onCheckedChange = onEnableFloatingBottomBarBlur,
                            )
                        }
                    }
                }
            }
            item(key = "navigation-badge") {
                CardSegmentContainer(
                    isFirst = false,
                    isLast = true,
                    horizontalPadding = 0.dp,
                ) {
                    SwitchPreference(
                        title = stringResource(Res.string.theme_navigation_badge),
                        summary = stringResource(Res.string.theme_navigation_badge_summary),
                        checked = state.enableNavigationBadge,
                        onCheckedChange = onEnableNavigationBadge,
                    )
                }
            }
            if (predictiveBackSupported) {
                item(key = "predictive-back") {
                    CardSegmentContainer(
                        isFirst = true,
                        isLast = false,
                        modifier = Modifier.padding(top = PageVerticalPadding),
                        horizontalPadding = 0.dp,
                    ) {
                        SwitchPreference(
                            title = stringResource(Res.string.theme_predictive_back),
                            summary = stringResource(Res.string.theme_predictive_back_summary),
                            checked = state.enablePredictiveBack,
                            onCheckedChange = onEnablePredictiveBack,
                        )
                    }
                }
            }
            item(key = "page-scale") {
                CardSegmentContainer(
                    isFirst = !predictiveBackSupported,
                    isLast = true,
                    modifier = if (predictiveBackSupported) Modifier else Modifier.padding(top = PageVerticalPadding),
                    horizontalPadding = 0.dp,
                ) {
                    ArrowPreference(
                        title = stringResource(Res.string.theme_page_scale),
                        summary = stringResource(Res.string.theme_page_scale_summary),
                        endActions = { Text("${(sliderValue * 100).toInt()}%", color = MiuixTheme.colorScheme.onSurfaceVariantActions) },
                        onClick = { showScaleDialog = !showScaleDialog },
                        holdDownState = showScaleDialog,
                        bottomAction = {
                            Slider(
                                value = sliderValue,
                                onValueChange = { sliderValue = it },
                                onValueChangeFinished = { onPageScale(sliderValue) },
                                valueRange = 0.8f..1.1f,
                                showKeyPoints = true,
                                keyPoints = listOf(0.8f, 0.9f, 1f, 1.1f),
                                magnetThreshold = 0.01f,
                                hapticEffect = SliderDefaults.SliderHapticEffect.Step,
                            )
                        },
                    )
                }
            }
        }
        ScaleDialog(
            show = showScaleDialog,
            onDismissRequest = { showScaleDialog = false },
            scaleProvider = { state.pageScale },
            onScaleChange = onPageScale,
        )
        ColorPickerDialog(
            show = showColorPicker,
            initialColor = Color(state.seedColor ?: DEFAULT_SEED_COLOR),
            title = stringResource(Res.string.theme_custom_color),
            onDismissRequest = { showColorPicker = false },
            onConfirm = { color ->
                showColorPicker = false
                onSeedColor(color.toArgb().toLong() and 0xFFFFFFFFL)
            },
            onResetRequest = {
                showColorPicker = false
                onSeedColor(null)
            },
        )
    }
}
