package com.app.market.data.repository

import com.app.market.domain.model.installed.PackageChange
import com.app.market.domain.model.installer.InstallerCandidate
import com.app.market.domain.model.installer.InstallerMode
import com.app.market.domain.model.installer.SavedPackage
import com.app.market.domain.repository.InstalledApkHashRepository
import com.app.market.domain.repository.InstalledPackagesRepository
import com.app.market.domain.repository.InstallerDiscoveryRepository
import com.app.market.domain.repository.InstallerPreferencesRepository
import com.app.market.domain.repository.PackageRepository
import com.app.market.domain.repository.SavedPackageRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.awt.Desktop
import java.net.URI

class DesktopPackageRepositoryImpl : PackageRepository {
    override val selfPackageName: String = "com.app.market"
    override val changes: SharedFlow<PackageChange> = MutableSharedFlow()
    override suspend fun installedVersionCodes(packageNames: Collection<String>): Map<String, Long> = emptyMap()
    override suspend fun installedVersionName(packageName: String): String? = null
    override suspend fun freshInstalledVersionCode(packageName: String): Long? = null
    override fun openApp(packageName: String): Boolean = false
    override fun openLink(link: String): Boolean {
        if (link.isBlank()) return false
        return runCatching { openInBrowser(link) }.isSuccess
    }
}

class DesktopInstallerPreferencesRepositoryImpl : InstallerPreferencesRepository {
    override suspend fun mode(): InstallerMode = InstallerMode.STANDARD
    override suspend fun setMode(mode: InstallerMode) {}
    override suspend fun thirdPartyInstallerPackage(): String = ""
    override suspend fun setThirdPartyInstallerPackage(packageName: String) {}
    override suspend fun saveToDownloads(): Boolean = true
    override suspend fun setSaveToDownloads(enabled: Boolean) {}
    override suspend fun deleteAfterUpdate(): Boolean = false
    override suspend fun setDeleteAfterUpdate(enabled: Boolean) = Unit
    override fun userActionNotRequiredConfigurable(): Boolean = false
    override suspend fun userActionNotRequiredEnabled(): Boolean = false
    override suspend fun setUserActionNotRequiredEnabled(enabled: Boolean) {}
    override fun deltaUpdateSupported(): Boolean = false
    override suspend fun deltaUpdateEnabled(): Boolean = false
    override suspend fun setDeltaUpdateEnabled(enabled: Boolean) {}
    override suspend fun deltaFallbackNoticeEnabled(): Boolean = false
    override suspend fun setDeltaFallbackNoticeEnabled(enabled: Boolean) {}
    override fun focusNotificationSupported(): Boolean = false
    override fun xiaomiIslandSupported(): Boolean = false
    override suspend fun xiaomiIslandOptimizationEnabled(): Boolean = false
    override suspend fun setXiaomiIslandOptimizationEnabled(enabled: Boolean) {}
}

class DesktopInstallerDiscoveryRepositoryImpl : InstallerDiscoveryRepository {
    override suspend fun listCandidates(): List<InstallerCandidate> = emptyList()
}

class DesktopSavedPackageRepositoryImpl : SavedPackageRepository {
    override suspend fun list(): List<SavedPackage> = emptyList()
    override suspend fun install(id: String) {}
    override suspend fun delete(id: String) {}
}

class DesktopInstalledPackagesRepositoryImpl : InstalledPackagesRepository {
    override suspend fun installed() = emptyList<com.app.market.domain.model.installed.InstalledPackage>()
}

class DesktopInstalledApkHashRepositoryImpl : InstalledApkHashRepository {
    override suspend fun tapTap(path: String): String = com.app.market.data.platform.tapTapApkHash(path)

    override suspend fun md5(path: String): String = "0"
}

private fun openInBrowser(url: String) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI(url))
    }
}
