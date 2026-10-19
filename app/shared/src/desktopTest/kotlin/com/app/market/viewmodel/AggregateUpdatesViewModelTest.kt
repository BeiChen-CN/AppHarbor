package com.app.market.viewmodel

import com.app.market.domain.model.download.DownloadState
import com.app.market.domain.model.installed.PackageChange
import com.app.market.domain.model.market.*
import com.app.market.domain.model.preference.HomePage
import com.app.market.domain.model.update.IgnoredUpdate
import com.app.market.domain.repository.*
import com.app.market.platform.ImageSaveResult
import com.app.market.platform.UiPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AggregateUpdatesViewModelTest {
    @Test fun installedPackageChangeWinsOverLateProviderSnapshot() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val barrier = CompletableDeferred<Unit>()
            val changes = MutableSharedFlow<PackageChange>()
            val sources = proxy<MarketSourceRepository> { name, args ->
                check(name == "checkUpdatesFlow")
                val source = args!![0] as AppSource
                flow {
                    if (source == AppSource.COOLAPK) barrier.await()
                    emit(listOf(app(source)))
                }
            }
            val features = TestFeaturePreferences().apply {
                setAggregateUpdates(true)
                setAggregateSources(setOf(AppSource.TENCENT, AppSource.COOLAPK))
            }
            val vm = model(sources, features, changes)
            vm.startInitialCheck()
            withTimeout(5000) { vm.uiState.first { it.sourcesCompleted == 1 && it.updates.isNotEmpty() } }
            changes.emit(PackageChange("com.example.app", 30, "3.0"))
            barrier.complete(Unit)
            val finished = withTimeout(5000) { vm.uiState.first { !it.loading && it.sourcesCompleted == 2 } }
            assertTrue(finished.updates.isEmpty())
            assertTrue(vm.updateCandidates("com.example.app").isEmpty())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun retryOnlyChecksFailedSourceAndPinsReselectWithoutNetworkRequests() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val calls = ConcurrentHashMap<AppSource, Int>()
            var failed = true
            val sources = proxy<MarketSourceRepository> { name, args ->
                check(name == "checkUpdatesFlow")
                val source = args!![0] as AppSource
                flow {
                    calls.merge(source, 1, Int::plus)
                    if (source == AppSource.COOLAPK && failed) error("offline")
                    emit(listOf(app(source)))
                }
            }
            val features = TestFeaturePreferences().apply {
                setAggregateUpdates(true)
                setAggregateSources(setOf(AppSource.TENCENT, AppSource.COOLAPK))
            }
            val vm = model(sources, features)
            vm.startInitialCheck()
            val initial = withTimeout(5000) { vm.uiState.first { !it.loading && it.sourcesCompleted == 2 } }
            assertEquals(setOf(AppSource.COOLAPK), initial.failedSources)
            assertEquals(AppSource.TENCENT, initial.updates.single().source)
            failed = false
            vm.retryFailedSources()
            val retried = withTimeout(5000) { vm.uiState.first { !it.loading && it.failedSources.isEmpty() && it.updates.singleOrNull()?.source == AppSource.COOLAPK } }
            assertEquals(2, retried.sourcesCompleted)
            assertEquals(mapOf(AppSource.TENCENT to 1, AppSource.COOLAPK to 2), calls.toMap())
            features.setPreferredUpdateSource("com.example.app", AppSource.TENCENT)
            withTimeout(5000) { vm.uiState.first { it.updates.singleOrNull()?.source == AppSource.TENCENT } }
            assertEquals(2, vm.updateCandidates("com.example.app").size)
            assertEquals(mapOf(AppSource.TENCENT to 1, AppSource.COOLAPK to 2), calls.toMap())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun cancellingSlowSourceKeepsCompletedResultsAndMarksCheckIncomplete() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        try {
            val barrier = CompletableDeferred<Unit>()
            val sources = proxy<MarketSourceRepository> { name, args ->
                check(name == "checkUpdatesFlow")
                val source = args!![0] as AppSource
                flow {
                    if (source == AppSource.COOLAPK) barrier.await()
                    emit(listOf(app(source)))
                }
            }
            val features = TestFeaturePreferences().apply {
                setAggregateUpdates(true)
                setAggregateSources(setOf(AppSource.TENCENT, AppSource.COOLAPK))
            }
            val vm = model(sources, features)
            vm.startInitialCheck()
            withTimeout(5000) { vm.uiState.first { it.sourcesCompleted == 1 } }
            vm.cancelCheck()
            barrier.complete(Unit)
            val cancelled = withTimeout(5000) { vm.uiState.first { !it.loading && it.cancelled } }
            assertEquals(AppSource.TENCENT, cancelled.updates.single().source)
            assertFalse(cancelled.checkFailed)
        } finally { Dispatchers.resetMain() }
    }

    private fun model(sources: MarketSourceRepository, features: FeaturePreferencesRepository,
        changes: MutableSharedFlow<PackageChange> = MutableSharedFlow()): UpdatesViewModel {
        val prefs = proxy<UpdatePreferencesRepository> { name, _ -> when (name) {
            "getHomePage" -> MutableStateFlow(HomePage.UPDATES)
            "getSearchSources" -> MutableStateFlow(setOf(AppSource.XIAOMI))
            "getUpdateSource" -> MutableStateFlow(AppSource.XIAOMI)
            "getPermanentIgnores", "getOnceIgnores" -> MutableStateFlow(emptyList<IgnoredUpdate>())
            else -> MutableStateFlow(true)
        } }
        val downloads = proxy<DownloadRepository> { _, _ -> MutableStateFlow(emptyMap<String, DownloadState>()) }
        val packages = proxy<PackageRepository> { name, _ -> when (name) {
            "getChanges" -> changes
            else -> error(name)
        } }
        return UpdatesViewModel(sources, prefs, downloads, packages, TestUi, features)
    }

    private fun app(source: AppSource) = MarketAppInfo(1, "com.example.app", "Example", "", if (source == AppSource.COOLAPK) "3.0" else "2.0",
        if (source == AppSource.COOLAPK) 30 else 20, "", 0, ratingScore = 0.0, source = source,
        installedVersionCode = 1, installedVersionName = "1.0")

    private inline fun <reified T> proxy(crossinline call: (String, Array<out Any?>?) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> call(method.name, args) } as T
}

private object TestUi : UiPlatform {
    override val packageInstallationSupported = false
    override fun showToast(message: String) = Unit
    override fun openAppSettings() = false
    override fun openUnknownSourcesSettings() = false
    override fun requestInstalledAppsPermission(onResult: (Boolean) -> Unit) = false
    override fun requestPostNotificationsPermission(onResult: () -> Unit) = false
    override suspend fun saveImageToPictures(url: String, fileName: String) = ImageSaveResult.Unsupported
}
