package com.app.market.viewmodel

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadState
import com.app.market.domain.model.download.DownloadTaskKey
import com.app.market.domain.model.install.DeltaFallback
import com.app.market.domain.model.install.InstallUserAction
import com.app.market.domain.model.update.AppUpdateInfo
import com.app.market.domain.repository.AppUpdateRepository
import com.app.market.domain.repository.DownloadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateViewModelTest {
    @Test
    fun newerReleaseOpensDialogAndStartsSelfUpdateDownload() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val downloads = RecordingAppUpdateDownloads()
            val info = AppUpdateInfo(
                versionName = "9.9.9",
                versionCode = 999L,
                changelog = "New things",
                releaseUrl = "https://example.test/release",
                assetName = "Trove.apk",
                downloadUrl = "https://example.test/Trove.apk",
                size = 123L,
            )
            val viewModel = AppUpdateViewModel(AppUpdateRepository { info }, downloads)
            viewModel.check()
            advanceUntilIdle()
            assertTrue(viewModel.uiState.value.dialogVisible)
            assertEquals(info, viewModel.uiState.value.update)

            viewModel.startUpdate()
            advanceUntilIdle()
            assertEquals("com.app.market", downloads.started?.packageName)
            assertEquals(999L, downloads.started?.versionCode)
            assertFalse(viewModel.uiState.value.dialogVisible)
            assertTrue(viewModel.uiState.value.started)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun currentReleaseIsReportedAsUpToDateAndErrorsKeepDialog() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val upToDate = AppUpdateViewModel(AppUpdateRepository {
                AppUpdateInfo("2.13.1", 241, "", "", "Trove.apk", "https://example.test/Trove.apk", 1)
            }, RecordingAppUpdateDownloads())
            upToDate.check()
            advanceUntilIdle()
            assertTrue(upToDate.uiState.value.upToDate)
            assertEquals(null, upToDate.uiState.value.update)

            val failed = AppUpdateViewModel(AppUpdateRepository { error("network") }, RecordingAppUpdateDownloads())
            failed.check()
            advanceUntilIdle()
            assertTrue(failed.uiState.value.dialogVisible)
            assertTrue(failed.uiState.value.error.isNotBlank())
        } finally {
            Dispatchers.resetMain()
        }
    }
}

private class RecordingAppUpdateDownloads : DownloadRepository {
    var started: DownloadMeta? = null
    override val states: StateFlow<Map<String, DownloadState>> = MutableStateFlow(emptyMap())
    override val taskStates: StateFlow<Map<DownloadTaskKey, DownloadState>> = MutableStateFlow(emptyMap())
    override val installedPackages: SharedFlow<String> = MutableSharedFlow()
    override val deltaFallbacks: SharedFlow<DeltaFallback> = MutableSharedFlow()
    override val pendingUserAction: StateFlow<InstallUserAction?> = MutableStateFlow(null)
    override fun start(meta: DownloadMeta, installAfterDownload: Boolean) { started = meta }
    override fun install(packageName: String) = Unit
    override fun cancel(packageName: String) = Unit
    override fun cancel(packageName: String, versionCode: Long) = Unit
    override fun clear(packageName: String) = Unit
    override fun consumePendingUserAction() = Unit
}
