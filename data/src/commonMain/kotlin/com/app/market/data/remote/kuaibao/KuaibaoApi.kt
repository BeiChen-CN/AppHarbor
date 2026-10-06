package com.app.market.data.remote.kuaibao

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
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlin.math.pow
import kotlin.random.Random

internal data class KuaibaoApiConfig(
    val apiBase: String = "https://www.3839.com",
    val mobileBase: String = "https://m.3839.com",
)

private const val SearchPath = "/app/hykb_web/index.php?m=search&ac=searchjson"
private const val CommentPath = "/app/comment.php"
private const val WebUserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
private const val MobileUserAgent =
    "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
private const val MaxSearchPage = 99

/** 详情页内嵌的下载信息（downInfo JS 对象）。 */
internal data class KuaibaoDownInfo(
    val appId: Long,
    val downloadUrl: String,
    val packageName: String,
    val displayName: String,
    val icon: String,
    val md5: String,
)

/** PC 版详情页「更新日志」里的一条版本记录（新版本在前）。 */
internal data class KuaibaoVersionEntry(
    val versionName: String,
    val dateText: String,
    val changeLog: String,
) {
    fun epochMillis(): Long = runCatching {
        LocalDate.parse(dateText.take(10))
            .atStartOfDayIn(TimeZone.of("Asia/Shanghai")).toEpochMilliseconds()
    }.getOrDefault(0L)
}

/** 快爆评分信息（评论接口 star_info）：10 分制均分 + 评价总数。 */
internal data class KuaibaoRating(
    val ratingScore: Double,
    val commentCount: Long,
)

