package com.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.market.domain.exception.InstalledPackagesUnavailableException
import com.app.market.domain.model.download.DownloadState
import com.app.market.domain.model.installed.PackageChange
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.preference.HomePage
import com.app.market.domain.model.update.IgnoredUpdate
import com.app.market.domain.model.update.aggregateUpdatesFlow
import com.app.market.domain.model.update.SourceCheckFailure
import com.app.market.domain.model.update.eligibleUpdates
import com.app.market.domain.model.update.selectLatestUpdates
import com.app.market.domain.repository.updateSelectionPolicy
import com.app.market.domain.repository.FeaturePreferencesRepository
import com.app.market.domain.repository.MainTabId
import com.app.market.domain.repository.DownloadRepository
import com.app.market.domain.repository.MarketSourceRepository
import com.app.market.domain.repository.PackageRepository
import com.app.market.domain.repository.UpdatePreferencesRepository
import com.app.market.platform.UiPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class UpdatesUiState(
    val loading: Boolean = false,
    val checkFailed: Boolean = false,
    val appListPermissionRequired: Boolean = false,
    val showSystemUpdates: Boolean = true,
    val removeSearchAds: Boolean = false,
    val filterQuickGames: Boolean = false,
    val filterReservationApps: Boolean = false,
    val updates: List<MarketAppInfo> = emptyList(),
    val failedSources: Set<AppSource> = emptySet(),
    val sourcesCompleted: Int = 0,
    val sourcesTotal: Int = 0,
    val failures: Map<AppSource, SourceCheckFailure> = emptyMap(),
    val sourceResults: Map<AppSource, List<MarketAppInfo>> = emptyMap(),
    val cancelled: Boolean = false,
)

/** Raw (unfiltered) update-check result. The visible [UpdatesUiState] is derived from this + prefs. */
private data class RawUpdates(
    val loading: Boolean = false,
    val checkFailed: Boolean = false,
    val appListPermissionRequired: Boolean = false,
    val all: List<MarketAppInfo> = emptyList(),
    val failedSources: Set<AppSource> = emptySet(),
    val sourcesCompleted: Int = 0,
    val sourcesTotal: Int = 0,
    val failures: Map<AppSource, SourceCheckFailure> = emptyMap(),
    val sourceResults: Map<AppSource, List<MarketAppInfo>> = emptyMap(),
    val cancelled: Boolean = false,
)

