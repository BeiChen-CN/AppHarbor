package com.app.market.data.store

import com.app.market.data.local.*
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.update.UpdateSources
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
    fun newlySupportedSourceIsAddedOnceAndUncheckingSticks() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val storage = FeatureMemoryPreferences()
            val sourcesKey = StringPreferenceKey("features", "aggregate_sources")
            // 模拟老版本存储：用户只勾选了小米与酷安，还没有执行过任何来源补齐迁移。
            storage.put(sourcesKey, "${AppSource.XIAOMI.token},${AppSource.COOLAPK.token}")
            val first = FeaturePreferencesRepositoryImpl(storage, scope)
            assertEquals(
                listOf(AppSource.XIAOMI, AppSource.COOLAPK, AppSource.WANDOUJIA),
                first.settings.value.aggregateSourceOrder,
            )
            // 用户取消勾选新增来源后必须保持取消状态，不能被迁移逻辑反复补回。
            first.setAggregateSourceOrder(listOf(AppSource.XIAOMI, AppSource.COOLAPK))
            val second = FeaturePreferencesRepositoryImpl(storage, scope)
            assertEquals(listOf(AppSource.XIAOMI, AppSource.COOLAPK), second.settings.value.aggregateSourceOrder)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun migrationDoesNotRestoreOtherUncheckedSources() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val storage = FeatureMemoryPreferences()
            val sourcesKey = StringPreferenceKey("features", "aggregate_sources")
            // 用户此前主动只留了小米：除豌豆荚（本次新开放）外的其它来源都应保持未勾选。
            storage.put(sourcesKey, AppSource.XIAOMI.token)
            val repository = FeaturePreferencesRepositoryImpl(storage, scope)
            assertEquals(
                listOf(AppSource.XIAOMI, AppSource.WANDOUJIA),
                repository.settings.value.aggregateSourceOrder,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun completedMigrationIsNotAppliedTwice() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val storage = FeatureMemoryPreferences()
            val sourcesKey = StringPreferenceKey("features", "aggregate_sources")
            val migrationKey = StringPreferenceKey("features", "aggregate_sources_migrations")
            storage.put(sourcesKey, AppSource.XIAOMI.token)
            storage.put(migrationKey, "wandoujia-updates")
            val repository = FeaturePreferencesRepositoryImpl(storage, scope)
            // 迁移已执行过：即使旧 CSV 里没有豌豆荚，也不再补回。
            assertEquals(listOf(AppSource.XIAOMI), repository.settings.value.aggregateSourceOrder)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun untouchedSourcesKeepDefaultFullSet() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val storage = FeatureMemoryPreferences()
            // 从未调整过来源：迁移不应写入存储，继续走默认全集。
            val repository = FeaturePreferencesRepositoryImpl(storage, scope)
            assertEquals(UpdateSources, repository.settings.value.aggregateSourceOrder)
            assertNull(storage.observe(StringPreferenceKey("features", "aggregate_sources")).value)
        } finally {
            scope.cancel()
        }
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
