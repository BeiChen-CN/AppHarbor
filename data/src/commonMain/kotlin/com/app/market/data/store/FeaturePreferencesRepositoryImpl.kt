package com.app.market.data.store

import com.app.market.data.local.BooleanPreferenceKey
import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.StringPreferenceKey
import com.app.market.data.local.preferences.UpdatePreferenceKeys
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.update.UpdateSources
import com.app.market.domain.repository.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class FeaturePreferencesRepositoryImpl(
    private val preferences: PreferencesDataSource,
    scope: CoroutineScope,
) : FeaturePreferencesRepository {
    private val mutableSettings = MutableStateFlow(FeaturePreferences())
    override val settings = mutableSettings.asStateFlow()
    private val mutableInitialized = MutableStateFlow(false)
    override val initialized = mutableInitialized.asStateFlow()
    private val pinLock = Mutex()

    init {
        scope.launch {
            combine(preferences.observe(Aggregate), preferences.observe(Comments),
                preferences.observe(Recommendations), preferences.observe(UpdatePreferenceKeys.TodaySource)) { aggregate, comments, recommendations, legacyToday ->
                FeaturePreferences(aggregate,
                    AppSource.fromToken(comments)?.takeIf { it in CommentSources },
                    AppSource.fromToken(recommendations)?.takeIf { it in RecommendationSources }
                        ?: AppSource.fromToken(legacyToday)?.takeIf { it in RecommendationSources }
                        ?: AppSource.XIAOMI)
            }.combine(combine(preferences.observe(Prereleases), preferences.observe(Sources), preferences.observe(Pinned)) { prereleases, sources, pinned ->
                Triple(prereleases, sources, pinned)
            }) { base, extra ->
                base.copy(includePrereleaseUpdates = extra.first,
                    aggregateSourceOrder = restoreSourceOrder(extra.second),
                    preferredUpdateSources = extra.third.orEmpty().split(',').mapNotNull { entry ->
                        val parts = entry.split('=', limit = 2)
                        val source = AppSource.fromToken(parts.getOrNull(1))?.takeIf { it in UpdateSources }
                        if (parts.first().isNotBlank() && source != null) parts.first() to source else null
                    }.toMap())
            }.combine(
                combine(preferences.observe(Unmetered), preferences.observe(RecommendationUpdates)) { unmetered, recommendationUpdates ->
                    unmetered to recommendationUpdates
                }
            ) { settings, flags ->
                settings.copy(
                    onlyUnmeteredDownloads = flags.first,
                    showRecommendationUpdates = flags.second,
                )
            }.combine(
                combine(preferences.observe(MeteredPrompt), preferences.observe(HiddenTabs)) { metered, hiddenTabs ->
                    metered to hiddenTabs
                }
            ) { settings, flags ->
                settings.copy(
                    meteredDownloadPrompt = flags.first,
                    hiddenMainTabs = MainTabId.parse(flags.second),
                )
            }
                .collect { mutableSettings.value = it; mutableInitialized.value = true }
        }
    }

    override suspend fun setAggregateUpdates(value: Boolean) = preferences.put(Aggregate, value)
    override suspend fun setCommentSource(value: AppSource?) {
        require(value == null || value in CommentSources)
        preferences.put(Comments, value?.token.orEmpty())
    }
    override suspend fun setRecommendationSource(value: AppSource) {
        require(value in RecommendationSources)
        preferences.put(Recommendations, value.token)
    }

    override suspend fun setIncludePrereleaseUpdates(value: Boolean) = preferences.put(Prereleases, value)
    override suspend fun setOnlyUnmeteredDownloads(value: Boolean) = preferences.put(Unmetered, value)
    override suspend fun setMeteredDownloadPrompt(value: Boolean) = preferences.put(MeteredPrompt, value)
    override suspend fun setShowRecommendationUpdates(value: Boolean) = preferences.put(RecommendationUpdates, value)

    override suspend fun setHiddenMainTabs(value: Set<MainTabId>) {
        // 至少保留一个页签，否则底栏会变成空条。
        require(value.size < MainTabId.Configurable.size)
        preferences.put(HiddenTabs, MainTabId.serialize(value))
    }

    /** 顺序即优先级：原样保存，不做排序。 */
    override suspend fun setAggregateSourceOrder(value: List<AppSource>) {
        require(value.isNotEmpty() && value.all { it in UpdateSources })
        preferences.put(Sources, value.distinct().joinToString(",") { it.token })
    }

    override suspend fun setPreferredUpdateSource(packageName: String, source: AppSource?) = pinLock.withLock {
        require(packageName.matches(Regex("[A-Za-z0-9_.]+")))
        require(source == null || source in UpdateSources)
        val pinned = preferences.observe(Pinned).first().orEmpty().split(',').mapNotNull { entry ->
            val parts = entry.split('=', limit = 2)
            val stored = AppSource.fromToken(parts.getOrNull(1))?.takeIf { it in UpdateSources }
            if (parts.first().isNotBlank() && stored != null) parts.first() to stored else null
        }.toMap().toMutableMap()
        if (source == null) pinned.remove(packageName) else pinned[packageName] = source
        preferences.put(Pinned, pinned.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value.token}" })
    }

    /**
     * 恢复存储的来源与顺序。
     *
     * 语义是「参与聚合的来源子集 + 优先级顺序」：未列出的来源视为未选中，不补回队尾，
     * 否则用户取消勾选会被静默撤销。存储为空（首次启动或旧数据为空）时退回默认全集。
     */
    private fun restoreSourceOrder(raw: String?): List<AppSource> {
        val stored = raw.orEmpty().split(',').mapNotNull(AppSource::fromToken)
            .filter { it in UpdateSources }.distinct()
        return stored.ifEmpty { UpdateSources }
    }

    private companion object {
        val Aggregate = BooleanPreferenceKey("features", "aggregate_updates")
        val Comments = StringPreferenceKey("features", "comment_source")
        val Recommendations = StringPreferenceKey("features", "recommendation_source")
        val Prereleases = BooleanPreferenceKey("features", "include_prerelease_updates")
        val Sources = StringPreferenceKey("features", "aggregate_sources")
        val Pinned = StringPreferenceKey("features", "preferred_update_sources")
        val Unmetered = BooleanPreferenceKey("features", "only_unmetered_downloads")
        val MeteredPrompt = BooleanPreferenceKey("features", "metered_download_prompt", default = true)
        val HiddenTabs = StringPreferenceKey("features", "hidden_main_tabs")
        val RecommendationUpdates = BooleanPreferenceKey("features", "recommendation_updates", default = true)
    }
}
