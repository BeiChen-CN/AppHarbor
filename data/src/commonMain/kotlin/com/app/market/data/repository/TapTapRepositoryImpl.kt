package com.app.market.data.repository

import com.app.market.data.remote.taptap.TapTapApi
import com.app.market.data.remote.taptap.TapTapAppRecord
import com.app.market.data.remote.taptap.TapTapDetailRecord
import com.app.market.data.remote.taptap.TapTapRecommendationRecord
import com.app.market.data.remote.taptap.TapTapSearchHit
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPart
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppScreenshot
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.ScreenshotOrientation
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.today.TodayArticle
import com.app.market.domain.model.today.TodayArticleBlock
import com.app.market.domain.model.today.TodayFeaturedItem
import com.app.market.domain.model.today.TodayFeedPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.model.update.ManualUpdateStatus
import com.app.market.domain.repository.InstalledApkHashRepository
import com.app.market.domain.repository.InstallerPreferencesRepository
import com.app.market.domain.repository.InstalledPackagesRepository
import com.app.market.domain.repository.TapTapRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class TapTapRepositoryImpl(
    private val api: TapTapApi,
    private val installedPackages: InstalledPackagesRepository,
    private val installedApkHash: InstalledApkHashRepository,
    private val installerPreferences: InstallerPreferencesRepository,
) : TapTapRepository {
    private val recordsByPackage = mutableMapOf<String, TapTapAppRecord>()
    private val recordsById = mutableMapOf<Long, TapTapAppRecord>()
    private val recommendationsById = mutableMapOf<Long, TapTapRecommendationRecord>()

    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        val (hits, hasMore) = api.search(keyword, page)
        val records = api.apps(hits.map(TapTapSearchHit::packageName))
        records.forEach(::remember)
        val byPackage = records.associateBy { it.packageName.lowercase() }
        SearchPage(
            items = hits.map { hit ->
                byPackage[hit.packageName.lowercase()]?.toApp()
                    ?: hit.toFallbackApp()
            },
            hasMore = hasMore,
        )
    }

    override suspend fun appDetail(appId: Long, packageName: String): AppDetail = withContext(Dispatchers.Default) {
        val resolvedId = appId.takeIf { it > 0L }
            ?: recordsByPackage[packageName.lowercase()]?.appId
            ?: api.apps(listOf(packageName)).firstOrNull()?.also(::remember)?.appId
            ?: throw MarketException("TapTap 未收录该应用")
        val detail = api.detail(resolvedId)
        val delivery = api.apps(listOf(detail.app.packageName)).firstOrNull()
        val merged = detail.app.withDelivery(delivery)
        remember(merged)
        val installed = installedPackages.installedPackage(merged.packageName)
        detail.toAppDetail(
            if (installed == null) merged.toApp() else merged.toApp().withInstalled(installed),
        )
    }

    override suspend fun appComments(app: MarketAppInfo): AppComments = withContext(Dispatchers.Default) {
        val resolvedId = app.appId.takeIf { it > 0L && app.source == AppSource.TAPTAP }
            ?: recordsByPackage[app.packageName.lowercase()]?.appId
            ?: api.apps(listOf(app.packageName)).firstOrNull()?.also(::remember)?.appId
            ?: throw MarketException("TapTap 未收录该应用")
        api.appComments(resolvedId)
    }

    override suspend fun todayFeed(page: Int, pageSize: Int): TodayFeedPage = withContext(Dispatchers.Default) {
        val (recommendations, hasMore) = api.recommendations(page, pageSize)
        recommendations.forEach { recommendationsById[it.appId] = it }
        val records = api.apps(recommendations.map(TapTapRecommendationRecord::packageName))
        records.forEach(::remember)
        val recordsByPackage = records.associateBy { it.packageName.lowercase() }
        TodayFeedPage(
            items = recommendations.map { recommendation ->
                val app = recordsByPackage[recommendation.packageName.lowercase()]?.toApp()
                    ?: recommendation.toFallbackApp()
                TodayFeaturedItem(
                    rId = recommendation.appId.toString(),
                    title = recommendation.displayName,
                    summary = recommendation.recommendation.ifBlank { recommendation.description },
                    coverImage = recommendation.coverImage,
                    app = app,
                    apps = listOf(app),
                    awardName = TapTapRecommendationLabel,
                )
            },
            hasMore = hasMore,
        )
    }

    override suspend fun todayArticle(rId: String): TodayArticle = withContext(Dispatchers.Default) {
        val appId = rId.toLongOrNull()?.takeIf { it > 0L }
            ?: throw MarketException("TapTap 推荐应用 id 无效")
        val detail = api.detail(appId)
        val delivery = api.apps(listOf(detail.app.packageName)).firstOrNull()
        val merged = detail.app.withDelivery(delivery)
        remember(merged)
        val app = merged.toApp()
        val recommendation = recommendationsById[appId]
        val header = recommendation?.coverImage
            .orEmpty()
            .ifBlank { detail.screenshots.firstOrNull()?.url.orEmpty() }
        val descriptionHtml = escapeHtml(detail.app.description).replace("\n", "<br>")
        TodayArticle(
            rId = appId.toString(),
            title = merged.displayName,
            awardName = TapTapRecommendationLabel,
            headerImage = header,
            richTextHtml = descriptionHtml,
            app = app,
            apps = listOf(app),
            blocks = buildList {
                if (header.isNotBlank()) {
                    add(TodayArticleBlock.Banner(header))
                    // 今日文章页会去掉正文首尾装饰图；用头图作边界以保留全部应用截图。
                    add(TodayArticleBlock.Image(header))
                }
                if (descriptionHtml.isNotBlank()) add(TodayArticleBlock.RichText(descriptionHtml))
                detail.screenshots.forEach { screenshot ->
                    add(
                        TodayArticleBlock.Image(
                            imageUrl = screenshot.url,
                            width = screenshot.width.toInt(),
                            height = screenshot.height.toInt(),
                        )
                    )
                }
                if (header.isNotBlank()) add(TodayArticleBlock.Image(header))
                add(TodayArticleBlock.App(app))
            },
        )
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        downloadMetaOf(app)
    }

    override suspend fun downloadUpdateMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        // 下载时重新读取安装信息，避免更新列表中的旧路径对应已被替换的 APK。
        val local = installedPackages.installedPackage(app.packageName)
        val basePath = local?.baseApkPath.orEmpty()
        val canPatch = installerPreferences.deltaUpdateEnabled() && local != null &&
                (local.splits.isBlank() || local.splits == "0") && basePath.isNotBlank()
        val hash = if (canPatch) installedApkHash.tapTap(basePath) else ""
        downloadMetaOf(app, hash).copy(installedBaseApkPath = basePath)
    }

    override suspend fun checkUpdates(): List<MarketAppInfo> = withContext(Dispatchers.Default) {
        val installed = installedPackages.installed()
            .filter { it.packageName.isNotBlank() && it.versionCode > 0L }
        if (installed.isEmpty()) return@withContext emptyList()
        val byPackage = installed.associateBy { it.packageName.lowercase() }
        installed.chunked(UpdateBatchSize)
            .flatMap { batch -> api.apps(batch.map(InstalledPackage::packageName)) }
            .onEach(::remember)
            .mapNotNull { remote ->
                val local = byPackage[remote.packageName.lowercase()] ?: return@mapNotNull null
                if (remote.apkId <= 0L || remote.versionCode <= local.versionCode) return@mapNotNull null
                remote.toApp().withInstalled(local)
            }
            .distinctBy { it.packageName.lowercase() }
    }

    override suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult =
        withContext(Dispatchers.Default) {
            val record = api.apps(listOf(request.packageName)).firstOrNull()
                ?: return@withContext ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
            remember(record)
            if (record.apkId <= 0L || record.versionCode <= request.versionCode) {
                return@withContext ManualUpdateResult(ManualUpdateStatus.RECOGNIZED_NO_UPDATE)
            }
            val installed = installedPackages.installedPackage(request.packageName) ?: InstalledPackage(
                packageName = request.packageName,
                versionCode = request.versionCode,
                versionName = request.versionName,
                isSystemApp = request.isSystemApp,
                installedBy = request.installedBy,
                splits = request.splits,
                oldApkHash = request.oldApkHash,
                apkSource = request.apkSource,
            )
            ManualUpdateResult(
                status = ManualUpdateStatus.UPDATE_AVAILABLE,
                app = record.toApp().withInstalled(installed),
            )
        }

    private suspend fun downloadMetaOf(app: MarketAppInfo, apkHash: String = ""): DownloadMeta {
        val record = api.apps(listOf(app.packageName)).firstOrNull()
            ?: recordsByPackage[app.packageName.lowercase()]
            ?: throw MarketException("TapTap 未收录该应用")
        remember(record)
        val apk = api.apk(record.apkId, apkHash)
        val versionCode = apk.versionCode.takeIf { it > 0L } ?: record.versionCode
        val versionName = apk.versionName.ifBlank { record.versionName }
        val size = apk.size.takeIf { it > 0L } ?: record.apkSize
        return DownloadMeta(
            appId = record.appId,
            packageName = record.packageName,
            displayName = record.displayName,
            versionName = versionName,
            versionCode = versionCode,
            url = apk.url,
            size = size,
            parts = listOf(
                DownloadPart(
                    name = "",
                    type = "base",
                    url = apk.url,
                    size = size,
                    hash = apk.md5.ifBlank { record.md5 },
                    patch = apk.patch,
                )
            ),
            installedBaseApkPath = app.installedBaseApkPath,
            icon = record.icon.ifBlank { app.icon },
            changeLog = record.changeLog,
            source = AppSource.TAPTAP,
        )
    }

    private fun remember(record: TapTapAppRecord) {
        recordsByPackage[record.packageName.lowercase()] = record
        if (record.appId > 0L) recordsById[record.appId] = record
    }

    private fun TapTapAppRecord.toApp(): MarketAppInfo = MarketAppInfo(
        appId = appId,
        packageName = packageName,
        displayName = displayName,
        publisherName = publisherName,
        versionName = versionName,
        versionCode = versionCode,
        icon = icon,
        apkSize = apkSize,
        ratingScore = rating,
        changeLog = changeLog,
        openLink = "https://www.taptap.cn/app/$appId",
        source = AppSource.TAPTAP,
        category = category,
        downloadCount = downloadCount,
    )

    private fun TapTapSearchHit.toFallbackApp(): MarketAppInfo = MarketAppInfo(
        appId = appId,
        packageName = packageName,
        displayName = displayName,
        publisherName = "",
        versionName = "",
        versionCode = 0L,
        icon = icon,
        apkSize = 0L,
        ratingScore = rating,
        openLink = "https://www.taptap.cn/app/$appId",
        source = AppSource.TAPTAP,
        category = category,
    )

    private fun TapTapRecommendationRecord.toFallbackApp(): MarketAppInfo = MarketAppInfo(
        appId = appId,
        packageName = packageName,
        displayName = displayName,
        publisherName = "",
        versionName = "",
        versionCode = 0L,
        icon = icon,
        apkSize = 0L,
        ratingScore = rating,
        changeLog = recommendation,
        openLink = "https://www.taptap.cn/app/$appId",
        source = AppSource.TAPTAP,
        category = category,
    )

    private fun MarketAppInfo.withInstalled(installed: InstalledPackage): MarketAppInfo = copy(
        isSystemApp = installed.isSystemApp,
        installedVersionName = installed.versionName,
        installedVersionCode = installed.versionCode,
        installedOldApkHash = installed.oldApkHash,
        installedBaseApkPath = installed.baseApkPath,
        installedSplits = installed.splits,
    )

    private fun TapTapAppRecord.withDelivery(delivery: TapTapAppRecord?): TapTapAppRecord {
        if (delivery == null) return this
        return copy(
            versionName = delivery.versionName.ifBlank { versionName },
            versionCode = delivery.versionCode.takeIf { it > 0L } ?: versionCode,
            apkSize = delivery.apkSize.takeIf { it > 0L } ?: apkSize,
            apkId = delivery.apkId.takeIf { it > 0L } ?: apkId,
            md5 = delivery.md5.ifBlank { md5 },
            changeLog = delivery.changeLog.ifBlank { changeLog },
        )
    }

    private fun TapTapDetailRecord.toAppDetail(appInfo: MarketAppInfo): AppDetail = AppDetail(
        app = appInfo,
        brief = developerNote,
        introduction = app.description,
        changeLog = app.changeLog,
        category = app.category,
        ageClassification = "",
        downloadCount = app.downloadCount,
        registrationNum = registrationName,
        updateTime = updatedAt,
        privacyUrl = privacyUrl,
        screenshots = screenshots.map { image ->
            AppScreenshot(
                url = image.url,
                orientation = if (image.width > image.height) {
                    ScreenshotOrientation.LANDSCAPE
                } else {
                    ScreenshotOrientation.PORTRAIT
                },
                expandedUrl = image.originalUrl,
            )
        },
        commentCount = app.commentCount,
        comments = emptyList(),
        sameDeveloperApps = emptyList(),
    )

    private companion object {
        const val UpdateBatchSize = 40
        const val TapTapRecommendationLabel = "TapTap 推荐"
    }
}

private fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
