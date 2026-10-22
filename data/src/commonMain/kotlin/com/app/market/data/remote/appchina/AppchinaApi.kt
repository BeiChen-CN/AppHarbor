package com.app.market.data.remote.appchina

import com.app.market.data.platform.debugLog
import com.app.market.data.remote.urlEncodeParameters
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPart
import com.app.market.domain.model.market.AppComment
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppScreenshot
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.ScreenshotOrientation
import com.app.market.domain.model.market.SearchPage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.ExperimentalTime
import kotlin.random.Random

internal data class AppchinaApiConfig(
    val webBase: String = "https://m.appchina.com",
    val apiBase: String = "https://mobile.appchina.com/market/api",
)

private const val MobileUserAgent =
    "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

private const val HistoryPageSize = 20
private const val ApiVersion = 23
private const val ClientVersionCode = 30_065_588
private const val Imei = "869394021134371"
private const val Channel = "ac.ex.360"

internal class AppchinaApi(
    private val client: HttpClient,
    private val config: AppchinaApiConfig = AppchinaApiConfig(),
) {
    private val deviceId = Random.nextInt(1_000_000_000, Int.MAX_VALUE).toString()
    private val guid = randomAppchinaUuid()
    suspend fun search(keyword: String, page: Int): SearchPage {
        val path = "/ajax/search/${keyword.encodeURLPath()}/${page + 1}"
        return parseAppchinaSearch(request(path, acceptJson = true))
    }

    suspend fun appDetail(appId: Long, packageName: String): AppDetail {
        val targetPackage = packageName.ifBlank {
            throw MarketException("应用汇缺少包名，无法加载应用详情")
        }
        return parseDetail(fetchDetailPage(targetPackage), targetPackage, appId).toAppDetail()
    }

    suspend fun appComments(app: MarketAppInfo): AppComments {
        val targetPackage = app.packageName.ifBlank {
            throw MarketException("应用汇缺少包名，无法加载评论")
        }
        return parseAppchinaComments(fetchDetailPage(targetPackage))
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val targetPackage = app.packageName.ifBlank {
            throw MarketException("应用汇缺少包名，无法解析下载地址")
        }
        val parsed = parseDetail(fetchDetailPage(targetPackage), targetPackage, app.appId)
        val url = parsed.downloadUrl
        if (url.isBlank()) throw MarketException("应用汇未提供该版本的下载地址")
        val size = parsed.apkSize.takeIf { it > 0L } ?: sizeOf(url).takeIf { it > 0L } ?: app.apkSize
        return DownloadMeta(
            appId = parsed.appId.takeIf { it > 0L } ?: app.appId,
            packageName = parsed.packageName.ifBlank { targetPackage },
            displayName = parsed.displayName.ifBlank { app.displayName },
            versionName = parsed.versionName.ifBlank { app.versionName },
            versionCode = parsed.versionCode.takeIf { it > 0L } ?: app.versionCode,
            url = url,
            size = size,
            parts = listOf(DownloadPart(name = "", type = "base", url = url, size = size, hash = "")),
            icon = parsed.icon.ifBlank { app.icon },
            changeLog = parsed.changeLog,
            requestHeaders = mapOf(
                HttpHeaders.UserAgent to MobileUserAgent,
                HttpHeaders.Referrer to config.webBase.trimEnd('/') + "/",
            ),
            source = AppSource.APPCHINA,
        )
    }

    @OptIn(ExperimentalTime::class)
    suspend fun historicalVersions(appId: Long, packageName: String, offset: Int): HistoricalVersionPage {
        if (appId <= 0L || packageName.isBlank()) throw MarketException("应用汇缺少应用标识，无法加载历史版本")
        val param = buildJsonObject {
            put("apiVer", ApiVersion)
            put("guid", guid)
            put("imei", Imei)
            put("type", "app.pastdetails")
            put("id", appId)
            put("packagename", packageName)
            put("start", offset)
            put("size", HistoryPageSize)
            put("clientTime", kotlin.time.Clock.System.now().toEpochMilliseconds())
            put("imglevel", 3)
            put("gpuType", 4)
            put("clientVersionCode", ClientVersionCode)
            put("channel", Channel)
        }.toString()
        val body = urlEncodeParameters(
            mapOf(
                "api" to "market.MarketAPI",
                "key" to "",
                "deviceId" to deviceId,
                "referer" to guid,
                "param" to param,
                "refreshcache" to "false",
            ),
        )
        return parseAppchinaHistory(postApi(body, "历史版本"), appId, packageName, offset)
    }

    fun historicalDownloadMeta(version: HistoricalVersion): DownloadMeta {
        val url = normalizeAppchinaUrl(version.downloadUrl)
        if (url.isBlank()) throw MarketException("应用汇未提供该历史版本的下载地址")
        return DownloadMeta(
            appId = version.versionId,
            packageName = version.packageName,
            displayName = version.displayName,
            versionName = version.versionName,
            versionCode = version.versionCode,
            url = url,
            size = version.size,
            parts = listOf(DownloadPart(name = "", type = "base", url = url, size = version.size, hash = "")),
            icon = version.icon,
            requestHeaders = mapOf(
                HttpHeaders.UserAgent to MobileUserAgent,
                HttpHeaders.Referrer to config.webBase.trimEnd('/') + "/",
            ),
            source = AppSource.APPCHINA,
        )
    }

    private suspend fun fetchDetailPage(packageName: String): String =
        request("/app/${packageName.encodeURLPath()}", acceptJson = false)

    private suspend fun postApi(body: String, label: String): String {
        val response = client.post(config.apiBase) {
            contentType(ContentType.Application.FormUrlEncoded)
            header(HttpHeaders.UserAgent, MobileUserAgent)
            header(HttpHeaders.Referrer, config.webBase.trimEnd('/') + "/")
            setBody(body)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("AppchinaApi") { "HTTP ${response.status.value} $label: ${text.take(200)}" }
            throw MarketException("应用汇服务器返回异常状态 HTTP ${response.status.value}")
        }
        return text
    }

    private suspend fun request(path: String, acceptJson: Boolean): String {
        val response = client.get(config.webBase.trimEnd('/') + path) {
            header(HttpHeaders.UserAgent, MobileUserAgent)
            header(HttpHeaders.Referrer, config.webBase.trimEnd('/') + "/")
            if (acceptJson) header(HttpHeaders.Accept, "application/json")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("AppchinaApi") { "HTTP ${response.status.value} $path: ${text.take(200)}" }
            throw MarketException("应用汇服务器返回异常状态 HTTP ${response.status.value}")
        }
        return text
    }

    /** HEAD 获取 APK 实际大小；失败时返回 0 交由上层回退。 */
    private suspend fun sizeOf(url: String): Long = runCatching {
        val response = client.head(url) { header(HttpHeaders.UserAgent, MobileUserAgent) }
        response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
    }.getOrDefault(0L)

    companion object {
        @OptIn(ExperimentalTime::class)
        internal fun parseDetail(html: String, requestedPackage: String, requestedAppId: Long): AppchinaDetail {
            val packageName = AppchinaPackageNameRegex.find(html)?.groupValues?.get(1)?.trim()
                ?.takeIf(String::isNotBlank) ?: requestedPackage
            val downloadUrl = AppchinaDownloadRegex.find(html)?.groupValues?.get(1)
                ?.let(::normalizeAppchinaUrl).orEmpty()
            val icon = AppchinaDetailIconRegex.find(html)?.groupValues?.get(1)
                ?.let(::normalizeAppchinaUrl).orEmpty()
            val sizeText = AppchinaSizeMetaRegex.find(html)?.groupValues?.get(1)
                ?: AppchinaInfoSizeRegex.find(html)?.groupValues?.get(1).orEmpty()
            val versionName = AppchinaVersionMetaRegex.find(html)?.groupValues?.get(1)?.trim()
                ?: AppchinaVersionHeaderRegex.find(html)?.groupValues?.get(1)?.let(::appchinaPlainText).orEmpty()
            val updateText = AppchinaUpdatedMetaRegex.find(html)?.groupValues?.get(1)?.trim().orEmpty()
            val category = AppchinaCategoryRegex.find(html)?.groupValues?.get(1)?.trim().orEmpty()
            val publisher = AppchinaDeveloperRegex.find(html)?.groupValues?.get(1)?.let(::appchinaPlainText).orEmpty()
            val displayName = AppchinaNameRegex.find(html)?.groupValues?.get(1)?.let(::appchinaPlainText)
                ?.takeIf(String::isNotBlank) ?: packageName
            val brief = AppchinaBriefRegex.find(html)?.groupValues?.get(1)?.let(::appchinaPlainText).orEmpty()
            val introduction = AppchinaIntroRegex.find(html)?.groupValues?.get(1)?.let(::appchinaPlainText).orEmpty()
            val changeLog = AppchinaChangeLogRegex.find(html)?.groupValues?.get(1)?.let(::appchinaPlainText).orEmpty()
            val screenshots = AppchinaScreenshotsRegex.find(html)?.groupValues?.get(1)?.let { block ->
                AppchinaImageRegex.findAll(block).mapNotNull { match ->
                    val url = normalizeAppchinaUrl(match.groupValues[1])
                    url.takeIf(String::isNotBlank)?.let {
                        AppScreenshot(url = it, orientation = ScreenshotOrientation.PORTRAIT)
                    }
                }.toList()
            }.orEmpty()
            val commentCount = AppchinaCommentCountRegex.find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

            return AppchinaDetail(
                appId = extractAppId(icon) ?: extractAppId(downloadUrl) ?: requestedAppId,
                packageName = packageName,
                displayName = displayName,
                brief = brief,
                icon = icon,
                versionName = versionName,
                versionCode = extractVersionCode(downloadUrl),
                apkSize = parseAppchinaSize(sizeText),
                ratingScore = parseAppchinaRating(html),
                commentCount = commentCount,
                category = category,
                publisherName = publisher,
                updateTime = parseAppchinaDate(updateText),
                introduction = introduction,
                changeLog = changeLog,
                screenshots = screenshots,
                downloadUrl = downloadUrl,
            )
        }
    }
}

