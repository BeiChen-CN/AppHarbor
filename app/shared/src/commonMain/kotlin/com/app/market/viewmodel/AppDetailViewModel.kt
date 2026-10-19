package com.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.market.domain.exception.CommentSourceResolutionException
import com.app.market.resources.Res
import com.app.market.resources.comments_unresolved
import org.jetbrains.compose.resources.getString
import com.app.market.domain.model.download.DownloadState
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.isDownloadBlocked
import com.app.market.domain.model.market.isReservation
import com.app.market.domain.repository.DownloadRepository
import com.app.market.domain.repository.MarketRepository
import com.app.market.domain.repository.MarketSourceRepository
import com.app.market.domain.repository.PackageRepository
import com.app.market.domain.repository.UpdatePreferencesRepository
import com.app.market.domain.repository.FeaturePreferencesRepository
import com.app.market.domain.repository.effectiveCommentSource
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.app.market.platform.UiPlatform
import com.app.market.ui.model.AppActionKind
import com.app.market.ui.model.actionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

const val APP_NOT_LISTED_MESSAGE = "应用商店尚未收录此应用"

@Immutable
data class AppDetailUiState(
    val loading: Boolean = true,
    val errorMessage: String = "",
    val detail: AppDetail? = null,
    /** Primary action for [detail]; derived from the app's resolved installed version (no I/O). */
    val actionKind: AppActionKind? = null,
    val commentsLoading: Boolean = false,
    val commentsError: String = "",
    val comments: AppComments = AppComments(emptyList(), 0L),
)

