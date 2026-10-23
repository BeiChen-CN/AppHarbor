package com.app.market.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveable
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.PullToRefresh
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Replace
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.repository.PackageRepository
import com.app.market.domain.repository.RecommendationSources
import com.app.market.resources.*
import com.app.market.ui.component.*
import com.app.market.ui.theme.LocalEnableFloatingBottomBar
import com.app.market.viewmodel.DiscoveryViewModel
import com.app.market.viewmodel.UpdatesViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun DiscoveryTab(
    viewModel: DiscoveryViewModel,
    bottomPadding: Dp,
    onOpenDetail: (MarketAppInfo) -> Unit,
    updatesViewModel: UpdatesViewModel? = null,
    onViewUpdates: () -> Unit = {},
    onOpenSearch: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val featureSettings by viewModel.featureSettings.collectAsStateWithLifecycle()
    val updates = updatesViewModel?.uiState?.collectAsStateWithLifecycle()?.value
    val pendingUpdates = if (featureSettings.showRecommendationUpdates && updates != null && !updates.loading) updates.updates else emptyList()
    val packages = koinInject<PackageRepository>()
    val floatingBar = LocalEnableFloatingBottomBar.current
    var picker by remember { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    holder.SaveableStateProvider(state.source.token) {
        val listState = rememberLazyGridState()
        val current by rememberUpdatedState(state)
        var handledRefresh by rememberSaveable { mutableIntStateOf(0) }
        LaunchedEffect(state.refreshRevision) {
            if (state.refreshRevision > handledRefresh) listState.scrollToItem(0)
            handledRefresh = state.refreshRevision
        }
        LaunchedEffect(listState, viewModel) {
            snapshotFlow {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                current.items.size to (last >= 0 && last >= info.totalItemsCount - 3 && !current.loading &&
                    !current.loadingMore && !current.refreshing && current.hasMore && current.error.isBlank())
            }.distinctUntilChanged().collect { if (it.second) viewModel.loadMore() }
        }
        HidingMainTabScaffold(
            title = stringResource(Res.string.nav_recommendations),
            bottomTransitionHeight = if (floatingBar) bottomPadding + PageVerticalPadding else 0.dp,
            actions = {
                IconButton(onClick = { picker = true }) {
                    Icon(
                        imageVector = MiuixIcons.Replace,
                        contentDescription = stringResource(Res.string.switch_search_source),
                    )
                }
                IconButton(onClick = onOpenSearch) {
                    Icon(
                        imageVector = MiuixIcons.Search,
                        contentDescription = stringResource(Res.string.nav_search),
                    )
                }
            },
        ) { topPadding, backdrop, scroll ->
            PullToRefresh(isRefreshing = state.refreshing, onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize().then(backdrop), topAppBarScrollBehavior = scroll,
                contentPadding = PaddingValues(top = topPadding),
                refreshTexts = listOf(stringResource(Res.string.pull_refresh), stringResource(Res.string.release_refresh),
                    stringResource(Res.string.loading), stringResource(Res.string.refresh_finished))) {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(320.dp),
                    state = listState,
                    modifier = Modifier.fillMaxSize().scrollEndHaptic().overScrollVertical().nestedScroll(scroll.nestedScrollConnection),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = topPadding + PageVerticalPadding, bottom = bottomPadding + PageVerticalPadding),
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    if (pendingUpdates.isNotEmpty()) item("updates", span = { GridItemSpan(maxLineSpan) }) {
                        PendingUpdatesCard(pendingUpdates, onViewUpdates, Modifier.padding(bottom = 20.dp))
                    }
                    items(state.items, key = { it.rId.ifBlank { it.articleLink } }) { item ->
                        // 复用原今日卡片；推荐不额外展示来源名称，保留奖项与应用自身信息。
                        val award = when (state.source) {
                            AppSource.TAPTAP -> item.awardName.replace("TapTap", "", ignoreCase = true).trim()
                            AppSource.KUAIBAO -> item.awardName.replace("好游快爆", "").trim()
                            else -> item.awardName
                        }.ifBlank { stringResource(if (state.source == AppSource.XIAOMI) Res.string.golden_award else Res.string.nav_recommendations) }
                        FeaturedArticleCard(
                            item = item.copy(awardName = award),
                            fullCoverOverlay = state.source.capabilities.todayFullCoverOverlay,
                            modifier = Modifier.animateItem(placementSpec = null).padding(bottom = 20.dp),
                        ) {
                            val app = item.app ?: item.apps.firstOrNull()
                            if (app != null) onOpenDetail(app) else if (item.articleLink.isNotBlank()) packages.openLink(item.articleLink)
                        }
                    }
                    if (state.loading && state.items.isEmpty()) items(2, key = { "skeleton-$it" }) {
                        Card(modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp).height(300.dp)) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingBox(Modifier.fillMaxSize()) }
                        }
                    }
                    if (state.loadingMore) item("loading", span = { GridItemSpan(maxLineSpan) }) {
                        Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) { LoadingBox(Modifier.fillMaxSize()) }
                    }
                    if (!state.loading && state.items.isEmpty() && state.error.isBlank()) item("empty", span = { GridItemSpan(maxLineSpan) }) {
                        RecommendationStatus(stringResource(Res.string.no_content))
                    }
                    if (state.error.isNotBlank()) item("error", span = { GridItemSpan(maxLineSpan) }) {
                        RecommendationStatus(stringResource(when { state.refreshFailed -> Res.string.recommendations_refresh_failed
                            state.items.isNotEmpty() -> Res.string.recommendations_more_failed
                            else -> Res.string.recommendations_load_failed }), viewModel::retry)
                    }
                }
            }
        }
    }
    SourcePickerDialog(picker, stringResource(Res.string.recommendation_source), RecommendationSources, state.source,
        viewModel::selectSource, { picker = false })
}

@Composable
private fun RecommendationStatus(message: String, onRetry: (() -> Unit)? = null) {
    Card(modifier = Modifier.fillMaxWidth(), insideMargin = PaddingValues(24.dp)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(message, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            if (onRetry != null) AppTextButton(stringResource(Res.string.retry), onRetry)
        }
    }
}
