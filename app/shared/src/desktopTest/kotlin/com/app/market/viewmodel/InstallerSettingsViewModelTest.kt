package com.app.market.viewmodel

import com.app.market.domain.model.installer.InstallerMode
import com.app.market.domain.repository.InstallerDiscoveryRepository
import com.app.market.domain.repository.InstallerPreferencesRepository
import com.app.market.platform.ImageSaveResult
import com.app.market.platform.UiPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InstallerSettingsViewModelTest {
    @Test
    fun deletionSettingPersistsIndependentlyOfSaveToDownloads() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val preferences = CleanupPreferences()
            val discovery = object : InstallerDiscoveryRepository {
                override suspend fun listCandidates() = emptyList<com.app.market.domain.model.installer.InstallerCandidate>()
            }
            val viewModel = InstallerSettingsViewModel(preferences, discovery, InstallerTestPlatform)
            advanceUntilIdle()
            assertFalse(viewModel.uiState.value.deleteAfterUpdate)
            assertTrue(viewModel.uiState.value.saveToDownloads)
            viewModel.setDeleteAfterUpdate(true)
            advanceUntilIdle()
            assertTrue(preferences.deleteAfterUpdate())
            assertTrue(preferences.saveToDownloads())
            val restored = InstallerSettingsViewModel(preferences, discovery, InstallerTestPlatform)
            advanceUntilIdle()
            assertTrue(restored.uiState.value.deleteAfterUpdate)
            restored.setSaveToDownloads(false)
            advanceUntilIdle()
            assertTrue(restored.uiState.value.deleteAfterUpdate)
            assertTrue(preferences.deleteAfterUpdate())
            assertFalse(preferences.saveToDownloads())
            restored.setDeleteAfterUpdate(false)
            advanceUntilIdle()
            assertFalse(preferences.deleteAfterUpdate())
            assertFalse(preferences.saveToDownloads())
        } finally { Dispatchers.resetMain() }
    }
}

private class CleanupPreferences : InstallerPreferencesRepository {
    private var save = true
    private var delete = false
    override suspend fun mode() = InstallerMode.STANDARD
    override suspend fun setMode(mode: InstallerMode) = Unit
    override suspend fun thirdPartyInstallerPackage() = ""
    override suspend fun setThirdPartyInstallerPackage(packageName: String) = Unit
    override suspend fun saveToDownloads() = save
    override suspend fun setSaveToDownloads(enabled: Boolean) { save = enabled }
    override suspend fun deleteAfterUpdate() = delete
    override suspend fun setDeleteAfterUpdate(enabled: Boolean) { delete = enabled }
    override fun userActionNotRequiredConfigurable() = false
    override suspend fun userActionNotRequiredEnabled() = false
    override suspend fun setUserActionNotRequiredEnabled(enabled: Boolean) = Unit
    override fun deltaUpdateSupported() = false
    override suspend fun deltaUpdateEnabled() = false
    override suspend fun setDeltaUpdateEnabled(enabled: Boolean) = Unit
    override suspend fun deltaFallbackNoticeEnabled() = false
    override suspend fun setDeltaFallbackNoticeEnabled(enabled: Boolean) = Unit
    override fun focusNotificationSupported() = false
    override fun xiaomiIslandSupported() = false
    override suspend fun xiaomiIslandOptimizationEnabled() = false
    override suspend fun setXiaomiIslandOptimizationEnabled(enabled: Boolean) = Unit
}

private object InstallerTestPlatform : UiPlatform {
    override val packageInstallationSupported = true
    override fun showToast(message: String) = Unit
    override fun openAppSettings() = false
    override fun openUnknownSourcesSettings() = false
    override fun requestInstalledAppsPermission(onResult: (Boolean) -> Unit) = false
    override fun requestPostNotificationsPermission(onResult: () -> Unit) = false
    override suspend fun saveImageToPictures(url: String, fileName: String) = ImageSaveResult.Unsupported
}
