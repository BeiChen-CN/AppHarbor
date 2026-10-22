package com.app.market.data.remote.taptap

import com.app.market.data.remote.randomHex
import com.app.market.data.remote.urlEncodeParameters
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.download.DownloadPatch
import com.app.market.domain.model.market.AppComment
import com.app.market.domain.model.market.AppComments
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal data class TapTapAppRecord(
    val appId: Long,
    val packageName: String,
    val displayName: String,
    val publisherName: String,
    val versionName: String,
    val versionCode: Long,
    val icon: String,
    val apkSize: Long,
    val apkId: Long,
    val md5: String,
    val rating: Double,
    val changeLog: String,
    val description: String,
    val category: String,
    val downloadCount: Long,
    val commentCount: Long,
    val releasedAt: Long,
)

internal data class TapTapScreenshot(
    val url: String,
    val originalUrl: String,
    val width: Long,
    val height: Long,
)

internal data class TapTapDetailRecord(
    val app: TapTapAppRecord,
    val developerNote: String,
    val privacyUrl: String,
    val registrationName: String,
    val updatedAt: Long,
    val screenshots: List<TapTapScreenshot>,
)

internal data class TapTapApkRecord(
    val url: String,
    val size: Long,
    val md5: String,
    val versionCode: Long,
    val versionName: String,
    val patch: DownloadPatch? = null,
)

internal data class TapTapRecommendationRecord(
    val appId: Long,
    val packageName: String,
    val displayName: String,
    val icon: String,
    val rating: Double,
    val category: String,
    val recommendation: String,
    val description: String,
    val coverImage: String,
)

internal data class TapTapRecommendationPage(
    val items: List<TapTapRecommendationRecord>,
    val nextPage: String,
)

internal data class TapTapApiConfig(
    val baseUrl: String = "https://api.taptapdada.com",
    val webBase: String = "https://www.taptap.cn",
)

private const val SearchPageSize = 10
private const val TapTapVersionName = "2.96.12-rel.100100"
private const val TapTapVersionCode = "296121001"
private const val TapTapUserAgent =
    "TapTap/2.96.12-rel#100100 (com.taptap; build:296121001; Android 16) Okhttp/3.12.1"
private const val TapTapWebUserAgent =
    "Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"
private const val ProtobufContentType =
    "application/x-protobuf; desc=\"https://pbdesc.xdrnd.cn/apis.desc\"; " +
            "messageType=\"apis.clientapi.search.AggSearchV6Request\"; charset=\"utf-8\""
private val HtmlBreak = Regex("<br\\s*[^>]*>", RegexOption.IGNORE_CASE)
private val HtmlTag = Regex("<[^>]+>")
private const val NonceAlphabet = "abcdefghijklmnopqrstuvwxyz0123456789"

