package com.app.market.viewmodel

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadState
import com.app.market.domain.model.download.DownloadTaskKey
import com.app.market.domain.model.install.DeltaFallback
import com.app.market.domain.model.install.InstallUserAction
import com.app.market.domain.model.installed.PackageChange
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.preference.HomePage
import com.app.market.domain.model.today.TodayArticle
import com.app.market.domain.model.today.TodayFeedPage
import com.app.market.domain.model.update.IgnoredUpdate
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.repository.DownloadRepository
import com.app.market.domain.repository.MarketSourceRepository
import com.app.market.domain.repository.PackageRepository
import com.app.market.domain.repository.SearchHistoryRepository
import com.app.market.domain.repository.UpdatePreferencesRepository
import com.app.market.platform.ImageSaveResult
import com.app.market.platform.UiPlatform
import com.app.market.ui.model.AppActionKind
import com.app.market.ui.model.SearchResultItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
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
class SearchViewModelUpdateDownloadTest {
    @Test
    fun updateActionRequestsUpdateMetadata() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val sources = RecordingSearchSources()
            val downloads = RecordingDownloads()
            val viewModel = searchViewModel(sources, downloads)
            advanceUntilIdle()

            viewModel.onAction(SearchResultItem(honorApp(), AppActionKind.UPDATE))
            advanceUntilIdle()

            assertEquals(listOf("com.hihonor.contacts"), sources.updateRequests)
            assertEquals(emptyList(), sources.installRequests)
            assertEquals(listOf("com.hihonor.contacts"), downloads.started.map(DownloadMeta::packageName))
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun installActionStillRequestsFullDownloadMetadata() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val sources = RecordingSearchSources()
            val downloads = RecordingDownloads()
            val viewModel = searchViewModel(sources, downloads)
            advanceUntilIdle()

            viewModel.onAction(SearchResultItem(honorApp(), AppActionKind.INSTALL))
            advanceUntilIdle()

            assertEquals(emptyList(), sources.updateRequests)
            assertEquals(listOf("com.hihonor.contacts"), sources.installRequests)
            assertEquals(listOf("com.hihonor.contacts"), downloads.started.map(DownloadMeta::packageName))
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun searchViewModel(
        sources: RecordingSearchSources,
        downloads: RecordingDownloads,
    ) = SearchViewModel(
        sources = sources,
        historyStore = EmptySearchHistory,
        updatePrefs = SearchPreferences(),
        packages = EmptyPackages,
        downloads = downloads,
        uiPlatform = SilentUiPlatform,
    )

    private fun honorApp() = MarketAppInfo(
        appId = 1L,
        packageName = "com.hihonor.contacts",
        displayName = "Contacts",
        publisherName = "Honor",
        versionName = "18.1.18.301",
        versionCode = 180118301L,
        icon = "",
        apkSize = 26_826_586L,
        deltaSize = 8_784_615L,
        ratingScore = 0.0,
        installedVersionName = "18.1.14.310",
        installedVersionCode = 180114310L,
        installedOldApkHash = "cefa226c48deb29a6eb60c316caa4e175f0a7ff5e63aacf6d095d30c55aee2a3",
        installedBaseApkPath = "/data/app/com.hihonor.contacts/base.apk",
        source = AppSource.HONOR,
    )
}

private class RecordingSearchSources : MarketSourceRepository {
    val updateRequests = mutableListOf<String>()
    val installRequests = mutableListOf<String>()

    override suspend fun downloadMeta(source: AppSource, app: MarketAppInfo, keyword: String): DownloadMeta {
        installRequests += app.packageName
        return app.downloadMeta()
    }