class AppDetailViewModel(
    private val sources: MarketSourceRepository,
    private val packages: PackageRepository,
    private val downloads: DownloadRepository,
    private val prefs: UpdatePreferencesRepository,
    private val uiPlatform: UiPlatform,
    private val features: FeaturePreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AppDetailUiState())
    val uiState: StateFlow<AppDetailUiState> = _uiState.asStateFlow()

    val downloadStates: StateFlow<Map<String, DownloadState>> = downloads.states

    // Detail-page sections gated by user preference (all default off).
    val showComments: StateFlow<Boolean> = prefs.showAppComments
    val showSameDeveloper: StateFlow<Boolean> = prefs.showSameDeveloper
    val showPromotions: StateFlow<Boolean> = prefs.showPromotions
    val featureSettings = features.settings
    private var commentsJob: Job? = null
    private var commentsGeneration = 0
    private var commentsRequestKey: String? = null

    // in-flight 去重靠 job 而非布尔标记：失败后必须允许重新加载，否则错误态永久卡死
    private var loadJob: Job? = null
    private val pendingDownloads = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            combine(prefs.initialized, prefs.showAppComments, features.settings.map { it.commentSource }.distinctUntilChanged()) {
                ready, enabled, source -> Triple(ready, enabled, source)
            }.collect { if (it.first) loadComments(force = false) }
        }
        viewModelScope.launch {
            packages.changes.collect { change ->
                refreshInstalledState(change)
            }
        }
    }

    fun load(
        appId: Long,
        packageName: String,
        externalQuery: String? = null,
        source: AppSource = AppSource.XIAOMI,
    ) {
        if (loadJob?.isActive == true) return
        if (_uiState.value.detail != null) return
        _uiState.update { it.copy(loading = true, errorMessage = "") }
        loadJob = viewModelScope.launch {
            try {
                val detail = sources.appDetail(source, appId, packageName, externalQuery).withInstalledState()
                _uiState.update {
                    it.copy(loading = false, detail = detail, actionKind = detail.app.actionKind())
                }
                loadOptionalSections(source, detail)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(loading = false, errorMessage = error.message ?: APP_NOT_LISTED_MESSAGE)
                }
            }
        }
    }

    private fun loadOptionalSections(source: AppSource, detail: AppDetail) {
        loadComments(force = false)
        viewModelScope.launch {
            prefs.initialized.first { it }
            supervisorScope {
                if (prefs.showSameDeveloper.value) launch {
                    runCatchingCancellable { sources.sameDeveloperApps(source, detail.app) }
                        .onSuccess { apps -> updateCurrentDetail(detail) { it.copy(sameDeveloperApps = apps) } }
                }
            }
        }
    }

    fun setCommentSource(source: AppSource?) {
        viewModelScope.launch {
            features.setCommentSource(source)
            prefs.setShowAppComments(true)
        }
    }

    fun reloadComments() = loadComments(force = true)

    private fun loadComments(force: Boolean) {
        val detail = _uiState.value.detail ?: return
        if (!prefs.initialized.value) return
        val source = effectiveCommentSource(features.settings.value.commentSource, detail.app.source)
        val key = "${detail.app.source}:${detail.app.appId}:${detail.app.packageName}:$source:${prefs.showAppComments.value}"
        if (!force && key == commentsRequestKey) return
        commentsRequestKey = key
        commentsJob?.cancel()
        val generation = ++commentsGeneration
        _uiState.update { it.copy(commentsLoading = false, commentsError = "", comments = AppComments(emptyList(), 0L)) }
        if (!prefs.showAppComments.value) return
        if (!source.capabilities.supportsComments) return
        commentsJob = viewModelScope.launch {
            _uiState.update { it.copy(commentsLoading = true) }
            val result = runCatchingCancellable { sources.appComments(source, detail.app) }
            val errorMessage = if (result.exceptionOrNull() is CommentSourceResolutionException) getString(Res.string.comments_unresolved)
                else result.exceptionOrNull()?.message.orEmpty()
            if (generation != commentsGeneration) return@launch
            _uiState.update { current -> current.copy(commentsLoading = false,
                commentsError = errorMessage,
                comments = result.getOrNull() ?: AppComments(emptyList(), 0L)) }
        }
    }

    /** 匿名第三方商店不认识本机，已安装版本得自己补，否则已装应用会显示成「安装」。 */
    private suspend fun AppDetail.withInstalledState(): AppDetail {
        val name = app.packageName
        val installedVersionCode = packages.installedVersionCodes(listOf(name))[name] ?: 0L
        if (installedVersionCode <= 0L) return this
        return copy(
            app = app.copy(
                installedVersionCode = installedVersionCode,
                installedVersionName = packages.installedVersionName(name).orEmpty(),
            )
        )
    }

    private fun updateCurrentDetail(expected: AppDetail, transform: (AppDetail) -> AppDetail) {
        _uiState.update { state ->
            val current = state.detail
            if (current == null ||
                current.app.appId != expected.app.appId ||
                current.app.packageName != expected.app.packageName
            ) {
                state
            } else {
                state.copy(detail = transform(current))
            }
        }
    }

    private fun refreshInstalledState(change: com.app.market.domain.model.installed.PackageChange) {
        val packageName = change.packageName
        val current = _uiState.value.detail ?: return
        if (current.app.packageName != packageName) return
        _uiState.update { state ->
            val detail = state.detail
            if (detail?.app?.packageName != packageName) {
                state
            } else {
                val app = detail.app.copy(
                    installedVersionCode = change.installedVersionCode ?: 0L,
                    installedVersionName = change.installedVersionName,
                )
                state.copy(
                    detail = detail.copy(app = app),
                    actionKind = app.actionKind(),
                )
            }
        }
    }

    fun install(app: MarketAppInfo) {
        if (app.isReservation() || app.isDownloadBlocked()) return
        if (!pendingDownloads.add(app.packageName)) return
        viewModelScope.launch {
            try {
                runCatchingCancellable { downloadMeta(app, app.actionKind() == AppActionKind.UPDATE) }
                    .onSuccess { downloads.start(it) }
                    .onFailure { uiPlatform.showToast(it.message ?: "Download failed") }
            } finally {
                pendingDownloads.remove(app.packageName)
            }
        }
    }

    fun redownload(app: MarketAppInfo) {
        if (app.isDownloadBlocked()) return
        if (!pendingDownloads.add(app.packageName)) return
        viewModelScope.launch {
            try {
                runCatchingCancellable { downloadMeta(app, update = false) }
                    .onSuccess { downloads.start(it, installAfterDownload = false) }
                    .onFailure { uiPlatform.showToast(it.message ?: "Download failed") }
            } finally {
                pendingDownloads.remove(app.packageName)
            }
        }
    }

    private suspend fun downloadMeta(app: MarketAppInfo, update: Boolean) =
        if (update) sources.downloadUpdateMeta(app.source, app)
        else sources.downloadMeta(app.source, app, app.displayName)

    fun openApp(app: MarketAppInfo) {
        // OPPO/三星/华为的 openLink 只是商店深链回退，不是已安装应用的启动目标
        val opened = if (app.source.capabilities.prefersOpenLinkLaunch) {
            packages.openLink(app.openLink) || packages.openApp(app.packageName)
        } else {
            packages.openApp(app.packageName) || packages.openLink(app.openLink)
        }
        if (!opened) {
            uiPlatform.showToast(app.packageName)
        }
    }

    fun installDownloaded(packageName: String) = downloads.install(packageName)
    fun cancelDownload(packageName: String) = downloads.cancel(packageName)
    fun clearDownload(packageName: String) = downloads.clear(packageName)
}

internal suspend fun loadRequestedDetail(
    market: MarketRepository,
    appId: Long,
    packageName: String,
    externalQuery: String?,
): AppDetail {
    val detail = market.appDetail(appId.coerceAtLeast(0L), packageName, externalQuery)
    if (detail.app.appId <= 0L || detail.app.packageName.isBlank()) {
        error(APP_NOT_LISTED_MESSAGE)
    }
    return detail
}

internal suspend fun loadOptionalDetailSections(
    market: MarketRepository,
    detail: AppDetail,
    loadComments: Boolean,
    loadSameDeveloper: Boolean,
    onCommentsLoaded: (AppComments) -> Unit,
    onSameDeveloperLoaded: (List<MarketAppInfo>) -> Unit,
) = supervisorScope {
    if (loadComments) {
        launch {
            loadOptionalSection(
                request = { market.appComments(detail.app.appId, detail.app.versionCode) },
                onLoaded = onCommentsLoaded,
            )
        }
    }
    if (loadSameDeveloper) {
        launch {
            loadOptionalSection(
                request = { market.sameDeveloperApps(detail.app.appId) },
                onLoaded = onSameDeveloperLoaded,
            )
        }
    }
}

private suspend fun <T> loadOptionalSection(
    request: suspend () -> T,
    onLoaded: (T) -> Unit,
) {
    try {
        onLoaded(request())
    } catch (error: CancellationException) {
        throw error
    } catch (_: Throwable) {
        // Optional sections must never hold back or replace the basic detail content.
    }
}
