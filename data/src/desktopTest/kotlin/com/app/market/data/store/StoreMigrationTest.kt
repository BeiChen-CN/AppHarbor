package com.app.market.data.store

import com.app.market.data.local.BooleanPreferenceKey
import com.app.market.data.local.PreferenceChanges
import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.StringPreferenceKey
import com.app.market.data.local.preferences.HistoryPreferenceKeys
import com.app.market.data.local.preferences.UpdatePreferenceKeys
import com.app.market.data.platform.ThemePlatformPreferences
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.preference.HomePage
import com.app.market.domain.model.update.UpdateHistoryEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class StoreMigrationTest {

    @Test
    fun themePrefsRestoreNavRailExpansion() = runTest {
        val store = ControlledPreferencesDataSource(eagerDefaults = true)
        val platform = RecordingThemePlatformPreferences()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        try {
            val prefs = ThemePreferencesRepositoryImpl(store, scope, platform)
            advanceUntilIdle()
            assertFalse(prefs.navRailExpanded.value)

            prefs.setNavRailExpanded(true)
            advanceUntilIdle()
            assertTrue(prefs.navRailExpanded.value)

            val restored = ThemePreferencesRepositoryImpl(store, scope, platform)
            advanceUntilIdle()
            assertTrue(restored.initialized.value)
            assertTrue(restored.navRailExpanded.value)

            restored.setNavRailExpanded(false)
            advanceUntilIdle()
            assertFalse(prefs.navRailExpanded.value)
            assertFalse(restored.navRailExpanded.value)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun themePrefsPersistScaleBoundsAndMirrorPredictiveBack() = runTest {
        val store = ControlledPreferencesDataSource(eagerDefaults = true)
        val platform = RecordingThemePlatformPreferences()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val prefs = ThemePreferencesRepositoryImpl(store, scope, platform)
        advanceUntilIdle()

        assertTrue(prefs.initialized.value)
        prefs.setPageScale(2f)
        prefs.setEnablePredictiveBack(true)
        advanceUntilIdle()

        assertEquals(1.1f, prefs.pageScale.value)
        assertTrue(prefs.enablePredictiveBack.value)
        assertTrue(platform.lastPredictiveBackEnabled)
        scope.cancel()
    }

    @Test
    fun updatePrefsWaitsForTheFirstPersistedSnapshot() = runTest {
        val store = ControlledPreferencesDataSource()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val prefs = UpdatePreferencesRepositoryImpl(store, scope, Json)

        advanceUntilIdle()
        assertFalse(prefs.initialized.value)

        store.emit(UpdatePreferenceKeys.HomePage, HomePage.SEARCH.token)
        store.emit(UpdatePreferenceKeys.ShowSystemUpdates, false)
        store.emitDefaultsForRemainingUpdatePreferences()
        advanceUntilIdle()

        assertTrue(prefs.initialized.value)
        assertEquals(HomePage.SEARCH, prefs.homePage.value)
        assertFalse(prefs.showSystemUpdates.value)
        scope.cancel()
    }

    @Test
    fun updatePrefsFallsBackAndUnblocksWhenInitialReadFails() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val prefs = UpdatePreferencesRepositoryImpl(FailingReadPreferencesDataSource(), scope, Json)

        advanceUntilIdle()

        assertTrue(prefs.initialized.value)
        assertEquals(HomePage.RECOMMENDATIONS, prefs.homePage.value)
        assertTrue(prefs.showSystemUpdates.value)
        scope.cancel()
    }

    @Test
    fun ignoredUpdateKeepsTheMarketSourceThatProducedIt() = runTest {
        val store = ControlledPreferencesDataSource(eagerDefaults = true)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val prefs = UpdatePreferencesRepositoryImpl(store, scope, Json)
        advanceUntilIdle()
        val app = MarketAppInfo(
            appId = 123L,
            packageName = "com.example.oppo",
            displayName = "OPPO Example",
            publisherName = "Example",
            versionName = "2.0",
            versionCode = 2L,
            icon = "",
            apkSize = 100L,
            ratingScore = 5.0,
            source = AppSource.OPPO,
        )

        prefs.ignorePermanently(app)
        advanceUntilIdle()

        assertEquals(AppSource.OPPO, prefs.permanentIgnores.value.single().source)
        assertTrue(store.read(UpdatePreferenceKeys.PermanentIgnores).orEmpty().contains("\"source\":\"oppo\""))
        scope.cancel()
    }

    @Test
    fun updateHistoryCommitsAPersistedPendingEntryInOrder() = runTest {
        val store = ControlledPreferencesDataSource(eagerDefaults = true)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val history = UpdateHistoryRepositoryImpl(store, scope, Json)
        val entry = UpdateHistoryEntry(
            appId = 1,
            packageName = "com.example.app",
            displayName = "Example",
            versionName = "2.0",
            versionCode = 2,
            previousVersionName = "1.0",
            icon = "",
            changeLog = "",
            installedAt = 1,
            source = AppSource.OPPO,
        )

        history.markPending(entry)
        history.commitPending(entry.packageName)

        assertEquals(listOf(entry.packageName), history.entries.value.map { it.packageName })
        assertEquals(AppSource.OPPO, history.entries.value.single().source)
        assertTrue(history.pendingEntries().isEmpty())
        assertTrue(store.read(HistoryPreferenceKeys.Updates).orEmpty().contains(entry.packageName))
        scope.cancel()
    }

    @Test
    fun updateHistoryKeepsPendingWhenAtomicCommitFails() = runTest {
        val store = ControlledPreferencesDataSource(eagerDefaults = true)
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val history = UpdateHistoryRepositoryImpl(store, scope, Json)
        val entry = UpdateHistoryEntry(
            appId = 1,
            packageName = "com.example.retry",
            displayName = "Retry",
            versionName = "2.0",
            versionCode = 2,
            previousVersionName = "1.0",
            icon = "",
            changeLog = "",
            installedAt = 1,
        )

        history.markPending(entry)
        store.failNextUpdate()

        assertFailsWith<IllegalStateException> { history.commitPending(entry.packageName) }
        assertEquals(listOf(entry.packageName), history.pendingEntries().map { it.packageName })
        assertTrue(history.entries.value.isEmpty())

        history.commitPending(entry.packageName)
        assertTrue(history.pendingEntries().isEmpty())
        assertEquals(listOf(entry.packageName), history.entries.value.map { it.packageName })
        scope.cancel()
    }
}

private class RecordingThemePlatformPreferences : ThemePlatformPreferences {
    var lastPredictiveBackEnabled = false

    override fun setPredictiveBackEnabled(enabled: Boolean) {
        lastPredictiveBackEnabled = enabled
    }
}

private class FailingReadPreferencesDataSource : PreferencesDataSource {
    override fun observe(key: StringPreferenceKey): Flow<String?> = flow { error("corrupt preferences") }
    override fun observe(key: BooleanPreferenceKey): Flow<Boolean> = flow { error("corrupt preferences") }
    override suspend fun update(namespace: String, changes: PreferenceChanges) = Unit
}

private class ControlledPreferencesDataSource(
    private val eagerDefaults: Boolean = false,
) : PreferencesDataSource {
    private val strings = mutableMapOf<StringPreferenceKey, MutableSharedFlow<String?>>()
    private val booleans = mutableMapOf<BooleanPreferenceKey, MutableSharedFlow<Boolean>>()
    private var failNextUpdate = false

    override fun observe(key: StringPreferenceKey): Flow<String?> =
        strings.getOrPut(key) {
            MutableSharedFlow<String?>(replay = 1).also { if (eagerDefaults) it.tryEmit(null) }
        }

    override fun observe(key: BooleanPreferenceKey): Flow<Boolean> =
        booleans.getOrPut(key) {
            MutableSharedFlow<Boolean>(replay = 1).also { if (eagerDefaults) it.tryEmit(key.default) }
        }

    override suspend fun update(namespace: String, changes: PreferenceChanges) {
        changes.requireNamespace(namespace)
        if (failNextUpdate) {
            failNextUpdate = false
            error("simulated preference write failure")
        }
        changes.strings.forEach { (key, value) -> stringFlow(key).emit(value) }
        changes.booleans.forEach { (key, value) -> booleanFlow(key).emit(value ?: key.default) }
    }

    fun failNextUpdate() {
        failNextUpdate = true
    }

    fun emit(key: StringPreferenceKey, value: String) {
        stringFlow(key).tryEmit(value)
    }

    fun emit(key: BooleanPreferenceKey, value: Boolean) {
        booleanFlow(key).tryEmit(value)
    }

    fun emitDefaultsForRemainingUpdatePreferences() {
        emit(UpdatePreferenceKeys.RemoveSearchAds, false)
        emit(UpdatePreferenceKeys.FilterQuickGames, false)
        emit(UpdatePreferenceKeys.FilterReservationApps, false)
        emit(UpdatePreferenceKeys.ShowAppComments, false)
        emit(UpdatePreferenceKeys.ShowSameDeveloper, false)
        emit(UpdatePreferenceKeys.ShowPromotions, false)
        emit(UpdatePreferenceKeys.DetectClipboardLinks, true)
        emit(UpdatePreferenceKeys.StripAppNameSubtitle, false)
        // 空串代表未配置过，回退默认源
        emit(UpdatePreferenceKeys.SearchSources, "")
        emit(UpdatePreferenceKeys.TodaySource, "")
        emit(UpdatePreferenceKeys.UpdateSource, "")
        emit(UpdatePreferenceKeys.PermanentIgnores, "[]")
        emit(UpdatePreferenceKeys.OnceIgnores, "[]")
    }

    private fun stringFlow(key: StringPreferenceKey): MutableSharedFlow<String?> =
        strings.getOrPut(key) {
            MutableSharedFlow<String?>(replay = 1).also { if (eagerDefaults) it.tryEmit(null) }
        }

    private fun booleanFlow(key: BooleanPreferenceKey): MutableSharedFlow<Boolean> =
        booleans.getOrPut(key) {
            MutableSharedFlow<Boolean>(replay = 1).also { if (eagerDefaults) it.tryEmit(key.default) }
        }
}
