package com.app.market

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.app.market.domain.model.theme.ThemeColorMode
import com.app.market.domain.model.theme.ThemeColorSource
import com.app.market.domain.model.theme.ThemeColorSpec
import com.app.market.domain.model.theme.ThemePaletteStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.market.domain.model.install.InstallUserAction
import com.app.market.domain.repository.DownloadRepository
import com.app.market.domain.repository.MeteredDownloadConfirmation
import com.app.market.domain.repository.InstallerPreferencesRepository
import com.app.market.domain.repository.ProfileRepository
import com.app.market.domain.repository.ThemePreferencesRepository
import com.app.market.domain.model.market.MarketLink
import com.app.market.domain.repository.UpdatePreferencesRepository
import com.app.market.platform.ApplyPredictiveBackPreference
import com.app.market.platform.UiPlatform
import com.app.market.resources.Res
import com.app.market.resources.cancel
import com.app.market.resources.metered_download_title
import com.app.market.resources.metered_download_message
import com.app.market.resources.metered_download_size
import com.app.market.resources.metered_download_size_unknown
import com.app.market.resources.metered_download_continue
import com.app.market.resources.confirm
import com.app.market.resources.go_to_settings
import com.app.market.resources.install_failed_title
import com.app.market.resources.install_permission_message
import com.app.market.resources.install_permission_settings_unavailable
import com.app.market.resources.install_permission_title
import com.app.market.resources.installer_delta_fallback_toast
import com.app.market.ui.component.AppTextButton
import com.app.market.ui.navigation.AppNavigation
import com.app.market.ui.theme.LocalEnableBlur
import com.app.market.ui.theme.LocalEnableFloatingBottomBar
import com.app.market.ui.theme.LocalEnableFloatingBottomBarBlur
import com.app.market.ui.theme.LocalEnableNavigationBadge
import com.app.market.ui.theme.parseColorSchemeMode
import com.app.market.ui.util.LocalStripAppNameSubtitle
import com.app.market.ui.util.formatSize
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.ThemeColorSpec as MiuixColorSpec
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle as MiuixPaletteStyle
import top.yukonga.miuix.kmp.window.WindowDialog

private const val DEFAULT_SEED_COLOR = 0xFF5B7CFFL

