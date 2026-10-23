package com.app.market.domain.repository

import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.preference.HomePage
import com.app.market.domain.model.update.IgnoredUpdate
import kotlinx.coroutines.flow.StateFlow

/** Update-screen preferences, ignore lists, and the cached last result. */
interface UpdatePreferencesRepository {
    val initialized: StateFlow<Boolean>
    val showSystemUpdates: StateFlow<Boolean>
    val removeSearchAds: StateFlow<Boolean>
    val filterQuickGames: StateFlow<Boolean>
    val filterReservationApps: StateFlow<Boolean>
    val showAppComments: StateFlow<Boolean>
    val showSameDeveloper: StateFlow<Boolean>
    val showPromotions: StateFlow<Boolean>
    /** 进入应用时识别剪贴板中的商店链接并询问是否打开。 */
    val detectClipboardLinks: StateFlow<Boolean>
        get() = kotlinx.coroutines.flow.MutableStateFlow(true)
    val stripAppNameSubtitle: StateFlow<Boolean>
    val homePage: StateFlow<HomePage>
    val searchSources: StateFlow<Set<AppSource>>
    val todaySource: StateFlow<AppSource>
    val updateSource: StateFlow<AppSource>
    val permanentIgnores: StateFlow<List<IgnoredUpdate>>
    val onceIgnores: StateFlow<List<IgnoredUpdate>>
    suspend fun setShowSystemUpdates(value: Boolean)
    suspend fun setRemoveSearchAds(value: Boolean)
    suspend fun setFilterQuickGames(value: Boolean)
    suspend fun setFilterReservationApps(value: Boolean)
    suspend fun setShowAppComments(value: Boolean)
    suspend fun setShowSameDeveloper(value: Boolean)
    suspend fun setShowPromotions(value: Boolean)
    suspend fun setDetectClipboardLinks(value: Boolean) {}

    /** 上次已询问过的剪贴板链接（统一格式），同一链接只询问一次。 */
    suspend fun lastClipboardLink(): String = ""
    suspend fun setLastClipboardLink(value: String) {}
    suspend fun setStripAppNameSubtitle(value: Boolean)
    suspend fun setHomePage(value: HomePage)
    suspend fun setSearchSources(value: Set<AppSource>)
    suspend fun setTodaySource(value: AppSource)
    suspend fun setUpdateSource(value: AppSource)
    fun isIgnored(app: MarketAppInfo): Boolean
    suspend fun ignoreOnce(app: MarketAppInfo)
    suspend fun ignorePermanently(app: MarketAppInfo)
    suspend fun removePermanent(packageName: String)
    suspend fun removeOnce(packageName: String)
    suspend fun currentRemoveSearchAds(): Boolean
    suspend fun currentFilterQuickGames(): Boolean
    suspend fun currentFilterReservationApps(): Boolean
    suspend fun loadCachedUpdates(): List<MarketAppInfo>
    suspend fun saveCachedUpdates(updates: List<MarketAppInfo>)
}
