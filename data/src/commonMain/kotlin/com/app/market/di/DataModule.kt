package com.app.market.di

import com.app.market.data.download.RecordingDownloadRepositoryImpl
import com.app.market.data.platform.debugLog
import com.app.market.data.remote.fdroid.FdroidApi
import com.app.market.data.remote.tencent.TencentApi
import com.app.market.data.remote.tencent.TencentApiConfig
import com.app.market.data.repository.TencentRepositoryImpl
import com.app.market.domain.repository.TencentRepository
import com.app.market.data.remote.coolapk.CoolapkApi
import com.app.market.data.remote.coolapk.CoolapkApiConfig
import com.app.market.data.remote.coolapk.CoolapkSigner
import com.app.market.data.repository.CoolapkRepositoryImpl
import com.app.market.domain.repository.CoolapkRepository
import com.app.market.data.remote.fdroid.FdroidApiConfig
import com.app.market.data.remote.honor.HonorApi
import com.app.market.data.remote.honor.HonorProtocol
import com.app.market.data.remote.huawei.HuaweiApi
import com.app.market.data.remote.huawei.HuaweiProtocol
import com.app.market.data.remote.kuaibao.KuaibaoApi
import com.app.market.data.remote.kuaibao.KuaibaoApiConfig
import com.app.market.data.remote.oppo.OppoApi
import com.app.market.data.remote.samsung.SamsungApi
import com.app.market.data.remote.taptap.TapTapApi
import com.app.market.data.remote.taptap.TapTapApiConfig
import com.app.market.data.remote.vivo.VivoApi
import com.app.market.data.remote.vivo.VivoAuroraApi
import com.app.market.data.remote.vivo.VivoUpdateApi
import com.app.market.data.remote.wandoujia.WandoujiaApi
import com.app.market.data.remote.wandoujia.WandoujiaApiConfig
import com.app.market.data.remote.xiaomi.UpdateInfoCache
import com.app.market.data.remote.xiaomi.XiaomiApi
import com.app.market.data.remote.xiaomi.XiaomiClient
import com.app.market.data.remote.xiaomi.XiaomiHttpClient
import com.app.market.data.repository.AnonymousAccountRepositoryImpl
import com.app.market.data.repository.FdroidRepositoryImpl
import com.app.market.data.repository.HonorRepositoryImpl
import com.app.market.data.repository.HuaweiRepositoryImpl
import com.app.market.data.repository.KuaibaoRepositoryImpl
import com.app.market.data.repository.MarketRepositoryImpl
import com.app.market.data.repository.MarketSourceRepositoryImpl
import com.app.market.data.repository.OppoRepositoryImpl
import com.app.market.data.repository.ProfileRepositoryImpl
import com.app.market.data.repository.SamsungRepositoryImpl
import com.app.market.data.repository.TapTapRepositoryImpl
import com.app.market.data.repository.TodayRepositoryImpl
import com.app.market.data.repository.VivoRepositoryImpl
import com.app.market.data.repository.WandoujiaRepositoryImpl
import com.app.market.data.store.SearchHistoryRepositoryImpl
import com.app.market.data.store.ThemePreferencesRepositoryImpl
import com.app.market.data.store.UpdateHistoryRepositoryImpl
import com.app.market.data.store.UpdatePreferencesRepositoryImpl
import com.app.market.domain.repository.AccountRepository
import com.app.market.domain.repository.DownloadRepository
import com.app.market.domain.repository.FdroidRepository
import com.app.market.domain.repository.HonorRepository
import com.app.market.domain.repository.HuaweiRepository
import com.app.market.domain.repository.KuaibaoRepository
import com.app.market.domain.repository.MarketRepository
import com.app.market.domain.repository.MarketSourceRepository
import com.app.market.domain.repository.OppoRepository
import com.app.market.domain.repository.ProfileRepository
import com.app.market.domain.repository.SamsungRepository
import com.app.market.domain.repository.TapTapRepository
import com.app.market.domain.repository.SearchHistoryRepository
import com.app.market.domain.repository.ThemePreferencesRepository
import com.app.market.domain.repository.TodayRepository
import com.app.market.domain.repository.UpdateHistoryRepository
import com.app.market.domain.repository.UpdatePreferencesRepository
import com.app.market.domain.repository.VivoRepository
import com.app.market.domain.repository.WandoujiaRepository
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.core.module.dsl.bind
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.module
import org.koin.dsl.onClose
import org.koin.core.qualifier.named
import com.app.market.data.repository.ReleaseSourceRepository
import com.app.market.data.remote.releases.ReleaseApi
import com.app.market.data.remote.fdroid.IzzyApiConfig

