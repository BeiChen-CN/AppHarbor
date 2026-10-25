package com.app.market.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.market.domain.model.update.AppUpdateInfo
import com.app.market.resources.Res
import com.app.market.resources.app_update_available_title
import com.app.market.resources.app_update_changelog
import com.app.market.resources.app_update_current
import com.app.market.resources.app_update_error_fallback
import com.app.market.resources.app_update_failed_title
import com.app.market.resources.app_update_install
import com.app.market.resources.app_update_latest
import com.app.market.resources.app_update_latest_title
import com.app.market.resources.app_update_later
import com.app.market.resources.app_update_open_release
import com.app.market.resources.app_update_size
import com.app.market.resources.app_update_up_to_date
import com.app.market.ui.util.formatSize
import misc.VersionInfo
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun AppUpdateDialog(
    update: AppUpdateInfo?,
    upToDate: Boolean,
    error: String,
    installSupported: Boolean,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    val title = when {
        error.isNotBlank() -> stringResource(Res.string.app_update_failed_title)
        update != null -> stringResource(Res.string.app_update_available_title)
        else -> stringResource(Res.string.app_update_latest_title)
    }
    WindowDialog(show = true, title = title, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            when {
                error.isNotBlank() -> Text(
                    text = error.ifBlank { stringResource(Res.string.app_update_error_fallback) },
                    color = MiuixTheme.colorScheme.error,
                    style = MiuixTheme.textStyles.main,
                )

                upToDate -> Text(
                    text = stringResource(
                        Res.string.app_update_up_to_date,
                        VersionInfo.VERSION_NAME,
                        VersionInfo.VERSION_CODE,
                    ),
                    style = MiuixTheme.textStyles.main,
                )

                update != null -> {
                    Text(
                        text = stringResource(
                            Res.string.app_update_current,
                            VersionInfo.VERSION_NAME,
                            VersionInfo.VERSION_CODE,
                        ),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Text(
                        text = stringResource(
                            Res.string.app_update_latest,
                            update.versionName,
                            update.versionCode,
                        ),
                        style = MiuixTheme.textStyles.body2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    if (update.size > 0L) {
                        Text(
                            text = stringResource(Res.string.app_update_size, formatSize(update.size)),
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    if (update.changelog.isNotBlank()) {
                        Text(
                            text = stringResource(Res.string.app_update_changelog),
                            style = MiuixTheme.textStyles.subtitle,
                            modifier = Modifier.padding(top = 14.dp),
                        )
                        Text(
                            text = update.changelog,
                            style = MiuixTheme.textStyles.body2,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 260.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(top = 4.dp),
                        )
                    }
                }
            }

            AppTextButton(
                text = stringResource(Res.string.app_update_later),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            )
            if (update != null && error.isBlank()) {
                AppButton(
                    text = stringResource(
                        if (installSupported) Res.string.app_update_install else Res.string.app_update_open_release,
                    ),
                    onClick = {
                        if (installSupported) onInstall() else onOpenRelease(update.releaseUrl)
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}