internal data class AppchinaDetail(
    val appId: Long,
    val packageName: String,
    val displayName: String,
    val brief: String,
    val icon: String,
    val versionName: String,
    val versionCode: Long,
    val apkSize: Long,
    val ratingScore: Double,
    val commentCount: Long,
    val category: String,
    val publisherName: String,
    val updateTime: Long,
    val introduction: String,
    val changeLog: String,
    val screenshots: List<AppScreenshot>,
    val downloadUrl: String,
)

internal fun AppchinaDetail.toAppDetail(): AppDetail = AppDetail(
    app = MarketAppInfo(
        appId = appId,
        packageName = packageName,
        displayName = displayName,
        publisherName = publisherName,
        versionName = versionName,
        versionCode = versionCode,
        icon = icon,
        apkSize = apkSize,
        ratingScore = ratingScore,
        changeLog = changeLog,
        openLink = appchinaOpenLink(packageName),
        source = AppSource.APPCHINA,
        category = category,
    ),
    brief = brief,
    introduction = introduction,
    changeLog = changeLog,
    category = category,
    ageClassification = "",
    downloadCount = 0L,
    registrationNum = "",
    updateTime = updateTime,
    privacyUrl = "",
    screenshots = screenshots,
    commentCount = commentCount,
    comments = emptyList(),
    sameDeveloperApps = emptyList(),
)