class UpdatesViewModel(
    private val sources: MarketSourceRepository,
    private val prefs: UpdatePreferencesRepository,
    private val downloads: DownloadRepository,
    private val packages: PackageRepository,
    private val uiPlatform: UiPlatform,
    private val features: FeaturePreferencesRepository,
) : ViewModel() {

    // Seeded loading=true so Compose can request notification permission before the first live check
    // without flashing "no updates".
    private val _raw = MutableStateFlow(RawUpdates(loading = true))
    private val installedChanges = MutableStateFlow<Map<String, PackageChange>>(emptyMap())

    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states

    // Separate flow (not in [uiState]) so the pager can read the seeded value synchronously.
    val homePage: StateFlow<HomePage> = prefs.homePage
    val searchSources: StateFlow<Set<AppSource>> = prefs.searchSources
    val updateSource: StateFlow<AppSource> = prefs.updateSource
    val featureSettings = features.settings

    // App-detail display toggles; consumed by the detail screen, surfaced here for the settings UI.
    val showAppComments: StateFlow<Boolean> = prefs.showAppComments
    val showSameDeveloper: StateFlow<Boolean> = prefs.showSameDeveloper
    val showPromotions: StateFlow<Boolean> = prefs.showPromotions
    val detectClipboardLinks: StateFlow<Boolean> = prefs.detectClipboardLinks

    // 全局应用名精简开关；App 根部经 CompositionLocal 下发，这里仅供设置页读写。
    val stripAppNameSubtitle: StateFlow<Boolean> = prefs.stripAppNameSubtitle

    // Visible list = pure function of the raw result + preference flows; filtered off-main via flowOn.
    val uiState: StateFlow<UpdatesUiState> =
        combine(
            combine(_raw, features.settings, installedChanges) { raw, settings, changes ->
                val reconciled = raw.sourceResults.mapValues { (_, apps) -> reconcileInstalledChanges(apps, changes) }
                raw.copy(sourceResults = reconciled, all = if (settings.aggregateUpdates)
                    selectLatestUpdates(reconciled.values.flatten(), settings.updateSelectionPolicy)
                else reconcileInstalledChanges(raw.all, changes))
            },
            prefs.showSystemUpdates,
            prefs.removeSearchAds,
            combine(prefs.filterQuickGames, prefs.filterReservationApps) { games, reservations -> games to reservations },
            combine(prefs.permanentIgnores, prefs.onceIgnores) { permanent, once -> permanent to once },
        ) { raw, showSystem, removeAds, filters, ignores ->
            derive(raw, showSystem, removeAds, filters.first, filters.second, ignores.first, ignores.second)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, UpdatesUiState(loading = true))

    private val pendingDownloads = mutableSetOf<String>()
    private var initialCheckStarted = false
    private var checkJob: kotlinx.coroutines.Job? = null

    init {
        // Seed the last result for an instant warm launch — reconciled against the installed set so
        // out-of-band updates don't flash a stale row. Only while the live check hasn't returned yet.
        viewModelScope.launch {
            val selectedSource = prefs.updateSource.value
            // The legacy cache is Xiaomi-owned. Never show it while a non-Xiaomi update source is selected.
            val cached = if (selectedSource == AppSource.XIAOMI && !features.settings.value.aggregateUpdates) {
                sources.loadReconciledCachedUpdates()
            } else {
                emptyList()
            }
            if (cached.isNotEmpty() && prefs.updateSource.value == selectedSource && !features.settings.value.aggregateUpdates) {
                _raw.update { if (it.all.isEmpty()) it.copy(all = cached) else it }
            }
        }
        viewModelScope.launch {
            packages.changes.collect { change ->
                installedChanges.update { it + (change.packageName to change) }
            }
        }

        viewModelScope.launch {
            var activeSource = Triple(prefs.updateSource.value, features.settings.value.aggregateUpdates, features.settings.value.aggregateSourceOrder)
            combine(prefs.updateSource, features.settings.map { it.aggregateUpdates to it.aggregateSourceOrder }.distinctUntilChanged()) { source, aggregate ->
                Triple(source, aggregate.first, aggregate.second)
            }.collect { nextSource ->
                if (nextSource == activeSource) return@collect
                activeSource = nextSource
                // Source-owned IDs, refs and delta metadata cannot survive a source switch.
                _raw.value = RawUpdates(loading = initialCheckStarted)
                if (initialCheckStarted) runCheck()
            }
        }
    }

    fun setShowSystemUpdates(value: Boolean) = persist { prefs.setShowSystemUpdates(value) }
    fun setRemoveSearchAds(value: Boolean) = persist { prefs.setRemoveSearchAds(value) }
    fun setFilterQuickGames(value: Boolean) = persist { prefs.setFilterQuickGames(value) }
    fun setFilterReservationApps(value: Boolean) = persist { prefs.setFilterReservationApps(value) }
    fun setShowAppComments(value: Boolean) = persist { prefs.setShowAppComments(value) }
    fun setShowSameDeveloper(value: Boolean) = persist { prefs.setShowSameDeveloper(value) }
    fun setShowPromotions(value: Boolean) = persist { prefs.setShowPromotions(value) }
    fun setDetectClipboardLinks(value: Boolean) = persist { prefs.setDetectClipboardLinks(value) }
    fun setStripAppNameSubtitle(value: Boolean) = persist { prefs.setStripAppNameSubtitle(value) }
    fun setHomePage(value: HomePage) = persist { prefs.setHomePage(value) }
    fun setSearchSource(value: AppSource) = persist { prefs.setSearchSources(setOf(value)) }
    fun setUpdateSource(value: AppSource) = persist { prefs.setUpdateSource(value) }
    fun setIncludePrereleaseUpdates(value: Boolean) = persist { features.setIncludePrereleaseUpdates(value) }
    fun setOnlyUnmeteredDownloads(value: Boolean) = persist { features.setOnlyUnmeteredDownloads(value) }
    fun setShowRecommendationUpdates(value: Boolean) = persist { features.setShowRecommendationUpdates(value) }
    fun setAggregateSources(value: Set<AppSource>) = persist { features.setAggregateSources(value) }
    fun setAggregateSourceOrder(value: List<AppSource>) = persist { features.setAggregateSourceOrder(value) }
    fun setMeteredDownloadPrompt(value: Boolean) = persist { features.setMeteredDownloadPrompt(value) }
    fun setHiddenMainTabs(value: Set<MainTabId>) = persist { features.setHiddenMainTabs(value) }
    fun setPreferredUpdateSource(packageName: String, source: AppSource?) = persist { features.setPreferredUpdateSource(packageName, source) }
    fun updateCandidates(packageName: String): List<MarketAppInfo> = eligibleUpdates(
        reconcileInstalledChanges(_raw.value.sourceResults.values.flatten(), installedChanges.value), features.settings.value.updateSelectionPolicy
    ).filter { it.packageName == packageName }

    fun setAggregateUpdates(value: Boolean) = persist { features.setAggregateUpdates(value) }
    fun setCommentSource(value: AppSource?) = persist { features.setCommentSource(value) }
    fun setRecommendationSource(value: AppSource) = persist { features.setRecommendationSource(value) }

    fun startInitialCheck() {
        if (initialCheckStarted) return
        initialCheckStarted = true
        runCheck()
    }

    fun refresh() {
        initialCheckStarted = true
        runCheck()
    }

    fun cancelCheck() {
        checkJob?.cancel()
        _raw.update { it.copy(loading = false, cancelled = true, checkFailed = false) }
    }

    fun retryFailedSources() {
        if (_raw.value.loading || _raw.value.failedSources.isEmpty()) return
        runCheck(retryOnly = true)
    }

    private fun runCheck(retryOnly: Boolean = false) {
        checkJob?.cancel()
        _raw.update { it.copy(loading = true, checkFailed = false, cancelled = false, appListPermissionRequired = false) }
        checkJob = viewModelScope.launch {
            try {
                prefs.initialized.first { it }
                features.initialized.first { it }
                val settings = features.settings.value
                val selectedSource = prefs.updateSource.value
                val selected = settings.aggregateSourceOrder
                val requested = if (retryOnly) selected.filter { it in _raw.value.failedSources } else selected
                val retained = if (retryOnly) _raw.value.sourceResults.filterKeys { it !in requested } else emptyMap()
                _raw.update {
                    it.copy(failedSources = emptySet(), failures = emptyMap(), sourceResults = retained,
                        sourcesCompleted = if (retryOnly) selected.size - requested.size else 0,
                        sourcesTotal = if (settings.aggregateUpdates) selected.size else 0)
                }
                if (settings.aggregateUpdates) {
                    sources.aggregateUpdatesFlow(requested, settings.updateSelectionPolicy, retained).collect { result ->
                        _raw.update { it.copy(all = result.updates, failedSources = result.failedSources,
                            failures = result.failures, sourceResults = result.sourceResults,
                            sourcesCompleted = selected.size - requested.size + result.completed, sourcesTotal = selected.size) }
                    }
                    _raw.update { it.copy(loading = false, checkFailed = it.sourcesTotal > 0 && it.failedSources.size == it.sourcesTotal) }
                    return@launch
                }
                var latestLiveUpdates: List<MarketAppInfo>? = null
                sources.checkUpdatesFlow(selectedSource).collect { updates ->
                    latestLiveUpdates = updates
                    _raw.update { current ->
                        current.copy(
                            all = preserveKnownDeltaSizes(current.all, updates),
                            appListPermissionRequired = false,
                        )
                    }
                }
                val finalUpdates = latestLiveUpdates ?: _raw.value.all
                _raw.update {
                    it.copy(
                        loading = false,
                        checkFailed = false,
                        appListPermissionRequired = false,
                        all = finalUpdates,
                    )
                }
                // The persisted cache predates source-aware rows and is Xiaomi-only.
                if (selectedSource == AppSource.XIAOMI) prefs.saveCachedUpdates(finalUpdates)
            } catch (_: InstalledPackagesUnavailableException) {
                _raw.update { it.copy(loading = false, checkFailed = false, appListPermissionRequired = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _raw.update { it.copy(loading = false, checkFailed = true, appListPermissionRequired = false) }
            }
        }
    }

    fun download(app: MarketAppInfo) {
        if (!pendingDownloads.add(app.packageName)) return
        viewModelScope.launch {
            try {
                runCatchingCancellable {
                    sources.downloadUpdateMeta(app.source, app)
                }
                    .onSuccess { downloads.start(it) }
                    .onFailure { uiPlatform.showToast(it.message ?: "Download failed") }
            } finally {
                pendingDownloads.remove(app.packageName)
            }
        }
    }

    fun downloadAll(apps: List<MarketAppInfo>) {
        apps.forEach(::download)
    }

    fun installDownloaded(packageName: String) = downloads.install(packageName)
    fun cancelDownload(packageName: String) = downloads.cancel(packageName)

    fun ignoreOnce(app: MarketAppInfo) = persist { prefs.ignoreOnce(app) }
    fun ignorePermanently(app: MarketAppInfo) = persist { prefs.ignorePermanently(app) }

    private fun persist(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun derive(
        raw: RawUpdates,
        showSystem: Boolean,
        removeAds: Boolean,
        filterGames: Boolean,
        filterReservations: Boolean,
        permanent: List<IgnoredUpdate>,
        once: List<IgnoredUpdate>,
    ): UpdatesUiState {
        val visible = raw.all
            .filter { showSystem || !it.isSystemApp }
            .filterNot { app ->
                permanent.any { it.packageName == app.packageName } ||
                        once.any { it.packageName == app.packageName && it.versionCode == app.versionCode }
            }
        return UpdatesUiState(
            loading = raw.loading,
            checkFailed = raw.checkFailed,
            appListPermissionRequired = raw.appListPermissionRequired,
            showSystemUpdates = showSystem,
            removeSearchAds = removeAds,
            filterQuickGames = filterGames,
            filterReservationApps = filterReservations,
            updates = visible,
            failedSources = raw.failedSources,
            sourcesCompleted = raw.sourcesCompleted,
            sourcesTotal = raw.sourcesTotal,
            failures = raw.failures,
            sourceResults = raw.sourceResults,
            cancelled = raw.cancelled,
        )
    }
}

internal fun mergeUpdateSources(
    primary: List<MarketAppInfo>,
    secondary: List<MarketAppInfo>,
): List<MarketAppInfo> {
    val merged = LinkedHashMap<String, MarketAppInfo>(primary.size + secondary.size)
    primary.forEach { merged[it.packageName] = it }
    secondary.forEach { incoming ->
        val current = merged[incoming.packageName]
        if (current == null || incoming.versionCode > current.versionCode) {
            merged[incoming.packageName] = incoming
        }
    }
    return merged.values.toList()
}

internal fun preserveKnownDeltaSizes(
    previous: List<MarketAppInfo>,
    incoming: List<MarketAppInfo>,
): List<MarketAppInfo> {
    if (incoming.isEmpty()) return incoming
    val sourceSafeIncoming = incoming.map { app ->
        // 三星商店的 deltaSize 语义不可信，展示阶段强制清零
        if (!app.source.capabilities.reportsDeltaSize && app.deltaSize != 0L) {
            app.copy(deltaSize = 0L)
        } else {
            app
        }
    }
    if (previous.isEmpty()) return sourceSafeIncoming
    val previousByPackage = previous.associateBy(MarketAppInfo::packageName)
    return sourceSafeIncoming.map { current ->
        val cached = previousByPackage[current.packageName] ?: return@map current
        val cachedDeltaSize = cached.deltaSize.takeIf { deltaSize ->
            current.deltaSize <= 0L &&
                    deltaSize > 0L &&
                    deltaSize < current.apkSize &&
                    cached.source == current.source &&
                    cached.versionCode == current.versionCode &&
                    cached.installedVersionCode == current.installedVersionCode &&
                    cached.apkSize == current.apkSize &&
                    cached.installedBaseApkPath == current.installedBaseApkPath &&
                    cached.installedSplits == current.installedSplits
        }
        if (cachedDeltaSize == null) current else current.copy(deltaSize = cachedDeltaSize)
    }
}

/** 安装广播优先于检查开始时的快照，防止迟到的来源结果重新显示已更新或已卸载的应用。 */
internal fun reconcileInstalledChanges(apps: List<MarketAppInfo>, changes: Map<String, PackageChange>): List<MarketAppInfo> = apps.mapNotNull { app ->
    val change = changes[app.packageName] ?: return@mapNotNull app
    val changedCode = change.installedVersionCode ?: return@mapNotNull null
    val installedCode = maxOf(changedCode, app.installedVersionCode)
    val installedName = if (changedCode >= app.installedVersionCode) change.installedVersionName.ifBlank { app.installedVersionName }
        else app.installedVersionName
    app.takeIf { it.versionCode > installedCode }?.copy(installedVersionCode = installedCode, installedVersionName = installedName)
}
