package com.app.market.data.remote.fdroid

import com.app.market.data.platform.debugLog
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPart
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppScreenshot
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.market.ScreenshotOrientation
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.model.update.ManualUpdateStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

internal data class FdroidApiConfig(
    val searchBase: String = "https://search.f-droid.org",
    val siteBase: String = "https://f-droid.org",
    val repoBase: String = "https://f-droid.org/repo",
    val archiveBase: String = "https://f-droid.org/archive",
    val source: AppSource = AppSource.FDROID,
    val indexedSearch: Boolean = false,
)

internal val IzzyApiConfig = FdroidApiConfig(
    siteBase = "https://apt.izzysoft.de/fdroid",
    repoBase = "https://apt.izzysoft.de/fdroid/repo",
    archiveBase = "https://apt.izzysoft.de/fdroid/repo",
    source = AppSource.IZZYONDROID,
    indexedSearch = true,
)

internal const val FdroidApiUserAgent = "AppMarket (F-Droid source; +https://github.com/YXBwbWFya2V0/AppMarket)"

/** 搜索接口仅返回名称/简介/图标/页面链接；包名从页面链接末段解析。 */
internal data class FdroidSearchEntry(
    val packageName: String,
    val displayName: String,
    val summary: String,
    val icon: String,
)

internal data class FdroidPackageVersion(val versionName: String, val versionCode: Long)

/** /api/v1/packages/<pkg>：仅主仓库已发布版本（不含 archive），无文件大小与哈希。 */
internal data class FdroidPackageVersions(
    val packageName: String,
    val suggestedVersionCode: Long,
    val versions: List<FdroidPackageVersion>,
) {
    val sorted: List<FdroidPackageVersion> get() = versions.sortedByDescending(FdroidPackageVersion::versionCode)

    /** F-Droid 语义的推荐版本（suggestedVersionCode 不在列表中时回退最高版本）。 */
    val suggested: FdroidPackageVersion?
        get() = versions.firstOrNull { it.versionCode == suggestedVersionCode } ?: sorted.firstOrNull()
}

private const val HistoryPageSize = 20

