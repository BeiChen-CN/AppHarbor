package com.app.market.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.today.TodayFeaturedItem
import com.app.market.domain.repository.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.minutes

data class DiscoveryUiState(
    val source: AppSource = AppSource.XIAOMI,
    val items: List<TodayFeaturedItem> = emptyList(),
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val error: String = "",
    val refreshing: Boolean = false,
    val refreshRevision: Int = 0,
    val refreshFailed: Boolean = false,
)

class DiscoveryViewModel(
    private val repository: DiscoveryRepository,
    private val preferences: FeaturePreferencesRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(DiscoveryUiState(preferences.settings.value.recommendationSource))
    val uiState = mutableState.asStateFlow()
    private var nextPage = 0
    private var generation = 0
    private var loadJob: Job? = null
    private var failedRefresh = false
    private data class CachedFeed(val state: DiscoveryUiState, val nextPage: Int, val savedAt: TimeSource.Monotonic.ValueTimeMark)
    private val cache = mutableMapOf<AppSource, CachedFeed>()

    init {
        viewModelScope.launch {
            preferences.settings.map { it.recommendationSource }.distinctUntilChanged().collect { source ->
                loadJob?.cancel()
                generation++
                failedRefresh = false
                val cached = cache[source]?.takeIf { it.savedAt.elapsedNow() < 5.minutes }
                nextPage = cached?.nextPage ?: 0
                mutableState.value = cached?.state ?: DiscoveryUiState(source)
                if (cached == null) requestPage(refresh = false)
            }
        }
    }

    fun selectSource(source: AppSource?) {
        if (source != null) viewModelScope.launch { preferences.setRecommendationSource(source) }
    }

    fun loadMore() {
        val state = mutableState.value
        if (state.loading || state.loadingMore || state.refreshing || !state.hasMore || state.error.isNotBlank()) return
        requestPage(refresh = false)
    }

    fun refresh() {
        if (mutableState.value.refreshing) return
        loadJob?.cancel()
        generation++
        requestPage(refresh = true)
    }

    fun retry() {
        val state = mutableState.value
        if (state.loading || state.loadingMore || state.refreshing) return
        requestPage(refresh = failedRefresh)
    }

    private fun requestPage(refresh: Boolean) {
        val expectedGeneration = generation
        val source = mutableState.value.source
        val requestedPage = if (refresh) 0 else nextPage
        mutableState.update { it.copy(refreshing = refresh, loading = it.items.isEmpty(),
            loadingMore = !refresh && it.items.isNotEmpty(), error = "", refreshFailed = false) }
        loadJob = viewModelScope.launch {
            val result = runCatchingCancellable { repository.feed(source, requestedPage) }
            if (expectedGeneration != generation) return@launch
            val feed = result.getOrNull()
            failedRefresh = refresh && feed == null
            if (feed != null) nextPage = requestedPage + 1
            mutableState.update { current -> current.copy(
                items = (if (refresh && feed != null) feed.items else current.items + feed?.items.orEmpty())
                    .distinctBy { it.rId.ifBlank { it.articleLink } },
                loading = false, loadingMore = false, refreshing = false, refreshFailed = failedRefresh,
                hasMore = feed?.hasMore ?: current.hasMore,
                error = if (result.isFailure) result.exceptionOrNull()?.message?.ifBlank { "Request failed" } ?: "Request failed" else "",
                refreshRevision = current.refreshRevision + if (refresh && feed != null) 1 else 0,
            ) }
            if (feed != null) cache[source] = CachedFeed(mutableState.value, nextPage, TimeSource.Monotonic.markNow())
        }
    }
}
