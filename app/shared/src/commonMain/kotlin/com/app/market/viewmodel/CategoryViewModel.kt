package com.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.market.domain.model.download.DownloadState
import com.app.market.domain.model.market.AppCategory
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.AppSubCategory
import com.app.market.domain.model.market.GameSubCategory
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.isDownloadBlocked
import com.app.market.domain.model.market.isReservation
import com.app.market.domain.repository.DownloadRepository
import com.app.market.domain.repository.MarketSourceRepository
import com.app.market.domain.repository.PackageRepository
import com.app.market.platform.UiPlatform
import com.app.market.ui.model.AppActionKind
import com.app.market.ui.model.SearchResultItem
import com.app.market.ui.model.resolveActionKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class CategoryUiState(
    val items: List<SearchResultItem> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val errorMessage: String = "",
)

@Immutable
data class CategorySelection(
    val appSubCategory: AppSubCategory = AppSubCategory.TOOLS,
    val gameSubCategory: GameSubCategory = GameSubCategory.ALL,
)

/** 游戏 / 应用分区浏览。当前统一走小米公开分类接口。 */
class CategoryViewModel(
    private val sources: MarketSourceRepository,
    private val packages: PackageRepository,
    private val downloads: DownloadRepository,
    private val uiPlatform: UiPlatform,
) : ViewModel() {
    private val _selection = MutableStateFlow(CategorySelection())
    val selection: StateFlow<CategorySelection> = _selection.asStateFlow()

    private val _states = MutableStateFlow<Map<AppCategory, CategoryUiState>>(emptyMap())
    val states: StateFlow<Map<AppCategory, CategoryUiState>> = _states.asStateFlow()

    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states
    private val jobs = mutableMapOf<AppCategory, Job>()
    private val pages = mutableMapOf<AppCategory, Int>()
    private val pendingDownloads = mutableSetOf<String>()

    fun selectAppSubCategory(value: AppSubCategory) {
        _selection.update { it.copy(appSubCategory = value) }
        reload(AppCategory.APPS)
    }

    fun selectGameSubCategory(value: GameSubCategory) {
        _selection.update { it.copy(gameSubCategory = value) }
        reload(AppCategory.GAMES)
    }

    fun ensureLoaded(category: AppCategory) {
        if (_states.value[category]?.items?.isNotEmpty() == true) return
        load(category, replace = true)
    }

    fun refresh(category: AppCategory) = load(category, replace = true)

    fun loadMore(category: AppCategory) {
        val state = _states.value[category] ?: return
        if (state.loading || state.loadingMore || !state.hasMore || state.items.isEmpty()) return
        load(category, replace = false)
    }

    private fun reload(category: AppCategory) {
        pages[category] = 0
        load(category, replace = true)
    }

    private fun load(category: AppCategory, replace: Boolean) {
        if (jobs[category]?.isActive == true) return
        val selection = _selection.value
        if (replace) pages[category] = 0
        val page = pages[category] ?: 0
        update(category) { it.copy(loading = replace, loadingMore = !replace, errorMessage = "") }
        jobs[category] = viewModelScope.launch {
            val result = runCatchingCancellable {
                sources.categoryApps(
                    source = AppSource.XIAOMI,
                    category = category,
                    subCategory = selection.appSubCategory,
                    gameSubCategory = selection.gameSubCategory,
                    page = page,
                )
            }
            val fetched = result.getOrNull()
            if (fetched == null) {
                update(category) {
                    it.copy(
                        loading = false,
                        loadingMore = false,
                        errorMessage = result.exceptionOrNull()?.message ?: "Load failed",
                    )
                }
                return@launch
            }
            pages[category] = page + 1
            val existing = if (replace) emptyList() else _states.value[category]?.items.orEmpty()
            val known = existing.mapTo(HashSet()) { it.app.packageName }
            val incoming = resolveItems(fetched.items.filter { known.add(it.packageName) })
            update(category) {
                it.copy(
                    items = existing + incoming,
                    loading = false,
                    loadingMore = false,
                    hasMore = fetched.hasMore && fetched.items.isNotEmpty(),
                )
            }
        }
    }

    private fun update(category: AppCategory, transform: (CategoryUiState) -> CategoryUiState) {
        _states.update { it + (category to transform(it[category] ?: CategoryUiState())) }
    }

    private suspend fun resolveItems(apps: List<MarketAppInfo>): List<SearchResultItem> {
        if (apps.isEmpty()) return emptyList()
        val installed = packages.installedVersionCodes(apps.map { it.packageName })
        return apps.map { app ->
            val code = installed[app.packageName] ?: 0L
            val resolved = app.copy(installedVersionCode = code)
            SearchResultItem(resolved, actionKind(resolved, code))
        }
    }

    private fun actionKind(app: MarketAppInfo, installedVersionCode: Long): AppActionKind =
        if (app.isReservation()) AppActionKind.RESERVE
        else if (installedVersionCode > 0L && app.isDownloadBlocked()) AppActionKind.OPEN
        else resolveActionKind(installedVersionCode, app.versionCode)

    fun onAction(item: SearchResultItem) {
        when (item.actionKind) {
            AppActionKind.RESERVE -> Unit
            AppActionKind.OPEN -> {
                val app = item.app
                val opened = if (app.source.capabilities.prefersOpenLinkLaunch) {
                    packages.openLink(app.openLink) || packages.openApp(app.packageName)
                } else {
                    packages.openApp(app.packageName) || packages.openLink(app.openLink)
                }
                if (!opened) uiPlatform.showToast(app.displayName)
            }

            else -> download(item.app, item.actionKind == AppActionKind.UPDATE)
        }
    }

    fun download(app: MarketAppInfo, update: Boolean = false) {
        if (app.isDownloadBlocked()) return
        if (!pendingDownloads.add(app.packageName)) return
        viewModelScope.launch {
            try {
                runCatchingCancellable {
                    if (update) sources.downloadUpdateMeta(app.source, app) else sources.downloadMeta(app.source, app)
                }
                    .onSuccess { downloads.start(it) }
                    .onFailure { uiPlatform.showToast(it.message ?: "Download failed") }
            } finally {
                pendingDownloads.remove(app.packageName)
            }
        }
    }

    fun installDownloaded(packageName: String) = downloads.install(packageName)
    fun cancelDownload(packageName: String) = downloads.cancel(packageName)
}
