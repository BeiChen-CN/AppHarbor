package com.app.market.data.remote.x360

import com.app.market.data.platform.debugLog
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPart
import com.app.market.domain.model.market.AppComment
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppScreenshot
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.ScreenshotOrientation
import com.app.market.domain.model.market.SearchPage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class X360ApiConfig(
    val baseUrl: String = "https://m.app.so.com",
    /** 搜索接口的随机限流重试次数。 */
    val searchRetries: Int = 3,
    /** 两次搜索重试之间的间隔。 */
    val searchRetryDelayMillis: Long = 1_200L,
)

private const val UserAgentMobile =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private const val SearchPageSize = 15

private val HtmlTag = Regex("<[^>]+>")
private val TitlePattern = Regex("""<title>([^<]*)</title>""")
private val AppNamePattern = Regex("""<div class="app-name">([^<]+)</div>""")
private val AppLogoPattern = Regex("""<div class="app-logo">\s*<img src="([^"]+)"""")
private val DetailMetaPattern = Regex("""<div class="app-detail">([\s\S]*?)</div>""")
private val MetaSpanPattern = Regex("""<span>(?:<img[^>]*/>)?([^<]+)</span>""")
private val MoreInfoPattern = Regex("""<div class="app-moreinfo" id="infoMore">([\s\S]*?)</div>\s*</div>""")
private val VersionPattern = Regex("""<p>版本：([^<]*)</p>""")
private val UpdateTimePattern = Regex("""<p>更新时间：([^<]*)</p>""")
private val DeveloperPattern = Regex("""开发者：([^<]*)""")
private val FullDescPattern = Regex("""<div class="app-about-full" id="fullDesc">([\s\S]*?)<a href="#" id="fold"""")
private val ShortDescPattern = Regex("""<div class="app-about-shot" id="shortDesc">([\s\S]*?)<a href="#" id="expand"""")
private val ScreenshotPattern = Regex("""<div class="swiper-image" style="background-image:url\(([^)]+)\)""")
private val DownloadUrlPattern = Regex("""'url'\s*:\s*'([^']+\.apk[^']*)'""")
private val AppIdPattern = Regex("""'apkid'\s*:\s*'([^']+)'""")
private val AppInfoLogoPattern = Regex("""'logo'\s*:\s*'([^']+)'""")
private val PrivacyPattern = Regex("隐私政策：<a href=\"([^\"]+)\"" )

/** 360 手机助手移动网页解析结果。 */
internal data class X360Detail(
    val appId: Long,
    val packageName: String,
    val displayName: String,
    val versionName: String,
    val publisherName: String,
    val icon: String,
    val rating: Double,
    val downloadCount: Long,
    val size: Long,
    val introduction: String,
    val updateTime: Long,
    val privacyUrl: String,
    val screenshots: List<String>,
    val downloadUrl: String,
)

internal class X360Api(
    private val client: HttpClient,
    private val config: X360ApiConfig = X360ApiConfig(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 搜索 360 手机助手。移动版接口对同一关键词存在随机空结果限流，这里按配置重试。
     */
    suspend fun search(keyword: String, page: Int = 0): SearchPage {
        val query = keyword.trim()
        if (query.isBlank()) return SearchPage(items = emptyList(), hasMore = false)
        val targetPage = (page + 1).coerceAtLeast(1)
        val url = buildString {
            append(config.baseUrl)
            append("/search/index?q=")
            append(encodeQuery(query))
            append("&requestType=ajax&page=")
            append(targetPage)
        }

        val appList = fetchSearchList(url)
            ?: throw MarketException("360 手机助手搜索暂不可用，请稍后重试或切换来源")

        val apps = appList.mapNotNull { element ->
            val obj = element.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return@mapNotNull null
            val packageName = obj["apkid"]?.jsonPrimitive?.contentOrNull.orEmpty()
            if (packageName.isBlank()) return@mapNotNull null
            MarketAppInfo(
                appId = id,
                packageName = packageName,
                displayName = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty().cleanHtmlText(),
                publisherName = obj["market_name"]?.jsonPrimitive?.contentOrNull.orEmpty().cleanHtmlText(),
                versionName = obj["version_name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                versionCode = obj["version_code"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                icon = obj["logo_url"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                apkSize = obj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                ratingScore = obj["rating"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: 0.0,
                downloadCount = obj["download_times"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                openLink = "${config.baseUrl}/detail/index?pname=$packageName&id=$id",
                source = AppSource.X360,
            )
        }

        return SearchPage(items = apps, hasMore = appList.size >= SearchPageSize)
    }

    /**
     * 搜索结果对部分关键词会返回 `applist: null`，重试若干次仍为空即视为不可用。
     */
    private suspend fun fetchSearchList(url: String): JsonArray? {
        var last: JsonArray? = null
        for (attempt in 0 until config.searchRetries.coerceAtLeast(1)) {
            if (attempt > 0) delay(config.searchRetryDelayMillis)
            val response = runCatching {
                client.get(url) {
                    header(HttpHeaders.UserAgent, UserAgentMobile)
                    header(HttpHeaders.Referrer, "${config.baseUrl}/search/index")
                }
            }.getOrNull() ?: continue
            if (!response.status.isSuccess()) continue
            val text = runCatching { response.bodyAsText() }.getOrNull() ?: continue
            val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
            val list = root["applist"]?.takeUnless { it is kotlinx.serialization.json.JsonNull }?.jsonArray
            if (list != null) {
                last = list
                break
            }
        }
        return last
    }

    suspend fun appDetail(appId: Long, packageName: String): AppDetail {
        if (appId <= 0L && packageName.isBlank()) throw MarketException("360 手机助手缺少应用标识，无法加载详情")
        val parsed = fetchDetail(appId, packageName)
        val app = MarketAppInfo(
            appId = parsed.appId,
            packageName = parsed.packageName,
            displayName = parsed.displayName,
            publisherName = parsed.publisherName,
            versionName = parsed.versionName,
            versionCode = 0L,
            icon = parsed.icon,
            apkSize = parsed.size,
            ratingScore = parsed.rating,
            openLink = "${config.baseUrl}/detail/index?pname=${parsed.packageName}&id=${parsed.appId}",
            source = AppSource.X360,
            downloadCount = parsed.downloadCount,
        )
        return AppDetail(
            app = app,
            brief = parsed.introduction.take(120),
            introduction = parsed.introduction,
            changeLog = "",
            category = "",
            ageClassification = "",
            downloadCount = parsed.downloadCount,
            registrationNum = "",
            updateTime = parsed.updateTime,
            privacyUrl = parsed.privacyUrl,
            screenshots = parsed.screenshots.map { url ->
                AppScreenshot(url = url, orientation = ScreenshotOrientation.PORTRAIT)
            },
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    suspend fun appComments(app: MarketAppInfo): AppComments {
        val name = app.displayName.trim()
        if (name.isBlank()) throw MarketException("360 手机助手缺少应用名，无法加载评论")
        val url = "${config.baseUrl}/message/index?name=${encodeQuery(name + "android")}"
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, UserAgentMobile)
            header(HttpHeaders.Referrer, "${config.baseUrl}/detail/index?pname=${app.packageName}")
        }
        if (!response.status.isSuccess()) {
            throw MarketException("360 手机助手评论加载失败：HTTP ${response.status.value}")
        }
        val text = response.bodyAsText()
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull()
            ?: throw MarketException("360 手机助手评论解析失败")
        val comments = array.mapNotNull { element ->
            val obj = element.jsonObject
            val content = obj["content"]?.jsonPrimitive?.contentOrNull.orEmpty().trim()
            if (content.isBlank()) return@mapNotNull null
            AppComment(
                userName = obj["username"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "360 用户" },
                content = content,
                score = obj["score"]?.jsonPrimitive?.doubleOrNull
                    ?: obj["score"]?.jsonPrimitive?.intOrNull?.toDouble()
                    ?: 0.0,
            )
        }
        return AppComments(items = comments, totalCount = comments.size.toLong())
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val parsed = fetchDetail(app.appId, app.packageName)
        if (parsed.downloadUrl.isBlank()) throw MarketException("360 手机助手未提供该应用的下载地址")
        // 搜索接口的 size 是精确字节数，详情页只有「73.46Mb」这类展示值，优先用前者。
        val size = app.apkSize.takeIf { it > 0L } ?: parsed.size
        return DownloadMeta(
            appId = parsed.appId.takeIf { it > 0L } ?: app.appId,
            packageName = parsed.packageName.ifBlank { app.packageName },
            displayName = parsed.displayName.ifBlank { app.displayName },
            versionName = parsed.versionName.ifBlank { app.versionName },
            versionCode = app.versionCode,
            url = parsed.downloadUrl,
            size = size,
            parts = listOf(DownloadPart(name = "", type = "base", url = parsed.downloadUrl, size = size, hash = "")),
            icon = parsed.icon.ifBlank { app.icon },
            requestHeaders = mapOf(
                HttpHeaders.UserAgent to UserAgentMobile,
                HttpHeaders.Referrer to "${config.baseUrl}/",
            ),
            source = AppSource.X360,
        )
    }

    private suspend fun fetchDetail(appId: Long, packageName: String): X360Detail {
        val query = buildString {
            if (packageName.isNotBlank()) append("pname=").append(encodeQuery(packageName))
            if (appId > 0L) {
                if (isNotEmpty()) append('&')
                append("id=").append(appId)
            }
        }
        val url = "${config.baseUrl}/detail/index?$query"
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, UserAgentMobile)
            header(HttpHeaders.Referrer, "${config.baseUrl}/")
        }
        if (!response.status.isSuccess()) {
            throw MarketException("360 手机助手详情加载失败：HTTP ${response.status.value}")
        }
        val html = response.bodyAsText()
        return parseDetail(html, appId, packageName)
    }

    private fun parseDetail(html: String, fallbackAppId: Long, fallbackPackage: String): X360Detail {
        val title = TitlePattern.find(html)?.groupValues?.get(1)
            ?.cleanHtmlText()
            ?.substringBeforeLast('_')
            ?.trim()
            .orEmpty()
        val name = AppNamePattern.find(html)?.groupValues?.get(1)?.cleanHtmlText()?.ifBlank { title }
            ?: title
        // 详情页尾部的 appInfo 内联对象给出真实包名与图标，优先于无法提供包名的头部块。
        val realPackage = AppIdPattern.find(html)?.groupValues?.get(1)?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: fallbackPackage
        val icon = AppInfoLogoPattern.find(html)?.groupValues?.get(1)
            ?: AppLogoPattern.find(html)?.groupValues?.get(1).orEmpty()

        val metaBlock = DetailMetaPattern.find(html)?.groupValues?.get(1).orEmpty()
        val metaParts = MetaSpanPattern.findAll(metaBlock).map { it.groupValues[1].trim() }.toList()
        val rating = metaParts.getOrNull(0)?.removeSuffix("分")?.toDoubleOrNull() ?: 0.0
        val downloadCount = metaParts.getOrNull(1)?.let(::parseWanCount) ?: 0L
        val size = metaParts.getOrNull(2)?.let(::parseDisplaySize) ?: 0L

        val moreInfo = MoreInfoPattern.find(html)?.groupValues?.get(1).orEmpty()
        // 兜底：整页匹配，避免结构微调时丢字段
        val versionName = VersionPattern.find(moreInfo)?.groupValues?.get(1)?.trim()
            ?: VersionPattern.find(html)?.groupValues?.get(1)?.trim()
            ?: ""
        val updateTime = UpdateTimePattern.find(moreInfo)?.groupValues?.get(1)?.trim()
            ?.let(::x360TimeMillis) ?: 0L
        val developer = DeveloperPattern.find(moreInfo)?.groupValues?.get(1)?.trim()
            ?: DeveloperPattern.find(html)?.groupValues?.get(1)?.trim()
            ?: ""

        val intro = FullDescPattern.find(html)?.groupValues?.get(1)?.cleanHtmlText()
            ?.removePrefix("应用详情：")
            ?.trim()
            .orEmpty()
            .ifBlank {
                ShortDescPattern.find(html)?.groupValues?.get(1)?.cleanHtmlText()
                    ?.removePrefix("应用详情：")
                    ?.trim()
                    .orEmpty()
            }

        val screenshots = ScreenshotPattern.findAll(html)
            .map { it.groupValues[1].trim() }
            .filter { it.startsWith("http") }
            .distinct()
            .toList()

        val downloadUrl = DownloadUrlPattern.find(html)?.groupValues?.get(1)
            ?.replace("&amp;", "&")
            .orEmpty()
        val privacyUrl = PrivacyPattern.find(html)?.groupValues?.get(1).orEmpty()

        return X360Detail(
            appId = fallbackAppId,
            packageName = realPackage,
            displayName = name,
            versionName = versionName,
            publisherName = developer,
            icon = icon,
            rating = rating,
            downloadCount = downloadCount,
            size = size,
            introduction = intro,
            updateTime = updateTime,
            privacyUrl = privacyUrl,
            screenshots = screenshots,
            downloadUrl = downloadUrl,
        )
    }
}

/** 「95487万人」这类带单位的下载量。 */
private fun parseWanCount(raw: String): Long {
    val clean = raw.trim().removeSuffix("下载").removeSuffix("人")
    return when {
        clean.endsWith("亿") -> (clean.removeSuffix("亿").toDoubleOrNull() ?: 0.0).let { (it * 100_000_000).toLong() }
        clean.endsWith("万") -> (clean.removeSuffix("万").toDoubleOrNull() ?: 0.0).let { (it * 10_000).toLong() }
        else -> clean.toDoubleOrNull()?.toLong() ?: 0L
    }
}

/** 360 详情页「73.46Mb」这类大小。 */
private fun parseDisplaySize(raw: String): Long {
    val clean = raw.trim()
    val upper = clean.uppercase()
    return when {
        upper.endsWith("GB") -> ((clean.dropLast(2).toDoubleOrNull() ?: 0.0) * 1024 * 1024 * 1024).toLong()
        upper.endsWith("MB") -> ((clean.dropLast(2).toDoubleOrNull() ?: 0.0) * 1024 * 1024).toLong()
        upper.endsWith("KB") -> ((clean.dropLast(2).toDoubleOrNull() ?: 0.0) * 1024).toLong()
        else -> 0L
    }
}

/** 「2026-10-21 10:32:42」按东八区解析。 */
internal fun x360TimeMillis(raw: String): Long {
    val text = raw.trim()
    if (text.isBlank()) return 0L
    return runCatching {
        LocalDateTime.parse(text.replace(' ', 'T'))
            .toInstant(TimeZone.of("Asia/Shanghai"))
            .toEpochMilliseconds()
    }.getOrElse {
        debugLog("X360") { "unparsed update time: $raw" }
        0L
    }
}

internal fun String.cleanHtmlText(): String = replace("&nbsp;", " ")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(HtmlTag, "")
    .trim()

/** 复用 Ktor 的百分号编码，避免自写实现对中文和空格处理不一致。 */
private fun encodeQuery(raw: String): String = raw.encodeURLParameter(spaceToPlus = false)

internal fun x360DownloadMeta(
    appId: Long,
    packageName: String,
    displayName: String,
    versionName: String,
    versionCode: Long,
    icon: String,
    downloadUrl: String,
    size: Long,
): DownloadMeta = DownloadMeta(
    appId = appId,
    packageName = packageName,
    displayName = displayName,
    versionName = versionName,
    versionCode = versionCode,
    url = downloadUrl,
    size = size,
    parts = listOf(DownloadPart(name = "", type = "base", url = downloadUrl, size = size, hash = "")),
    icon = icon,
    source = AppSource.X360,
)
