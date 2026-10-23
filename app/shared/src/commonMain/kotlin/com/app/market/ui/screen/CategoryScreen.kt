package com.app.market.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.market.domain.model.market.AppCategory
import com.app.market.domain.model.market.AppSubCategory
import com.app.market.domain.model.market.GameSubCategory
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.resources.Res
import com.app.market.resources.category_education
import com.app.market.resources.category_media
import com.app.market.resources.category_office
import com.app.market.resources.category_shopping
import com.app.market.resources.category_social
import com.app.market.resources.category_sports
import com.app.market.resources.category_tools
import com.app.market.resources.game_category_action
import com.app.market.resources.game_category_all
import com.app.market.resources.game_category_card
import com.app.market.resources.game_category_casual
import com.app.market.resources.game_category_fighting
import com.app.market.resources.game_category_flight
import com.app.market.resources.game_category_kids
import com.app.market.resources.game_category_racing
import com.app.market.resources.game_category_rpg
import com.app.market.resources.game_category_runner
import com.app.market.resources.game_category_strategy
import com.app.market.resources.nav_apps
import com.app.market.resources.nav_categories
import com.app.market.resources.nav_games
import com.app.market.resources.no_results
import com.app.market.resources.open
import com.app.market.resources.reserve
import com.app.market.resources.retry
import com.app.market.resources.update
import com.app.market.ui.component.AppRow
import com.app.market.ui.component.AppTextButton
import com.app.market.ui.component.CardSegmentContainer
import com.app.market.ui.component.LoadingBox
import com.app.market.ui.component.MainTabScaffold
import com.app.market.ui.component.PageVerticalPadding
import com.app.market.ui.model.AppActionKind
import com.app.market.ui.util.installActionText
import com.app.market.viewmodel.CategoryViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryTab(
    viewModel: CategoryViewModel,
    bottomPadding: Dp,
    onOpenDetail: (MarketAppInfo) -> Unit,
) {
    var category by rememberSaveable { mutableStateOf(AppCategory.GAMES) }
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val states by viewModel.states.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val state = states[category] ?: com.app.market.viewmodel.CategoryUiState()
    val listState = rememberLazyListState()

    LaunchedEffect(category, selection) { viewModel.ensureLoaded(category) }
    LaunchedEffect(listState, category) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= 0 && info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
        }.distinctUntilChanged().collect { if (it) viewModel.loadMore(category) }
    }

    val installText = installActionText()
    val updateText = stringResource(Res.string.update)
    val openText = stringResource(Res.string.open)
    val reserveText = stringResource(Res.string.reserve)

    MainTabScaffold(title = stringResource(Res.string.nav_categories)) { topPadding, backdrop, scroll ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().then(backdrop).scrollEndHaptic().overScrollVertical()
                .nestedScroll(scroll.nestedScrollConnection),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = topPadding + PageVerticalPadding,
                bottom = bottomPadding + PageVerticalPadding,
            ),
        ) {
            item(key = "category-toggle") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CategoryToggle(
                        text = stringResource(Res.string.nav_games),
                        selected = category == AppCategory.GAMES,
                        onClick = { category = AppCategory.GAMES },
                    )
                    CategoryToggle(
                        text = stringResource(Res.string.nav_apps),
                        selected = category == AppCategory.APPS,
                        onClick = { category = AppCategory.APPS },
                    )
                }
            }
            item(key = "sub-categories") {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (category == AppCategory.GAMES) {
                        GameSubCategory.entries.forEach { sub ->
                            CategoryToggle(
                                text = stringResource(gameSubCategoryRes(sub)),
                                selected = selection.gameSubCategory == sub,
                                onClick = { viewModel.selectGameSubCategory(sub) },
                            )
                        }
                    } else {
                        AppSubCategory.entries.forEach { sub ->
                            CategoryToggle(
                                text = stringResource(appSubCategoryRes(sub)),
                                selected = selection.appSubCategory == sub,
                                onClick = { viewModel.selectAppSubCategory(sub) },
                            )
                        }
                    }
                }
            }
            if (state.loading && state.items.isEmpty()) {
                item(key = "loading") { LoadingBox(Modifier.fillMaxWidth().padding(vertical = 80.dp)) }
            }
            if (state.errorMessage.isNotBlank() && state.items.isEmpty()) {
                item(key = "error") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            text = state.errorMessage,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                        AppTextButton(text = stringResource(Res.string.retry), onClick = { viewModel.refresh(category) })
                    }
                }
            }
            if (!state.loading && state.items.isEmpty() && state.errorMessage.isBlank()) {
                item(key = "empty") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(Res.string.no_results),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
            itemsIndexed(state.items, key = { _, item -> item.app.packageName }) { index, item ->
                CardSegmentContainer(isFirst = index == 0, isLast = index == state.items.lastIndex) {
                    AppRow(
                        app = item.app,
                        actionText = when (item.actionKind) {
                            AppActionKind.INSTALL -> installText
                            AppActionKind.UPDATE -> updateText
                            AppActionKind.OPEN -> openText
                            AppActionKind.RESERVE -> reserveText
                        },
                        actionKind = item.actionKind,
                        downloadState = downloadStates[item.app.packageName],
                        onOpenDetail = { onOpenDetail(item.app) },
                        onAction = { viewModel.onAction(item) },
                        onInstallDownloaded = viewModel::installDownloaded,
                        onCancel = viewModel::cancelDownload,
                    )
                }
            }
            if (state.loadingMore) {
                item(key = "loading-more") { LoadingBox(Modifier.fillMaxWidth().padding(vertical = 16.dp)) }
            }
        }
    }
}

@Composable
private fun CategoryToggle(text: String, selected: Boolean, onClick: () -> Unit) {
    AppTextButton(
        text = text,
        onClick = onClick,
        colors = if (selected) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors(),
    )
}

private fun gameSubCategoryRes(value: GameSubCategory): StringResource = when (value) {
    GameSubCategory.ALL -> Res.string.game_category_all
    GameSubCategory.STRATEGY -> Res.string.game_category_strategy
    GameSubCategory.ACTION -> Res.string.game_category_action
    GameSubCategory.RACING -> Res.string.game_category_racing
    GameSubCategory.RPG -> Res.string.game_category_rpg
    GameSubCategory.CARD -> Res.string.game_category_card
    GameSubCategory.FIGHTING -> Res.string.game_category_fighting
    GameSubCategory.KIDS -> Res.string.game_category_kids
    GameSubCategory.CASUAL -> Res.string.game_category_casual
    GameSubCategory.FLIGHT -> Res.string.game_category_flight
    GameSubCategory.RUNNER -> Res.string.game_category_runner
}

private fun appSubCategoryRes(value: AppSubCategory): StringResource = when (value) {
    AppSubCategory.TOOLS -> Res.string.category_tools
    AppSubCategory.MEDIA -> Res.string.category_media
    AppSubCategory.SOCIAL -> Res.string.category_social
    AppSubCategory.OFFICE -> Res.string.category_office
    AppSubCategory.EDUCATION -> Res.string.category_education
    AppSubCategory.SHOPPING -> Res.string.category_shopping
    AppSubCategory.SPORTS -> Res.string.category_sports
}
