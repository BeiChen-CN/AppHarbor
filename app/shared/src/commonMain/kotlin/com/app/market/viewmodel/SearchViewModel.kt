package com.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.market.domain.model.download.DownloadState
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.market.isDownloadBlocked
import com.app.market.domain.model.market.isReservation
import com.app.market.domain.repository.DownloadRepository
import com.app.market.domain.repository.MarketSourceRepository
import com.app.market.domain.repository.PackageRepository
import com.app.market.domain.repository.SearchHistoryRepository
import com.app.market.domain.repository.UpdatePreferencesRepository
import com.app.market.platform.UiPlatform
import com.app.market.ui.model.AppActionKind
import com.app.market.ui.model.SearchResultItem
import com.app.market.ui.model.resolveActionKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 每个来源独立翻页，各自的结尾位置可能不同。 */
@Immutable
data class SourcePaging(val page: Int = 0, val hasMore: Boolean = false)

@Immutable
data class SearchUiState(
    val keyword: String = "",
    val loading: Boolean = false,
    val errorMessage: String = "",
    val showNoResults: Boolean = false,
    val results: List<SearchResultItem> = emptyList(),
    val history: List<String> = emptyList(),
    val selectedHistory: String? = null,
    /** Keyword the current [results] belong to (may differ from the live input [keyword]). */
    val activeKeyword: String = "",
    val paging: Map<AppSource, SourcePaging> = emptyMap(),
    val loadingMore: Boolean = false,
    /** Bumps on every completed search; used to invalidate an in-flight page load and reset scroll. */
    val searchEpoch: Int = 0,
    val sources: Set<AppSource> = AppSource.Default,
) {
    val hasMore: Boolean get() = paging.values.any { it.hasMore }
}

/** Cap pages fetched per load-more trigger so an all-duplicate tail can't spin indefinitely. */
private const val MAX_PAGES_PER_LOAD = 5

