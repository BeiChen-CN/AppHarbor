package com.app.market.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.market.domain.model.download.DownloadPhase
import com.app.market.domain.model.download.DownloadState
import com.app.market.resources.Res
import com.app.market.resources.waiting_unmetered
import com.app.market.resources.download
import com.app.market.resources.download_failed
import com.app.market.resources.download_finished
import com.app.market.resources.download_paused
import com.app.market.resources.download_ready
import com.app.market.resources.downloading_apps
import com.app.market.resources.downloading_apps_empty
import com.app.market.resources.downloading_short
import com.app.market.resources.installing_short
import com.app.market.resources.waiting_install_confirmation
import com.app.market.ui.component.AppActionButton
import com.app.market.ui.component.AppIcon
import com.app.market.ui.component.CardSegmentContainer
import com.app.market.ui.component.MarketScaffold
import com.app.market.ui.component.PageVerticalPadding
import com.app.market.ui.model.AppActionKind
import com.app.market.ui.util.appDisplayName
import com.app.market.viewmodel.DownloadingAppsViewModel
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun DownloadingAppsScreen(
    viewModel: DownloadingAppsViewModel,
    onOpenDetail: (DownloadState) -> Unit,
    onBack: () -> Unit,
) {
    val states by viewModel.downloadStates.collectAsStateWithLifecycle()
    val entries = remember(states) { states.values.sortedBy(DownloadState::displayName) }
    val downloadText = stringResource(Res.string.download)

    MarketScaffold(
        title = stringResource(Res.string.downloading_apps),
        onBack = onBack,
    ) { innerPadding, backdropModifier, scrollBehavior ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(backdropModifier)
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + PageVerticalPadding,
                bottom = innerPadding.calculateBottomPadding() + PageVerticalPadding,
            ),
        ) {
            if (entries.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(Res.string.downloading_apps_empty),
                            style = MiuixTheme.textStyles.main,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            } else {
                itemsIndexed(entries, key = { _, state -> state.packageName }) { index, state ->
                    CardSegmentContainer(
                        isFirst = index == 0,
                        isLast = index == entries.lastIndex,
                        modifier = Modifier.animateItem(placementSpec = null),
                    ) {
                        DownloadingAppRow(
                            state = state,
                            actionText = downloadText,
                            onOpenDetail = { onOpenDetail(state) },
                            onResume = { viewModel.resume(state) },
                            onInstallDownloaded = viewModel::installDownloaded,
                            onCancel = viewModel::pause,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadingAppRow(
    state: DownloadState,
    actionText: String,
    onOpenDetail: () -> Unit,
    onResume: () -> Unit,
    onInstallDownloaded: (String) -> Unit,
    onCancel: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AppIcon(
            url = state.icon,
            contentDescription = appDisplayName(state.displayName, state.source),
            size = 48.dp,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = appDisplayName(state.displayName, state.source),
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MiuixTheme.textStyles.headline1.copy(fontWeight = FontWeight.Medium),
            )
            Text(
                text = statusText(state),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        AppActionButton(
            packageName = state.packageName,
            actionText = actionText,
            actionKind = AppActionKind.INSTALL,
            downloadState = state,
            onAction = onResume,
            onResumeDownload = onResume,
            onInstallDownloaded = onInstallDownloaded,
            onCancel = onCancel,
            minHeight = 34.dp,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            modifier = Modifier.width(70.dp),
        )
    }
}

@Composable
private fun statusText(state: DownloadState): String = when (state.phase) {
    DownloadPhase.QUEUED -> stringResource(Res.string.downloading_short)
    DownloadPhase.DOWNLOADING -> state.progress?.let { "$it%" }
        ?: stringResource(Res.string.downloading_short)

    DownloadPhase.PAUSED -> if (state.waitingForUnmetered) stringResource(Res.string.waiting_unmetered) else state.progress
        ?.let { "${stringResource(Res.string.download_paused)} · $it%" }
        ?: stringResource(Res.string.download_paused)

    DownloadPhase.INSTALLING -> stringResource(Res.string.installing_short)
    DownloadPhase.AWAITING_USER_ACTION -> stringResource(Res.string.waiting_install_confirmation)
    DownloadPhase.DOWNLOADED -> if (state.isComplete) {
        stringResource(Res.string.download_ready)
    } else {
        stringResource(Res.string.download_finished)
    }

    DownloadPhase.FAILED -> state.errorMessage.ifBlank { stringResource(Res.string.download_failed) }
}
