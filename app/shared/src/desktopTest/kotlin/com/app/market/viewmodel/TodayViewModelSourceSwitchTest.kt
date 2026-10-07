package com.app.market.viewmodel

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.preference.HomePage
import com.app.market.domain.model.today.TodayArticle
import com.app.market.domain.model.today.TodayFeaturedItem
import com.app.market.domain.model.today.TodayFeedPage
import com.app.market.domain.model.update.IgnoredUpdate
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.repository.MarketSourceRepository
import com.app.market.domain.repository.UpdatePreferencesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelSourceSwitchTest {
    @Test
    fun changingTodaySourceReplacesTheAwardFeed() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val preferences = FakePreferences(today = AppSource.OPPO)
            val repository = FakeMarketSourceRepository()
            val viewModel = TodayViewModel(repository, preferences)

            advanceUntilIdle()
            assertEquals("OPPO", viewModel.uiState.value.feed.items.single().title)
            assertEquals(AppSource.OPPO, viewModel.uiState.value.source)

            preferences.setTodaySource(AppSource.VIVO)
            advanceUntilIdle()

            assertEquals("VIVO", viewModel.uiState.value.feed.items.single().title)
            assertEquals(AppSource.VIVO, viewModel.uiState.value.source)
            assertEquals(listOf(AppSource.OPPO, AppSource.VIVO), repository.feedSources)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun explicitTodaySourceIgnoresSearchSourceChanges() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val preferences = FakePreferences(today = AppSource.OPPO)
            val repository = FakeMarketSourceRepository()
            val viewModel = TodayViewModel(repository, preferences)

            advanceUntilIdle()
            assertEquals("OPPO", viewModel.uiState.value.feed.items.single().title)

            preferences.setSearchSources(setOf(AppSource.VIVO))
            advanceUntilIdle()

            assertEquals("OPPO", viewModel.uiState.value.feed.items.single().title)
            assertEquals(listOf(AppSource.OPPO), repository.feedSources)
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class FakeMarketSourceRepository : MarketSourceRepository {
    val feedSources = mutableListOf<AppSource>()

    override suspend fun goldMiFeed(source: AppSource, page: Int, pageSize: Int): TodayFeedPage {
        feedSources += source
        return TodayFeedPage(
            items = listOf(
                TodayFeaturedItem(
                    rId = source.token,
                    title = source.name,
                    summary = "",
                    coverImage = "",
                    app = null,
                    awardName = source.name,
                )
            ),
            hasMore = false,
        )
    }

    override suspend fun search(source: AppSource, keyword: String, page: Int): SearchPage = error("Not used")
    override suspend fun appDetail(
        source: AppSource,
        appId: Long,
        packageName: String,
        externalQuery: String?,
    ): AppDetail = error("Not used")

    override suspend fun appComments(source: AppSource, app: MarketAppInfo): AppComments = error("Not used")
    override suspend fun sameDeveloperApps(source: AppSource, app: MarketAppInfo): List<MarketAppInfo> = error("Not used")
    override suspend fun downloadMeta(source: AppSource, app: MarketAppInfo, keyword: String): DownloadMeta = error("Not used")
    override suspend fun downloadUpdateMeta(source: AppSource, app: MarketAppInfo): DownloadMeta = error("Not used")
    override suspend fun loadReconciledCachedUpdates(): List<MarketAppInfo> = error("Not used")
    override fun checkUpdatesFlow(source: AppSource): Flow<List<MarketAppInfo>> = emptyFlow()
    override suspend fun checkManualUpdate(source: AppSource, request: ManualUpdateRequest): ManualUpdateResult = error("Not used")
    override suspend fun todayArticle(source: AppSource, rId: String): TodayArticle = error("Not used")
    override suspend fun historicalVersions(
        source: AppSource,
        appId: Long,
        packageName: String,
        offset: Int,
    ): HistoricalVersionPage = error("Not used")

    override suspend fun historicalDownloadMeta(source: AppSource, version: HistoricalVersion): DownloadMeta =
        error("Not used")
}

private class FakePreferences(
    today: AppSource = AppSource.XIAOMI,
) : UpdatePreferencesRepository {
    override val initialized = MutableStateFlow(true)
    override val showSystemUpdates = MutableStateFlow(true)
    override val removeSearchAds = MutableStateFlow(false)
    override val filterQuickGames = MutableStateFlow(false)
    override val filterReservationApps = MutableStateFlow(false)
    override val showAppComments = MutableStateFlow(false)
    override val showSameDeveloper = MutableStateFlow(false)
    override val showPromotions = MutableStateFlow(false)
    override val stripAppNameSubtitle = MutableStateFlow(false)
    override val homePage = MutableStateFlow(HomePage.TODAY)
    override val searchSources = MutableStateFlow(setOf(AppSource.XIAOMI))
    override val todaySource = MutableStateFlow(today)
    override val updateSource = MutableStateFlow(AppSource.XIAOMI)
    override val permanentIgnores: StateFlow<List<IgnoredUpdate>> = MutableStateFlow(emptyList())
    override val onceIgnores: StateFlow<List<IgnoredUpdate>> = MutableStateFlow(emptyList())

    override suspend fun setShowSystemUpdates(value: Boolean) {
        showSystemUpdates.value = value
    }

    override suspend fun setRemoveSearchAds(value: Boolean) {
        removeSearchAds.value = value
    }

    override suspend fun setFilterQuickGames(value: Boolean) {
        filterQuickGames.value = value
    }

    override suspend fun setFilterReservationApps(value: Boolean) {
        filterReservationApps.value = value
    }

    override suspend fun setShowAppComments(value: Boolean) {
        showAppComments.value = value
    }

    override suspend fun setShowSameDeveloper(value: Boolean) {
        showSameDeveloper.value = value
    }

    override suspend fun setShowPromotions(value: Boolean) {
        showPromotions.value = value
    }

    override suspend fun setStripAppNameSubtitle(value: Boolean) {
        stripAppNameSubtitle.value = value
    }

    override suspend fun setHomePage(value: HomePage) {
        homePage.value = value
    }

    override suspend fun setSearchSources(value: Set<AppSource>) {
        searchSources.value = value
    }

    override suspend fun setTodaySource(value: AppSource) {
        todaySource.value = value
    }

    override suspend fun setUpdateSource(value: AppSource) {
        updateSource.value = value
    }

    override fun isIgnored(app: MarketAppInfo): Boolean = false
    override suspend fun ignoreOnce(app: MarketAppInfo) = Unit
    override suspend fun ignorePermanently(app: MarketAppInfo) = Unit
    override suspend fun removePermanent(packageName: String) = Unit
    override suspend fun removeOnce(packageName: String) = Unit
    override suspend fun currentRemoveSearchAds(): Boolean = removeSearchAds.value
    override suspend fun currentFilterQuickGames(): Boolean = filterQuickGames.value
    override suspend fun currentFilterReservationApps(): Boolean = filterReservationApps.value
    override suspend fun loadCachedUpdates(): List<MarketAppInfo> = emptyList()
    override suspend fun saveCachedUpdates(updates: List<MarketAppInfo>) = Unit
}