class SearchViewModel(
    private val sources: MarketSourceRepository,
    private val historyStore: SearchHistoryRepository,
    private val updatePrefs: UpdatePreferencesRepository,
    private val packages: PackageRepository,
    private val downloads: DownloadRepository,
    private val uiPlatform: UiPlatform,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states
    private val pendingDownloads = mutableSetOf<String>()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch { reloadHistory() }
        viewModelScope.launch {
            updatePrefs.searchSources.collect { sources ->
                val changed = _uiState.value.sources != sources
                _uiState.update { it.copy(sources = sources) }
                // 源变了旧结果就不作数，按当前关键词重搜
                if (changed) rerunActiveSearch()
            }
        }
        viewModelScope.launch {
            packages.changes.collect { change ->
                _uiState.update { state ->
                    state.copy(
                        results = state.results.map { item ->
                            if (item.app.packageName != change.packageName) item
                            else {
                                val installedVersionCode = change.installedVersionCode ?: 0L
                                val app = item.app.copy(
                                    installedVersionCode = installedVersionCode,
                                    installedVersionName = change.installedVersionName,
                                )
                                item.copy(
                                    app = app,
                                    actionKind = searchActionKind(app, installedVersionCode),
                                )
                            }
                        }
                    )
                }
            }
        }
    }

    fun setKeyword(value: String) {
        // Clearing the field drops results (history reappears) and resets pagination so infinite-scroll
        // won't refetch the old query; the in-flight search is cancelled so its late result can't repopulate.
        if (value.isBlank()) {
            searchJob?.cancel()
            searchJob = null
            _uiState.update {
                it.copy(
                    keyword = value,
                    loading = false,
                    results = emptyList(),
                    showNoResults = false,
                    errorMessage = "",
                    activeKeyword = "",
                    paging = emptyMap(),
                    loadingMore = false,
                )
            }
        } else {
            _uiState.update { it.copy(keyword = value) }
        }
    }

    fun setSearchSource(source: AppSource) {
        viewModelScope.launch { updatePrefs.setSearchSources(setOf(source)) }
    }

    fun clearSearch() {
        // Cancel the in-flight search and drop loading; else the spinner stays up and the late result
        // repopulates the just-cancelled search.
        searchJob?.cancel()
        searchJob = null
        _uiState.update {
            it.copy(
                keyword = "",
                loading = false,
                results = emptyList(),
                showNoResults = false,
                errorMessage = "",
                selectedHistory = null,
                activeKeyword = "",
                paging = emptyMap(),
                loadingMore = false,
            )
        }
    }

    fun selectHistory(keyword: String?) = _uiState.update { it.copy(selectedHistory = keyword) }

    fun searchWith(keyword: String) {
        _uiState.update { it.copy(keyword = keyword) }
        runSearch()
    }

    fun runSearch() {
        val keyword = _uiState.value.keyword.trim()
        if (keyword.isBlank() || _uiState.value.loading) return
        executeSearch(keyword, recordHistory = true, keepResults = keyword == _uiState.value.activeKeyword)
    }

    private fun rerunActiveSearch() {
        val keyword = _uiState.value.keyword.trim()
        if (keyword.isBlank()) {
            clearSearch()
            return
        }
        executeSearch(keyword, recordHistory = false, keepResults = false)
    }

    private fun executeSearch(keyword: String, recordHistory: Boolean, keepResults: Boolean) {
        searchJob?.cancel()
        _uiState.update {
            it.copy(
                loading = true,
                loadingMore = false,
                errorMessage = "",
                showNoResults = false,
                results = if (keepResults) it.results else emptyList(),
                activeKeyword = if (keepResults) it.activeKeyword else "",
                paging = if (keepResults) it.paging else emptyMap(),
            )
        }
        if (recordHistory) {
            // 历史写入独立于 searchJob：清空搜索会取消 searchJob，不应连带丢掉刚搜过的词
            viewModelScope.launch {
                historyStore.add(keyword)
                reloadHistory()
            }
        }
        searchJob = viewModelScope.launch {
            val sources = _uiState.value.sources
            val fetched = fetchPages(keyword, sources.associateWith { 0 })
            // 单源故障不该挡住另一源的结果
            if (fetched.values.none { it.isSuccess }) {
                val failure = firstError(fetched)
                _uiState.update { it.copy(loading = false, errorMessage = failure?.message ?: "Search failed") }
                return@launch
            }
            val items = toItems(mergeApps(emptyList(), orderedApps(fetched)), emptyList())
            _uiState.update {
                it.copy(
                    loading = false,
                    results = items,
                    activeKeyword = keyword,
                    paging = pagingOf(fetched, pages = sources.associateWith { 0 }),
                    showNoResults = items.isEmpty(),
                    searchEpoch = it.searchEpoch + 1,
                )
            }
        }
    }

    /**
     * Appends the next page(s), skipping all-duplicate pages so each trigger makes progress. Aborts if a
     * newer search supersedes this one (tracked by [SearchUiState.activeKeyword] + [SearchUiState.searchEpoch]).
     */
    fun loadMore() {
        val snapshot = _uiState.value
        if (snapshot.loading || snapshot.loadingMore || !snapshot.hasMore || snapshot.activeKeyword.isBlank()) return
        val baseKeyword = snapshot.activeKeyword
        val baseEpoch = snapshot.searchEpoch
        _uiState.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            fun superseded() = _uiState.value.let { it.activeKeyword != baseKeyword || it.searchEpoch != baseEpoch }
            var paging = snapshot.paging
            var addedAny = false
            var failed = false
            var attempts = 0
            while (!addedAny && attempts < MAX_PAGES_PER_LOAD && paging.values.any { it.hasMore } && !superseded()) {
                attempts++
                val nextPages = paging.filterValues { it.hasMore }.mapValues { (_, state) -> state.page + 1 }
                val fetched = fetchPages(baseKeyword, nextPages)
                if (fetched.values.none { it.isSuccess }) {
                    failed = true; break
                }
                // 失败的源保留原页码，下次触发重试
                paging = paging + pagingOf(fetched, nextPages)
                val incoming = orderedApps(fetched)
                // Dedup + commit under the staleness guard: a superseded search flips it, so its page is dropped.
                val before = _uiState.value
                if (before.activeKeyword != baseKeyword || before.searchEpoch != baseEpoch) break
                val mergedApps = mergeApps(before.results.map { it.app }, incoming)
                addedAny = mergedApps.size > before.results.size
                val resolved = toItems(mergedApps, before.results)
                val committedPaging = paging
                _uiState.update { state ->
                    if (state.activeKeyword != baseKeyword || state.searchEpoch != baseEpoch) state
                    else state.copy(results = resolved, paging = committedPaging)
                }
            }
            // Stop the spinner; keep hasMore on a transient failure (retry), else end pagination when no new items.
            _uiState.update { state ->
                when {
                    state.activeKeyword != baseKeyword || state.searchEpoch != baseEpoch -> state
                    addedAny || failed -> state.copy(loadingMore = false)
                    else -> state.copy(
                        loadingMore = false,
                        paging = state.paging.mapValues { (_, value) -> value.copy(hasMore = false) },
                    )
                }
            }
        }
    }

    /** 各源并发取指定页，单源异常收进 Result 交调用方判定。 */
    private suspend fun fetchPages(keyword: String, pages: Map<AppSource, Int>): Map<AppSource, Result<SearchPage>> =
        coroutineScope {
            pages.map { (source, page) ->
                source to async { runCatchingCancellable { searchSource(source, keyword, page) } }
            }.associate { (source, deferred) -> source to deferred.await() }
        }

    private suspend fun searchSource(source: AppSource, keyword: String, page: Int): SearchPage = when (source) {
        else -> sources.search(source, keyword, page)
    }

    /** 小米在前，让合并结果保持主源的相关性排序。 */
    private fun orderedApps(fetched: Map<AppSource, Result<SearchPage>>): List<MarketAppInfo> =
        AppSource.entries.flatMap { fetched[it]?.getOrNull()?.items.orEmpty() }

    private fun pagingOf(
        fetched: Map<AppSource, Result<SearchPage>>,
        pages: Map<AppSource, Int>,
    ): Map<AppSource, SourcePaging> = fetched.mapNotNull { (source, result) ->
        val page = result.getOrNull() ?: return@mapNotNull null
        source to SourcePaging(pages[source] ?: 0, page.hasMore)
    }.toMap()

    private fun firstError(fetched: Map<AppSource, Result<SearchPage>>): Throwable? =
        fetched.values.firstNotNullOfOrNull { it.exceptionOrNull() }

    /** 同包名保留高版本；位置由先入者定，避免翻页补进来的条目把列表重排。 */
    private fun mergeApps(existing: List<MarketAppInfo>, incoming: List<MarketAppInfo>): List<MarketAppInfo> {
        val byPackage = LinkedHashMap<String, MarketAppInfo>(existing.size + incoming.size)
        existing.forEach { byPackage[it.packageName] = it }
        incoming.forEach { app ->
            val current = byPackage[app.packageName]
            if (current == null || app.versionCode > current.versionCode) byPackage[app.packageName] = app
        }
        return byPackage.values.toList()
    }

    /** Resolves each app's install/update/open action off-main (cached PackageManager lookup). */
    private suspend fun toItems(
        apps: List<MarketAppInfo>,
        existing: List<SearchResultItem>,
    ): List<SearchResultItem> {
        if (apps.isEmpty()) return emptyList()
        val known = existing.associateBy { it.app.packageName }
        // 只对新增或被替换的条目重查安装状态，翻页不整表重算
        val changed = apps.filter { known[it.packageName]?.app != it }
        if (changed.isEmpty()) return apps.mapNotNull { known[it.packageName] }
        val installed = packages.installedVersionCodes(changed.map { it.packageName })
        val resolved = changed.associate { app ->
            val cachedVersionCode = installed[app.packageName] ?: 0L
            // A stale package-list cache can make a system app that was just upgraded look older
            // than the store result. Verify only would-be updates with a direct PackageManager
            // lookup before exposing the update action.
            val installedVersionCode = if (cachedVersionCode in 1 until app.versionCode) {
                packages.freshInstalledVersionCode(app.packageName) ?: cachedVersionCode
            } else {
                cachedVersionCode
            }
            val resolvedApp = app.copy(installedVersionCode = installedVersionCode)
            app.packageName to SearchResultItem(
                resolvedApp,
                searchActionKind(resolvedApp, installedVersionCode),
            )
        }
        return apps.mapNotNull { resolved[it.packageName] ?: known[it.packageName] }
    }

    private fun searchActionKind(app: MarketAppInfo, installedVersionCode: Long): AppActionKind =
        if (app.isReservation()) AppActionKind.RESERVE
        else if (installedVersionCode > 0L && app.isDownloadBlocked()) AppActionKind.OPEN
        else resolveActionKind(installedVersionCode, app.versionCode)

    fun removeHistory(keyword: String) {
        _uiState.update { it.copy(selectedHistory = null) }
        viewModelScope.launch {
            historyStore.remove(keyword)
            reloadHistory()
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyStore.clear()
            reloadHistory()
        }
    }

    fun onAction(item: SearchResultItem) {
        if (item.actionKind == AppActionKind.RESERVE) return
        if (item.actionKind == AppActionKind.OPEN) {
            // OPPO/三星/华为的 openLink 只是商店深链回退，不是已安装应用的启动目标
            val opened = if (item.app.source.capabilities.prefersOpenLinkLaunch) {
                packages.openLink(item.app.openLink) || packages.openApp(item.app.packageName)
            } else {
                packages.openApp(item.app.packageName) || packages.openLink(item.app.openLink)
            }
            if (!opened) {
                uiPlatform.showToast(item.app.displayName)
            }
            return
        }
        download(item.app, item.actionKind == AppActionKind.UPDATE)
    }

    fun download(app: MarketAppInfo, update: Boolean = false) {
        startDownload(app, update)
    }

    private fun startDownload(app: MarketAppInfo, update: Boolean) {
        if (app.isDownloadBlocked()) return
        if (!pendingDownloads.add(app.packageName)) return
        viewModelScope.launch {
            try {
                runCatchingCancellable {
                    if (update) {
                        sources.downloadUpdateMeta(app.source, app)
                    } else {
                        sources.downloadMeta(app.source, app, _uiState.value.keyword)
                    }
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
    fun clearDownload(packageName: String) = downloads.clear(packageName)

    private suspend fun reloadHistory() {
        val history = historyStore.load()
        _uiState.update { it.copy(history = history) }
    }
}