@OptIn(ExperimentalTime::class)
internal class TapTapApi(
    private val client: HttpClient,
    private val json: Json,
    private val config: TapTapApiConfig = TapTapApiConfig(),
) {
    private val uid = randomUuid()
    private val launchSessionId = randomUuid()
    private val webUid = randomUuid()
    private val searchSessions = mutableMapOf<String, String>()
    private var recommendationSessionId = ""
    private val xUa = buildString {
        append("V=1&PN=TapTap&VN=").append(TapTapVersionName)
        append("&VN_CODE=").append(TapTapVersionCode)
        append("&LOC=CN&LANG=zh_CN&CH=organic-direct_index")
        append("&UID=").append(uid)
        append("&NT=1&SR=1080x2400&DEB=Android&DEM=AppMarket&OSV=16")
    }
    private val webXUa = "V=1&PN=WebM&LANG=zh_CN&VN_CODE=100000000&LOC=CN&PLT=Android" +
        "&DS=Android&UID=$webUid&OS=Android&OSV=15"

    suspend fun appComments(appId: Long): AppComments {
        if (appId <= 0L) throw MarketException("TapTap 应用 id 无效")
        val query = urlEncodeParameters(
            linkedMapOf(
                "app_id" to appId.toString(),
                "filter_platform" to "",
                "from" to "0",
                "limit" to "10",
                "mapping" to "",
                "session_id" to randomUuid(),
                "sort" to "hot",
                "source_type" to "",
                "stage_type" to "2",
            ),
        )
        val response = client.get("${config.webBase}/webapiv2/review/v2/list-by-app?$query") {
            header(HttpHeaders.UserAgent, TapTapWebUserAgent)
            header("X-UA", webXUa)
        }
        val root = response.jsonObject("评论")
        return parseTapTapComments(root)
    }

    suspend fun search(keyword: String, page: Int): Pair<List<TapTapSearchHit>, Boolean> {
        val normalizedPage = page.coerceAtLeast(0)
        val sessionId = if (normalizedPage == 0) "" else searchSessions[keyword].orEmpty()
        val body = encodeTapTapSearchRequest(
            keyword = keyword,
            offset = normalizedPage * SearchPageSize,
            limit = SearchPageSize,
            sessionId = sessionId,
        )
        val query = urlEncodeParameters(linkedMapOf("X-ENC" to "pb", "X-UA" to xUa))
        val path = "/search/v6/agg-search?$query"
        val timestamp = nowSeconds()
        val nonce = randomHex(8)
        val signature = tapTapProtobufSignature("POST", path, timestamp, nonce, body)
        val response = client.post(config.baseUrl + path) {
            header(HttpHeaders.UserAgent, TapTapUserAgent)
            header(HttpHeaders.ContentType, ProtobufContentType)
            header("X-Tap-Ts", timestamp)
            header("X-Tap-Nonce", nonce)
            header("X-Tap-Sign", signature)
            setBody(body)
        }
        requireSuccess(response, "搜索")
        val parsed = parseTapTapSearchResponse(response.body<ByteArray>())
        if (parsed.sessionId.isNotBlank()) searchSessions[keyword] = parsed.sessionId
        return parsed.hits to parsed.hasMore
    }

    suspend fun apps(packageNames: List<String>): List<TapTapAppRecord> {
        val identifiers = packageNames
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinctBy(String::lowercase)
        if (identifiers.isEmpty()) return emptyList()
        val root = signedForm(
            path = "/app/v1/mini-multi-get",
            values = linkedMapOf("identifiers" to identifiers.joinToString(",")),
            operation = "批量应用信息",
        )
        return root.obj("data")?.array("list")
            .orEmpty()
            .mapNotNull { (it as? JsonObject)?.let(::parseApp) }
    }

    suspend fun recommendations(page: Int, pageSize: Int): Pair<List<TapTapRecommendationRecord>, Boolean> {
        val normalizedPage = page.coerceAtLeast(0)
        val normalizedPageSize = pageSize.coerceAtLeast(1)
        if (normalizedPage == 0) recommendationSessionId = ""
        val values = linkedMapOf(
            "X-UA" to xUa,
            "remain_ram" to "4294967296",
            "launch_session_id" to launchSessionId,
            "remain_rom" to "68719476736",
            "allowed_types" to "satisfaction",
            "from" to (normalizedPage * normalizedPageSize).toString(),
            "limit" to normalizedPageSize.toString(),
        )
        recommendationSessionId.takeIf(String::isNotBlank)?.let { values["session_id"] = it }
        val query = urlEncodeParameters(values)
        val response = client.get("${config.baseUrl}/landing/v9/timeline?$query") {
            header(HttpHeaders.UserAgent, TapTapUserAgent)
        }
        val parsed = parseTapTapRecommendations(response.jsonObject("找游戏推荐"))
        nextPageParameter(parsed.nextPage, "session_id")
            .takeIf(String::isNotBlank)
            ?.let { recommendationSessionId = it }
        return parsed.items to parsed.nextPage.isNotBlank()
    }

    suspend fun detail(appId: Long): TapTapDetailRecord {
        if (appId <= 0L) throw MarketException("TapTap 应用 id 无效")
        val query = urlEncodeParameters(linkedMapOf("id" to appId.toString(), "X-UA" to xUa))
        val response = client.get("${config.baseUrl}/app/v6/detail?$query") {
            header(HttpHeaders.UserAgent, TapTapUserAgent)
        }
        val root = response.jsonObject("应用详情")
        val app = root.obj("data")?.obj("app")
            ?: throw MarketException("TapTap 未返回应用详情")
        val parsed = parseApp(app) ?: throw MarketException("TapTap 未返回有效应用信息")
        val compliance = app.obj("compliance_info")
        val whatsNew = app.obj("apk_whats_new")
        return TapTapDetailRecord(
            app = parsed.copy(
                versionName = whatsNew?.string("version_label")
                    ?.substringBeforeLast(" (")
                    .orEmpty()
                    .ifBlank { parsed.versionName },
                versionCode = whatsNew?.long("version_code")
                    ?.takeIf { it > 0L }
                    ?: parsed.versionCode,
                changeLog = plainText(whatsNew?.obj("whatsnew")?.string("text"))
                    .ifBlank { parsed.changeLog },
            ),
            developerNote = plainText(app.obj("developer_note")?.string("text")),
            privacyUrl = compliance?.string("privacy_policy").orEmpty(),
            registrationName = compliance?.string("developer_legal_name").orEmpty(),
            updatedAt = whatsNew?.long("update_date")?.times(1000L) ?: 0L,
            screenshots = app.array("screenshots").orEmpty().mapNotNull { value ->
                val image = value as? JsonObject ?: return@mapNotNull null
                val url = image.string("url")
                if (url.isBlank()) return@mapNotNull null
                TapTapScreenshot(
                    url = url,
                    originalUrl = image.string("original_url").ifBlank { url },
                    width = image.long("width"),
                    height = image.long("height"),
                )
            },
        )
    }

    suspend fun apk(apkId: Long, apkHash: String = ""): TapTapApkRecord {
        if (apkId <= 0L) throw MarketException("TapTap 暂未提供该应用的安装包")
        val root = signedForm(
            path = "/apk/v1/detail",
            values = linkedMapOf(
                "node" to uid,
                "referer" to "search",
                "end_point" to "d1",
                "auto_download" to "0",
                "sandbox" to "1",
                "abi" to "arm64-v8a",
                "id" to apkId.toString(),
                "screen_densities" to "xxhdpi",
            ).apply {
                // 不声明专有 TapPatch SDK：请求 Jojo 格式，由本项目的 Kotlin 引擎合并。
                if (apkHash.matches(Regex("[a-fA-F0-9]{32}"))) put("apk_hash", apkHash.lowercase())
            },
            operation = "安装包信息",
        )
        val apk = root.obj("data")?.obj("apk")
            ?: throw MarketException("TapTap 未返回安装包信息")
        val url = apk.string("download")
        if (!url.startsWith("https://")) throw MarketException("TapTap 未返回有效下载地址")
        return TapTapApkRecord(
            url = url,
            size = apk.long("size"),
            md5 = apk.string("md5").lowercase(),
            versionCode = apk.long("version_code"),
            versionName = apk.string("version_name"),
            patch = parseTapTapPatch(root.obj("data")?.obj("apk_patch"), apkHash, apk.long("size")),
        )
    }

    private suspend fun signedForm(
        path: String,
        values: LinkedHashMap<String, String>,
        operation: String,
    ): JsonObject {
        values["time"] = nowSeconds()
        values["nonce"] = randomNonce()
        values["sign"] = tapTapFormSignature(values, xUa)
        val query = urlEncodeParameters(linkedMapOf("X-UA" to xUa))
        val response = client.post("${config.baseUrl}$path?$query") {
            header(HttpHeaders.UserAgent, TapTapUserAgent)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(urlEncodeParameters(values))
        }
        return response.jsonObject(operation)
    }

    private suspend fun HttpResponse.jsonObject(operation: String): JsonObject {
        val root = runCatching { json.parseToJsonElement(bodyAsText()) as? JsonObject }.getOrNull()
        if (root == null) {
            requireSuccess(this, operation)
            throw MarketException("TapTap ${operation}返回了无效数据")
        }
        if (root.bool("success") != true) {
            val error = root.obj("data")?.string("error_description")
                .orEmpty()
                .ifBlank { root.obj("data")?.string("msg").orEmpty() }
            throw MarketException("TapTap ${operation}失败${error.takeIf(String::isNotBlank)?.let { "：$it" }.orEmpty()}")
        }
        requireSuccess(this, operation)
        return root
    }

    private fun parseApp(app: JsonObject): TapTapAppRecord? {
        val packageName = app.string("identifier")
        if (packageName.isBlank()) return null
        val download = app.obj("download")
        val apk = download?.obj("apk")
        val stats = app.obj("stat")
        val developer = app.array("developers")
            .orEmpty()
            .mapNotNull { it as? JsonObject }
            .let { items ->
                items.firstOrNull { it.string("type") == "publisher" }
                    ?: items.firstOrNull { it.string("type") == "author" }
                    ?: items.firstOrNull()
            }
        return TapTapAppRecord(
            appId = app.long("id"),
            packageName = packageName,
            displayName = app.string("title").ifBlank { packageName },
            publisherName = developer?.string("name").orEmpty(),
            versionName = apk?.string("version_name").orEmpty(),
            versionCode = apk?.long("version_code") ?: 0L,
            icon = app.obj("icon")?.string("url").orEmpty(),
            apkSize = apk?.long("size") ?: 0L,
            apkId = download?.long("apk_id") ?: 0L,
            md5 = apk?.string("md5").orEmpty().lowercase(),
            rating = stats?.obj("rating")?.double("score") ?: 0.0,
            changeLog = plainText(app.obj("whatsnew")?.string("text")),
            description = plainText(app.obj("description")?.string("text")),
            category = app.array("tags").orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("value")?.takeIf(String::isNotBlank) }
                .joinToString(" / "),
            downloadCount = stats?.long("hits_total") ?: 0L,
            commentCount = stats?.long("review_count") ?: 0L,
            releasedAt = app.long("released_time") * 1000L,
        )
    }

    private fun nowSeconds(): String = Clock.System.now().epochSeconds.toString()

    private fun randomNonce(): String = buildString(5) {
        repeat(5) { append(NonceAlphabet[Random.nextInt(NonceAlphabet.length)]) }
    }

    private fun randomUuid(): String {
        val value = randomHex(16)
        return "${value.take(8)}-${value.substring(8, 12)}-${value.substring(12, 16)}-" +
                "${value.substring(16, 20)}-${value.substring(20)}"
    }
}

