package com.app.market.di

import com.app.market.viewmodel.AppDetailViewModel
import com.app.market.viewmodel.DeviceProfileViewModel
import com.app.market.viewmodel.DownloadingAppsViewModel
import com.app.market.viewmodel.HistoricalVersionsViewModel
import com.app.market.viewmodel.IgnoredAppsViewModel
import com.app.market.viewmodel.InstallerSettingsViewModel
import com.app.market.viewmodel.ManualUpdateViewModel
import com.app.market.viewmodel.SavedPackagesViewModel
import com.app.market.viewmodel.SearchViewModel
import com.app.market.viewmodel.ThemeSettingsViewModel
import com.app.market.viewmodel.TodayViewModel
import com.app.market.viewmodel.UpdateHistoryViewModel
import com.app.market.viewmodel.UpdatesViewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module
import com.app.market.viewmodel.DiscoveryViewModel

val viewModelModule = module {
    viewModelOf(::DiscoveryViewModel)
    viewModelOf(::UpdatesViewModel)
    viewModelOf(::ThemeSettingsViewModel)
    viewModelOf(::UpdateHistoryViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::SavedPackagesViewModel)
    viewModelOf(::DownloadingAppsViewModel)
    viewModelOf(::ManualUpdateViewModel)
    viewModelOf(::InstallerSettingsViewModel)
    viewModelOf(::IgnoredAppsViewModel)
    viewModelOf(::HistoricalVersionsViewModel)
    viewModelOf(::DeviceProfileViewModel)
    viewModelOf(::AppDetailViewModel)
    viewModelOf(::TodayViewModel)
}
