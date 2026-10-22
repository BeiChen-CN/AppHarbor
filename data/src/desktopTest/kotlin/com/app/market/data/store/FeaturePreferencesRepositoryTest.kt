package com.app.market.data.store

import com.app.market.data.local.*
import com.app.market.domain.model.market.AppSource
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class FeaturePreferencesRepositoryTest {
    @Test
    fun independentSourcesPersistAndInvalidCommentSourceFallsBack() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val storage = FeatureMemoryPreferences()
            val first = FeaturePreferencesRepositoryImpl(storage, scope)
            assertTrue(first.settings.value.showRecommendationUpdates)
            first.setAggregateUpdates(true)
            first.setCommentSource(AppSource.KUAIBAO)
            first.setRecommendationSource(AppSource.VIVO)
            first.setIncludePrereleaseUpdates(true)
            first.setOnlyUnmeteredDownloads(true)
            first.setShowRecommendationUpdates(false)
            first.setAggregateSources(setOf(AppSource.COOLAPK, AppSource.GITHUB))
            first.setPreferredUpdateSource("com.example.one", AppSource.GITHUB)
            first.setPreferredUpdateSource("com.example.two", AppSource.COOLAPK)
            val restored = FeaturePreferencesRepositoryImpl(storage, scope)
            assertEquals(first.settings.value, restored.settings.value)
            assertTrue(restored.settings.value.aggregateUpdates)
            assertTrue(restored.settings.value.includePrereleaseUpdates)
            assertTrue(restored.settings.value.onlyUnmeteredDownloads)
            assertFalse(restored.settings.value.showRecommendationUpdates)
            restored.setShowRecommendationUpdates(true)
            assertTrue(restored.settings.value.showRecommendationUpdates)
            assertEquals(setOf(AppSource.COOLAPK, AppSource.GITHUB), restored.settings.value.aggregateSources)
            assertEquals(mapOf("com.example.one" to AppSource.GITHUB, "com.example.two" to AppSource.COOLAPK), restored.settings.value.preferredUpdateSources)
            restored.setPreferredUpdateSource("com.example.one", null)
            assertEquals(mapOf("com.example.two" to AppSource.COOLAPK), restored.settings.value.preferredUpdateSources)
            assertFailsWith<IllegalArgumentException> { first.setAggregateSources(emptySet()) }
            assertEquals(AppSource.KUAIBAO, restored.settings.value.commentSource)
            assertEquals(AppSource.VIVO, restored.settings.value.recommendationSource)
            storage.put(StringPreferenceKey("features", "comment_source"), "github")
            assertNull(restored.settings.value.commentSource)
            assertEquals(AppSource.VIVO, restored.settings.value.recommendationSource)
        } finally { scope.cancel() }
    }

    @Test
    fun legacyTodaySourceIsUsedUntilRecommendationSourceIsSelected() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val storage = FeatureMemoryPreferences()
            storage.put(StringPreferenceKey("update_preferences", "today_source"), "oppo")
            val repository = FeaturePreferencesRepositoryImpl(storage, scope)
            assertEquals(AppSource.OPPO, repository.settings.value.recommendationSource)
            repository.setRecommendationSource(AppSource.KUAIBAO)
            storage.put(StringPreferenceKey("update_preferences", "today_source"), "vivo")
            assertEquals(AppSource.KUAIBAO, repository.settings.value.recommendationSource)
            assertEquals(AppSource.KUAIBAO, FeaturePreferencesRepositoryImpl(storage, scope).settings.value.recommendationSource)
        } finally { scope.cancel() }
    }
}

private class FeatureMemoryPreferences : PreferencesDataSource {
    private val strings = mutableMapOf<StringPreferenceKey, MutableStateFlow<String?>>()
    private val booleans = mutableMapOf<BooleanPreferenceKey, MutableStateFlow<Boolean>>()
    override fun observe(key: StringPreferenceKey) = strings.getOrPut(key) { MutableStateFlow(null) }
    override fun observe(key: BooleanPreferenceKey) = booleans.getOrPut(key) { MutableStateFlow(key.default) }
    override suspend fun update(namespace: String, changes: PreferenceChanges) {
        changes.strings.forEach { (key, value) -> observe(key).value = value }
        changes.booleans.forEach { (key, value) -> observe(key).value = value ?: key.default }
    }
}
