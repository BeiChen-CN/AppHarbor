package com.app.market.data.repository

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppCategory
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.AppSubCategory
import com.app.market.domain.model.market.GameSubCategory
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
import com.app.market.domain.repository.TencentRepository
import com.app.market.domain.repository.CoolapkRepository
import com.app.market.domain.repository.AppchinaRepository
import com.app.market.domain.repository.Box7723Repository
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
    private val appchina: AppchinaRepository,
    private val box7723: Box7723Repository,
    private val fdroid: FdroidRepository,
    private val coolapk: CoolapkRepository,
    private val tencent: TencentRepository,
    private val izzy: FdroidRepository,
    private val releases: ReleaseSourceRepository,
    private val tuna: FdroidRepository,
) : MarketSourceRepository {
    private val commentTargets = CommentTargetCache()
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
        AppSource.APPCHINA -> appchina.search(keyword, page)
        AppSource.BOX7723 -> box7723.search(keyword, page)
        AppSource.FDROID -> fdroid.search(keyword, page)
        AppSource.COOLAPK -> coolapk.search(keyword, page)
        AppSource.TENCENT -> tencent.search(keyword, page)
        AppSource.IZZYONDROID -> izzy.search(keyword, page)
        AppSource.FDROID_TUNA -> tuna.search(keyword, page)
        AppSource.GITHUB, AppSource.GITLAB -> releases.search(source, keyword, page)
    }

    override suspend fun categoryApps(
        source: AppSource,
        category: AppCategory,
        subCategory: AppSubCategory,
        gameSubCategory: GameSubCategory,
        page: Int,
    ): SearchPage {
        // 当前统一使用小米公开网页分类接口；其余来源的分类 API 尚未接入。
        val categoryId = if (category == AppCategory.GAMES) gameSubCategory.xiaomiCategoryId else subCategory.xiaomiCategoryId
        return market.categoryApps(categoryId, page)
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
        AppSource.APPCHINA -> appchina.appDetail(appId, packageName)
        AppSource.BOX7723 -> box7723.appDetail(
            resolveStoreAppId(source, packageName, appId) { box7723.search(it, 0) },
        )
        // F-Droid 无站内数字 id，以 packageName 为真实键
        AppSource.FDROID -> fdroid.appDetail(packageName)
        AppSource.COOLAPK -> coolapk.appDetail(appId, packageName)
        AppSource.TENCENT -> tencent.appDetail(packageName)
        AppSource.IZZYONDROID -> izzy.appDetail(packageName)
        AppSource.FDROID_TUNA -> tuna.appDetail(packageName)
        AppSource.GITHUB, AppSource.GITLAB -> releases.appDetail(source, appId, packageName)
    }

    override suspend fun appComments(source: AppSource, app: MarketAppInfo): AppComments {
        if (!source.capabilities.supportsComments) return AppComments(emptyList(), 0L)
        return when (source) {
            // 好游快爆网页端有原生评论协议
            AppSource.KUAIBAO -> {
                val target = if (app.source == AppSource.KUAIBAO) app else commentTargets.resolve(AppSource.KUAIBAO, app.packageName) {
                    var match: MarketAppInfo? = null
                    val checked = mutableSetOf<Long>()
                    for (query in listOf(app.packageName, app.displayName).filter { it.isNotBlank() }.distinct()) {
                        val hits = kuaibao.search(query, 0).items.take(8)
                        for (hit in hits) {
                            if (!checked.add(hit.appId)) continue
                            val detail = try { kuaibao.appDetail(hit.appId).app }
                            catch (error: kotlinx.coroutines.CancellationException) { throw error }
                            catch (_: Exception) { continue }
                            if (detail.packageName == app.packageName) { match = detail; break }
                        }
                        if (match != null) break
                    }
                    match ?: throw MarketException("好游快爆未收录此应用的评论")
                }
                try { kuaibao.appComments(target) }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Exception) { commentTargets.invalidate(AppSource.KUAIBAO, app.packageName); throw error }
            }
            // 应用汇详情页自带最近评论，直接按包名抓取
            AppSource.APPCHINA -> appchina.appComments(app)
            // 应用宝评论页内嵌结构化评分与评论正文
            AppSource.TENCENT -> tencent.appComments(app)
            // TapTap 网页版评价接口
            AppSource.TAPTAP -> tapTap.appComments(app)
            AppSource.FDROID -> AppComments(emptyList(), 0L)
            else -> {
                val xiaomi = if (app.source == AppSource.XIAOMI) app else commentTargets.resolve(AppSource.XIAOMI, app.packageName) { app.onXiaomi() }
                try { market.appComments(xiaomi.appId, xiaomi.versionCode) }
                catch (error: kotlinx.coroutines.CancellationException) { throw error }
                catch (error: Exception) { commentTargets.invalidate(AppSource.XIAOMI, app.packageName); throw error }
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
        AppSource.APPCHINA -> appchina.downloadMeta(app)
        AppSource.BOX7723 -> box7723.downloadMeta(app)
        AppSource.FDROID -> fdroid.downloadMeta(app)
        AppSource.COOLAPK -> coolapk.downloadMeta(app)
        AppSource.TENCENT -> tencent.downloadMeta(app)
        AppSource.IZZYONDROID -> izzy.downloadMeta(app)
        AppSource.FDROID_TUNA -> tuna.downloadMeta(app)
        AppSource.GITHUB, AppSource.GITLAB -> releases.downloadMeta(source, app)
    }

    override suspend fun downloadUpdateMeta(source: AppSource, app: MarketAppInfo): DownloadMeta = when (source) {
        AppSource.XIAOMI -> market.downloadUpdateMeta(app)
        AppSource.VIVO -> vivo.downloadUpdateMeta(app)
        // 豌豆荚无原生更新元数据协议，回退小米商店解析
        AppSource.WANDOUJIA -> market.downloadUpdateMeta(app.onXiaomi())
        // 好游快爆网页渠道不暴露版本号，同样回退小米
        AppSource.KUAIBAO -> market.downloadUpdateMeta(app.onXiaomi())
        // 应用汇无原生批量更新协议，更新下载回退小米
        AppSource.APPCHINA -> market.downloadUpdateMeta(app.onXiaomi())
        AppSource.BOX7723 -> market.downloadUpdateMeta(app.onXiaomi())
        AppSource.OPPO -> oppo.downloadUpdateMeta(app)
        AppSource.SAMSUNG -> samsung.downloadUpdateMeta(app)
        AppSource.HONOR -> honor.downloadUpdateMeta(app)
        AppSource.HUAWEI -> huawei.downloadUpdateMeta(app)
        AppSource.TAPTAP -> tapTap.downloadUpdateMeta(app)
        // F-Droid / 酷安使用自身全量下载解析。
        AppSource.FDROID -> fdroid.downloadMeta(app)
        AppSource.COOLAPK -> coolapk.downloadMeta(app)
        AppSource.TENCENT -> tencent.downloadMeta(app)
        AppSource.IZZYONDROID -> izzy.downloadMeta(app)
        AppSource.FDROID_TUNA -> tuna.downloadMeta(app)
        AppSource.GITHUB, AppSource.GITLAB -> releases.downloadMeta(source, app)
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
        // 应用汇无原生批量更新协议；能力声明已保证不会作为更新来源被选中
        AppSource.APPCHINA -> flow { emit(emptyList()) }
        AppSource.BOX7723 -> flow { emit(emptyList()) }
        // P2 起通过仓库索引做本地比对，首次调用会触发索引同步（约 20MB gzip）
        AppSource.FDROID -> flow { emit(fdroid.checkUpdates()) }
        AppSource.COOLAPK -> flow { emit(coolapk.checkUpdates()) }
        AppSource.TENCENT -> flow { emit(tencent.checkUpdates()) }
        AppSource.IZZYONDROID -> flow { emit(izzy.checkUpdates()) }
        AppSource.FDROID_TUNA -> flow { emit(tuna.checkUpdates()) }
        AppSource.GITHUB, AppSource.GITLAB -> flow { emit(releases.checkUpdates(source)) }
    }

    override suspend fun checkManualUpdate(source: AppSource, request: ManualUpdateRequest): ManualUpdateResult =
        when (source) {
            AppSource.XIAOMI -> market.checkManualUpdate(request)
            AppSource.VIVO -> vivo.checkManualUpdate(request)
            // 豌豆荚无原生手动更新协议，回退小米商店解析
            AppSource.WANDOUJIA -> market.checkManualUpdate(request)
            AppSource.KUAIBAO -> market.checkManualUpdate(request)
            AppSource.APPCHINA -> market.checkManualUpdate(request)
            AppSource.BOX7723 -> market.checkManualUpdate(request)
            AppSource.OPPO -> oppo.checkManualUpdate(request)
            AppSource.SAMSUNG -> samsung.checkManualUpdate(request)
            AppSource.HONOR -> honor.checkManualUpdate(request)
            AppSource.HUAWEI -> huawei.checkManualUpdate(request)
            AppSource.TAPTAP -> tapTap.checkManualUpdate(request)
            AppSource.FDROID -> fdroid.checkManualUpdate(request)
            AppSource.COOLAPK -> coolapk.checkManualUpdate(request)
            AppSource.TENCENT -> tencent.checkManualUpdate(request)
            AppSource.IZZYONDROID -> izzy.checkManualUpdate(request)
            AppSource.FDROID_TUNA -> tuna.checkManualUpdate(request)
            AppSource.GITHUB, AppSource.GITLAB -> releases.checkManualUpdate(source, request)
        }

    override suspend fun goldMiFeed(source: AppSource, page: Int, pageSize: Int): TodayFeedPage =
        when (source) {
            AppSource.OPPO -> oppo.beautyFeed(page, pageSize)
            // 无独立今日内容的来源回退小米商店今日。
            AppSource.XIAOMI, AppSource.WANDOUJIA, AppSource.SAMSUNG, AppSource.HUAWEI, AppSource.HONOR, AppSource.KUAIBAO, AppSource.APPCHINA, AppSource.BOX7723, AppSource.FDROID, AppSource.FDROID_TUNA, AppSource.COOLAPK, AppSource.TENCENT, AppSource.IZZYONDROID, AppSource.GITHUB, AppSource.GITLAB ->
                today.goldMiFeed(page, pageSize)

            AppSource.VIVO -> vivo.auroraFeed(page, pageSize)
            AppSource.TAPTAP -> tapTap.todayFeed(page, pageSize)
        }

    override suspend fun todayArticle(source: AppSource, rId: String): TodayArticle =
        when (source) {
            AppSource.OPPO -> oppo.beautyArticle(rId)
            AppSource.XIAOMI, AppSource.WANDOUJIA, AppSource.SAMSUNG, AppSource.HUAWEI, AppSource.HONOR, AppSource.KUAIBAO, AppSource.APPCHINA, AppSource.BOX7723, AppSource.FDROID, AppSource.FDROID_TUNA, AppSource.COOLAPK, AppSource.TENCENT, AppSource.IZZYONDROID, AppSource.GITHUB, AppSource.GITLAB ->
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
        AppSource.APPCHINA -> appchina.historicalVersions(appId, packageName, offset)
        AppSource.FDROID -> fdroid.historicalVersions(packageName, offset)
        AppSource.IZZYONDROID -> izzy.historicalVersions(packageName, offset)
        AppSource.FDROID_TUNA -> tuna.historicalVersions(packageName, offset)
        // 其余来源未声明 supportsHistoricalVersions，UI 不会进入；返回空页兜底
        else -> HistoricalVersionPage(items = emptyList(), nextOffset = null)
    }

    override suspend fun historicalDownloadMeta(source: AppSource, version: HistoricalVersion): DownloadMeta =
        when (source) {
            AppSource.WANDOUJIA -> wandoujia.historicalDownloadMeta(version)
            AppSource.APPCHINA -> appchina.historicalDownloadMeta(version)
            AppSource.FDROID -> fdroid.historicalDownloadMeta(version)
            AppSource.IZZYONDROID -> izzy.historicalDownloadMeta(version)
            AppSource.FDROID_TUNA -> tuna.historicalDownloadMeta(version)
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
