package com.app.market.di

import com.app.market.domain.repository.AccountRepository
import com.app.market.domain.repository.TencentRepository
import com.app.market.domain.repository.CoolapkRepository
import com.app.market.domain.repository.DownloadRepository
import com.app.market.domain.repository.InstalledApkHashRepository
import com.app.market.domain.repository.InstalledPackagesRepository
import com.app.market.domain.repository.InstallerDiscoveryRepository
import com.app.market.domain.repository.InstallerPreferencesRepository
import com.app.market.domain.repository.MarketRepository
import com.app.market.domain.repository.MarketSourceRepository
import com.app.market.domain.repository.OppoRepository
import com.app.market.domain.repository.PackageRepository
import com.app.market.domain.repository.ProfileRepository
import com.app.market.domain.repository.SavedPackageRepository
import com.app.market.domain.repository.SearchHistoryRepository
import com.app.market.domain.repository.ThemePreferencesRepository
import com.app.market.domain.repository.TodayRepository
import com.app.market.domain.repository.UpdateHistoryRepository
import com.app.market.domain.repository.UpdatePreferencesRepository
import com.app.market.domain.repository.VivoRepository
import com.app.market.domain.repository.WandoujiaRepository
import org.koin.core.KoinApplication
import kotlin.test.Test
import kotlin.test.assertNotNull

class DataModuleTest {
    @Test
    fun resolvesEveryDomainRepositoryBinding() {
        val application = KoinApplication.init().modules(dataModules)
        try {
            with(application.koin) {
                assertNotNull(get<AccountRepository>())
                assertNotNull(get<DownloadRepository>())
                assertNotNull(get<InstalledApkHashRepository>())
                assertNotNull(get<InstalledPackagesRepository>())
                assertNotNull(get<InstallerPreferencesRepository>())
                assertNotNull(get<InstallerDiscoveryRepository>())
                assertNotNull(get<MarketRepository>())
                assertNotNull(get<MarketSourceRepository>())
                assertNotNull(get<CoolapkRepository>())
                assertNotNull(get<TencentRepository>())
                assertNotNull(get<PackageRepository>())
                assertNotNull(get<ProfileRepository>())
                assertNotNull(get<SavedPackageRepository>())
                assertNotNull(get<SearchHistoryRepository>())
                assertNotNull(get<TodayRepository>())
                assertNotNull(get<ThemePreferencesRepository>())
                assertNotNull(get<UpdateHistoryRepository>())
                assertNotNull(get<UpdatePreferencesRepository>())
                assertNotNull(get<VivoRepository>())
                assertNotNull(get<WandoujiaRepository>())
                assertNotNull(get<OppoRepository>())
            }
        } finally {
            application.close()
        }
    }
}
