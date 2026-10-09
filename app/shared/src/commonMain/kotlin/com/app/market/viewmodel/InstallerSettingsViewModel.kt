package com.app.market.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.app.market.domain.model.installer.InstallerCandidate
import com.app.market.domain.model.installer.InstallerMode
import com.app.market.domain.repository.InstallerDiscoveryRepository
import com.app.market.domain.repository.InstallerPreferencesRepository
import com.app.market.platform.UiPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class InstallerSettingsUiState(
    val mode: InstallerMode = InstallerMode.STANDARD,
    val saveToDownloads: Boolean = false,
    val deleteAfterUpdate: Boolean = false,
    val userActionNotRequiredConfigurable: Boolean = false,
    val userActionNotRequiredEnabled: Boolean = false,
    val deltaUpdateSupported: Boolean = false,
    val deltaUpdateEnabled: Boolean = false,
    val deltaFallbackNoticeEnabled: Boolean = true,
    val focusNotificationSupported: Boolean = false,
    val xiaomiIslandSupported: Boolean = false,
    val xiaomiIslandOptimizationEnabled: Boolean = false,
    val installationSupported: Boolean = false,
    val installerCandidates: List<InstallerCandidate> = emptyList(),
    val thirdPartyInstallerPackage: String = "",
    val showInstallerPicker: Boolean = false,
)

class InstallerSettingsViewModel(
    private val controller: InstallerPreferencesRepository,
    private val discovery: InstallerDiscoveryRepository,
    platform: UiPlatform,
) : ViewModel() {

    private val installationSupported = platform.packageInstallationSupported

    private val _uiState = MutableStateFlow(
        InstallerSettingsUiState(installationSupported = installationSupported)
    )
    val uiState: StateFlow<InstallerSettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // 偏好读取链路上任何一环抛错都不应让设置页崩溃：失败即保留默认值
            runCatchingCancellable { loadInitialState() }
        }
    }

    private suspend fun loadInitialState() {
        val candidates = if (installationSupported) {
            runCatchingCancellable { discovery.listCandidates() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val persistedPackage = controller.thirdPartyInstallerPackage()
        val selectedPackage = persistedPackage.takeIf { packageName ->
            candidates.any { it.packageName == packageName }
        }.orEmpty()
        val persistedMode = controller.mode()
        val mode = if (persistedMode == InstallerMode.THIRD_PARTY && selectedPackage.isBlank()) {
            controller.setMode(InstallerMode.STANDARD)
            InstallerMode.STANDARD
        } else {
            persistedMode
        }
        if (persistedPackage.isNotBlank() && selectedPackage.isBlank()) {
            controller.setThirdPartyInstallerPackage("")
        }
        _uiState.value = InstallerSettingsUiState(
            mode = mode,
            saveToDownloads = controller.saveToDownloads(),
            deleteAfterUpdate = controller.deleteAfterUpdate(),
            userActionNotRequiredConfigurable = controller.userActionNotRequiredConfigurable(),
            userActionNotRequiredEnabled = controller.userActionNotRequiredEnabled(),
            deltaUpdateSupported = controller.deltaUpdateSupported(),
            deltaUpdateEnabled = controller.deltaUpdateEnabled(),
            deltaFallbackNoticeEnabled = controller.deltaFallbackNoticeEnabled(),
            focusNotificationSupported = controller.focusNotificationSupported(),
            xiaomiIslandSupported = controller.xiaomiIslandSupported(),
            xiaomiIslandOptimizationEnabled = controller.xiaomiIslandOptimizationEnabled(),
            installationSupported = installationSupported,
            installerCandidates = candidates,
            thirdPartyInstallerPackage = selectedPackage,
        )
    }

    fun setMode(mode: InstallerMode) {
        if (mode == InstallerMode.THIRD_PARTY) {
            showThirdPartyInstallerPicker()
            return
        }
        _uiState.update { it.copy(mode = mode) }
        viewModelScope.launch { controller.setMode(mode) }
    }

    fun showThirdPartyInstallerPicker() {
        if (!installationSupported) return
        _uiState.update { it.copy(showInstallerPicker = true) }
        viewModelScope.launch {
            val candidates = runCatchingCancellable { discovery.listCandidates() }.getOrDefault(emptyList())
            val selectedPackage = _uiState.value.thirdPartyInstallerPackage.takeIf { packageName ->
                candidates.any { it.packageName == packageName }
            }.orEmpty()
            if (_uiState.value.mode == InstallerMode.THIRD_PARTY && selectedPackage.isBlank()) {
                controller.setMode(InstallerMode.STANDARD)
            }
            if (_uiState.value.thirdPartyInstallerPackage.isNotBlank() && selectedPackage.isBlank()) {
                controller.setThirdPartyInstallerPackage("")
            }
            _uiState.update {
                it.copy(
                    mode = if (it.mode == InstallerMode.THIRD_PARTY && selectedPackage.isBlank()) {
                        InstallerMode.STANDARD
                    } else {
                        it.mode
                    },
                    installerCandidates = candidates,
                    thirdPartyInstallerPackage = selectedPackage,
                )
            }
        }
    }

    fun dismissThirdPartyInstallerPicker() {
        _uiState.update { it.copy(showInstallerPicker = false) }
    }

    fun selectThirdPartyInstaller(candidate: InstallerCandidate) {
        if (_uiState.value.installerCandidates.none { it.packageName == candidate.packageName }) return
        _uiState.update {
            it.copy(
                mode = InstallerMode.THIRD_PARTY,
                thirdPartyInstallerPackage = candidate.packageName,
                showInstallerPicker = false,
            )
        }
        viewModelScope.launch {
            controller.setThirdPartyInstallerPackage(candidate.packageName)
            controller.setMode(InstallerMode.THIRD_PARTY)
        }
    }

    fun setDeleteAfterUpdate(enabled: Boolean) {
        _uiState.update { it.copy(deleteAfterUpdate = enabled) }
        viewModelScope.launch { controller.setDeleteAfterUpdate(enabled) }
    }

    fun setSaveToDownloads(enabled: Boolean) {
        _uiState.update { it.copy(saveToDownloads = enabled) }
        viewModelScope.launch { controller.setSaveToDownloads(enabled) }
    }

    fun setUserActionNotRequiredEnabled(enabled: Boolean) {
        val state = _uiState.value
        if (state.mode != InstallerMode.STANDARD || !state.userActionNotRequiredConfigurable) return
        _uiState.update { it.copy(userActionNotRequiredEnabled = enabled) }
        viewModelScope.launch { controller.setUserActionNotRequiredEnabled(enabled) }
    }

    fun setDeltaFallbackNoticeEnabled(enabled: Boolean) {
        _uiState.update { it.copy(deltaFallbackNoticeEnabled = enabled) }
        viewModelScope.launch { controller.setDeltaFallbackNoticeEnabled(enabled) }
    }

    fun setDeltaUpdateEnabled(enabled: Boolean) {
        if (!_uiState.value.deltaUpdateSupported) return
        _uiState.update { it.copy(deltaUpdateEnabled = enabled) }
        viewModelScope.launch { controller.setDeltaUpdateEnabled(enabled) }
    }

    fun setXiaomiIslandOptimizationEnabled(enabled: Boolean) {
        _uiState.update { it.copy(xiaomiIslandOptimizationEnabled = enabled) }
        viewModelScope.launch { controller.setXiaomiIslandOptimizationEnabled(enabled) }
    }
}