internal fun parseTapTapRecommendations(root: JsonObject): TapTapRecommendationPage {
    val data = root.obj("data")
    val items = data?.array("list").orEmpty().mapNotNull { value ->
        val item = value as? JsonObject ?: return@mapNotNull null
        if (item.string("type") != "app") return@mapNotNull null
        val app = item.obj("app") ?: return@mapNotNull null
        val appId = app.long("id")
        val packageName = app.string("identifier")
        if (appId <= 0L || packageName.isBlank()) return@mapNotNull null
        val editor = item.obj("editor_rec_info")
        val cover = editor?.obj("banner") ?: item.obj("banner")
        val labels = item.array("rec_info").orEmpty()
            .mapNotNull { (it as? JsonObject)?.string("label")?.takeIf(String::isNotBlank) }
        val recommendation = editor?.string("rec_text")
            .orEmpty()
            .ifBlank { app.string("rec_text") }
            .ifBlank { labels.joinToString(" · ") }
        TapTapRecommendationRecord(
            appId = appId,
            packageName = packageName,
            displayName = app.string("title").ifBlank { packageName },
            icon = app.obj("icon")?.string("url").orEmpty(),
            rating = app.double("score"),
            category = app.array("tags").orEmpty()
                .mapNotNull { (it as? JsonObject)?.string("value")?.takeIf(String::isNotBlank) }
                .joinToString(" / "),
            recommendation = recommendation,
            description = plainText(app.obj("description")?.string("text")),
            coverImage = cover?.string("large_url")
                .orEmpty()
                .ifBlank { cover?.string("url").orEmpty() }
                .ifBlank { cover?.string("original_url").orEmpty() },
        )
    }
    return TapTapRecommendationPage(
        items = items,
        nextPage = data?.string("next_page").orEmpty(),
    )
}