internal class KuaibaoApi(
    private val client: HttpClient,
    private val config: KuaibaoApiConfig = KuaibaoApiConfig(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun search(keyword: String, page: Int): SearchPage {
        val body = urlEncodeParameters(
            mapOf(
                "p" to (page + 1).toString(),
                "word" to keyword,
                "r" to Random.nextDouble().toString(),
            ),
        )
        val response = client.post(config.apiBase.trimEnd('/') + SearchPath) {
            contentType(ContentType.Application.FormUrlEncoded)
            header(HttpHeaders.UserAgent, WebUserAgent)
            header(HttpHeaders.Referrer, config.apiBase.trimEnd('/') + "/search.php")
            header("X-Requested-With", "XMLHttpRequest")
            setBody(body)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("KuaibaoApi") { "HTTP ${response.status.value} search: ${text.take(200)}" }
            throw MarketException("好游快爆服务器返回异常状态 HTTP ${response.status.value}")
        }
        return parseKuaibaoSearchResponse(text, page)
    }

    suspend fun appDetail(appId: Long): AppDetail {
        if (appId <= 0L) throw MarketException("好游快爆缺少站内应用 id")
        return coroutineScope {
            // 移动版页面提供 downInfo / 适龄；PC 版页面提供版本号与更新日志；评论接口提供评分与评价数
            val mobileDeferred = async { fetchDetailPage(appId) }
            val pcDeferred = async { runCatching { fetchPcDetailPage(appId) }.getOrDefault("") }
            val ratingDeferred = async { runCatching { fetchRating(appId) }.getOrNull() }
            val html = mobileDeferred.await()
            val downInfo = parseKuaibaoDownInfo(html)
                ?: throw MarketException("好游快爆未提供该应用的下载信息")
            val parsed = parseKuaibaoDetail(html, appId, downInfo)
            val current = parseKuaibaoVersionEntries(pcDeferred.await()).firstOrNull()
            val rating = ratingDeferred.await()
            val withVersion = if (current != null) {
                parsed.copy(
                    app = parsed.app.copy(
                        versionName = current.versionName,
                        changeLog = current.changeLog.ifBlank { parsed.changeLog },
                    ),
                    changeLog = current.changeLog.ifBlank { parsed.changeLog },
                    updateTime = current.epochMillis(),
                )
            } else {
                parsed
            }
            val withRating = withVersion.copy(
                app = withVersion.app.copy(
                    ratingScore = rating?.ratingScore ?: withVersion.app.ratingScore,
                ),
                commentCount = rating?.commentCount ?: withVersion.commentCount,
            )
            val size = sizeOf(downInfo.downloadUrl)
            withRating.copy(app = withRating.app.copy(apkSize = size))
        }
    }

    /** 评分来自评论接口的 star_info（静态页面里的评分值由 JS 动态填充，抓不到）。 */
    private suspend fun fetchRating(appId: Long): KuaibaoRating? {
        val query = urlEncodeParameters(
            mapOf(
                "ac" to "get_comment_list",
                "m" to "pc",
                "v" to "1.0",
                "fid" to appId.toString(),
                "pid" to "1",
                "page" to "1",
                "limit" to "1",
                "list_type" to "all",
                "sort" to "default",
                "customize_tag_id" to "0",
                "get_recommend" to "1",
            ),
        )
        val response = client.get(config.apiBase.trimEnd('/') + CommentPath + "?" + query) {
            header(HttpHeaders.UserAgent, WebUserAgent)
            header(HttpHeaders.Referrer, config.apiBase.trimEnd('/') + "/a/$appId.htm")
            header("X-Requested-With", "XMLHttpRequest")
        }
        if (!response.status.isSuccess()) return null
        return parseKuaibaoRating(response.bodyAsText())
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        if (app.appId <= 0L) throw MarketException("好游快爆缺少站内应用 id")
        val html = fetchDetailPage(app.appId)
        val downInfo = parseKuaibaoDownInfo(html)
            ?: throw MarketException("好游快爆未提供该应用的下载信息")
        if (downInfo.downloadUrl.isBlank()) throw MarketException("好游快爆未提供该版本的下载地址")
        val size = sizeOf(downInfo.downloadUrl).takeIf { it > 0L } ?: app.apkSize
        val url = normalizeKuaibaoUrl(downInfo.downloadUrl)
        val part = DownloadPart(name = "", type = "base", url = url, size = size, hash = downInfo.md5)
        return DownloadMeta(
            appId = app.appId,
            packageName = downInfo.packageName.ifBlank { app.packageName },
            displayName = downInfo.displayName.ifBlank { app.displayName },
            versionName = app.versionName,
            versionCode = app.versionCode,
            url = url,
            size = size,
            parts = listOf(part),
            icon = downInfo.icon.ifBlank { app.icon },
            changeLog = app.changeLog,
            requestHeaders = mapOf(
                HttpHeaders.UserAgent to WebUserAgent,
                HttpHeaders.Referrer to config.apiBase.trimEnd('/') + "/",
            ),
            source = AppSource.KUAIBAO,
        )
    }

    suspend fun appComments(app: MarketAppInfo): AppComments {
        if (app.appId <= 0L) throw MarketException("好游快爆缺少站内应用 id")
        val query = urlEncodeParameters(
            mapOf(
                "ac" to "get_comment_list",
                "m" to "pc",
                "v" to "1.0",
                "fid" to app.appId.toString(),
                "pid" to "1",
                "page" to "1",
                "limit" to KuaibaoCommentLimit.toString(),
                "list_type" to "all",
                "sort" to "default",
                "customize_tag_id" to "0",
                "get_recommend" to "1",
            ),
        )
        val response = client.get(config.apiBase.trimEnd('/') + CommentPath + "?" + query) {
            header(HttpHeaders.UserAgent, WebUserAgent)
            header(HttpHeaders.Referrer, config.apiBase.trimEnd('/') + "/a/${app.appId}.htm")
            header("X-Requested-With", "XMLHttpRequest")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("KuaibaoApi") { "HTTP ${response.status.value} comments: ${text.take(200)}" }
            throw MarketException("好游快爆服务器返回异常状态 HTTP ${response.status.value}")
        }
        return parseKuaibaoComments(text)
    }

    private suspend fun fetchDetailPage(appId: Long): String {
        val response = client.get(config.mobileBase.trimEnd('/') + "/a/$appId.htm") {
            header(HttpHeaders.UserAgent, MobileUserAgent)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("KuaibaoApi") { "HTTP ${response.status.value} detail $appId" }
            throw MarketException("好游快爆服务器返回异常状态 HTTP ${response.status.value}")
        }
        return text
    }

    private suspend fun fetchPcDetailPage(appId: Long): String {
        val response = client.get(config.apiBase.trimEnd('/') + "/a/$appId.htm") {
            header(HttpHeaders.UserAgent, WebUserAgent)
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("KuaibaoApi") { "HTTP ${response.status.value} pc detail $appId" }
            throw MarketException("好游快爆服务器返回异常状态 HTTP ${response.status.value}")
        }
        return text
    }

    /** 通过 HEAD 请求获取 APK 实际大小；失败时返回 0 交由上层回退。 */
    private suspend fun sizeOf(url: String): Long = runCatching {
        val response = client.head(normalizeKuaibaoUrl(url)) {
            header(HttpHeaders.UserAgent, WebUserAgent)
        }
        response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L
    }.getOrDefault(0L)
}

internal fun normalizeKuaibaoUrl(url: String): String =
    when {
        url.startsWith("https://") || url.startsWith("http://") -> url
        url.startsWith("//") -> "https:$url"
        else -> url
    }

internal fun parseKuaibaoSearchResponse(text: String, page: Int): SearchPage {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: return SearchPage(emptyList(), hasMore = false)
    val fragment = root["result"]?.jsonPrimitive?.contentOrNull.orEmpty()
    val items = parseKuaibaoSearchFragment(fragment)
    val hasMore = root["nextpage"]?.jsonPrimitive?.booleanOrNull
        ?: (items.isNotEmpty() && page < MaxSearchPage)
    return SearchPage(items = items, hasMore = hasMore)
}

internal fun parseKuaibaoSearchFragment(fragment: String): List<MarketAppInfo> =
    KuaibaoItemRegex.findAll(fragment).mapNotNull { match ->
        parseKuaibaoSearchItem(match.value)
    }.toList()

private val KuaibaoItemRegex = Regex("""<li[^>]*>[\s\S]*?</li>""")
private val KuaibaoAppIdRegex = Regex("""/a/(\d+)\.htm""")
private val KuaibaoIconRegex = Regex("""<img[^>]+src="([^"]+)"""")
private val KuaibaoNameRegex = Regex("""sp-name">([^<]+)""")
private val KuaibaoStarRegex = Regex("""star-bar-a"[^>]*width:\s*(\d+)""")
private val KuaibaoDescRegex = Regex("""list-1-3">([\s\S]*?)</p>""")
private val KuaibaoStatsRegex = Regex("""list-1-4">([\s\S]*?)</p>""")
private val KuaibaoTagListRegex = Regex("""list-1-5">([\s\S]*?)(?:</div>|</li>)""")
private val KuaibaoSpanRegex = Regex("""<span>([^<]+)</span>""")
private val KuaibaoDownloadsRegex = Regex("""([\d.]+)(万)?下载""")
private val KuaibaoSizePartRegex = Regex("""([\d.]+)\s*([KMGT])B?""")

private fun parseKuaibaoSearchItem(item: String): MarketAppInfo? {
    val appId = KuaibaoAppIdRegex.find(item)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    val displayName = KuaibaoNameRegex.find(item)?.groupValues?.get(1)?.trim().orEmpty()
    if (displayName.isBlank()) return null
    val icon = normalizeKuaibaoUrl(KuaibaoIconRegex.find(item)?.groupValues?.get(1).orEmpty())
    val rating = KuaibaoStarRegex.find(item)?.groupValues?.get(1)?.toIntOrNull()
        ?.let { (it / 20.0).coerceIn(0.0, 5.0) } ?: 0.0
    val description = KuaibaoDescRegex.find(item)?.groupValues?.get(1)
        ?.let(::kuaibaoPlainText).orEmpty()
    val stats = KuaibaoStatsRegex.find(item)?.groupValues?.get(1)
        ?.let(::kuaibaoPlainText).orEmpty()
    val downloadCount = parseKuaibaoDownloadCount(stats)
    val apkSize = parseKuaibaoSize(stats)
    val tags = KuaibaoTagListRegex.find(item)?.groupValues?.get(1)
        ?.let { KuaibaoSpanRegex.findAll(it).mapNotNull { span -> span.groupValues[1].trim().takeIf(String::isNotEmpty) }.toList() }
        .orEmpty()
    return MarketAppInfo(
        appId = appId,
        packageName = "",
        displayName = displayName,
        publisherName = "",
        versionName = "",
        versionCode = 0L,
        icon = icon,
        apkSize = apkSize,
        ratingScore = rating,
        changeLog = "",
        openLink = "https://www.3839.com/a/$appId.htm",
        source = AppSource.KUAIBAO,
        category = tags.firstOrNull().orEmpty(),
        downloadCount = downloadCount,
    )
}

private fun parseKuaibaoDownloadCount(stats: String): Long {
    val match = KuaibaoDownloadsRegex.find(stats) ?: return 0L
    val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
    val magnitude = if (match.groupValues[2] == "万") 10_000.0 else 1.0
    return (value * magnitude).toLong()
}

private fun parseKuaibaoSize(stats: String): Long {
    val part = stats.split('|').firstNotNullOfOrNull { segment ->
        KuaibaoSizePartRegex.matchEntire(segment.trim())
    } ?: return 0L
    val value = part.groupValues[1].toDoubleOrNull() ?: return 0L
    val unit = when (part.groupValues[2]) {
        "K" -> 1024.0
        "M" -> 1024.0 * 1024
        "G" -> 1024.0 * 1024 * 1024
        "T" -> 1024.0 * 1024 * 1024 * 1024
        else -> return 0L
    }
    return (value * unit).toLong()
}

internal fun parseKuaibaoDownInfo(html: String): KuaibaoDownInfo? {
    val raw = KuaibaoDownInfoRegex.find(html)?.groupValues?.get(1) ?: return null
    val json = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
    fun field(name: String): String = json[name]?.jsonPrimitive?.contentOrNull.orEmpty()
    val appId = field("kb_id").toLongOrNull() ?: return null
    return KuaibaoDownInfo(
        appId = appId,
        downloadUrl = field("apkurl"),
        packageName = field("package"),
        displayName = field("appname"),
        icon = normalizeKuaibaoUrl(field("icon")),
        md5 = field("md5"),
    )
}

private val KuaibaoDownInfoRegex = Regex("""downInfo\s*=\s*(\{[^{}]*\})""")

private val KuaibaoCommentLimit = 20

/** PC 版更新日志条目：<div class="lb-info">版本  x.y.z  yyyy-MM-dd</div><div class="lb-text"><p>…</p></div> */
private val KuaibaoVersionEntryRegex = Regex(
    """<div class="lb-info">\s*版本\s+([\d.]+)\s+(\d{4}-\d{2}-\d{2})\s*</div>\s*<div class="lb-text">\s*<p>([\s\S]*?)</p>""",
)

internal fun parseKuaibaoVersionEntries(html: String): List<KuaibaoVersionEntry> =
    KuaibaoVersionEntryRegex.findAll(html).map { match ->
        KuaibaoVersionEntry(
            versionName = match.groupValues[1].trim(),
            dateText = match.groupValues[2].trim(),
            changeLog = kuaibaoPlainText(match.groupValues[3]),
        )
    }.toList()

internal fun parseKuaibaoComments(text: String): AppComments {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        ?: return AppComments(emptyList(), 0L)
    // code=100 表示成功；出错时返回空列表而不是抛错，评论失败不应阻断详情页
    if (root["code"]?.jsonPrimitive?.intOrNull != 100) return AppComments(emptyList(), 0L)
    val result = root["result"]?.jsonObject ?: return AppComments(emptyList(), 0L)
    val totalCount = result["count"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: result["count"]?.jsonPrimitive?.longOrNull ?: 0L
    val data = result["data"]?.jsonArray ?: return AppComments(emptyList(), totalCount)
    val items = (0 until data.size).mapNotNull { index ->
        val json = data[index] as? JsonObject ?: return@mapNotNull null
        val user = json["user"] as? JsonObject
        val content = json["content"]?.jsonPrimitive?.contentOrNull
            ?.let(::kuaibaoPlainText).orEmpty()
        if (content.isBlank()) return@mapNotNull null
        AppComment(
            userName = user?.get("nickname")?.jsonPrimitive?.contentOrNull.orEmpty()
                .ifBlank { "快爆玩家" },
            content = content,
            score = (json["star"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0)
                .coerceIn(0, 5).toDouble(),
        )
    }
    return AppComments(items = items, totalCount = totalCount)
}

internal fun parseKuaibaoRating(text: String): KuaibaoRating? {
    val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    if (root["code"]?.jsonPrimitive?.intOrNull != 100) return null
    val starInfo = root["result"]?.jsonObject?.get("star_info") as? JsonObject ?: return null
    // star 为 10 分制均分（如 8 → 8.0/10）；未评分时为 0
    val star10 = starInfo["star"]?.jsonPrimitive?.doubleOrNull ?: return null
    if (star10 <= 0.0) return null
    val commentCount = starInfo["star_usernum"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: root["result"]?.jsonObject?.get("count")?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        ?: 0L
    return KuaibaoRating(
        ratingScore = (star10 / 2.0).coerceIn(0.0, 5.0),
        commentCount = commentCount,
    )
}

internal fun parseKuaibaoDetail(html: String, appId: Long, downInfo: KuaibaoDownInfo): AppDetail {
    val displayName = downInfo.displayName.ifBlank {
        KuaibaoTitleRegex.find(html)?.groupValues?.get(1)?.trim().orEmpty()
    }
    val rating = KuaibaoDetailRatingRegex.find(html)?.groupValues?.get(1)
        ?.toDoubleOrNull()?.div(2.0)?.coerceIn(0.0, 5.0) ?: 0.0
    val publisher = kuaibaoFieldValue(html, "开发商").orEmpty()
    val age = kuaibaoFieldValue(html, "适龄范围").orEmpty()
    val introduction = kuaibaoSection(html, "游戏介绍", "更新日志")
    val changeLog = kuaibaoSection(html, "更新日志", "历史日志")
    val tags = KuaibaoSpanRegex.findAll(html).mapNotNull { it.groupValues[1].trim().takeIf(String::isNotEmpty) }.toList()
    val screenshots = parseKuaibaoScreenshots(html)
    return AppDetail(
        app = MarketAppInfo(
            appId = appId,
            packageName = downInfo.packageName,
            displayName = displayName,
            publisherName = publisher,
            versionName = "",
            versionCode = 0L,
            icon = downInfo.icon,
            apkSize = 0L,
            ratingScore = rating,
            changeLog = changeLog,
            openLink = "https://www.3839.com/a/$appId.htm",
            source = AppSource.KUAIBAO,
            category = tags.firstOrNull().orEmpty(),
        ),
        brief = "",
        introduction = introduction,
        changeLog = changeLog,
        category = tags.firstOrNull().orEmpty(),
        ageClassification = age,
        downloadCount = 0L,
        registrationNum = "",
        privacyUrl = "",
        screenshots = screenshots,
        comments = emptyList(),
        sameDeveloperApps = emptyList(),
    )
}

private val KuaibaoTitleRegex = Regex("""<title>([^<]+?)[_-]""")
private val KuaibaoDetailRatingRegex = Regex("""sp-val">([\d.]+)""")
private val KuaibaoScreenshotBlockRegex = Regex("""<div class="sp-img[^"]*">[\s\S]*?</div>""")
private val KuaibaoImgRegex = Regex("""<img[^>]+src="([^"]+)"""")
private val KuaibaoScriptRegex = Regex("""<script[\s\S]*?</script>""", RegexOption.IGNORE_CASE)
private val KuaibaoStyleRegex = Regex("""<style[\s\S]*?</style>""", RegexOption.IGNORE_CASE)
private val KuaibaoBreakRegex = Regex("""<\s*br\s*/?\s*>""", RegexOption.IGNORE_CASE)
private val KuaibaoTagStripRegex = Regex("""<[^>]+>""")

/** 提取 [startMark] 之后到 [endMark] 之前的可见文本（游戏介绍 / 更新日志等分区）。 */
internal fun kuaibaoSection(html: String, startMark: String, endMark: String): String {
    val start = html.indexOf(startMark)
    if (start < 0) return ""
    val bodyStart = start + startMark.length
    val end = html.indexOf(endMark, bodyStart).takeIf { it > bodyStart } ?: html.length
    val cleaned = html.substring(bodyStart, end)
        .replace(KuaibaoScriptRegex, "")
        .replace(KuaibaoStyleRegex, "")
        .replace(KuaibaoBreakRegex, "\n")
        .replace(KuaibaoTagStripRegex, "")
    return cleaned.lines()
        .map { it.replace("&nbsp;", " ").trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") && it != "更多" && it != "展开" }
        .joinToString("\n")
        .trim()
}

private fun kuaibaoFieldValue(html: String, label: String): String? =
    Regex("""$label[\s\S]{0,300}?>([^<>]{1,60})<""").find(html)
        ?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

internal fun parseKuaibaoScreenshots(html: String): List<AppScreenshot> =
    KuaibaoScreenshotBlockRegex.findAll(html).mapNotNull { block ->
        val url = normalizeKuaibaoUrl(KuaibaoImgRegex.find(block.value)?.groupValues?.get(1).orEmpty())
        if (url.isBlank()) null else AppScreenshot(
            url = url,
            expandedUrl = url,
            orientation = ScreenshotOrientation.PORTRAIT,
        )
    }.toList()

internal fun kuaibaoPlainText(html: String): String =
    html.replace(KuaibaoBreakRegex, "\n")
        .replace(KuaibaoTagStripRegex, "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .trim()
