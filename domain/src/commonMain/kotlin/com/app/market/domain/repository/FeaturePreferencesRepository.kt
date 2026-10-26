package com.app.market.domain.repository

import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.update.UpdateSources
import com.app.market.domain.model.update.UpdateSelectionPolicy
import kotlinx.coroutines.flow.StateFlow

data class FeaturePreferences(
    val aggregateUpdates: Boolean = false,
    val commentSource: AppSource? = null,
    val recommendationSource: AppSource = AppSource.XIAOMI,
    val showRecommendationUpdates: Boolean = true,
    val includePrereleaseUpdates: Boolean = false,
    /** 聚合来源的优先级顺序：靠前者优先，只有靠前者不是最新版本时才顺移到下一来源。 */
    val aggregateSourceOrder: List<AppSource> = UpdateSources,
    val preferredUpdateSources: Map<String, AppSource> = emptyMap(),
    val onlyUnmeteredDownloads: Boolean = false,
    /** 计费网络下载前弹窗确认；关闭后不再提示。 */
    val meteredDownloadPrompt: Boolean = true,
    /** 底栏中隐藏的类别页签。 */
    val hiddenMainTabs: Set<MainTabId> = emptySet(),
) {
    val aggregateSources: Set<AppSource> get() = aggregateSourceOrder.toSet()
}

val FeaturePreferences.updateSelectionPolicy: UpdateSelectionPolicy
    get() = UpdateSelectionPolicy(includePrereleaseUpdates, preferredUpdateSources, aggregateSourceOrder)

val CommentSources = listOf(
    AppSource.XIAOMI, AppSource.KUAIBAO, AppSource.TAPTAP, AppSource.TENCENT, AppSource.APPCHINA,
    AppSource.X360, AppSource.SHOUDJI,
)
val RecommendationSources = listOf(AppSource.XIAOMI, AppSource.VIVO, AppSource.OPPO, AppSource.TAPTAP, AppSource.KUAIBAO)

fun effectiveCommentSource(preferred: AppSource?, app: AppSource): AppSource =
    preferred ?: if (app == AppSource.OPPO) AppSource.XIAOMI else app

interface FeaturePreferencesRepository {
    val initialized: StateFlow<Boolean>
    val settings: StateFlow<FeaturePreferences>
    suspend fun setAggregateUpdates(value: Boolean)
    suspend fun setCommentSource(value: AppSource?)
    suspend fun setRecommendationSource(value: AppSource)
    suspend fun setShowRecommendationUpdates(value: Boolean)
    suspend fun setIncludePrereleaseUpdates(value: Boolean)

    /** 保存聚合来源及其优先级顺序；顺序即"优先使用靠前来源"的次序。 */
    suspend fun setAggregateSourceOrder(value: List<AppSource>)

    /** 无序设置的兼容入口：按枚举顺序落库；空集合没有意义，直接拒绝。 */
    suspend fun setAggregateSources(value: Set<AppSource>) {
        require(value.isNotEmpty()) { "at least one aggregate source is required" }
        setAggregateSourceOrder(UpdateSources.filter { it in value })
    }

    suspend fun setPreferredUpdateSource(packageName: String, source: AppSource?)
    suspend fun setOnlyUnmeteredDownloads(value: Boolean)
    suspend fun setMeteredDownloadPrompt(value: Boolean)
    suspend fun setHiddenMainTabs(value: Set<MainTabId>)
}