private val commonDataModule = module {
    single<CoroutineScope> {
        // 数据层后台协程的最后防线：存储/IO 异常记录日志而不是崩溃整个进程
        val handler = CoroutineExceptionHandler { _, error ->
            debugLog("DataScope") { "Uncaught exception in data scope: ${error.stackTraceToString()}" }
        }
        CoroutineScope(SupervisorJob() + Dispatchers.Default + handler)
    }
    single { Json { ignoreUnknownKeys = true; isLenient = true } }

    singleOf(::UpdatePreferencesRepositoryImpl) { bind<UpdatePreferencesRepository>() }
    singleOf(::ThemePreferencesRepositoryImpl) { bind<ThemePreferencesRepository>() }
    singleOf(::SearchHistoryRepositoryImpl) { bind<SearchHistoryRepository>() }
    singleOf(::UpdateHistoryRepositoryImpl) { bind<UpdateHistoryRepository>() }

    singleOf(::AnonymousAccountRepositoryImpl) { bind<AccountRepository>() }
    singleOf(::UpdateInfoCache)
    singleOf(::XiaomiClient)
    singleOf(::XiaomiHttpClient)
    singleOf(::XiaomiApi)
    singleOf(::ProfileRepositoryImpl) { bind<ProfileRepository>() }
    singleOf(::MarketRepositoryImpl) { bind<MarketRepository>() }
    singleOf(::TodayRepositoryImpl) { bind<TodayRepository>() }
    singleOf(::VivoApi)
    singleOf(::VivoUpdateApi)
    singleOf(::VivoAuroraApi)
    singleOf(::VivoRepositoryImpl) { bind<VivoRepository>() }
    single { WandoujiaApiConfig() }
    singleOf(::WandoujiaApi)
    singleOf(::WandoujiaRepositoryImpl) { bind<WandoujiaRepository>() }
    singleOf(::OppoApi)
    singleOf(::OppoRepositoryImpl) { bind<OppoRepository>() }
    singleOf(::SamsungApi)
    singleOf(::SamsungRepositoryImpl) { bind<SamsungRepository>() }
    singleOf(::HonorProtocol)
    singleOf(::HonorApi)
    singleOf(::HonorRepositoryImpl) { bind<HonorRepository>() }
    singleOf(::HuaweiProtocol)
    singleOf(::HuaweiApi)
    singleOf(::HuaweiRepositoryImpl) { bind<HuaweiRepository>() }
    single { TapTapApiConfig() }
    singleOf(::TapTapApi)
    singleOf(::TapTapRepositoryImpl) { bind<TapTapRepository>() }
    single { KuaibaoApiConfig() }
    singleOf(::KuaibaoApi)
    singleOf(::KuaibaoRepositoryImpl) { bind<KuaibaoRepository>() }
    single { FdroidApiConfig() }
    singleOf(::FdroidApi)
    singleOf(::FdroidRepositoryImpl) { bind<FdroidRepository>() }
    single(named("izzy")) { FdroidApi(get(), IzzyApiConfig, get(named("izzy"))) }
    single<FdroidRepository>(named("izzy")) { FdroidRepositoryImpl(get(named("izzy")), get()) }
    singleOf(::ReleaseApi)
    single { ReleaseSourceRepository(get(), get(), get(), get(), get<com.app.market.data.remote.xiaomi.platform.DeviceDefaultsDataSource>().current().cpuArchitecture) }
    single { TencentApiConfig() }
    singleOf(::TencentApi)
    singleOf(::TencentRepositoryImpl) { bind<TencentRepository>() }
    single { CoolapkApiConfig() }
    single { CoolapkSigner() }
    single { CoolapkApi(get(), get(), get()) } onClose { it?.close() }
    singleOf(::CoolapkRepositoryImpl) { bind<CoolapkRepository>() }
    single<MarketSourceRepository> {
        MarketSourceRepositoryImpl(
            market = get(), today = get(), vivo = get(), wandoujia = get(), oppo = get(),
            samsung = get(), honor = get(), huawei = get(), tapTap = get(), kuaibao = get(),
            fdroid = get(), coolapk = get(), tencent = get(), izzy = get(named("izzy")), releases = get(),
        )
    }

    singleOf(::RecordingDownloadRepositoryImpl) { bind<DownloadRepository>() }
}

internal expect val platformDataModule: Module

/** Complete data graph loaded by an application composition root. */
val dataModules: List<Module>
    get() = listOf(commonDataModule, platformDataModule)