private val AppchinaJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

internal fun parseAppchinaSearch(text: String): SearchPage {
    val response = runCatching { AppchinaJson.decodeFromString<AppchinaSearchResponse>(text) }
        .getOrElse { error ->
            debugLog("AppchinaApi") { "Invalid search payload: ${text.take(200)}" }
            throw MarketException("应用汇搜索返回了无效数据", error)
        }
    val items = response.list.mapNotNull(::appchinaAppInfo)
    return SearchPage(items = items, hasMore = !response.nextPage.isNullOrBlank() && response.list.isNotEmpty())
}

internal fun parseAppchinaHistory(
    text: String,
    appId: Long,
    packageName: String,
    offset: Int,
): HistoricalVersionPage {
    val response = runCatching { AppchinaJson.decodeFromString<AppchinaHistoryResponse>(text) }
        .getOrElse { error ->
            debugLog("AppchinaApi") { "Invalid history payload: ${text.take(200)}" }
            throw MarketException("应用汇历史版本返回了无效数据", error)
        }
    val items = response.list.mapNotNull { item ->
        val url = normalizeAppchinaUrl(item.apkUrl)
        if (url.isBlank() || item.versionCode <= 0L) return@mapNotNull null
        HistoricalVersion(
            appId = item.id,
            packageName = item.packageName.ifBlank { packageName },
            displayName = item.name.ifBlank { packageName },
            icon = normalizeAppchinaUrl(item.iconUrl),
            versionId = item.id,
            versionName = item.versionName,
            versionCode = item.versionCode,
            minSdkVersion = item.sdkVersion,
            downloadUrl = url,
            size = item.size,
            createdAt = parseAppchinaDate(item.createTime),
            updatedAt = parseAppchinaDate(item.lastUpdate),
        )
    }
    val total = response.total.takeIf { it > 0 } ?: response.listSize
    val received = response.list.size
    val consumed = offset + received
    val nextOffset = if (received >= HistoryPageSize && consumed < total) consumed else null
    return HistoricalVersionPage(items = items, nextOffset = nextOffset)
}