internal fun parseTapTapComments(root: JsonObject): AppComments {
    val data = root.obj("data") ?: return AppComments(emptyList(), 0L)
    val items = data.array("list").orEmpty().mapNotNull { value ->
        val item = value as? JsonObject ?: return@mapNotNull null
        val moment = item.obj("moment") ?: return@mapNotNull null
        val review = moment.obj("review") ?: return@mapNotNull null
        val content = plainText(review.obj("contents")?.string("text"))
        if (content.isBlank()) return@mapNotNull null
        AppComment(
            userName = moment.obj("author")?.obj("user")?.string("name")
                ?.takeIf(String::isNotBlank) ?: "TapTap 用户",
            content = content,
            score = review.double("score").coerceIn(0.0, 5.0),
        )
    }
    val total = data.long("total").takeIf { it > 0L } ?: items.size.toLong()
    return AppComments(items = items, totalCount = total)
}

private fun nextPageParameter(url: String, name: String): String = url
    .substringAfter('?', "")
    .split('&')
    .firstOrNull { it.substringBefore('=') == name }
    ?.substringAfter('=', "")
    .orEmpty()

private suspend fun requireSuccess(response: HttpResponse, operation: String) {
    if (!response.status.isSuccess()) {
        throw MarketException("TapTap ${operation}请求失败（HTTP ${response.status.value}）")
    }
}

