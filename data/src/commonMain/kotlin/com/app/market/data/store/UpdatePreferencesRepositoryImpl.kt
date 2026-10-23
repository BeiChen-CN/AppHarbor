package com.app.market.data.store

import com.app.market.data.local.PreferenceChanges
import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.StringPreferenceKey
import com.app.market.data.local.preferences.UpdatePreferenceKeys
import com.app.market.data.platform.debugLog
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.preference.HomePage
import com.app.market.domain.model.update.IgnoredUpdate
import com.app.market.domain.repository.UpdatePreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put


internal class UpdatePreferencesRepositoryImpl(
    private val preferences: PreferencesDataSource,
    private val scope: CoroutineScope,
    private val json: Json
) : UpdatePreferencesRepository {

    private val _initialized = MutableStateFlow(false)
    override val initialized: StateFlow<Boolean> = _initialized.asStateFlow()
    private val ignoreMutex = Mutex()
    private val _showSystemUpdates = MutableStateFlow(true)
    override val showSystemUpdates: StateFlow<Boolean> = _showSystemUpdates.asStateFlow()
    private val _removeSearchAds = MutableStateFlow(false)
    override val removeSearchAds: StateFlow<Boolean> = _removeSearchAds.asStateFlow()
    private val _filterQuickGames = MutableStateFlow(false)
    override val filterQuickGames: StateFlow<Boolean> = _filterQuickGames.asStateFlow()
    private val _filterReservationApps = MutableStateFlow(false)
    override val filterReservationApps: StateFlow<Boolean> = _filterReservationApps.asStateFlow()
    private val _showAppComments = MutableStateFlow(false)
    override val showAppComments: StateFlow<Boolean> = _showAppComments.asStateFlow()
    private val _showSameDeveloper = MutableStateFlow(false)
    override val showSameDeveloper: StateFlow<Boolean> = _showSameDeveloper.asStateFlow()
    private val _showPromotions = MutableStateFlow(false)
    override val showPromotions: StateFlow<Boolean> = _showPromotions.asStateFlow()
    private val _detectClipboardLinks = MutableStateFlow(UpdatePreferenceKeys.DetectClipboardLinks.default)
    override val detectClipboardLinks: StateFlow<Boolean> = _detectClipboardLinks.asStateFlow()
    private val _stripAppNameSubtitle = MutableStateFlow(false)
    override val stripAppNameSubtitle: StateFlow<Boolean> = _stripAppNameSubtitle.asStateFlow()

    private val _homePage = MutableStateFlow(HomePage.RECOMMENDATIONS)
    override val homePage: StateFlow<HomePage> = _homePage.asStateFlow()
    private val _searchSources = MutableStateFlow(AppSource.Default)
    override val searchSources: StateFlow<Set<AppSource>> = _searchSources.asStateFlow()
    private val _todaySource = MutableStateFlow(AppSource.DefaultTodaySource)
    override val todaySource: StateFlow<AppSource> = _todaySource.asStateFlow()
    private val _updateSource = MutableStateFlow(AppSource.DefaultUpdateSource)
    override val updateSource: StateFlow<AppSource> = _updateSource.asStateFlow()
    private val _permanentIgnores = MutableStateFlow<List<IgnoredUpdate>>(emptyList())
    override val permanentIgnores: StateFlow<List<IgnoredUpdate>> = _permanentIgnores.asStateFlow()
    private val _onceIgnores = MutableStateFlow<List<IgnoredUpdate>>(emptyList())
    override val onceIgnores: StateFlow<List<IgnoredUpdate>> = _onceIgnores.asStateFlow()

    init {
        // observe 的首个发射即当前值，无需再串行读一遍
        val arrivals = listOf(
            observe("showSystemUpdates", preferences.observe(UpdatePreferenceKeys.ShowSystemUpdates)) { _showSystemUpdates.value = it },
            observe("removeSearchAds", preferences.observe(UpdatePreferenceKeys.RemoveSearchAds)) { _removeSearchAds.value = it },
            observe("filterQuickGames", preferences.observe(UpdatePreferenceKeys.FilterQuickGames)) { _filterQuickGames.value = it },
            observe(
                "filterReservationApps",
                preferences.observe(UpdatePreferenceKeys.FilterReservationApps)
            ) { _filterReservationApps.value = it },
            observe("showAppComments", preferences.observe(UpdatePreferenceKeys.ShowAppComments)) { _showAppComments.value = it },
            observe("showSameDeveloper", preferences.observe(UpdatePreferenceKeys.ShowSameDeveloper)) { _showSameDeveloper.value = it },
            observe("showPromotions", preferences.observe(UpdatePreferenceKeys.ShowPromotions)) { _showPromotions.value = it },
            observe("detectClipboardLinks", preferences.observe(UpdatePreferenceKeys.DetectClipboardLinks)) {
                _detectClipboardLinks.value = it
            },
            observe("stripAppNameSubtitle", preferences.observe(UpdatePreferenceKeys.StripAppNameSubtitle)) {
                _stripAppNameSubtitle.value = it
            },
            observe("homePage", preferences.observe(UpdatePreferenceKeys.HomePage)) { _homePage.value = HomePage.fromToken(it) },
            observe("searchSources", preferences.observe(UpdatePreferenceKeys.SearchSources)) {
                _searchSources.value = AppSource.parse(it)
            },
            observe("todaySource", preferences.observe(UpdatePreferenceKeys.TodaySource)) {
                _todaySource.value = AppSource.fromToken(it).todayOrDefault()
            },
            observe("updateSource", preferences.observe(UpdatePreferenceKeys.UpdateSource)) {
                _updateSource.value = AppSource.fromToken(it) ?: AppSource.DefaultUpdateSource
            },
            observe("permanentIgnores", preferences.observe(UpdatePreferenceKeys.PermanentIgnores)) {
                _permanentIgnores.value = parseIgnored(it)
            },
            observe("onceIgnores", preferences.observe(UpdatePreferenceKeys.OnceIgnores)) { _onceIgnores.value = parseIgnored(it) },
        )
        scope.launch {
            arrivals.forEach { it.await() }
            _initialized.value = true
        }
    }

    /** 收集 [flow] 到状态，返回一个在首个值到达（或读取失败）时完成的信号。 */
    private fun <T> observe(name: String, flow: Flow<T>, collect: (T) -> Unit): CompletableDeferred<Unit> {
        val firstValue = CompletableDeferred<Unit>()
        scope.launch {
            try {
                flow.collect {
                    collect(it)
                    firstValue.complete(Unit)
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                debugLog("UpdatePreferencesRepository") { "observe $name failed: $error" }
            } finally {
                firstValue.complete(Unit)
            }
        }
        return firstValue
    }

    override suspend fun setShowSystemUpdates(value: Boolean) =
        preferences.put(UpdatePreferenceKeys.ShowSystemUpdates, value)

    override suspend fun setRemoveSearchAds(value: Boolean) =
        preferences.put(UpdatePreferenceKeys.RemoveSearchAds, value)

    override suspend fun setFilterQuickGames(value: Boolean) =
        preferences.put(UpdatePreferenceKeys.FilterQuickGames, value)

    override suspend fun setFilterReservationApps(value: Boolean) =
        preferences.put(UpdatePreferenceKeys.FilterReservationApps, value)

    override suspend fun setShowAppComments(value: Boolean) =
        preferences.put(UpdatePreferenceKeys.ShowAppComments, value)

    override suspend fun setShowSameDeveloper(value: Boolean) =
        preferences.put(UpdatePreferenceKeys.ShowSameDeveloper, value)

    override suspend fun setShowPromotions(value: Boolean) =
        preferences.put(UpdatePreferenceKeys.ShowPromotions, value)

    override suspend fun setDetectClipboardLinks(value: Boolean) =
        preferences.put(UpdatePreferenceKeys.DetectClipboardLinks, value)

    override suspend fun lastClipboardLink(): String =
        preferences.read(UpdatePreferenceKeys.LastClipboardLink).orEmpty()

    override suspend fun setLastClipboardLink(value: String) =
        preferences.put(UpdatePreferenceKeys.LastClipboardLink, value)

    override suspend fun setStripAppNameSubtitle(value: Boolean) =
        preferences.put(UpdatePreferenceKeys.StripAppNameSubtitle, value)

    override suspend fun setHomePage(value: HomePage) =
        preferences.put(UpdatePreferenceKeys.HomePage, value.token)

    override suspend fun setSearchSources(value: Set<AppSource>) =
        preferences.put(UpdatePreferenceKeys.SearchSources, AppSource.serialize(value.ifEmpty { AppSource.Default }))

    override suspend fun setTodaySource(value: AppSource) =
        preferences.put(UpdatePreferenceKeys.TodaySource, value.todayOrDefault().token)

    override suspend fun setUpdateSource(value: AppSource) =
        preferences.put(UpdatePreferenceKeys.UpdateSource, value.token)

    override fun isIgnored(app: MarketAppInfo): Boolean {
        if (_permanentIgnores.value.any { it.packageName == app.packageName }) return true
        return _onceIgnores.value.any { it.packageName == app.packageName && it.versionCode == app.versionCode }
    }

    override suspend fun ignorePermanently(app: MarketAppInfo) = ignoreMutex.withLock {
        val once = readIgnored(UpdatePreferenceKeys.OnceIgnores).filterNot { it.packageName == app.packageName }
        val permanent = upsert(readIgnored(UpdatePreferenceKeys.PermanentIgnores), app)
        persistIgnores(permanent, once)
    }

    override suspend fun ignoreOnce(app: MarketAppInfo) = ignoreMutex.withLock {
        val permanent = readIgnored(UpdatePreferenceKeys.PermanentIgnores)
        if (permanent.none { it.packageName == app.packageName }) {
            val once = upsert(readIgnored(UpdatePreferenceKeys.OnceIgnores), app)
            persistIgnores(permanent, once)
        }
    }

    override suspend fun removePermanent(packageName: String) = ignoreMutex.withLock {
        val permanent = readIgnored(UpdatePreferenceKeys.PermanentIgnores).filterNot { it.packageName == packageName }
        persistIgnores(permanent, readIgnored(UpdatePreferenceKeys.OnceIgnores))
    }

    override suspend fun removeOnce(packageName: String) = ignoreMutex.withLock {
        val once = readIgnored(UpdatePreferenceKeys.OnceIgnores).filterNot { it.packageName == packageName }
        persistIgnores(readIgnored(UpdatePreferenceKeys.PermanentIgnores), once)
    }

    private suspend fun persistIgnores(permanent: List<IgnoredUpdate>, once: List<IgnoredUpdate>) {
        preferences.update(
            UpdatePreferenceKeys.PermanentIgnores.namespace,
            PreferenceChanges(
                strings = mapOf(
                    UpdatePreferenceKeys.PermanentIgnores to encodeIgnored(permanent),
                    UpdatePreferenceKeys.OnceIgnores to encodeIgnored(once),
                )
            ),
        )
    }

    private suspend fun readIgnored(key: StringPreferenceKey): List<IgnoredUpdate> =
        parseIgnored(preferences.read(key))

    private fun upsert(existing: List<IgnoredUpdate>, app: MarketAppInfo): List<IgnoredUpdate> =
        (existing.filterNot { it.packageName == app.packageName } + IgnoredUpdate(
            appId = app.appId,
            packageName = app.packageName,
            displayName = app.displayName,
            versionName = app.versionName,
            versionCode = app.versionCode,
            icon = app.icon,
            isSystemApp = app.isSystemApp,
            source = app.source,
        )).sortedIgnores()

    private fun List<IgnoredUpdate>.sortedIgnores(): List<IgnoredUpdate> =
        sortedWith(compareBy<IgnoredUpdate> { !it.isSystemApp }.thenBy { it.displayName })

    private fun parseIgnored(value: String?): List<IgnoredUpdate> {
        val raw = value?.takeIf { it.isNotBlank() } ?: return emptyList()
        val arr = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val pkg = o.str("packageName")
            if (pkg.isBlank()) return@mapNotNull null
            IgnoredUpdate(
                appId = o.long("appId"),
                packageName = pkg,
                displayName = o.str("displayName", pkg),
                versionName = o.str("versionName"),
                versionCode = o.long("versionCode"),
                icon = o.str("icon"),
                isSystemApp = o.bool("isSystemApp"),
                source = AppSource.fromToken(o.str("source")),
            )
        }
    }

    private fun encodeIgnored(items: List<IgnoredUpdate>): String {
        val sorted = items.sortedWith(compareBy<IgnoredUpdate> { !it.isSystemApp }.thenBy { it.displayName })
        val arr = buildJsonArray {
            sorted.forEach { item ->
                add(
                    buildJsonObject {
                        put("appId", item.appId)
                        put("packageName", item.packageName)
                        put("displayName", item.displayName)
                        put("versionName", item.versionName)
                        put("versionCode", item.versionCode)
                        put("icon", item.icon)
                        put("isSystemApp", item.isSystemApp)
                        item.source?.let { put("source", it.token) }
                    }
                )
            }
        }
        return arr.toString()
    }

    override suspend fun loadCachedUpdates(): List<MarketAppInfo> = withContext(Dispatchers.Default) {
        val raw = preferences.read(UpdatePreferenceKeys.CachedUpdates)?.takeIf { it.isNotBlank() } ?: return@withContext emptyList()
        val arr = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return@withContext emptyList()
        arr.mapNotNull { element ->
            val o = element as? JsonObject ?: return@mapNotNull null
            val pkg = o.str("packageName")
            if (pkg.isBlank()) return@mapNotNull null
            MarketAppInfo(
                appId = o.long("appId"),
                packageName = pkg,
                displayName = o.str("displayName", pkg),
                publisherName = o.str("publisherName"),
                versionName = o.str("versionName"),
                versionCode = o.long("versionCode"),
                icon = o.str("icon"),
                apkSize = o.long("apkSize"),
                deltaSize = o.long("deltaSize"),
                ratingScore = o.double("ratingScore"),
                changeLog = o.str("changeLog"),
                isSystemApp = o.bool("isSystemApp"),
                openLink = o.str("openLink"),
                installedVersionName = o.str("installedVersionName"),
                installedVersionCode = o.long("installedVersionCode"),
                installedOldApkHash = o.str("installedOldApkHash", "0"),
                installedBaseApkPath = o.str("installedBaseApkPath"),
                installedSplits = o.str("installedSplits", "0"),
                type = o.str("type"),
                source = AppSource.entries.firstOrNull { it.token == o.str("source") } ?: AppSource.XIAOMI,
            )
        }
    }

    // 编码整份更新列表（含 changeLog 全文）不能留在调用方的主线程上，与 loadCachedUpdates 对称
    override suspend fun saveCachedUpdates(updates: List<MarketAppInfo>) = withContext(Dispatchers.Default) {
        val arr = buildJsonArray {
            updates.forEach { app ->
                add(
                    buildJsonObject {
                        put("appId", app.appId)
                        put("packageName", app.packageName)
                        put("displayName", app.displayName)
                        put("publisherName", app.publisherName)
                        put("versionName", app.versionName)
                        put("versionCode", app.versionCode)
                        put("icon", app.icon)
                        put("apkSize", app.apkSize)
                        put("deltaSize", app.deltaSize)
                        put("ratingScore", app.ratingScore)
                        put("changeLog", app.changeLog)
                        put("isSystemApp", app.isSystemApp)
                        put("openLink", app.openLink)
                        put("installedVersionName", app.installedVersionName)
                        put("installedVersionCode", app.installedVersionCode)
                        put("installedOldApkHash", app.installedOldApkHash)
                        put("installedBaseApkPath", app.installedBaseApkPath)
                        put("installedSplits", app.installedSplits)
                        put("type", app.type)
                        put("source", app.source.token)
                    }
                )
            }
        }
        preferences.put(UpdatePreferenceKeys.CachedUpdates, arr.toString())
    }

    // 这三个值已有常驻热 StateFlow；每次搜索/翻页再往磁盘跑一趟纯属浪费
    override suspend fun currentRemoveSearchAds(): Boolean = current(_removeSearchAds)

    override suspend fun currentFilterQuickGames(): Boolean = current(_filterQuickGames)

    override suspend fun currentFilterReservationApps(): Boolean = current(_filterReservationApps)

    private suspend fun current(state: StateFlow<Boolean>): Boolean {
        initialized.first { it }
        return state.value
    }
}

private fun AppSource?.todayOrDefault(): AppSource =
    this?.takeIf { it.capabilities.supportsTodayFeed } ?: AppSource.DefaultTodaySource