internal fun parseAppchinaComments(html: String): AppComments {
    val total = AppchinaCommentCountRegex.find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
    val block = AppchinaCommentBlockRegex.find(html)?.groupValues?.get(1) ?: return AppComments(emptyList(), total)
    val items = AppchinaCommentItemRegex.findAll(block).mapNotNull { match ->
        val item = match.groupValues[1]
        val userName = AppchinaCommentUserRegex.find(item)?.groupValues?.get(1)
            ?.let(::appchinaPlainText)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val content = AppchinaCommentContentRegex.find(item)?.groupValues?.get(1)
            ?.let(::appchinaPlainText)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        AppComment(userName = userName, content = content, score = 0.0)
    }.toList()
    return AppComments(items = items, totalCount = total.takeIf { it > 0L } ?: items.size.toLong())
}

private fun randomAppchinaUuid(): String {
    val hex = Random.nextBytes(16).joinToString("") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }
    return buildString {
        append(hex, 0, 8).append('-')
        append(hex, 8, 12).append('-')
        append(hex, 12, 16).append('-')
        append(hex, 16, 20).append('-')
        append(hex, 20, 32)
    }
}

private fun appchinaAppInfo(item: AppchinaSearchItem): MarketAppInfo? {
    if (item.offShelf || item.prePublish.equals("true", ignoreCase = true)) return null
    val packageName = item.packageName.trim()
    if (packageName.isBlank() || item.name.isBlank()) return null
    val icon = normalizeAppchinaUrl(item.iconUrl)
    val apkUrl = normalizeAppchinaUrl(item.apkUrl)
    return MarketAppInfo(
        appId = extractAppId(icon) ?: extractAppId(apkUrl) ?: 0L,
        packageName = packageName,
        displayName = item.name.trim(),
        publisherName = "",
        versionName = "",
        versionCode = extractVersionCode(apkUrl),
        icon = icon,
        apkSize = parseAppchinaSize(item.size),
        ratingScore = parseAppchinaRating(item.likePercentage),
        openLink = appchinaOpenLink(packageName),
        source = AppSource.APPCHINA,
    )
}

internal fun normalizeAppchinaUrl(url: String): String = when {
    url.startsWith("https://") -> url
    url.startsWith("http://") -> "https://" + url.removePrefix("http://")
    url.startsWith("//") -> "https:$url"
    else -> url
}

internal fun appchinaOpenLink(packageName: String): String =
    "https://m.appchina.com/app/$packageName"

internal fun extractAppId(url: String): Long? =
    AppchinaIconIdRegex.find(url)?.groupValues?.get(1)?.toLongOrNull()
        ?: AppchinaDownloadIdRegex.find(url)?.groupValues?.get(1)?.toLongOrNull()

internal fun extractVersionCode(downloadUrl: String): Long =
    AppchinaPackageVersionRegex.find(downloadUrl)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

internal fun parseAppchinaSize(raw: String): Long {
    val match = AppchinaSizeRegex.matchEntire(raw.trim()) ?: return 0L
    val value = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return 0L
    val unit = when (match.groupValues[2].uppercase()) {
        "K" -> 1024.0
        "M" -> 1024.0 * 1024
        "G" -> 1024.0 * 1024 * 1024
        "T" -> 1024.0 * 1024 * 1024 * 1024
        else -> return 0L
    }
    return (value * unit).toLong()
}

/** 好评百分比换算成 5 分制评分；无评分时返回 0。 */
internal fun parseAppchinaRating(raw: String): Double {
    val percent = AppchinaRatingRegex.find(raw)?.groupValues?.get(1)?.toDoubleOrNull() ?: return 0.0
    return (percent / 20.0).coerceIn(0.0, 5.0)
}

@OptIn(ExperimentalTime::class)
internal fun parseAppchinaDate(raw: String): Long = runCatching {
    LocalDate.parse(raw.take(10)).atStartOfDayIn(TimeZone.of("Asia/Shanghai")).toEpochMilliseconds()
}.getOrDefault(0L)

