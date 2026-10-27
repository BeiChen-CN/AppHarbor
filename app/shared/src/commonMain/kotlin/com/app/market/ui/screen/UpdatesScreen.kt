package com.app.market.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.market.domain.model.download.DownloadPhase
import com.app.market.domain.model.download.DownloadState
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.platform.UiPlatform
import com.app.market.resources.*
import com.app.market.ui.component.AppTextButton
import com.app.market.domain.model.update.SourceCheckFailure
import com.app.market.viewmodel.UpdatesUiState
import com.app.market.ui.component.UpdateSourceDialog
import com.app.market.ui.util.appSourceLabel
import com.app.market.ui.component.AppActionButton
import com.app.market.ui.component.AppButton
import com.app.market.ui.component.AppIcon
import com.app.market.ui.component.CardSegmentContainer
import com.app.market.ui.component.LoadingBox
import com.app.market.ui.component.MainTabScaffold
import com.app.market.ui.component.PageVerticalPadding
import com.app.market.ui.model.AppActionKind
import com.app.market.ui.util.appDisplayName
import com.app.market.ui.util.formatSize
import com.app.market.viewmodel.UpdatesViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun UpdatesTab(
    viewModel: UpdatesViewModel,
    bottomPadding: Dp,
    onOpenDetail: (MarketAppInfo) -> Unit,
) {
    val uiPlatform = koinInject<UiPlatform>()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val featureSettings by viewModel.featureSettings.collectAsStateWithLifecycle()
    val downloadStates = viewModel.downloadStates.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var sourceApp by remember { mutableStateOf<MarketAppInfo?>(null) }

    MainTabScaffold(
        title = stringResource(Res.string.nav_updates),
        actions = {
            AppTextButton(stringResource(if (state.loading) Res.string.cancel_check else Res.string.check_again),
                if (state.loading) viewModel::cancelCheck else viewModel::refresh, Modifier.padding(end = 12.dp))
        },
    ) { topPadding, backdropModifier, scrollBehavior ->
        val listPadding = PaddingValues(
            start = 12.dp,
            end = 12.dp,
            top = topPadding + PageVerticalPadding,
            bottom = bottomPadding + PageVerticalPadding,
        )
        val contentModifier = Modifier.fillMaxSize().then(backdropModifier).scrollEndHaptic()
            .overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection)
        if (state.updates.isNotEmpty()) {
            UpdatesCardList(
                updates = state.updates,
                checkState = state,
                aggregate = featureSettings.aggregateUpdates,
                downloadStates = downloadStates,
                listState = listState,
                listPadding = listPadding,
                viewModel = viewModel,
                onOpenDetail = onOpenDetail,
                onChooseSource = if (featureSettings.aggregateUpdates) ({ sourceApp = it }) else null,
                modifier = contentModifier,
            )
        } else {
            LazyColumn(modifier = contentModifier, contentPadding = listPadding,
                verticalArrangement = Arrangement.spacedBy(0.dp)) {
                updateCheckStatus(state, featureSettings.aggregateUpdates, viewModel)
                item("result") {
                    when {
                        state.appListPermissionRequired -> AppListPermissionRequiredState(uiPlatform = uiPlatform)
                        state.loading -> LoadingBox(Modifier.fillMaxWidth().height(120.dp))
                        else -> Text(stringResource(when {
                            state.cancelled -> Res.string.check_cancelled_hint
                            state.checkFailed -> Res.string.update_check_failed
                            state.failedSources.isNotEmpty() -> Res.string.partial_check_hint
                            else -> Res.string.no_updates
                        }), modifier = Modifier.fillMaxWidth().padding(24.dp), textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
    sourceApp?.let { app ->
        UpdateSourceDialog(app.displayName, app.packageName, viewModel.updateCandidates(app.packageName),
            featureSettings.preferredUpdateSources[app.packageName], featureSettings.aggregateSources,
            onSelect = { viewModel.setPreferredUpdateSource(app.packageName, it) }, onDismiss = { sourceApp = null })
    }
}

private fun LazyListScope.updateCheckStatus(state: UpdatesUiState, aggregate: Boolean, viewModel: UpdatesViewModel) {
    if (!aggregate && !state.cancelled) return
    val failures = state.failures.entries.toList()
    val retry = !state.loading && state.failedSources.isNotEmpty()
    val headerLast = failures.isEmpty() && !retry
    item("check-progress") {
        CardSegmentContainer(isFirst = true, isLast = headerLast, horizontalPadding = 0.dp,
            modifier = Modifier.padding(bottom = if (headerLast) 20.dp else 0.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (aggregate) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(stringResource(Res.string.aggregate_status, state.sourcesCompleted, state.sourcesTotal))
                        // 有来源还在请求时补一句「正在检查 X」，让用户知道进度卡在哪一步。
                        if (state.loading && state.activeSources.isNotEmpty()) {
                            // joinToString 不是 inline，lambda 里调不了 @Composable，先 map 出标签再拼。
                            val activeLabels = state.activeSources.map { appSourceLabel(it) }.joinToString("、")
                            Text(
                                text = stringResource(Res.string.aggregate_active_sources, activeLabels),
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.body2,
                                textAlign = TextAlign.End,
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
                if (state.cancelled) Text(stringResource(Res.string.check_cancelled_hint))
            }
        }
    }
    itemsIndexed(failures, key = { _, entry -> "check-failure-${entry.key.token}" }) { index, (source, failure) ->
        val last = index == failures.lastIndex && !retry
        CardSegmentContainer(isFirst = false, isLast = last, horizontalPadding = 0.dp,
            modifier = Modifier.padding(bottom = if (last) 20.dp else 0.dp)) {
            Text(stringResource(Res.string.source_check_failure, appSourceLabel(source),
                stringResource(if (failure == SourceCheckFailure.TIMEOUT) Res.string.source_timeout else Res.string.source_request_failed)),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary, style = MiuixTheme.textStyles.body2)
        }
    }
    if (retry) item("retry-failed-sources") {
        CardSegmentContainer(isFirst = false, isLast = true, horizontalPadding = 0.dp, modifier = Modifier.padding(bottom = 20.dp)) {
            AppTextButton(stringResource(Res.string.retry_failed_sources), viewModel::retryFailedSources, Modifier.padding(horizontal = 8.dp))
        }
    }
}

@Composable
private fun UpdatesCardList(
    updates: List<MarketAppInfo>,
    checkState: UpdatesUiState,
    aggregate: Boolean,
    downloadStates: State<Map<String, DownloadState>>,
    listState: LazyListState,
    listPadding: PaddingValues,
    viewModel: UpdatesViewModel,
    onOpenDetail: (MarketAppInfo) -> Unit,
    onChooseSource: ((MarketAppInfo) -> Unit)?,
    modifier: Modifier
) {
    val updateActionText = stringResource(Res.string.update)
    val totalUpdateSize = remember(updates) {
        updates.sumOf { app -> app.deltaSize.takeIf { it > 0 } ?: app.apkSize }
    }
    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(0.dp),
        contentPadding = listPadding,
    ) {
        updateCheckStatus(checkState, aggregate, viewModel)
        item(key = "summary") {
            Card(
                modifier = Modifier
                    .padding(bottom = 20.dp)
                    .fillMaxWidth(),
                insideMargin = PaddingValues(16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            stringResource(Res.string.num_updates_pending, updates.size),
                            color = MiuixTheme.colorScheme.onSurface,
                            style = MiuixTheme.textStyles.headline1.copy(
                                fontWeight = FontWeight.Medium,
                                lineHeight = 22.sp,
                            ),
                        )
                        Text(
                            formatSize(totalUpdateSize),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
                        )
                    }
                    AppButton(
                        text = stringResource(Res.string.update_all),
                        onClick = { viewModel.downloadAll(updates) },
                        cornerRadius = 100.dp,
                        minHeight = 34.dp,
                        insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                    )
                }
            }
        }
        itemsIndexed(updates, key = { _, app -> app.packageName }) { index, app ->
            val packageName = app.packageName
            val downloadState by remember(packageName) {
                derivedStateOf { downloadStates.value[packageName] }
            }
            CardSegmentContainer(
                isFirst = index == 0,
                isLast = index == updates.lastIndex,
                modifier = Modifier.animateItem(placementSpec = null),
                horizontalPadding = 0.dp,
            ) {
                UpdateItemRow(
                    app = app,
                    downloadState = downloadState,
                    onOpenDetail = { onOpenDetail(app) },
                    onAction = { viewModel.download(app) },
                    onInstallDownloaded = viewModel::installDownloaded,
                    onCancel = viewModel::cancelDownload,
                    onIgnoreOnce = { viewModel.ignoreOnce(app) },
                    onIgnorePermanent = { viewModel.ignorePermanently(app) },
                    actionText = updateActionText,
                    onChooseSource = onChooseSource?.let { { it(app) } },
                )
            }
        }
    }
}

// 也被更新历史页复用：折叠态把日志空白压平成单行
internal val WhitespaceRegex = Regex("\\s+")

@Composable
private fun UpdateItemRow(
    app: MarketAppInfo,
    downloadState: DownloadState?,
    actionText: String,
    onOpenDetail: () -> Unit,
    onAction: () -> Unit,
    onInstallDownloaded: (String) -> Unit,
    onCancel: (String) -> Unit,
    onIgnoreOnce: () -> Unit,
    onIgnorePermanent: () -> Unit,
    onChooseSource: (() -> Unit)? = null,
) {
    var expanded by remember(app.packageName, app.versionCode) { mutableStateOf(false) }
    val changeLog = app.changeLog.ifBlank { stringResource(Res.string.no_change_log) }
    // 折叠态把换行等空白压平成单行连续文字，避免首行只是「新增」之类的小标题
    val collapsedChangeLog = remember(changeLog) {
        changeLog.replace(WhitespaceRegex, " ").trim()
    }
    // 「展开」除了展开日志还负责露出忽略操作，因此折叠态始终显示，不做溢出检测
    val showMore = !expanded
    val changeLogStyle = MiuixTheme.textStyles.body2
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDetail)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppIcon(
                    url = app.icon,
                    contentDescription = appDisplayName(app.displayName, app.source),
                    size = 48.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = appDisplayName(app.displayName, app.source),
                        color = MiuixTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MiuixTheme.textStyles.headline1.copy(
                            fontWeight = FontWeight.Medium,
                            lineHeight = 22.sp,
                        ),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(Res.string.version_transition_label),
                            maxLines = 1,
                            style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        Text(
                            text = stringResource(
                                Res.string.version_transition,
                                app.installedVersionName.ifBlank { "-" },
                                app.versionName,
                            ),
                            modifier = Modifier
                                .padding(start = 4.dp)
                                .basicMarquee(iterations = Int.MAX_VALUE),
                            maxLines = 1,
                            style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    Text(
                        text = updateSizeText(app),
                        maxLines = 1,
                        style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 暂停态下进度按钮只能恢复下载，这里单独给一个取消入口，
                // 避免「只能暂停不能取消」。安装阶段没有可取消的传输，不显示该按钮。
                if (downloadState != null &&
                    downloadState.phase != DownloadPhase.INSTALLING &&
                    downloadState.phase != DownloadPhase.AWAITING_USER_ACTION &&
                    downloadState.phase != DownloadPhase.DOWNLOADED
                ) {
                    AppTextButton(
                        text = stringResource(Res.string.cancel),
                        onClick = { onCancel(app.packageName) },
                        minHeight = 34.dp,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 4.dp),
                    )
                }
                AppActionButton(
                    packageName = app.packageName,
                    actionText = actionText,
                    actionKind = AppActionKind.UPDATE,
                    downloadState = downloadState,
                    onAction = onAction,
                    onResumeDownload = onAction,
                    onInstallDownloaded = onInstallDownloaded,
                    onCancel = onCancel,
                    minHeight = 34.dp,
                    insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth()) {
            SelectionContainer(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (expanded) changeLog else collapsedChangeLog,
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Clip,
                    color = MiuixTheme.colorScheme.onSurfaceSecondary,
                    style = changeLogStyle,
                )
            }
            if (showMore) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .height(IntrinsicSize.Min)
                        .clickable(interactionSource = null, indication = null) {
                            expanded = true
                        },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.width(58.dp).fillMaxHeight().background(
                            Brush.horizontalGradient(
                                0f to Color.Transparent,
                                1f to MiuixTheme.colorScheme.surfaceContainer,
                            ),
                        ),
                    )
                    Text(
                        text = stringResource(Res.string.expand_change_log),
                        modifier = Modifier.background(MiuixTheme.colorScheme.surfaceContainer),
                        color = MiuixTheme.colorScheme.primary,
                        style = changeLogStyle,
                    )
                }
            }
        }
        if (onChooseSource != null) {
            AppTextButton(stringResource(Res.string.update_versions_source, appSourceLabel(app.source)), onChooseSource)
        }
        if (expanded) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = stringResource(Res.string.ignore_once),
                        modifier = Modifier.clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onIgnoreOnce,
                        ),
                        color = MiuixTheme.colorScheme.primary,
                        style = changeLogStyle,
                    )
                    Text(
                        text = stringResource(Res.string.ignore_permanent),
                        modifier = Modifier.clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onIgnorePermanent,
                        ),
                        color = MiuixTheme.colorScheme.primary,
                        style = changeLogStyle,
                    )
                }
                Text(
                    text = stringResource(Res.string.collapse_change_log),
                    modifier = Modifier.clickable(interactionSource = null, indication = null) {
                        expanded = false
                    },
                    color = MiuixTheme.colorScheme.primary,
                    style = changeLogStyle,
                )
            }
        }
    }
}

internal fun updateSizeText(app: MarketAppInfo) = buildAnnotatedString {
    val fullSize = formatSize(app.apkSize)
    val hasDelta = app.deltaSize > 0L && app.deltaSize < app.apkSize
    if (!hasDelta) {
        append(fullSize)
        return@buildAnnotatedString
    }

    append(formatSize(app.deltaSize))
    append("  ")
    val fullSizeStart = length
    append(fullSize)
    addStyle(
        SpanStyle(textDecoration = TextDecoration.LineThrough),
        fullSizeStart,
        length,
    )
}

@Composable
private fun AppListPermissionRequiredState(
    modifier: Modifier = Modifier,
    uiPlatform: UiPlatform,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(Res.string.app_list_permission_required),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            style = MiuixTheme.textStyles.main,
        )
        Text(
            stringResource(Res.string.app_list_permission_refresh_hint),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            style = MiuixTheme.textStyles.body2,
        )
        AppButton(
            text = stringResource(Res.string.go_to_settings),
            onClick = { uiPlatform.openAppSettings() },
            modifier = Modifier.fillMaxWidth(),
            cornerRadius = 100.dp,
            colors = ButtonDefaults.buttonColorsPrimary(),
        )
    }
}
