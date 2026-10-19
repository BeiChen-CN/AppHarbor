package com.app.market.viewmodel

import com.app.market.domain.model.download.DownloadState
import com.app.market.domain.model.installed.PackageChange
import com.app.market.domain.model.market.*
import com.app.market.domain.repository.*
import com.app.market.platform.ImageSaveResult
import com.app.market.platform.UiPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import java.lang.reflect.Proxy
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class CommentSourceViewModelTest {
    @Test
    fun commentSourceIsIndependentAndOldRequestsCannotOverwriteNewComments() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val prefs = TestFeaturePreferences()
            prefs.setCommentSource(AppSource.XIAOMI)
            val calls = mutableListOf<AppSource>()
            val barrier = CompletableDeferred<Unit>()
            val app = MarketAppInfo(44, "com.example.app", "Example", "", "1", 1, "", 0,
                ratingScore = 4.0, source = AppSource.TENCENT)
            val repository = object : MarketSourceRepository by unusedRepository() {
                override suspend fun appDetail(source: AppSource, appId: Long, packageName: String, externalQuery: String?) =
                    AppDetail(app, "", "", "", "", "", 0, "", privacyUrl = "", screenshots = emptyList(),
                        commentCount = 123, comments = emptyList(), sameDeveloperApps = emptyList())
                override suspend fun appComments(source: AppSource, app: MarketAppInfo): AppComments {
                    calls += source
                    if (source == AppSource.XIAOMI) barrier.await()
                    return AppComments(listOf(AppComment(source.token, "comment", 5.0)), 1)
                }
            }
            val enabled = MutableStateFlow(true)
            val updatePrefs = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(UpdatePreferencesRepository::class.java)) { _, method, args ->
                when (method.name) {
                    "getInitialized" -> MutableStateFlow(true)
                    "getShowAppComments" -> enabled
                    "getShowSameDeveloper", "getShowPromotions" -> MutableStateFlow(false)
                    "setShowAppComments" -> { enabled.value = args!![0] as Boolean; Unit }
                    else -> error("Unexpected ${method.name}")
                }
            } as UpdatePreferencesRepository
            val downloads = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(DownloadRepository::class.java)) { _, method, _ ->
                check(method.name == "getStates")
                MutableStateFlow<Map<String, DownloadState>>(emptyMap())
            } as DownloadRepository
            val vm = AppDetailViewModel(repository, CommentPackages, downloads, updatePrefs, CommentUi, prefs)
            vm.load(44, app.packageName, source = AppSource.TENCENT)
            runCurrent()
            assertTrue(vm.uiState.value.commentsLoading)
            vm.setCommentSource(AppSource.KUAIBAO)
            advanceUntilIdle()
            barrier.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(AppSource.XIAOMI, AppSource.KUAIBAO), calls)
            assertEquals("kuaibao", vm.uiState.value.comments.items.single().userName)
            assertEquals(AppSource.TENCENT, vm.uiState.value.detail!!.app.source)
            assertFalse(vm.uiState.value.commentsLoading)
            assertEquals(123L, vm.uiState.value.detail!!.commentCount)
            assertEquals(1L, vm.uiState.value.comments.totalCount)
            enabled.value = false
            advanceUntilIdle()
            assertTrue(vm.uiState.value.comments.items.isEmpty())
            assertEquals(123L, vm.uiState.value.detail!!.commentCount)
            assertEquals(4.0, vm.uiState.value.detail!!.app.ratingScore)
        } finally { Dispatchers.resetMain() }
    }

    private fun unusedRepository(): MarketSourceRepository = Proxy.newProxyInstance(javaClass.classLoader,
        arrayOf(MarketSourceRepository::class.java)) { _, method, _ -> error("Unexpected ${method.name}") } as MarketSourceRepository
}

private object CommentPackages : PackageRepository {
    override val selfPackageName = "com.app.market"
    override val changes = MutableSharedFlow<PackageChange>()
    override suspend fun installedVersionCodes(packageNames: Collection<String>) = emptyMap<String, Long>()
    override suspend fun installedVersionName(packageName: String): String? = null
    override suspend fun freshInstalledVersionCode(packageName: String): Long? = null
    override fun openApp(packageName: String) = false
    override fun openLink(link: String) = false
}

private object CommentUi : UiPlatform {
    override val packageInstallationSupported = false
    override fun showToast(message: String) = Unit
    override fun openAppSettings() = false
    override fun openUnknownSourcesSettings() = false
    override fun requestInstalledAppsPermission(onResult: (Boolean) -> Unit) = false
    override fun requestPostNotificationsPermission(onResult: () -> Unit) = false
    override suspend fun saveImageToPictures(url: String, fileName: String) = ImageSaveResult.Unsupported
}
