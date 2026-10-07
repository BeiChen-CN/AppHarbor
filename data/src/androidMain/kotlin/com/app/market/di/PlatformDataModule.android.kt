package com.app.market.di

import android.content.Context
import com.app.market.data.download.PlatformDownloadDataSource
import com.app.market.data.remote.fdroid.FdroidIndexCache
import com.app.market.data.remote.fdroid.JvmFdroidIndexCache
import com.app.market.data.install.AndroidInstallRepositoryImpl
import com.app.market.data.install.DeltaFallbackBus
import com.app.market.data.install.PackageStagingDownloader
import com.app.market.data.install.RemotePackageDownloader
import com.app.market.data.install.ThirdPartyInstallEngine
import com.app.market.data.install.backend.InstallerBackendSelector
import com.app.market.data.install.backend.PrivilegedPackageInstallerFactory
import com.app.market.data.install.backend.RootInstallerBackend
import com.app.market.data.install.backend.ShizukuInstallerBackend
import com.app.market.data.install.backend.StandardInstallerBackend
import com.app.market.data.install.backend.root.RootBinderBridge
import com.app.market.data.install.network.ArtifactSourceReader
import com.app.market.data.install.network.InstallHttpClient
import com.app.market.data.install.platform.InstallResultHandler
import com.app.market.data.install.platform.InstallTaskProcessor
import com.app.market.data.install.platform.PackageChangeHandler
import com.app.market.data.install.platform.SavedPackageInstallLauncher
import com.app.market.data.install.storage.ArtifactStagingStore
import com.app.market.data.install.storage.MediaStorePackageStore
import com.app.market.data.install.storage.SavedPackageIndex
import com.app.market.data.install.task.InstallTaskStore
import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.PreferencesDataSourceImpl
import com.app.market.data.platform.AndroidThemePlatformPreferences
import com.app.market.data.platform.ThemePlatformPreferences
import com.app.market.data.platform.createAndroidHttpClient
import com.app.market.data.remote.xiaomi.platform.AndroidDeviceDefaultsDataSource
import com.app.market.data.remote.xiaomi.platform.AndroidInstalledApkHashRepositoryImpl
import com.app.market.data.remote.xiaomi.platform.AndroidInstalledPackagesRepositoryImpl
import com.app.market.data.remote.xiaomi.platform.AndroidXiaomiDeviceIdentityDataSource
import com.app.market.data.remote.xiaomi.platform.DeviceDefaultsDataSource
import com.app.market.data.remote.xiaomi.platform.XiaomiDeviceIdentityDataSource
import com.app.market.data.repository.AndroidDownloadDataSource
import com.app.market.data.repository.AndroidInstallerDiscoveryRepositoryImpl
import com.app.market.data.repository.InstallerPreferencesRepositoryImpl
import com.app.market.data.repository.PackageRepositoryImpl
import com.app.market.data.repository.SavedPackageRepositoryImpl
import com.app.market.domain.repository.InstallRepository
import com.app.market.domain.repository.InstalledApkHashRepository
import com.app.market.domain.repository.InstalledPackagesRepository
import com.app.market.domain.repository.InstallerDiscoveryRepository
import com.app.market.domain.repository.InstallerPreferencesRepository
import com.app.market.domain.repository.PackageRepository
import com.app.market.domain.repository.SavedPackageRepository
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import java.io.File

internal actual val platformDataModule: Module = module {
    single { createAndroidHttpClient() }
    single<FdroidIndexCache> { JvmFdroidIndexCache(File(get<Context>().cacheDir, "fdroid"), get(), get()) }
    singleOf(::PreferencesDataSourceImpl) { bind<PreferencesDataSource>() }
    singleOf(::AndroidThemePlatformPreferences) { bind<ThemePlatformPreferences>() }
    singleOf(::AndroidDeviceDefaultsDataSource) { bind<DeviceDefaultsDataSource>() }
    singleOf(::AndroidXiaomiDeviceIdentityDataSource) { bind<XiaomiDeviceIdentityDataSource>() }
    singleOf(::AndroidInstalledPackagesRepositoryImpl) { bind<InstalledPackagesRepository>() }
    singleOf(::AndroidInstalledApkHashRepositoryImpl) { bind<InstalledApkHashRepository>() }
    singleOf(::PackageRepositoryImpl) {
        bind<PackageRepository>()
        bind<PackageChangeHandler>()
    }
    singleOf(::InstallerPreferencesRepositoryImpl) { bind<InstallerPreferencesRepository>() }
    singleOf(::AndroidInstallerDiscoveryRepositoryImpl) { bind<InstallerDiscoveryRepository>() }

    singleOf(::InstallTaskStore)
    singleOf(::SavedPackageIndex)
    singleOf(::MediaStorePackageStore)
    singleOf(::ArtifactStagingStore)
    singleOf(::InstallHttpClient)
    singleOf(::DeltaFallbackBus)
    singleOf(::ArtifactSourceReader)
    singleOf(::PackageStagingDownloader)
    singleOf(::PrivilegedPackageInstallerFactory)
    singleOf(::RootBinderBridge)
    singleOf(::StandardInstallerBackend)
    singleOf(::RootInstallerBackend)
    singleOf(::ShizukuInstallerBackend)
    singleOf(::InstallerBackendSelector)
    singleOf(::ThirdPartyInstallEngine)
    singleOf(::AndroidInstallRepositoryImpl) { bind<InstallRepository>() }
    singleOf(::RemotePackageDownloader)
    singleOf(::SavedPackageRepositoryImpl) { bind<SavedPackageRepository>() }
    singleOf(::AndroidDownloadDataSource) {
        bind<PlatformDownloadDataSource>()
        bind<InstallTaskProcessor>()
        bind<InstallResultHandler>()
        bind<SavedPackageInstallLauncher>()
    }
}