@Composable
fun App(
    externalDetailLink: MarketLink? = null,
    externalDetailQuery: String? = null,
    onExternalDetailConsumed: (MarketLink) -> Unit = {},
    externalSearchKeyword: String? = null,
    onExternalSearchConsumed: (String) -> Unit = {},
    externalOpenDownloads: Boolean = false,
    onExternalDownloadsConsumed: () -> Unit = {},
) {
    val profileStore = koinInject<ProfileRepository>()
    val downloads = koinInject<DownloadRepository>()
    val meteredConfirmation = koinInject<MeteredDownloadConfirmation>()
    val pendingDownloads by meteredConfirmation.pendingDownloads.collectAsStateWithLifecycle()
    val uiPlatform = koinInject<UiPlatform>()
    val updatePrefs = koinInject<UpdatePreferencesRepository>()
    val themePrefs = koinInject<ThemePreferencesRepository>()
    val colorSchemeModeToken by themePrefs.colorSchemeMode.collectAsStateWithLifecycle()
    val colorMode by themePrefs.colorMode.collectAsStateWithLifecycle()
    val colorSource by themePrefs.colorSource.collectAsStateWithLifecycle()
    val seedColor by themePrefs.seedColor.collectAsStateWithLifecycle()
    val paletteStyle by themePrefs.paletteStyle.collectAsStateWithLifecycle()
    val colorSpec by themePrefs.colorSpec.collectAsStateWithLifecycle()
    val amoledDark by themePrefs.amoledDark.collectAsStateWithLifecycle()
    val enableBlur by themePrefs.enableBlur.collectAsStateWithLifecycle()
    val enableFloatingBottomBar by themePrefs.enableFloatingBottomBar.collectAsStateWithLifecycle()
    val enableFloatingBottomBarBlur by themePrefs.enableFloatingBottomBarBlur.collectAsStateWithLifecycle()
    val enableNavigationBadge by themePrefs.enableNavigationBadge.collectAsStateWithLifecycle()
    val enablePredictiveBack by themePrefs.enablePredictiveBack.collectAsStateWithLifecycle()
    val pageScale by themePrefs.pageScale.collectAsStateWithLifecycle()
    val stripAppNameSubtitle by updatePrefs.stripAppNameSubtitle.collectAsStateWithLifecycle()
    val pendingUserAction by downloads.pendingUserAction.collectAsStateWithLifecycle(
        minActiveState = Lifecycle.State.RESUMED,
    )
    val failureMessage = (pendingUserAction as? InstallUserAction.InstallationFailed)?.message
    var retainedFailureMessage by remember { mutableStateOf("") }
    LaunchedEffect(failureMessage) {
        if (failureMessage != null) retainedFailureMessage = failureMessage
    }
    val settingsUnavailableMessage = stringResource(Res.string.install_permission_settings_unavailable)
    val installerPrefs = koinInject<InstallerPreferencesRepository>()
    LaunchedEffect(Unit) {
        downloads.deltaFallbacks.collect { fallback ->
            if (!installerPrefs.deltaFallbackNoticeEnabled()) return@collect
            uiPlatform.showToast(
                getString(Res.string.installer_delta_fallback_toast, fallback.artifactName, fallback.reason)
            )
        }
    }
    LaunchedEffect(Unit) { runCatching { profileStore.syncFromServerIfDue() } }
    val colorSchemeMode = remember(colorSchemeModeToken) { parseColorSchemeMode(colorSchemeModeToken) }
    val controller = remember(colorMode, colorSource, seedColor, paletteStyle, colorSpec, colorSchemeMode) {
        val darkMode = when (colorMode) {
            ThemeColorMode.SYSTEM -> ColorSchemeMode.System
            ThemeColorMode.LIGHT -> ColorSchemeMode.Light
            ThemeColorMode.DARK -> ColorSchemeMode.Dark
        }
        val monetMode = when (colorMode) {
            ThemeColorMode.SYSTEM -> ColorSchemeMode.MonetSystem
            ThemeColorMode.LIGHT -> ColorSchemeMode.MonetLight
            ThemeColorMode.DARK -> ColorSchemeMode.MonetDark
        }
        val miuixPalette = when (paletteStyle) {
            ThemePaletteStyle.TONAL_SPOT -> MiuixPaletteStyle.TonalSpot
            ThemePaletteStyle.NEUTRAL -> MiuixPaletteStyle.Neutral
            ThemePaletteStyle.VIBRANT -> MiuixPaletteStyle.Vibrant
            ThemePaletteStyle.EXPRESSIVE -> MiuixPaletteStyle.Expressive
            ThemePaletteStyle.RAINBOW -> MiuixPaletteStyle.Rainbow
            ThemePaletteStyle.FRUIT_SALAD -> MiuixPaletteStyle.FruitSalad
            ThemePaletteStyle.MONOCHROME -> MiuixPaletteStyle.Monochrome
            ThemePaletteStyle.FIDELITY -> MiuixPaletteStyle.Fidelity
            ThemePaletteStyle.CONTENT -> MiuixPaletteStyle.Content
        }
        val miuixSpec = when (colorSpec) {
            ThemeColorSpec.SPEC_2021 -> MiuixColorSpec.Spec2021
            ThemeColorSpec.SPEC_2025 -> MiuixColorSpec.Spec2025
        }
        when (colorSource) {
            ThemeColorSource.DEFAULT -> ThemeController(
                colorSchemeMode = darkMode,
                paletteStyle = miuixPalette,
                colorSpec = miuixSpec,
            )
            // Monet 必须让 MIUIX 读取系统壁纸；传入固定 keyColor 会绕过 platformDynamicColors。
            ThemeColorSource.MONET -> ThemeController(
                colorSchemeMode = monetMode,
                paletteStyle = miuixPalette,
                colorSpec = miuixSpec,
            )
            ThemeColorSource.CUSTOM -> ThemeController(
                colorSchemeMode = monetMode,
                keyColor = Color(seedColor ?: DEFAULT_SEED_COLOR),
                paletteStyle = miuixPalette,
                colorSpec = miuixSpec,
            )
        }
    }
    ApplyPredictiveBackPreference(enablePredictiveBack)
    val baseColors = controller.currentColors()
    val themeIsDark = when (controller.colorSchemeMode) {
        ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> true
        ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> false
        ColorSchemeMode.System, ColorSchemeMode.MonetSystem -> isSystemInDarkTheme()
    }
    val themeColors = if (amoledDark && themeIsDark) {
        baseColors.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainer = Color.Black,
            surfaceContainerHigh = Color(0xFF0C0C0C),
            surfaceContainerHighest = Color(0xFF151515),
        )
    } else {
        baseColors
    }
    MiuixTheme(colors = themeColors) {
        val systemDensity = LocalDensity.current
        val scaledDensity = remember(systemDensity, pageScale) {
            Density(systemDensity.density * pageScale, systemDensity.fontScale)
        }
        CompositionLocalProvider(
            LocalContentColor provides MiuixTheme.colorScheme.onBackground,
            LocalStripAppNameSubtitle provides stripAppNameSubtitle,
            LocalDensity provides scaledDensity,
            LocalEnableBlur provides enableBlur,
            LocalEnableFloatingBottomBar provides enableFloatingBottomBar,
            LocalEnableFloatingBottomBarBlur provides enableFloatingBottomBarBlur,
            LocalEnableNavigationBadge provides enableNavigationBadge,
        ) {
            AppNavigation(
                externalDetailLink = externalDetailLink,
                externalDetailQuery = externalDetailQuery,
                onExternalDetailConsumed = onExternalDetailConsumed,
                externalSearchKeyword = externalSearchKeyword,
                onExternalSearchConsumed = onExternalSearchConsumed,
                externalOpenDownloads = externalOpenDownloads,
                onExternalDownloadsConsumed = onExternalDownloadsConsumed,
            )
            WindowDialog(
                show = pendingDownloads.isNotEmpty(),
                title = stringResource(Res.string.metered_download_title),
                onDismissRequest = meteredConfirmation::dismissDownloads,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(Res.string.metered_download_message, pendingDownloads.size))
                    val knownSize = pendingDownloads.sumOf { it.meta.size.coerceAtLeast(0L) }
                    if (knownSize > 0L) {
                        Text(stringResource(Res.string.metered_download_size, formatSize(knownSize)))
                    }
                    if (pendingDownloads.any { it.meta.size <= 0L }) {
                        Text(stringResource(Res.string.metered_download_size_unknown))
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        AppTextButton(
                            text = stringResource(Res.string.cancel),
                            onClick = meteredConfirmation::dismissDownloads,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(16.dp))
                        AppTextButton(
                            text = stringResource(Res.string.metered_download_continue),
                            onClick = meteredConfirmation::confirmDownloads,
                            colors = ButtonDefaults.textButtonColorsPrimary(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            InstallFailureDialog(
                show = failureMessage != null,
                message = failureMessage ?: retainedFailureMessage,
                onDismiss = downloads::consumePendingUserAction,
            )
            UnknownSourcesPermissionDialog(
                show = pendingUserAction == InstallUserAction.GrantUnknownSourcesPermission,
                onDismiss = downloads::consumePendingUserAction,
                onOpenSettings = {
                    downloads.consumePendingUserAction()
                    if (!uiPlatform.openUnknownSourcesSettings()) {
                        uiPlatform.showToast(settingsUnavailableMessage)
                    }
                },
            )
        }
    }
}

@Composable
private fun InstallFailureDialog(
    show: Boolean,
    message: String,
    onDismiss: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(Res.string.install_failed_title),
        onDismissRequest = onDismiss,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SelectionContainer(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = message,
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            AppTextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(Res.string.confirm),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun UnknownSourcesPermissionDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(Res.string.install_permission_title),
        onDismissRequest = onDismiss,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = stringResource(Res.string.install_permission_message),
                color = MiuixTheme.colorScheme.onSurface,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                AppTextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(Res.string.cancel),
                    onClick = onDismiss,
                )
                Spacer(Modifier.width(16.dp))
                AppTextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(Res.string.go_to_settings),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = onOpenSettings,
                )
            }
        }
    }
}