private fun plainText(value: String?): String = value.orEmpty()
    .replace(HtmlBreak, "\n")
    .replace(HtmlTag, "")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#39;", "'")
    .trim()

private fun JsonObject.obj(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.array(name: String): JsonArray? = this[name] as? JsonArray
private fun JsonObject.string(name: String): String = primitive(name)?.contentOrNull.orEmpty()
private fun JsonObject.long(name: String): Long = primitive(name)?.longOrNull ?: 0L
private fun JsonObject.double(name: String): Double = primitive(name)?.doubleOrNull ?: 0.0
private fun JsonObject.bool(name: String): Boolean? = primitive(name)?.booleanOrNull
private fun JsonObject.primitive(name: String) = this[name]?.let {
    runCatching { it.jsonPrimitive }.getOrNull()
}

/** 只接受已协商且比全量更小的 Jojo 补丁；未知格式保留全量下载。 */
internal fun parseTapTapPatch(patch: JsonObject?, apkHash: String, fullSize: Long): DownloadPatch? {
    if (patch == null || !apkHash.matches(Regex("[a-fA-F0-9]{32}"))) return null
    if (!patch.string("hash_v2").equals(apkHash, ignoreCase = true)) return null
    val type = patch.string("diff_type")
    if (type.isNotBlank() && !type.equals("Jojo", ignoreCase = true)) return null
    val url = patch.string("download")
    val size = patch.long("size")
    val hash = patch.string("md5").lowercase()
    if (!url.startsWith("https://") || size <= 0L || fullSize <= size ||
        !hash.matches(Regex("[a-f0-9]{32}"))) return null
    return DownloadPatch(url = url, size = size, hash = hash, version = 7, oldApkHash = apkHash.lowercase())
}
