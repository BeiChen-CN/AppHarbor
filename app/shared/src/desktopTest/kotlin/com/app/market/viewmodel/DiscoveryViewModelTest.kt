package com.app.market.viewmodel

import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.today.TodayFeaturedItem
import com.app.market.domain.model.today.TodayFeedPage
import com.app.market.domain.repository.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class DiscoveryViewModelTest {
    @Test
    fun cachedSourcesRestorePaginationAndFailedRefreshPreservesCards() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FeedRecorder()
            val prefs = TestFeaturePreferences().apply { setRecommendationSource(AppSource.TAPTAP) }
            val vm = DiscoveryViewModel(repository, prefs)
            advanceUntilIdle()
            vm.loadMore()
            advanceUntilIdle()
            vm.selectSource(AppSource.KUAIBAO)
            advanceUntilIdle()
            vm.selectSource(AppSource.TAPTAP)
            advanceUntilIdle()
            assertEquals(3, repository.requests.size)
            assertEquals(2, vm.uiState.value.items.size)
            assertFalse(vm.uiState.value.hasMore)
            repository.failTapTap = true
            vm.refresh()
            advanceUntilIdle()
            assertEquals(2, vm.uiState.value.items.size)
            assertTrue(vm.uiState.value.error.isNotBlank())
            repository.failTapTap = false
            vm.retry()
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.items.size)
            assertTrue(vm.uiState.value.hasMore)
            assertEquals(1, vm.uiState.value.refreshRevision)
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(AppSource.TAPTAP to 1, repository.requests.last())
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun recommendationsPaginateSelectedSourceWithoutDuplicateReloads() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FeedRecorder()
            val prefs = TestFeaturePreferences()
            prefs.setRecommendationSource(AppSource.TAPTAP)
            val vm = DiscoveryViewModel(repository, prefs)
            advanceUntilIdle()
            assertEquals(listOf(AppSource.TAPTAP to 0), repository.requests)
            assertEquals(1, vm.uiState.value.items.size)
            vm.loadMore()
            advanceUntilIdle()
            assertEquals(AppSource.TAPTAP to 1, repository.requests.last())
            assertEquals(2, repository.requests.size)
            assertEquals(2, vm.uiState.value.items.size)
            assertFalse(vm.uiState.value.hasMore)
            prefs.setCommentSource(AppSource.XIAOMI)
            advanceUntilIdle()
            assertEquals(2, repository.requests.size)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun changingSourceDuringLoadRejectsPreviousSourceResults() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FeedRecorder().apply { taptapBarrier = CompletableDeferred() }
            val prefs = TestFeaturePreferences()
            prefs.setRecommendationSource(AppSource.TAPTAP)
            val vm = DiscoveryViewModel(repository, prefs)
            runCurrent()
            vm.selectSource(AppSource.KUAIBAO)
            advanceUntilIdle()
            repository.taptapBarrier!!.complete(Unit)
            advanceUntilIdle()
            assertEquals(AppSource.KUAIBAO, prefs.settings.value.recommendationSource)
            assertEquals(AppSource.KUAIBAO, vm.uiState.value.source)
            assertTrue(vm.uiState.value.items.all { it.app!!.source == AppSource.KUAIBAO })
            assertFalse(vm.uiState.value.loading)
        } finally { Dispatchers.resetMain() }
    }

    @Test
    fun failedRecommendationSourceRetriesSamePageWithoutDuplicates() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val repository = FeedRecorder().apply { failKuaibao = true }
            val prefs = TestFeaturePreferences().apply { setRecommendationSource(AppSource.KUAIBAO) }
            val vm = DiscoveryViewModel(repository, prefs)
            advanceUntilIdle()
            assertEquals(0, vm.uiState.value.items.size)
            assertTrue(vm.uiState.value.error.isNotEmpty())
            repository.failKuaibao = false
            vm.retry()
            advanceUntilIdle()
            assertEquals(1, vm.uiState.value.items.size)
            assertEquals(listOf(AppSource.KUAIBAO to 0, AppSource.KUAIBAO to 0), repository.requests)
            assertTrue(vm.uiState.value.error.isEmpty())
        } finally { Dispatchers.resetMain() }
    }
}

internal class TestFeaturePreferences : FeaturePreferencesRepository {
    override val initialized = MutableStateFlow(true)
    override val settings = MutableStateFlow(FeaturePreferences())
    override suspend fun setAggregateUpdates(value: Boolean) { settings.update { it.copy(aggregateUpdates = value) } }
    override suspend fun setCommentSource(value: AppSource?) { settings.update { it.copy(commentSource = value) } }
    override suspend fun setRecommendationSource(value: AppSource) { settings.update { it.copy(recommendationSource = value) } }
    override suspend fun setIncludePrereleaseUpdates(value: Boolean) { settings.update { it.copy(includePrereleaseUpdates = value) } }
    override suspend fun setAggregateSources(value: Set<AppSource>) { settings.update { it.copy(aggregateSources = value) } }
    override suspend fun setOnlyUnmeteredDownloads(value: Boolean) { settings.update { it.copy(onlyUnmeteredDownloads = value) } }
    override suspend fun setPreferredUpdateSource(packageName: String, source: AppSource?) {
        settings.update { it.copy(preferredUpdateSources = it.preferredUpdateSources.toMutableMap().apply {
            if (source == null) remove(packageName) else put(packageName, source)
        }) }
    }
}

private class FeedRecorder : DiscoveryRepository {
    val requests = mutableListOf<Pair<AppSource, Int>>()
    var taptapBarrier: CompletableDeferred<Unit>? = null
    var failKuaibao = false
    var failTapTap = false
    override suspend fun feed(source: AppSource, page: Int): TodayFeedPage {
        requests += source to page
        if (source == AppSource.TAPTAP) taptapBarrier?.await()
        if (source == AppSource.TAPTAP && failTapTap) error("offline")
        if (source == AppSource.KUAIBAO && failKuaibao) error("offline")
        val app = MarketAppInfo(page.toLong(), "com.example.$page", "Game", "", "1", 1, "", 0, ratingScore = 0.0, source = source)
        return TodayFeedPage(listOf(TodayFeaturedItem(page.toString(), "Game", "", "", app)), source == AppSource.TAPTAP && page == 0)
    }
}
