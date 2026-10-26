package com.app.market.data.remote.shoudji

import com.app.market.data.platform.debugLog
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPart
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppScreenshot
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.ScreenshotOrientation
import com.app.market.domain.model.market.SearchPage
import io.ktor.client.HttpClient
import io.ktor.client.request.cookie
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class ShoudjiApiConfig(
    val webBaseUrl: String = "https://www.shouji.com.cn",
    val mobileBaseUrl: String = "https://m.shouji.com.cn",
    /** 阿里云 ESA 的 acw_sc__v2 挑战最多重放轮数。 */
    val challengeRetries: Int = 8,
    /** 两轮挑战之间的等待，服务端按时间窗签发新挑战。 */
    val challengeDelayMillis: Long = 900L,
    /** 搜索时并发解析下载信息的条目数。 */
    val searchConcurrency: Int = 4,
    /** 单次搜索最多解析多少条结果。 */
    val searchLimit: Int = 15,
)

private const val UserAgentDesktop =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private const val UserAgentMobile =
    "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

/** 手机乐园站内搜索的 SERP 结果项：链接里带数字 id。 */
private val SearchResultPattern = Regex(
    """<div class="result f s0"\s*>\s*<h3 class="c-title">\s*<a[^>]*href="https://www\.shouji\.com\.cn/down/(\d+)\.html"[^>]*>(.*?)</a>""",
    setOf(RegexOption.DOT_MATCHES_ALL),
)

private val TitlePattern = Regex("""<title>([^<]*)</title>""")
private val TagPattern = Regex("<[^>]+>")

