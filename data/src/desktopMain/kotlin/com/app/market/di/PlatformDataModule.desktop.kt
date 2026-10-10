package com.app.market.di

import com.app.market.data.download.PlatformDownloadDataSource
import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.PreferencesDataSourceImpl
import com.app.market.data.platform.DesktopThemePlatformPreferences
import com.app.market.data.platform.ThemePlatformPreferences
import com.app.market.data.platform.createHttpClient
import com.app.market.data.remote.fdroid.FdroidIndexCache
import com.app.market.data.remote.fdroid.JvmFdroidIndexCache
import com.app.market.data.remote.xiaomi.platform.DesktopDeviceDefaultsDataSource
import com.app.market.data.remote.xiaomi.platform.DesktopXiaomiDeviceIdentityDataSource
import com.app.market.data.remote.xiaomi.platform.DeviceDefaultsDataSource
import com.app.market.data.remote.xiaomi.platform.XiaomiDeviceIdentityDataSource
import com.app.market.data.repository.DesktopDownloadDataSource
import com.app.market.data.repository.DesktopInstalledApkHashRepositoryImpl
import com.app.market.data.repository.DesktopInstalledPackagesRepositoryImpl
import com.app.market.data.repository.DesktopInstallerDiscoveryRepositoryImpl
import com.app.market.data.repository.DesktopInstallerPreferencesRepositoryImpl
import com.app.market.data.repository.DesktopPackageDownloader
import com.app.market.data.repository.DesktopPackageRepositoryImpl
import com.app.market.data.repository.DesktopSavedPackageRepositoryImpl
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
import org.koin.core.qualifier.named
import com.app.market.data.remote.fdroid.IzzyApiConfig
import com.app.market.data.remote.releases.JvmReleaseApkInspector
import com.app.market.data.remote.releases.ReleaseApkInspector
import java.io.File

internal actual val platformDataModule: Module = module {
    single<ReleaseApkInspector> { JvmReleaseApkInspector(get()) }
    single<FdroidIndexCache>(named("izzy")) { JvmFdroidIndexCache(File(File(System.getProperty("user.home"), ".app-market"), "cache/izzyondroid"), get(), IzzyApiConfig) }
    single { createHttpClient() }
    // 与桌面端偏好设置同一根目录（~/.app-market），缓存目录由系统/用户清理策略管理
    single<FdroidIndexCache> {
        JvmFdroidIndexCache(File(File(System.getProperty("user.home"), ".app-market"), "cache/fdroid"), get(), get())
    }
    singleOf(::PreferencesDataSourceImpl) { bind<PreferencesDataSource>() }
    singleOf(::DesktopThemePlatformPreferences) { bind<ThemePlatformPreferences>() }
    singleOf(::DesktopDeviceDefaultsDataSource) { bind<DeviceDefaultsDataSource>() }
    singleOf(::DesktopXiaomiDeviceIdentityDataSource) { bind<XiaomiDeviceIdentityDataSource>() }
    singleOf(::DesktopInstalledPackagesRepositoryImpl) { bind<InstalledPackagesRepository>() }
    singleOf(::DesktopInstalledApkHashRepositoryImpl) { bind<InstalledApkHashRepository>() }
    singleOf(::DesktopPackageRepositoryImpl) { bind<PackageRepository>() }
    singleOf(::DesktopSavedPackageRepositoryImpl) { bind<SavedPackageRepository>() }
    singleOf(::DesktopInstallerPreferencesRepositoryImpl) { bind<InstallerPreferencesRepository>() }
    singleOf(::DesktopInstallerDiscoveryRepositoryImpl) { bind<InstallerDiscoveryRepository>() }
    single { DesktopPackageDownloader(get()) }
    singleOf(::DesktopDownloadDataSource) { bind<PlatformDownloadDataSource>() }
}
