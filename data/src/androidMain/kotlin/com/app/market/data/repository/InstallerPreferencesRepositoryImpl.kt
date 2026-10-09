package com.app.market.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.app.market.data.install.HyperOsFocusCapability
import com.app.market.data.local.PreferenceChanges
import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.preferences.InstallerPreferenceKeys
import com.app.market.domain.model.installer.InstallerMode
import com.app.market.domain.repository.InstallerPreferencesRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class InstallerPreferencesRepositoryImpl(
    private val context: Context,
    private val preferences: PreferencesDataSource,
) : InstallerPreferencesRepository {
    private val migrationMutex = Mutex()

    override suspend fun mode(): InstallerMode =
        InstallerMode.fromKey(preferences.read(InstallerPreferenceKeys.Mode))

    override suspend fun setMode(mode: InstallerMode) {
        preferences.put(InstallerPreferenceKeys.Mode, mode.key)
    }

    override suspend fun thirdPartyInstallerPackage(): String =
        preferences.read(InstallerPreferenceKeys.ThirdPartyInstallerPackage).orEmpty()

    override suspend fun setThirdPartyInstallerPackage(packageName: String) {
        preferences.put(InstallerPreferenceKeys.ThirdPartyInstallerPackage, packageName)
    }

    override suspend fun saveToDownloads(): Boolean {
        migrateSavePreference()
        return preferences.read(InstallerPreferenceKeys.SaveToDownloads)
    }

    override suspend fun setSaveToDownloads(enabled: Boolean) {
        migrateSavePreference()
        preferences.put(InstallerPreferenceKeys.SaveToDownloads, enabled)
    }

    override suspend fun deleteAfterUpdate(): Boolean =
        preferences.read(InstallerPreferenceKeys.DeleteAfterUpdate)

    override suspend fun setDeleteAfterUpdate(enabled: Boolean) {
        preferences.put(InstallerPreferenceKeys.DeleteAfterUpdate, enabled)
    }

    override fun userActionNotRequiredConfigurable(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.applicationInfo.flags and SYSTEM_APP_FLAGS == 0

    override suspend fun userActionNotRequiredEnabled(): Boolean =
        preferences.read(InstallerPreferenceKeys.UserActionNotRequired)

    override suspend fun setUserActionNotRequiredEnabled(enabled: Boolean) {
        preferences.put(InstallerPreferenceKeys.UserActionNotRequired, enabled)
    }

    override fun deltaUpdateSupported(): Boolean = true

    override suspend fun deltaUpdateEnabled(): Boolean =
        deltaUpdateSupported() && preferences.read(InstallerPreferenceKeys.DeltaUpdate)

    override suspend fun setDeltaUpdateEnabled(enabled: Boolean) {
        preferences.put(InstallerPreferenceKeys.DeltaUpdate, enabled && deltaUpdateSupported())
    }

    override suspend fun deltaFallbackNoticeEnabled(): Boolean =
        preferences.read(InstallerPreferenceKeys.DeltaFallbackNotice)

    override suspend fun setDeltaFallbackNoticeEnabled(enabled: Boolean) {
        preferences.put(InstallerPreferenceKeys.DeltaFallbackNotice, enabled)
    }

    override fun focusNotificationSupported(): Boolean =
        HyperOsFocusCapability.supportsFocus(context)

    override fun xiaomiIslandSupported(): Boolean =
        HyperOsFocusCapability.isIslandMode(context)

    override suspend fun xiaomiIslandOptimizationEnabled(): Boolean =
        preferences.read(InstallerPreferenceKeys.XiaomiIslandOptimization)

    override suspend fun setXiaomiIslandOptimizationEnabled(enabled: Boolean) {
        preferences.put(InstallerPreferenceKeys.XiaomiIslandOptimization, enabled)
    }

    private suspend fun migrateSavePreference() = migrationMutex.withLock {
        if (preferences.read(InstallerPreferenceKeys.SaveToDownloadsMigrated)) return@withLock
        val legacyDeleteAfterInstall = preferences.read(InstallerPreferenceKeys.LegacyDeleteAfterInstall)
        preferences.update(
            InstallerPreferenceKeys.SaveToDownloads.namespace,
            PreferenceChanges(
                booleans = mapOf(
                    InstallerPreferenceKeys.SaveToDownloads to !legacyDeleteAfterInstall,
                    InstallerPreferenceKeys.SaveToDownloadsMigrated to true,
                    InstallerPreferenceKeys.LegacyDeleteAfterInstall to null,
                )
            ),
        )
    }

    private companion object {
        const val SYSTEM_APP_FLAGS = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
    }
}