internal fun appchinaPlainText(html: String): String = html
    .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .trim()

@Serializable
private data class AppchinaSearchResponse(
    val nextPage: String? = null,
    val list: List<AppchinaSearchItem> = emptyList(),
)

@Serializable
private data class AppchinaSearchItem(
    val packageName: String = "",
    val name: String = "",
    val iconUrl: String = "",
    val shortDesc: String = "",
    val size: String = "",
    val likePercentage: String = "",
    val apkUrl: String = "",
    val offShelf: Boolean = false,
    val prePublish: String = "false",
)

@Serializable
private data class AppchinaHistoryResponse(
    val total: Int = 0,
    val listSize: Int = 0,
    val list: List<AppchinaHistoryItem> = emptyList(),
)

@Serializable
private data class AppchinaHistoryItem(
    val id: Long = 0L,
    val name: String = "",
    val packageName: String = "",
    val iconUrl: String = "",
    val apkUrl: String = "",
    val versionCode: Long = 0L,
    val versionName: String = "",
    val size: Long = 0L,
    val sdkVersion: Int = 0,
    val createTime: String = "",
    val lastUpdate: String = "",
)

private val AppchinaIconIdRegex = Regex("""/icon/\d+/(\d+)\.png""")
private val AppchinaDownloadIdRegex = Regex("""/e/(\d+)/""")
private val AppchinaPackageVersionRegex = Regex("""package_(\d+)(?:[./]|$)""")
private val AppchinaPackageNameRegex = Regex("""var\s+packageName\s*=\s*'([^']+)'""")
private val AppchinaDownloadRegex = Regex("""class="normal-down-btn"[^>]*href="([^"]+)"""")
private val AppchinaDetailIconRegex = Regex("""class="app-details-icon"[^>]*src="([^"]+)"""")
private val AppchinaNameRegex = Regex("""class="app-details-appname"><h1>([\s\S]*?)</h1>""")
private val AppchinaBriefRegex = Regex("""class="app-details-appdes"><h3>([\s\S]*?)</h3>""")
private val AppchinaVersionHeaderRegex = Regex("""<span>\s*版本\s*([^<]+)</span>""")
private val AppchinaVersionMetaRegex = Regex("""最新版本:</span>\s*<span class="fl">([^<]+)</span>""")
private val AppchinaSizeMetaRegex = Regex("""软件大小:</span>\s*<span class="fl">([^<]+)</span>""")
private val AppchinaInfoSizeRegex = Regex("""<div><span>([\d,.]+\s*[KMGT]B?)</span>""")
private val AppchinaUpdatedMetaRegex = Regex("""最后更新:</span>\s*<span class="fl">([^<]+)</span>""")
private val AppchinaCategoryRegex = Regex("""应用分类:</span>\s*<span class="fl"><a[^>]*>([^<]+)</a>""")
private val AppchinaDeveloperRegex = Regex("""开发者:</span>\s*<a[^>]*>([\s\S]*?)</a>""")
private val AppchinaIntroRegex = Regex("""App_Introduce">\s*<span>([\s\S]*?)</span>""")
private val AppchinaChangeLogRegex = Regex("""App_Update">\s*<span>([\s\S]*?)</span>""")
private val AppchinaScreenshotsRegex = Regex("""class="App_ImgBox">([\s\S]*?)</div>""")
private val AppchinaImageRegex = Regex("""<img\b[^>]*src="([^"]+)"""")
private val AppchinaCommentCountRegex = Regex("""icon_04[^>]*>[\s\S]{0,200}?<span>(\d+)</span>""")
private val AppchinaCommentBlockRegex = Regex("""class="App_SpreadContent app-details-comment">\s*<ul>([\s\S]*?)</ul>""")
private val AppchinaCommentItemRegex = Regex("""<li>\s*<table>([\s\S]*?)</table>\s*</li>""")
private val AppchinaCommentUserRegex = Regex("""<td[^>]*>([\s\S]*?)</td>""")
private val AppchinaCommentContentRegex = Regex("""<td colspan="2"[^>]*>([\s\S]*?)</td>""")
private val AppchinaRatingRegex = Regex("""(\d+(?:\.\d+)?)%好评""")
private val AppchinaSizeRegex = Regex("""([\d,.]+)\s*([KMGT])B?""", RegexOption.IGNORE_CASE)
