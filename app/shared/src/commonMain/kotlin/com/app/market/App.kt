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
import androidx.compose.runtime.Composable
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
import com.app.market.domain.repository.InstallerPreferencesRepository
import com.app.market.domain.repository.ProfileRepository
import com.app.market.domain.repository.ThemePreferencesRepository
import com.app.market.domain.repository.UpdatePreferencesRepository
import com.app.market.platform.ApplyPredictiveBackPreference
import com.app.market.platform.UiPlatform
import com.app.market.resources.Res
import com.app.market.resources.cancel
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
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun App(
    externalDetailPackageName: String? = null,
    externalDetailQuery: String? = null,
    onExternalDetailConsumed: (String) -> Unit = {},
    externalSearchKeyword: String? = null,
    onExternalSearchConsumed: (String) -> Unit = {},
    externalOpenDownloads: Boolean = false,
    onExternalDownloadsConsumed: () -> Unit = {},
) {
    val profileStore = koinInject<ProfileRepository>()
    val downloads = koinInject<DownloadRepository>()
    val uiPlatform = koinInject<UiPlatform>()
    val updatePrefs = koinInject<UpdatePreferencesRepository>()
    val themePrefs = koinInject<ThemePreferencesRepository>()
    val colorSchemeModeToken by themePrefs.colorSchemeMode.collectAsStateWithLifecycle()
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
    val controller = remember(colorSchemeMode) { ThemeController(colorSchemeMode) }
    ApplyPredictiveBackPreference(enablePredictiveBack)
    MiuixTheme(controller = controller) {
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
                externalDetailPackageName = externalDetailPackageName,
                externalDetailQuery = externalDetailQuery,
                onExternalDetailConsumed = onExternalDetailConsumed,
                externalSearchKeyword = externalSearchKeyword,
                onExternalSearchConsumed = onExternalSearchConsumed,
                externalOpenDownloads = externalOpenDownloads,
                onExternalDownloadsConsumed = onExternalDownloadsConsumed,
            )
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