internal class FdroidApi(
    private val client: HttpClient,
    private val config: FdroidApiConfig = FdroidApiConfig(),
    private val indexCache: FdroidIndexCache,
) {
    /**
     * 官方全文搜索接口无分页参数（实测 page/n/limit 均被忽略），单次最多返回 10 条，
     * 且不含版本信息；版本号在详情/下载阶段按包名补齐。
     */
    suspend fun search(keyword: String, page: Int): SearchPage {
        if (config.indexedSearch) {
            if (keyword.isBlank()) return SearchPage(emptyList(), false)
            val matches = indexCache.summary().apps.entries.filter { (pkg, entry) ->
                pkg.contains(keyword, true) || entry.displayName.contains(keyword, true) ||
                    entry.summary.contains(keyword, true)
            }.sortedWith(compareByDescending<Map.Entry<String, FdroidAppSummary>> { it.key.equals(keyword, true) }
                .thenBy { it.value.displayName.lowercase() })
            val offset = page.coerceAtLeast(0) * HistoryPageSize
            val items = matches.drop(offset).take(HistoryPageSize).map { (pkg, entry) ->
                MarketAppInfo(
                    appId = fdroidStableId(pkg), packageName = pkg,
                    displayName = entry.displayName.ifBlank { pkg }, publisherName = "",
                    versionName = entry.latest?.versionName.orEmpty(), versionCode = entry.latest?.versionCode ?: 0L,
                    icon = assetUrl(entry.icon), apkSize = entry.latest?.apkSize ?: 0L,
                    ratingScore = 0.0, openLink = sitePage(pkg), source = config.source,
                )
            }
            return SearchPage(items, offset + items.size < matches.size)
        }
        if (keyword.isBlank() || page > 0) return SearchPage(emptyList(), hasMore = false)
        val root = getJson("${config.searchBase.trimEnd('/')}/api/search_apps") {
            parameter("q", keyword)
        }
        return SearchPage(items = parseSearchApps(root).map { it.toMarketAppInfo() }, hasMore = false)
    }

    suspend fun appDetail(packageName: String): AppDetail {
        // P2：索引切片优先（长描述/截图/分类/版本文件信息），切片不可用再降级轻量 API
        val fragment = softFragment(packageName)
        if (fragment != null && fragment.versions.isNotEmpty()) return fragment.toAppDetail()
        if (config.indexedSearch) throw MarketException("${config.source.token} 未收录该应用或索引不可用")
        return appDetailViaApi(packageName)
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val fragment = softFragment(app.packageName)
        if (fragment != null) {
            val target = fragment.versions.firstOrNull { it.versionCode == app.versionCode && app.versionCode > 0L }
                ?: fragment.versions.suggested()
            if (target != null) {
                return fdroidDownloadMeta(
                    source = config.source,
                    appId = app.appId,
                    packageName = app.packageName,
                    displayName = fragment.displayName().ifBlank { app.packageName },
                    versionName = target.versionName.ifBlank { app.versionName },
                    versionCode = target.versionCode,
                    icon = assetUrl(fragment.icon.fdroidAssetPath()).ifBlank { app.icon },
                    url = assetUrl(target.apkName),
                    size = target.apkSize,
                    hash = target.apkSha256,
                )
            }
        }
        if (config.indexedSearch) throw MarketException("${config.source.token} 未返回安装包信息")
        return downloadMetaViaApi(app)
    }

    suspend fun historicalVersions(packageName: String, offset: Int): HistoricalVersionPage {
        val fragment = softFragment(packageName)
        if (fragment != null && fragment.versions.isNotEmpty()) {
            val sorted = fragment.versions
            val window = sorted.drop(offset.coerceAtLeast(0)).take(HistoryPageSize)
            val items = window.map { version ->
                HistoricalVersion(
                    appId = fdroidStableId(packageName),
                    packageName = packageName,
                    displayName = fragment.displayName(),
                    icon = assetUrl(fragment.icon.fdroidAssetPath()),
                    versionId = version.versionCode,
                    versionName = version.versionName,
                    versionCode = version.versionCode,
                    minSdkVersion = version.minSdkVersion,
                    // 切片内的 APK 路径来自索引 file.name，与主仓库结构一致，直链可直接拼出
                    downloadUrl = assetUrl(version.apkName),
                    size = version.apkSize,
                    createdAt = version.added,
                    updatedAt = version.added,
                )
            }
            val next = offset.coerceAtLeast(0) + window.size
            return HistoricalVersionPage(items = items, nextOffset = next.takeIf { it < sorted.size })
        }
        if (config.indexedSearch) return HistoricalVersionPage(emptyList(), null)
        return historicalVersionsViaApi(packageName, offset)
    }

    suspend fun historicalDownloadMeta(version: HistoricalVersion): DownloadMeta {
        val fragment = softFragment(version.packageName)
        if (fragment != null) {
            val target = fragment.versions.firstOrNull { it.versionCode == version.versionCode }
            if (target != null) {
                return fdroidDownloadMeta(
                    source = config.source,
                    appId = version.appId,
                    packageName = version.packageName,
                    displayName = version.displayName.ifBlank { version.packageName },
                    versionName = version.versionName,
                    versionCode = version.versionCode,
                    icon = version.icon,
                    url = assetUrl(target.apkName),
                    size = target.apkSize,
                    hash = target.apkSha256,
                )
            }
        }
        if (config.indexedSearch) throw MarketException("${config.source.token} 未收录该历史版本")
        return historicalDownloadMetaViaApi(version)
    }

    /**
     * 批量更新检查（P2）：以仓库索引摘要与本地安装列表比对。
     * 签名不兼容的应用一律不推送——F-Droid 默认用自己的密钥重签 APK，
     * 与其他渠道安装的版本互不兼容，覆盖安装必然失败。
     */
    suspend fun checkUpdates(installed: List<InstalledPackage>): List<MarketAppInfo> {
        if (installed.isEmpty()) return emptyList()
        val summary = indexCache.summary()
        return installed.mapNotNull { local ->
            summary.apps[local.packageName]?.let { entry -> buildUpdate(local, entry) }
        }
    }

    suspend fun checkManualUpdate(request: ManualUpdateRequest, installedSigners: List<String>): ManualUpdateResult {
        val fragment = softFragment(request.packageName)
        if (fragment != null) {
            val latest = fragment.versions.suggested()
                ?: return ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
            if (latest.versionCode <= request.versionCode) {
                return ManualUpdateResult(ManualUpdateStatus.RECOGNIZED_NO_UPDATE)
            }
            // 签名指纹已知且不兼容时按无可用更新处理：该应用无法从 F-Droid 覆盖安装
            val localSigners = installedSigners.normalizedSigners()
            val remoteSigners = (listOf(fragment.preferredSigner) + latest.signers).normalizedSigners()
            if (localSigners.isNotEmpty() && remoteSigners.isNotEmpty() &&
                localSigners.none { it in remoteSigners }
            ) {
                return ManualUpdateResult(ManualUpdateStatus.RECOGNIZED_NO_UPDATE)
            }
            val app = fragment.toMarketAppInfo(latest).copy(
                installedVersionName = request.versionName,
                installedVersionCode = request.versionCode,
                installedSplits = request.splits,
            )
            return ManualUpdateResult(ManualUpdateStatus.UPDATE_AVAILABLE, app)
        }
        if (config.indexedSearch) return ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
        return checkManualUpdateViaApi(request)
    }

    // region 索引切片映射

    /** F-Droid 语义的推荐版本：优先无 antiFeatures 的最高版本，其次全局最高。 */
    private fun List<FdroidFragmentVersion>.suggested(): FdroidFragmentVersion? =
        firstOrNull { it.antiFeatures.isEmpty() } ?: firstOrNull()

    private fun buildUpdate(local: InstalledPackage, entry: FdroidAppSummary): MarketAppInfo? {
        val latest = entry.latest ?: return null
        if (latest.versionCode <= local.versionCode) return null
        // 签名未知时不推送（无法判定覆盖安装是否可行），签名不兼容时不推送
        val localSigners = (listOf(local.signerSha256) + local.signerSha256List).normalizedSigners()
        val remoteSigners = (listOf(entry.preferredSigner) + latest.signers).normalizedSigners()
        if (localSigners.isEmpty() || remoteSigners.isEmpty()) return null
        if (localSigners.none { it in remoteSigners }) return null
        return MarketAppInfo(
            appId = fdroidStableId(local.packageName),
            packageName = local.packageName,
            displayName = entry.displayName.ifBlank { local.packageName },
            publisherName = "",
            versionName = latest.versionName,
            versionCode = latest.versionCode,
            icon = assetUrl(entry.icon),
            apkSize = latest.apkSize,
            ratingScore = 0.0,
            changeLog = latest.whatsNew,
            isSystemApp = local.isSystemApp,
            openLink = sitePage(local.packageName),
            installedVersionName = local.versionName,
            installedVersionCode = local.versionCode,
            installedSplits = local.splits,
            source = config.source,
        )
    }

    private fun FdroidAppFragment.toAppDetail(): AppDetail {
        val latest = versions.suggested()
        val app = toMarketAppInfo(latest)
        return AppDetail(
            app = app,
            brief = summary.fdroidLocalized(),
            introduction = description.fdroidLocalized(),
            changeLog = latest?.whatsNew?.fdroidLocalized().orEmpty(),
            category = categories.firstOrNull().orEmpty(),
            ageClassification = "",
            downloadCount = 0L,
            registrationNum = "",
            privacyUrl = "",
            screenshots = phoneScreenshots().take(MaxScreenshots).map {
                AppScreenshot(url = assetUrl(it.name), orientation = ScreenshotOrientation.PORTRAIT)
            },
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    private fun FdroidAppFragment.toMarketAppInfo(latest: FdroidFragmentVersion?): MarketAppInfo = MarketAppInfo(
        appId = fdroidStableId(packageName),
        packageName = packageName,
        displayName = displayName().ifBlank { packageName },
        publisherName = authorName,
        versionName = latest?.versionName.orEmpty(),
        versionCode = latest?.versionCode ?: 0L,
        icon = assetUrl(icon.fdroidAssetPath()),
        apkSize = latest?.apkSize ?: 0L,
        ratingScore = 0.0,
        changeLog = latest?.whatsNew?.fdroidLocalized().orEmpty(),
        openLink = sitePage(packageName),
        source = config.source,
    )

    private fun FdroidAppFragment.displayName(): String = name.fdroidLocalized()

    /** 手机截图本地化挑选：zh-CN -> en-US -> 任一非空语言。 */
    private fun FdroidAppFragment.phoneScreenshots(): List<FdroidIndexAsset> {
        val phone = screenshots?.phone ?: return emptyList()
        return (phone["zh-CN"] ?: phone["en-US"] ?: phone.values.firstOrNull { it.isNotEmpty() })
            .orEmpty()
            .filter { it.name.isNotBlank() }
    }

    /** 索引读取失败不阻断详情/下载：静默降级到轻量 API 路径。 */
    private suspend fun softFragment(packageName: String): FdroidAppFragment? = try {
        indexCache.fragment(packageName)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        debugLog("FdroidApi") { "index fragment unavailable for $packageName: ${e.message}" }
        null
    }

    /** 仓库相对路径（/pkg/xxx）拼接为直链；空路径返回空串。 */
    private fun assetUrl(path: String): String =
        path.takeIf { it.isNotBlank() }?.let { "${config.repoBase.trimEnd('/')}$it" }.orEmpty()

    private fun List<String>.normalizedSigners(): List<String> =
        map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct()

    // endregion

    // region P1 轻量 API 路径（索引不可用时的降级实现）

    private fun parseSearchApps(root: JsonObject): List<FdroidSearchEntry> =
        root.fdjArray("apps").orEmpty().mapNotNull { element ->
            val entry = element as? JsonObject ?: return@mapNotNull null
            val url = entry.fdjString("url")
            val packageName = url.substringAfterLast('/').substringBefore('?')
            if (packageName.isBlank()) return@mapNotNull null
            FdroidSearchEntry(
                packageName = packageName,
                displayName = entry.fdjString("name"),
                summary = entry.fdjString("summary"),
                icon = entry.fdjString("icon"),
            )
        }

    /** 按包名精确匹配一次搜索，补齐展示名/简介/图标；失败静默降级。 */
    private suspend fun resolveIdentity(packageName: String): FdroidSearchEntry? = runCatching {
        val root = getJson("${config.searchBase.trimEnd('/')}/api/search_apps") {
            parameter("q", packageName)
        }
        parseSearchApps(root).firstOrNull { it.packageName.equals(packageName, ignoreCase = true) }
    }.getOrNull()

    private suspend fun appDetailViaApi(packageName: String): AppDetail {
        val versions = packageVersions(packageName)
            ?: throw MarketException("F-Droid 未收录该应用")
        val version = versions.suggested
            ?: throw MarketException("F-Droid 未提供该应用的可用版本")
        val identity = resolveIdentity(packageName)
        val app = MarketAppInfo(
            appId = fdroidStableId(packageName),
            packageName = packageName,
            displayName = identity.displayOrPackage(packageName),
            publisherName = "",
            versionName = version.versionName,
            versionCode = version.versionCode,
            icon = identity?.icon.orEmpty(),
            apkSize = 0L,
            ratingScore = 0.0,
            openLink = sitePage(packageName),
            source = config.source,
        )
        return AppDetail(
            app = app,
            brief = identity?.summary.orEmpty(),
            // 轻量路径拿不到仓库索引，长描述/截图/分类等富信息降级留空
            introduction = "",
            changeLog = "",
            category = "",
            ageClassification = "",
            downloadCount = 0L,
            registrationNum = "",
            privacyUrl = "",
            screenshots = emptyList(),
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    private suspend fun downloadMetaViaApi(app: MarketAppInfo): DownloadMeta {
        val versions = packageVersions(app.packageName)
            ?: throw MarketException("F-Droid 未收录该应用")
        val target = versions.sorted.firstOrNull { it.versionCode == app.versionCode }
            ?: versions.suggested
            ?: throw MarketException("F-Droid 未提供该应用的可用版本")
        val (url, size) = resolveApk(app.packageName, target.versionCode)
        return fdroidDownloadMeta(
            source = config.source,
            appId = app.appId,
            packageName = app.packageName,
            displayName = app.displayName.ifBlank { app.packageName },
            versionName = target.versionName.ifBlank { app.versionName },
            versionCode = target.versionCode,
            icon = app.icon,
            url = url,
            size = size,
        )
    }

    private suspend fun historicalVersionsViaApi(packageName: String, offset: Int): HistoricalVersionPage {
        val versions = packageVersions(packageName)
            ?: throw MarketException("F-Droid 未收录该应用")
        val identity = resolveIdentity(packageName)
        val sorted = versions.sorted
        val window = sorted.drop(offset.coerceAtLeast(0)).take(HistoryPageSize)
        val items = window.map { version ->
            HistoricalVersion(
                appId = fdroidStableId(packageName),
                packageName = packageName,
                displayName = identity.displayOrPackage(packageName),
                icon = identity?.icon.orEmpty(),
                versionId = version.versionCode,
                versionName = version.versionName,
                versionCode = version.versionCode,
                minSdkVersion = 0,
                // 预测的主仓库直链；实际下载时由 historicalDownloadMeta 在 repo/archive 间探测
                downloadUrl = apkUrl(config.repoBase, packageName, version.versionCode),
                size = 0L,
                createdAt = 0L,
                updatedAt = 0L,
            )
        }
        val next = offset.coerceAtLeast(0) + window.size
        return HistoricalVersionPage(items = items, nextOffset = next.takeIf { it < sorted.size })
    }

    private suspend fun historicalDownloadMetaViaApi(version: HistoricalVersion): DownloadMeta {
        val (url, size) = resolveApk(version.packageName, version.versionCode)
        return fdroidDownloadMeta(
            source = config.source,
            appId = version.appId,
            packageName = version.packageName,
            displayName = version.displayName.ifBlank { version.packageName },
            versionName = version.versionName,
            versionCode = version.versionCode,
            icon = version.icon,
            url = url,
            size = size,
        )
    }

    private suspend fun checkManualUpdateViaApi(request: ManualUpdateRequest): ManualUpdateResult {
        val versions = packageVersions(request.packageName)
            ?: return ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
        val latest = versions.suggested
            ?: return ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
        val identity = resolveIdentity(request.packageName)
        val app = MarketAppInfo(
            appId = fdroidStableId(request.packageName),
            packageName = request.packageName,
            displayName = identity.displayOrPackage(request.packageName),
            publisherName = "",
            versionName = latest.versionName,
            versionCode = latest.versionCode,
            icon = identity?.icon.orEmpty(),
            apkSize = 0L,
            ratingScore = 0.0,
            openLink = sitePage(request.packageName),
            installedVersionName = request.versionName,
            installedVersionCode = request.versionCode,
            installedSplits = request.splits,
            source = config.source,
        )
        return if (latest.versionCode > request.versionCode) {
            ManualUpdateResult(ManualUpdateStatus.UPDATE_AVAILABLE, app)
        } else {
            ManualUpdateResult(ManualUpdateStatus.RECOGNIZED_NO_UPDATE, app)
        }
    }

    /** /api/v1/packages/<pkg>；404 视为未收录返回 null，其余非 2xx 视为服务异常。 */
    private suspend fun packageVersions(packageName: String): FdroidPackageVersions? {
        val path = "/api/v1/packages/$packageName"
        val response = client.get(config.siteBase.trimEnd('/') + path) {
            header(HttpHeaders.UserAgent, FdroidApiUserAgent)
        }
        if (response.status.value == 404) return null
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("FdroidApi") { "HTTP ${response.status.value} $path: ${text.take(200)}" }
            throw MarketException("F-Droid 服务器返回异常状态 HTTP ${response.status.value}")
        }
        val root = parseFdroidObject(text)
        val packages = root.fdjArray("packages").orEmpty().mapNotNull { element ->
            (element as? JsonObject)?.let { json ->
                FdroidPackageVersion(
                    versionName = json.fdjString("versionName"),
                    versionCode = json.fdjLong("versionCode"),
                )
            }
        }
        if (packages.isEmpty()) return null
        return FdroidPackageVersions(
            packageName = root.fdjString("packageName", packageName),
            suggestedVersionCode = root.fdjLong("suggestedVersionCode"),
            versions = packages,
        )
    }

    /**
     * 解析版本 APK 直链与文件大小：主仓库 HEAD 拿不到 Content-Length 时回退 archive 仓库
     * （F-Droid 会把旧版本从主仓库迁移到 /archive/）。
     */
    private suspend fun resolveApk(packageName: String, versionCode: Long): Pair<String, Long> {
        val repo = apkUrl(config.repoBase, packageName, versionCode)
        val repoSize = headContentLength(repo)
        if (repoSize > 0L) return repo to repoSize
        val archive = apkUrl(config.archiveBase, packageName, versionCode)
        val archiveSize = headContentLength(archive)
        if (archiveSize > 0L) return archive to archiveSize
        throw MarketException("F-Droid 未提供该版本的安装包文件")
    }

    private suspend fun headContentLength(url: String): Long = runCatching {
        val response = client.head(url) { header(HttpHeaders.UserAgent, FdroidApiUserAgent) }
        if (response.status.isSuccess()) {
            response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
        } else {
            0L
        }
    }.getOrDefault(0L)

    private suspend fun getJson(url: String, request: HttpRequestBuilder.() -> Unit = {}): JsonObject {
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, FdroidApiUserAgent)
            request()
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("FdroidApi") { "HTTP ${response.status.value} $url: ${text.take(200)}" }
            throw MarketException("F-Droid 服务器返回异常状态 HTTP ${response.status.value}")
        }
        return parseFdroidObject(text)
    }

    private fun FdroidSearchEntry.toMarketAppInfo(): MarketAppInfo = MarketAppInfo(
        appId = fdroidStableId(packageName),
        packageName = packageName,
        displayName = displayName.ifBlank { packageName },
        publisherName = "",
        versionName = "",
        versionCode = 0L,
        icon = icon,
        apkSize = 0L,
        ratingScore = 0.0,
        openLink = sitePage(packageName),
        source = config.source,
    )

    private fun FdroidSearchEntry?.displayOrPackage(packageName: String): String =
        this?.displayName?.takeIf { it.isNotBlank() } ?: packageName

    private fun sitePage(packageName: String): String =
        if (config.indexedSearch) "${config.siteBase.trimEnd('/')}/index/apk/$packageName"
        else "${config.siteBase.trimEnd('/')}/en/packages/$packageName"

    // endregion

    private companion object {
        const val MaxScreenshots = 8
    }
}

private fun apkUrl(base: String, packageName: String, versionCode: Long): String =
    "${base.trimEnd('/')}/${packageName}_${versionCode}.apk"

internal fun fdroidDownloadMeta(
    appId: Long,
    packageName: String,
    displayName: String,
    versionName: String,
    versionCode: Long,
    icon: String,
    url: String,
    size: Long,
    source: AppSource = AppSource.FDROID,
    hash: String = "",
): DownloadMeta = DownloadMeta(
    appId = appId,
    packageName = packageName,
    displayName = displayName,
    versionName = versionName,
    versionCode = versionCode,
    url = url,
    size = size,
    parts = listOf(DownloadPart(name = "", type = "base", url = url, size = size, hash = hash)),
    icon = icon,
    requestHeaders = mapOf(HttpHeaders.UserAgent to FdroidApiUserAgent),
    source = source,
)
