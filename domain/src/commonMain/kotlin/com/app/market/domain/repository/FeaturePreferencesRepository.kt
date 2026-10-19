package com.app.market.domain.repository

import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.update.UpdateSources
import com.app.market.domain.model.update.UpdateSelectionPolicy
import kotlinx.coroutines.flow.StateFlow

data class FeaturePreferences(
    val aggregateUpdates: Boolean = false,
    val commentSource: AppSource? = null,
    val recommendationSource: AppSource = AppSource.XIAOMI,
    val includePrereleaseUpdates: Boolean = false,
    val aggregateSources: Set<AppSource> = UpdateSources.toSet(),
    val preferredUpdateSources: Map<String, AppSource> = emptyMap(),
    val onlyUnmeteredDownloads: Boolean = false,
)

val FeaturePreferences.updateSelectionPolicy: UpdateSelectionPolicy
    get() = UpdateSelectionPolicy(includePrereleaseUpdates, preferredUpdateSources)

val CommentSources = listOf(AppSource.XIAOMI, AppSource.KUAIBAO)
val RecommendationSources = listOf(AppSource.XIAOMI, AppSource.VIVO, AppSource.OPPO, AppSource.TAPTAP, AppSource.KUAIBAO)

fun effectiveCommentSource(preferred: AppSource?, app: AppSource): AppSource =
    preferred ?: if (app == AppSource.OPPO) AppSource.XIAOMI else app

interface FeaturePreferencesRepository {
    val initialized: StateFlow<Boolean>
    val settings: StateFlow<FeaturePreferences>
    suspend fun setAggregateUpdates(value: Boolean)
    suspend fun setCommentSource(value: AppSource?)
    suspend fun setRecommendationSource(value: AppSource)
    suspend fun setIncludePrereleaseUpdates(value: Boolean)
    suspend fun setAggregateSources(value: Set<AppSource>)
    suspend fun setPreferredUpdateSource(packageName: String, source: AppSource?)
    suspend fun setOnlyUnmeteredDownloads(value: Boolean)
}