/** 详情页 `#infocont` 的图标。 */
private val IconPattern = Regex("""<div class="imgBox">\s*<img src="([^"]+)"""")

/** 详情页 `#swiperContont` 的截图列表。 */
private val ScreenshotBlockPattern =
    Regex("""<div class="img_item Qimgs" id="swiperContont">([\s\S]*?)</div>""")

private val ScreenshotPattern = Regex("""<img src="([^"]+)"""")

/** 详情页 `#detailCount` 的正文，结束标记是下方的「展开」脚注。 */
private val IntroPattern = Regex(
    """<div class="txtcont" id="detailCount">([\s\S]*?)</div>\s*<div class="txt_foot">""",
)

/** acw_sc__v2 挑战页里的种子串。 */
private val ChallengeArgPattern = Regex("""arg1\s*=\s*'([0-9A-Fa-f]{40})'""")

internal class ShoudjiApi(
    private val client: HttpClient,
    private val config: ShoudjiApiConfig = ShoudjiApiConfig(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(keyword: String, page: Int = 0): SearchPage {
        val query = keyword.trim()
        if (query.isBlank()) return SearchPage(items = emptyList(), hasMore = false)
        // 站内搜索没有分页链接，只提供首屏结果。
        if (page > 0) return SearchPage(items = emptyList(), hasMore = false)

        val url = "${config.webBaseUrl}/search/?q=" + encodeQuery(query)
        val html = fetchText(url, UserAgentDesktop)
            ?: throw MarketException("手机乐园搜索失败，请稍后重试或切换来源")

        val hits = SearchResultPattern.findAll(html)
            .mapNotNull { match ->
                val id = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val title = match.groupValues[2].stripTags()
                id to title
            }
            .distinctBy { it.first }
            .take(config.searchLimit)
            .toList()

        if (hits.isEmpty()) return SearchPage(items = emptyList(), hasMore = false)

        val semaphore = Semaphore(config.searchConcurrency)
        val apps = coroutineScope {
            hits.map { (id, title) ->
                async {
                    semaphore.withPermit { resolveSearchHit(id, title) }
                }
            }.awaitAll().filterNotNull()
        }

        return SearchPage(items = apps, hasMore = false)
    }

    /**
     * 搜索结果本身只有标题，包名与下载信息要问 `/downs/detail`。
     * 部分条目没有包名（多为搬运站外安装包），这类无法作为商店条目使用，直接跳过。
     */
    private suspend fun resolveSearchHit(id: Long, title: String): MarketAppInfo? {
        val info = fetchDownloadInfo(id) ?: return null
        val packageName = info.packageName.trim()
        if (packageName.isBlank()) {
            debugLog("Shoudji") { "search hit $id has no package name, skipped" }
            return null
        }
        return MarketAppInfo(
            appId = id,
            packageName = packageName,
            displayName = info.displayName.ifBlank { title.substringBefore('下') },
            publisherName = "",
            versionName = info.versionName,
            versionCode = 0L,
            icon = info.icon,
            apkSize = info.size,
            ratingScore = 0.0,
            openLink = "${config.webBaseUrl}/down/$id.html",
            source = AppSource.SHOUDJI,
            category = info.category,
        )
    }

    suspend fun appDetail(appId: Long, packageName: String): AppDetail {
        if (appId <= 0L) throw MarketException("手机乐园缺少应用 id，无法加载详情")
        val url = "${config.mobileBaseUrl}/down/$appId.html"
        val html = fetchText(url, UserAgentMobile)
            ?: throw MarketException("手机乐园详情加载失败，请稍后重试")

        val title = TitlePattern.find(html)?.groupValues?.get(1)?.stripTags().orEmpty()
        val baseInfo = parseBaseInfo(html)
        val icon = IconPattern.find(html)?.groupValues?.get(1).orEmpty()
        val intro = IntroPattern.find(html)?.groupValues?.get(1)?.stripTags()
            ?.replace(Regex("\n{3,}"), "\n\n")
            ?.trim()
            .orEmpty()
        val screenshots = ScreenshotBlockPattern.find(html)?.groupValues?.get(1)
            ?.let { block ->
                ScreenshotPattern.findAll(block).map { it.groupValues[1] }.toList()
            }
            .orEmpty()
            .filter { it.startsWith("http") }
            .distinct()

        val resolvedPackage = baseInfo.packageName.ifBlank { packageName }
        val displayName = title.substringBefore('下').ifBlank { title }

        val app = MarketAppInfo(
            appId = appId,
            packageName = resolvedPackage,
            displayName = displayName,
            publisherName = baseInfo.publisher,
            versionName = baseInfo.versionName,
            versionCode = 0L,
            icon = icon,
            apkSize = baseInfo.size,
            ratingScore = 0.0,
            openLink = "${config.webBaseUrl}/down/$appId.html",
            source = AppSource.SHOUDJI,
            category = baseInfo.category,
        )

        return AppDetail(
            app = app,
            brief = intro.take(120),
            introduction = intro,
            changeLog = "",
            category = baseInfo.category,
            ageClassification = "",
            downloadCount = 0L,
            registrationNum = "",
            updateTime = baseInfo.updateTime,
            privacyUrl = "",
            screenshots = screenshots.map { url ->
                AppScreenshot(url = url, orientation = ScreenshotOrientation.PORTRAIT)
            },
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        if (app.appId <= 0L) throw MarketException("手机乐园缺少应用 id，无法下载")
        val info = fetchDownloadInfo(app.appId)
            ?: throw MarketException("手机乐园下载信息解析失败")
        if (info.url.isBlank()) throw MarketException("手机乐园未提供该应用的下载地址")

        val size = info.size.takeIf { it > 0L } ?: app.apkSize
        return DownloadMeta(
            appId = app.appId,
            packageName = info.packageName.ifBlank { app.packageName },
            displayName = info.displayName.ifBlank { app.displayName },
            versionName = info.versionName.ifBlank { app.versionName },
            versionCode = app.versionCode,
            url = info.url,
            size = size,
            parts = listOf(DownloadPart(name = "", type = "base", url = info.url, size = size, hash = "")),
            icon = info.icon.ifBlank { app.icon },
            requestHeaders = mapOf(
                HttpHeaders.UserAgent to UserAgentMobile,
                HttpHeaders.Referrer to "${config.mobileBaseUrl}/",
            ),
            source = AppSource.SHOUDJI,
        )
    }

    /** `/downs/detail/<id>/2` 同时给出包名、版本、大小与直链。 */
    private suspend fun fetchDownloadInfo(id: Long): ShoudjiDownloadInfo? {
        val url = "${config.mobileBaseUrl}/downs/detail/$id/2"
        val text = fetchText(url, UserAgentMobile) ?: return null
        if (text.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
        val data = root["data"] as? JsonObject ?: return null
        return ShoudjiDownloadInfo(
            displayName = data.stringOrEmpty("name"),
            packageName = data.stringOrEmpty("and_pkgname"),
            versionName = data.stringOrEmpty("and_ver"),
            icon = data.stringOrEmpty("icon"),
            category = data.stringOrEmpty("type"),
            url = data.stringOrEmpty("and_url"),
            // and_size_format 已是字节；and_size + and_unit 需要换算。
            size = data.longOrZero("and_size_format")
                .takeIf { it > 0L }
                ?: data.doubleOrZero("and_size").let { raw ->
                    when (data.stringOrEmpty("and_unit")) {
                        "3" -> (raw * 1024).toLong()
                        "2" -> (raw * 1024 * 1024).toLong()
                        else -> raw.toLong()
                    }
                },
        )
    }

    private fun parseBaseInfo(html: String): ShoudjiBaseInfo {
        fun value(label: String): String {
            val pattern = Regex(""">\s*$label：(?:<[^>]*>)*([^<]*)""")
            return pattern.find(html)?.groupValues?.get(1)?.stripTags().orEmpty()
        }

        return ShoudjiBaseInfo(
            category = value("分类"),
            size = parseDisplaySize(value("大小")),
            versionName = value("版本"),
            updateTime = shoudjiTimeMillis(value("更新时间")),
            packageName = value("包名"),
            publisher = value("厂商").takeUnless { it == "暂无资料" }.orEmpty(),
        )
    }

    /**
     * 手机乐园所有页面都挂在阿里云 ESA 边缘，命中风控时返回 40 位十六进制种子串的 JS 挑战页。
     * 这里按种子算出 `acw_sc__v2` 后重放；挑战会连续下发多次，必须循环直到拿到真实页面。
     */
    private suspend fun fetchText(url: String, userAgent: String): String? {
        // acw_tc / cdn_sec_tc 由 Ktor 的 cookie 存储维护，这里只补算出来的 acw_sc__v2。
        var challengeCookie: String? = null
        repeat(config.challengeRetries) { attempt ->
            if (attempt > 0) delay(config.challengeDelayMillis)
            val response = runCatching {
                client.get(url) {
                    header(HttpHeaders.UserAgent, userAgent)
                    header(HttpHeaders.Accept, "*/*")
                    if (challengeCookie != null) cookie(AcwCookieName, challengeCookie!!)
                }
            }.getOrNull() ?: return@repeat
            if (!response.status.isSuccess()) return@repeat
            val body = runCatching { response.bodyAsText() }.getOrNull() ?: return@repeat
            val seed = ChallengeArgPattern.find(body)?.groupValues?.get(1)
            if (seed != null && body.length < ChallengeBodyLimit) {
                challengeCookie = acwScV2(seed.uppercase()).takeIf { it.isNotBlank() }
                return@repeat
            }
            return body
        }
        debugLog("Shoudji") { "challenge not cleared: $url" }
        return null
    }
}

private const val AcwCookieName = "acw_sc__v2"

/** 挑战页体积远小于正常页面，用它区分「挑战页」与「真页面」。 */
private const val ChallengeBodyLimit = 6_000

internal data class ShoudjiDownloadInfo(
    val displayName: String,
    val packageName: String,
    val versionName: String,
    val icon: String,
    val category: String,
    val url: String,
    val size: Long,
)

internal data class ShoudjiBaseInfo(
    val category: String,
    val size: Long,
    val versionName: String,
    val updateTime: Long,
    val packageName: String,
    val publisher: String,
)

private fun String.stripTags(): String = replace("&nbsp;", " ")
    .replace(TagPattern, "")
    .replace("&amp;", "&")
    .replace("&quot;", "\"")
    .trim()

private fun JsonObject.stringOrEmpty(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull.orEmpty()

private fun JsonObject.longOrZero(key: String): Long =
    this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()?.toLong() ?: 0L

private fun JsonObject.doubleOrZero(key: String): Double =
    this[key]?.jsonPrimitive?.doubleOrNull
        ?: this[key]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull()
        ?: 0.0

/** 「252.68M」这类展示用大小。 */
internal fun parseDisplaySize(raw: String): Long {
    val clean = raw.trim()
    if (clean.isEmpty()) return 0L
    val upper = clean.uppercase()
    return when {
        upper.endsWith("GB") || upper.endsWith("G") ->
            ((clean.dropLastWhile { it.isLetter() }.toDoubleOrNull() ?: 0.0) * 1024 * 1024 * 1024).toLong()

        upper.endsWith("MB") || upper.endsWith("M") ->
            ((clean.dropLastWhile { it.isLetter() }.toDoubleOrNull() ?: 0.0) * 1024 * 1024).toLong()

        upper.endsWith("KB") || upper.endsWith("K") ->
            ((clean.dropLastWhile { it.isLetter() }.toDoubleOrNull() ?: 0.0) * 1024).toLong()

        else -> clean.toDoubleOrNull()?.toLong() ?: 0L
    }
}

/** 「2026/04/29 14:11」这类站点时间，按东八区解析。 */
internal fun shoudjiTimeMillis(raw: String): Long {
    val text = raw.trim().replace('/', '-')
    if (text.isBlank()) return 0L
    val normalized = when (text.count { it == ':' }) {
        0 -> "$text 00:00:00"
        1 -> "$text:00"
        else -> text
    }
    return runCatching {
        LocalDateTime.parse(normalized.replace(' ', 'T'))
            .toInstant(TimeZone.of("Asia/Shanghai"))
            .toEpochMilliseconds()
    }.getOrElse {
        debugLog("Shoudji") { "unparsed time: $raw" }
        0L
    }
}

private fun encodeQuery(raw: String): String = raw.encodeURLParameter(spaceToPlus = true)

/**
 * 阿里云 ESA 的 `acw_sc__v2` 反爬算法：
 * 1. 用固定 40 项置换表重排挑战种子；
 * 2. 与固定 40 字节密钥逐字节异或后转小写十六进制。
 */
internal fun acwScV2(seed: String): String {
    if (seed.length != AcwPermutation.size) return ""
    val permuted = CharArray(AcwPermutation.size) { index -> seed[AcwPermutation[index] - 1] }
    val out = StringBuilder(AcwPermutation.size)
    var index = 0
    while (index < permuted.size && index < AcwKey.length) {
        val left = permuted.concatToString(index, index + 2).toIntOrNull(16) ?: return ""
        val right = AcwKey.substring(index, index + 2).toInt(16)
        out.append(((left xor right) and 0xFF).toString(16).padStart(2, '0'))
        index += 2
    }
    return out.toString()
}

private val AcwPermutation = intArrayOf(
    0xf, 0x23, 0x1d, 0x18, 0x21, 0x10, 0x1, 0x26, 0xa, 0x9,
    0x13, 0x1f, 0x28, 0x1b, 0x16, 0x17, 0x19, 0xd, 0x6, 0xb,
    0x27, 0x12, 0x14, 0x8, 0xe, 0x15, 0x20, 0x1a, 0x2, 0x1e,
    0x7, 0x4, 0x11, 0x5, 0x3, 0x1c, 0x22, 0x25, 0xc, 0x24,
)

private const val AcwKey = "3000176000856006061501533003690027800375"