    override suspend fun downloadUpdateMeta(source: AppSource, app: MarketAppInfo): DownloadMeta {
        updateRequests += app.packageName
        return app.downloadMeta()
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
    override suspend fun loadReconciledCachedUpdates(): List<MarketAppInfo> = error("Not used")
    override fun checkUpdatesFlow(source: AppSource): Flow<List<MarketAppInfo>> = emptyFlow()
    override suspend fun checkManualUpdate(source: AppSource, request: ManualUpdateRequest): ManualUpdateResult =
        error("Not used")

    override suspend fun goldMiFeed(source: AppSource, page: Int, pageSize: Int): TodayFeedPage = error("Not used")
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

private fun MarketAppInfo.downloadMeta() = DownloadMeta(
    appId = appId,
    packageName = packageName,
    displayName = displayName,
    versionName = versionName,
    versionCode = versionCode,
    url = "https://example.invalid/base.apk",
    size = apkSize,
    installedBaseApkPath = installedBaseApkPath,
    source = source,
)

private class RecordingDownloads : DownloadRepository {
    override val states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    override val taskStates = MutableStateFlow<Map<DownloadTaskKey, DownloadState>>(emptyMap())
    override val installedPackages = MutableSharedFlow<String>()
    override val deltaFallbacks = MutableSharedFlow<DeltaFallback>()
    override val pendingUserAction = MutableStateFlow<InstallUserAction?>(null)
    val started = mutableListOf<DownloadMeta>()

    override fun start(meta: DownloadMeta, installAfterDownload: Boolean) {
        started += meta
    }

    override fun install(packageName: String) = Unit
    override fun cancel(packageName: String) = Unit
    override fun cancel(packageName: String, versionCode: Long) = Unit
    override fun clear(packageName: String) = Unit
    override fun consumePendingUserAction() = Unit
}

private object EmptyPackages : PackageRepository {
    override val selfPackageName = "com.app.market.test"
    override val changes: SharedFlow<PackageChange> = MutableSharedFlow()
    override suspend fun installedVersionCodes(packageNames: Collection<String>): Map<String, Long> = emptyMap()
    override suspend fun installedVersionName(packageName: String): String? = null
    override suspend fun freshInstalledVersionCode(packageName: String): Long? = null
    override fun openApp(packageName: String) = false
    override fun openLink(link: String) = false
}

private object EmptySearchHistory : SearchHistoryRepository {
    override suspend fun load(): List<String> = emptyList()
    override suspend fun add(keyword: String) = Unit
    override suspend fun remove(keyword: String) = Unit
    override suspend fun clear() = Unit
}

private object SilentUiPlatform : UiPlatform {
    override val packageInstallationSupported = false
    override fun showToast(message: String) = Unit
    override fun openAppSettings() = false
    override fun openUnknownSourcesSettings() = false
    override fun requestInstalledAppsPermission(onResult: (Boolean) -> Unit) = false
    override fun requestPostNotificationsPermission(onResult: () -> Unit) = false
    override suspend fun saveImageToPictures(url: String, fileName: String) = ImageSaveResult.Unsupported
}

private class SearchPreferences : UpdatePreferencesRepository {
    override val initialized = MutableStateFlow(true)
    override val showSystemUpdates = MutableStateFlow(true)
    override val removeSearchAds = MutableStateFlow(false)
    override val filterQuickGames = MutableStateFlow(false)
    override val filterReservationApps = MutableStateFlow(false)
    override val showAppComments = MutableStateFlow(false)
    override val showSameDeveloper = MutableStateFlow(false)
    override val showPromotions = MutableStateFlow(false)
    override val stripAppNameSubtitle = MutableStateFlow(false)
    override val homePage = MutableStateFlow(HomePage.SEARCH)
    override val searchSources = MutableStateFlow(setOf(AppSource.HONOR))
    override val todaySource = MutableStateFlow(AppSource.HONOR)
    override val updateSource = MutableStateFlow(AppSource.HONOR)
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

    override fun isIgnored(app: MarketAppInfo) = false
    override suspend fun ignoreOnce(app: MarketAppInfo) = Unit
    override suspend fun ignorePermanently(app: MarketAppInfo) = Unit
    override suspend fun removePermanent(packageName: String) = Unit
    override suspend fun removeOnce(packageName: String) = Unit
    override suspend fun currentRemoveSearchAds() = removeSearchAds.value
    override suspend fun currentFilterQuickGames() = filterQuickGames.value
    override suspend fun currentFilterReservationApps() = filterReservationApps.value
    override suspend fun loadCachedUpdates(): List<MarketAppInfo> = emptyList()
    override suspend fun saveCachedUpdates(updates: List<MarketAppInfo>) = Unit
}
