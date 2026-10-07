package com.app.market.data.repository

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.today.TodayArticle
import com.app.market.domain.model.today.TodayFeedPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.exception.MarketException
import com.app.market.domain.repository.FdroidRepository
import com.app.market.domain.repository.HonorRepository
import com.app.market.domain.repository.HuaweiRepository
import com.app.market.domain.repository.KuaibaoRepository
import com.app.market.domain.repository.MarketRepository
import com.app.market.domain.repository.MarketSourceRepository
import com.app.market.domain.repository.OppoRepository
import com.app.market.domain.repository.SamsungRepository
import com.app.market.domain.repository.TodayRepository
import com.app.market.domain.repository.TapTapRepository
import com.app.market.domain.repository.VivoRepository
import com.app.market.domain.repository.WandoujiaRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Central source router. Capabilities are declared on [AppSource.capabilities]. */
internal class MarketSourceRepositoryImpl(
    private val market: MarketRepository,
    private val today: TodayRepository,
    private val vivo: VivoRepository,
    private val wandoujia: WandoujiaRepository,
    private val oppo: OppoRepository,
    private val samsung: SamsungRepository,
    private val honor: HonorRepository,
    private val huawei: HuaweiRepository,
    private val tapTap: TapTapRepository,
    private val kuaibao: KuaibaoRepository,
    private val fdroid: FdroidRepository,
) : MarketSourceRepository {
    override suspend fun search(source: AppSource, keyword: String, page: Int): SearchPage = when (source) {
        AppSource.XIAOMI -> market.search(keyword, page)
        AppSource.VIVO -> vivo.search(keyword, page)
        AppSource.WANDOUJIA -> wandoujia.search(keyword, page)
        AppSource.OPPO -> oppo.search(keyword, page)
        AppSource.SAMSUNG -> samsung.search(keyword, page)
        AppSource.HONOR -> honor.search(keyword, page)
        AppSource.HUAWEI -> huawei.search(keyword, page)
        AppSource.TAPTAP -> tapTap.search(keyword, page)
        AppSource.KUAIBAO -> kuaibao.search(keyword, page)
        AppSource.FDROID -> fdroid.search(keyword, page)
    }

    override suspend fun appDetail(
        source: AppSource,
        appId: Long,
        packageName: String,
        externalQuery: String?,
    ): AppDetail = when (source) {
        AppSource.XIAOMI -> market.appDetail(appId, packageName, externalQuery)
        AppSource.VIVO -> vivo.appDetail(
            resolveStoreAppId(source, packageName, appId) { vivo.search(it, 0) },
            packageName,
        )

        AppSource.WANDOUJIA -> wandoujia.appDetail(
            resolveStoreAppId(source, packageName, appId) { wandoujia.search(it, 0) },
        )

        AppSource.OPPO -> oppo.appDetail(appId, packageName, externalQuery)
        AppSource.SAMSUNG -> samsung.appDetail(appId, packageName, externalQuery)
        AppSource.HONOR -> honor.appDetail(appId, packageName)
        AppSource.HUAWEI -> huawei.appDetail(appId, packageName)
        AppSource.TAPTAP -> tapTap.appDetail(appId, packageName)
        AppSource.KUAIBAO -> kuaibao.appDetail(appId)
        // F-Droid 无站内数字 id，以 packageName 为真实键
        AppSource.FDROID -> fdroid.appDetail(packageName)
    }

    override suspend fun appComments(source: AppSource, app: MarketAppInfo): AppComments {
        if (!source.capabilities.supportsComments) return AppComments(emptyList(), 0L)
        return when (source) {
            // 好游快爆网页端有原生评论协议
            AppSource.KUAIBAO -> kuaibao.appComments(app)
            AppSource.FDROID -> AppComments(emptyList(), 0L)
            else -> {
                val xiaomi = app.onXiaomi()
                market.appComments(xiaomi.appId, xiaomi.versionCode)
            }
        }
    }

    override suspend fun sameDeveloperApps(source: AppSource, app: MarketAppInfo): List<MarketAppInfo> {
        if (!source.capabilities.supportsSameDeveloperApps) return emptyList()
        val xiaomi = app.onXiaomi()
        return filterSameDeveloperCandidates(app.packageName, market.sameDeveloperApps(xiaomi.appId))
    }

    override suspend fun downloadMeta(source: AppSource, app: MarketAppInfo, keyword: String): DownloadMeta = when (source) {
        AppSource.XIAOMI -> market.downloadMeta(app, keyword)
        AppSource.VIVO -> vivo.downloadMeta(app)
        AppSource.WANDOUJIA -> wandoujia.downloadMeta(app)
        AppSource.OPPO -> oppo.downloadMeta(app)
        AppSource.SAMSUNG -> samsung.downloadMeta(app)
        AppSource.HONOR -> honor.downloadMeta(app)
        AppSource.HUAWEI -> huawei.downloadMeta(app)
        AppSource.TAPTAP -> tapTap.downloadMeta(app)
        AppSource.KUAIBAO -> kuaibao.downloadMeta(app)
        AppSource.FDROID -> fdroid.downloadMeta(app)
    }

    override suspend fun downloadUpdateMeta(source: AppSource, app: MarketAppInfo): DownloadMeta = when (source) {
        AppSource.XIAOMI -> market.downloadUpdateMeta(app)
        AppSource.VIVO -> vivo.downloadUpdateMeta(app)
        // 豌豆荚无原生更新元数据协议，回退小米商店解析
        AppSource.WANDOUJIA -> market.downloadUpdateMeta(app.onXiaomi())
        // 好游快爆网页渠道不暴露版本号，同样回退小米
        AppSource.KUAIBAO -> market.downloadUpdateMeta(app.onXiaomi())
        AppSource.OPPO -> oppo.downloadUpdateMeta(app)
        AppSource.SAMSUNG -> samsung.downloadUpdateMeta(app)
        AppSource.HONOR -> honor.downloadUpdateMeta(app)
        AppSource.HUAWEI -> huawei.downloadUpdateMeta(app)
        AppSource.TAPTAP -> tapTap.downloadUpdateMeta(app)
        // F-Droid 无更新来源资格（capabilities 已保证不会走到这里）；兜底走自身的下载解析
        AppSource.FDROID -> fdroid.downloadMeta(app)
    }

    override suspend fun loadReconciledCachedUpdates(): List<MarketAppInfo> =
        market.loadReconciledCachedUpdates()

    override fun checkUpdatesFlow(source: AppSource): Flow<List<MarketAppInfo>> = when (source) {
        AppSource.XIAOMI -> market.checkUpdatesFlow()
        AppSource.VIVO -> flow { emit(vivo.checkUpdates()) }
        AppSource.WANDOUJIA -> flow { emit(wandoujia.checkUpdates()) }
        AppSource.OPPO -> flow { emit(oppo.checkUpdates()) }
        AppSource.SAMSUNG -> flow { emit(samsung.checkUpdates()) }
        AppSource.HONOR -> flow { emit(honor.checkUpdates()) }
        AppSource.HUAWEI -> flow { emit(huawei.checkUpdates()) }
        AppSource.TAPTAP -> flow { emit(tapTap.checkUpdates()) }
        // 好游快爆无更新协议；能力声明已保证不会作为更新来源被选中
        AppSource.KUAIBAO -> flow { emit(emptyList()) }
        // P2 起通过仓库索引做本地比对，首次调用会触发索引同步（约 20MB gzip）
        AppSource.FDROID -> flow { emit(fdroid.checkUpdates()) }
    }

    override suspend fun checkManualUpdate(source: AppSource, request: ManualUpdateRequest): ManualUpdateResult =
        when (source) {
            AppSource.XIAOMI -> market.checkManualUpdate(request)
            AppSource.VIVO -> vivo.checkManualUpdate(request)
            // 豌豆荚无原生手动更新协议，回退小米商店解析
            AppSource.WANDOUJIA -> market.checkManualUpdate(request)
            AppSource.KUAIBAO -> market.checkManualUpdate(request)
            AppSource.OPPO -> oppo.checkManualUpdate(request)
            AppSource.SAMSUNG -> samsung.checkManualUpdate(request)
            AppSource.HONOR -> honor.checkManualUpdate(request)
            AppSource.HUAWEI -> huawei.checkManualUpdate(request)
            AppSource.TAPTAP -> tapTap.checkManualUpdate(request)
            AppSource.FDROID -> fdroid.checkManualUpdate(request)
        }

    override suspend fun goldMiFeed(source: AppSource, page: Int, pageSize: Int): TodayFeedPage =
        when (source) {
            AppSource.OPPO -> oppo.beautyFeed(page, pageSize)
            // 豌豆荚 / 三星 / 华为 / 荣耀 / 好游快爆 / F-Droid 无独立今日内容，回退小米商店今日
            AppSource.XIAOMI, AppSource.WANDOUJIA, AppSource.SAMSUNG, AppSource.HUAWEI, AppSource.HONOR, AppSource.KUAIBAO, AppSource.FDROID ->
                today.goldMiFeed(page, pageSize)

            AppSource.VIVO -> vivo.auroraFeed(page, pageSize)
            AppSource.TAPTAP -> tapTap.todayFeed(page, pageSize)
        }

    override suspend fun todayArticle(source: AppSource, rId: String): TodayArticle =
        when (source) {
            AppSource.OPPO -> oppo.beautyArticle(rId)
            AppSource.XIAOMI, AppSource.WANDOUJIA, AppSource.SAMSUNG, AppSource.HUAWEI, AppSource.HONOR, AppSource.KUAIBAO, AppSource.FDROID ->
                today.todayArticle(rId)

            AppSource.VIVO -> vivo.auroraArticle(rId)
            AppSource.TAPTAP -> tapTap.todayArticle(rId)
        }

    override suspend fun historicalVersions(
        source: AppSource,
        appId: Long,
        packageName: String,
        offset: Int,
    ): HistoricalVersionPage = when (source) {
        AppSource.WANDOUJIA -> wandoujia.historicalVersions(appId, packageName, offset)
        AppSource.FDROID -> fdroid.historicalVersions(packageName, offset)
        // 其余来源未声明 supportsHistoricalVersions，UI 不会进入；返回空页兜底
        else -> HistoricalVersionPage(items = emptyList(), nextOffset = null)
    }

    override suspend fun historicalDownloadMeta(source: AppSource, version: HistoricalVersion): DownloadMeta =
        when (source) {
            AppSource.WANDOUJIA -> wandoujia.historicalDownloadMeta(version)
            AppSource.FDROID -> fdroid.historicalDownloadMeta(version)
            else -> throw MarketException("该来源不支持历史版本下载")
        }

    private suspend fun MarketAppInfo.onXiaomi(): MarketAppInfo {
        if (source == AppSource.XIAOMI) return this
        return market.appDetail(
            appId = 0L,
            packageName = packageName,
            externalQuery = "id=$packageName",
        ).app.copy(
            installedVersionName = installedVersionName,
            installedVersionCode = installedVersionCode,
            installedOldApkHash = installedOldApkHash,
            installedBaseApkPath = installedBaseApkPath,
            installedSplits = installedSplits,
        )
    }
}

private suspend fun resolveStoreAppId(
    source: AppSource,
    packageName: String,
    appId: Long,
    search: suspend (String) -> SearchPage,
): Long = appId.takeIf { it > 0L } ?: search(packageName).items
    .firstOrNull { it.packageName.equals(packageName, true) }
    ?.appId
?: throw IllegalStateException("${source.token} 未收录该应用")

internal fun filterSameDeveloperCandidates(
    currentPackageName: String,
    candidates: List<MarketAppInfo>,
): List<MarketAppInfo> = candidates
    .filterNot { it.packageName.equals(currentPackageName, ignoreCase = true) }
    .distinctBy { it.packageName.lowercase() }
