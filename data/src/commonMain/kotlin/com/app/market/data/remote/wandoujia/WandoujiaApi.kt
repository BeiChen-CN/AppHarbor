package com.app.market.data.remote.wandoujia

import com.app.market.data.platform.debugLog
import com.app.market.data.platform.gunzip
import com.app.market.data.platform.gzip
import com.app.market.data.remote.kibToBytes
import com.app.market.data.remote.randomHex
import com.app.market.data.remote.secondsToMillis
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
import com.app.market.domain.model.market.ScreenshotOrientation
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.model.update.ManualUpdateStatus
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

internal data class WandoujiaApiConfig(
    val apiBase: String = "https://w-api.pp.cn",
)

private const val ApiVersionCode = 803110002
private const val ApiVersionName = "8.3.11.2"
private const val ProductId = 2011
private const val SearchPageSize = 10
private const val HistoryPageSize = 20
private const val SuccessCode = 2000000L
private const val ApiUserAgent = "Dalvik/2.1.0 (Linux; U; Android 15; AppMarket)"
private const val DownloadUserAgent =
    "Mozilla/4.0 (compatible; MSIE 7.0; Windows NT 5.2; Trident/4.0; " +
            ".NET CLR 1.1.4322; .NET CLR 2.0.50727; .NET CLR 3.0.04506.30; " +
            ".NET CLR 3.0.4506.2152; .NET CLR 3.5.30729)"

private val WandoujiaBreak = Regex("""<\\?/?br\s*/?>""", RegexOption.IGNORE_CASE)
private val HtmlTag = Regex("<[^>]+>")

@OptIn(ExperimentalTime::class)
internal class WandoujiaApi(
    private val client: HttpClient,
    private val config: WandoujiaApiConfig = WandoujiaApiConfig(),
) {
    private val identity = randomHex(16)
    private val joinedAt = Clock.System.now().toEpochMilliseconds()

    suspend fun search(keyword: String, page: Int): SearchPage {
        val requestPage = page + 1
        val serviceData = buildJsonObject {
            if (page == 0) put("rcount", 4)
            put("screenWidth", 1080)
            put("s", "1")
            if (page == 0) put("functions", "searchNotice,onlyShowNoDownload")
            put("offset", page * SearchPageSize)
            put("pos", "wdj/search/search_result_top/organic(豌豆荚)")
            put("count", SearchPageSize)
            put("recommend", if (page == 0) 1 else 0)
            put("page", requestPage)
            put("keyword", keyword)
            put("ua", "%28Android%3BN%3BAndroidOS15%3BAppMarket%29")
            put("resourceType", 2)
        }
        val data = buildJsonArray {
            add(buildJsonObject {
                put("service", "search.app.list")
                put("data", serviceData)
            })
        }
        return parseWandoujiaSearch(combine(data), page)
    }

    suspend fun appDetail(appId: Long): AppDetail = parseWandoujiaDetail(detailObject(appId))

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val json = detailObject(app.appId)
        val parsed = parseWandoujiaApp(json, detailSizeInKib = true)
            ?: throw MarketException("豌豆荚未返回该应用的有效信息")
        return wandoujiaDownloadMeta(
            appId = parsed.appId,
            packageName = parsed.packageName.ifBlank { app.packageName },
            displayName = parsed.displayName.ifBlank { app.displayName },
            versionName = parsed.versionName,
            versionCode = parsed.versionCode,
            icon = parsed.icon.ifBlank { app.icon },
            changeLog = parsed.changeLog,
            downloadUrl = json.wdjString("downloadUrl"),
            fallbackSize = parsed.apkSize,
        )
    }

    suspend fun checkUpdates(installed: List<InstalledPackage>): List<MarketAppInfo> {
        if (installed.isEmpty()) return emptyList()
        val root = postCheckUpdates(installed)
        val installedByPackage = installed.associateBy(InstalledPackage::packageName)
        val updates = root.wdjObject("data").wdjArray("content")
        return (0 until (updates?.size ?: 0)).mapNotNull { index ->
            val json = updates.wdjObjectAt(index) ?: return@mapNotNull null
            val local = installedByPackage[json.wdjString("packageName")] ?: return@mapNotNull null
            val remote = parseWandoujiaApp(json, detailSizeInKib = true) ?: return@mapNotNull null
            if (remote.versionCode <= local.versionCode) return@mapNotNull null
            remote.copy(
                installedVersionName = local.versionName,
                installedVersionCode = local.versionCode,
                installedBaseApkPath = local.baseApkPath,
                installedSplits = local.splits,
                isSystemApp = local.isSystemApp,
            )
        }
    }

    /**
     * 单个包名的手动更新查询：复用批量协议，只提交一个包。
     *
     * 豌豆荚搜索不认包名（只有部分应用能被包名命中），走 checkUpdateV1 才可靠。
     */
    suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult {
        val installed = InstalledPackage(
            packageName = request.packageName,
            versionCode = request.versionCode,
            isSystemApp = request.isSystemApp,
            versionName = request.versionName,
            splits = request.splits,
        )
        val root = postCheckUpdates(listOf(installed))
        val updates = root.wdjObject("data").wdjArray("content")
        val json = (0 until (updates?.size ?: 0)).mapNotNull { updates.wdjObjectAt(it) }
            .firstOrNull { it.wdjString("packageName").equals(request.packageName, ignoreCase = true) }
            ?: return ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
        val remote = parseWandoujiaApp(json, detailSizeInKib = true)
            ?: return ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
        if (remote.versionCode <= request.versionCode) {
            return ManualUpdateResult(ManualUpdateStatus.RECOGNIZED_NO_UPDATE)
        }
        return ManualUpdateResult(
            ManualUpdateStatus.UPDATE_AVAILABLE,
            remote.copy(
                installedVersionName = request.versionName,
                installedVersionCode = request.versionCode,
                installedSplits = request.splits,
                isSystemApp = request.isSystemApp,
            ),
        )
    }

    private suspend fun postCheckUpdates(installed: List<InstalledPackage>): JsonObject {
        val content = buildJsonArray {
            installed.forEach { app ->
                add(buildJsonObject {
                    put("packageName", app.packageName)
                    put("versionName", app.versionName)
                    put("versionCode", app.versionCode)
                    put("signature", "")
                    put("isSystemApp", if (app.isSystemApp) 1 else 0)
                    put("installedTime", 0L)
                    put("RFF", "")
                    put("ZFF", "")
                })
            }
        }
        val data = buildJsonObject {
            put("flags", 1081)
            put("content", content)
        }
        val requestId = requestId()
        val envelope = buildJsonObject {
            put("id", requestId)
            put("client", clientIdentity())
            put("data", data)
            put("sign", "")
        }
        val root = postM90("/api/resource.app.checkUpdateV1", envelope)
        requireSuccess(root, "更新检查")
        return root
    }

    suspend fun historicalVersions(
        appId: Long,
        packageName: String,
        offset: Int,
    ): HistoricalVersionPage {
        val data = buildJsonObject {
            put("offset", offset)
            put("appId", appId)
            put("count", HistoryPageSize)
            put("packageName", packageName)
            put("page", offset / HistoryPageSize + 1)
        }
        val root = direct("/api/resource.app.getHistoryInfo", data)
        val response = root.wdjObject("data")
            ?: throw MarketException("豌豆荚未返回历史版本数据")
        val responseAppId = response.wdjLong("appId", appId)
        val responsePackage = response.wdjString("packageName", packageName)
        val displayName = response.wdjString("appName", responsePackage)
        val icon = response.wdjString("icon")
        val versions = response.wdjArray("previousVersions")
        val items = (0 until (versions?.size ?: 0)).mapNotNull { index ->
            val item = versions.wdjObjectAt(index) ?: return@mapNotNull null
            val downloadUrl = item.wdjString("downloadUrl")
            if (downloadUrl.isBlank()) return@mapNotNull null
            HistoricalVersion(
                appId = responseAppId,
                packageName = responsePackage,
                displayName = displayName,
                icon = icon,
                versionId = item.wdjLong("versionId"),
                versionName = item.wdjString("versionName"),
                versionCode = item.wdjLong("versionCode"),
                minSdkVersion = item.wdjInt("minSdkVersion"),
                downloadUrl = downloadUrl,
                size = wandoujiaUrlParameter(downloadUrl, "size")?.toLongOrNull()
                    ?: item.wdjLong("size").kibToBytes(),
                createdAt = item.wdjLong("createTime").secondsToMillis(),
                updatedAt = item.wdjLong("updateTime").secondsToMillis(),
            )
        }
        val nextOffset = response.wdjInt("nextOffset", -1).takeIf { it >= 0 }
        return HistoricalVersionPage(items = items, nextOffset = nextOffset)
    }

    private suspend fun detailObject(appId: Long): JsonObject {
        val data = buildJsonArray {
            add(buildJsonObject {
                put("service", "resource.app.getDetail")
                put("data", buildJsonObject {
                    put("screenWidth", 1080)
                    put("appId", appId)
                })
            })
        }
        val root = combine(data)
        val item = root.wdjArray("data")?.firstOrNull { element ->
            (element as? JsonObject).wdjString("service") == "resource.app.getDetail"
        } as? JsonObject ?: throw MarketException("豌豆荚未返回应用详情")
        requireSuccess(item, "应用详情")
        return item.wdjObject("data")?.wdjObject("app")
            ?: throw MarketException("豌豆荚未收录该应用")
    }

    private suspend fun combine(data: JsonArray): JsonObject {
        val requestId = requestId()
        val envelope = envelope(
            requestId = requestId,
            data = data,
            sign = wandoujiaCombineSign(requestId, data),
        )
        return post("/api2/combine", envelope).also { requireSuccess(it, "请求") }
    }

    private suspend fun direct(path: String, data: JsonObject): JsonObject {
        val requestId = requestId()
        val envelope = envelope(
            requestId = requestId,
            data = data,
            sign = wandoujiaDirectSign(data),
        )
        return post(path, envelope).also { requireSuccess(it, "请求") }
    }

    private fun envelope(requestId: Long, data: kotlinx.serialization.json.JsonElement, sign: String): JsonObject =
        buildJsonObject {
            put("id", requestId)
            put("client", clientIdentity())
            put("data", data)
            put("sign", sign)
            put("encrypt", "md5")
        }

    private fun clientIdentity(): JsonObject = buildJsonObject {
        put("caller", WandoujiaCaller)
        put("ex", buildJsonObject {
            put("osVersion", 35)
            put("ch", "wap_seo_others_homepage")
            put("productId", ProductId)
            put("brand", "generic")
            put("udid", identity)
            put("utdid", identity.take(24))
            put("utoken", "")
            put("joinTime", joinedAt)
            put("aid", identity)
        })
        put("guestMode", true)
        put("versionCode", ApiVersionCode)
        put("VName", ApiVersionName)
        put("puid", "")
        put("uuid", identity)
        put("umid", "")
        put("recognition", "0_0")
        put("androidId", identity.take(16))
    }

    private suspend fun post(path: String, body: JsonObject): JsonObject {
        val response = client.post(config.apiBase.trimEnd('/') + path) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, ApiUserAgent)
            setBody(body.toString())
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            debugLog("WandoujiaApi") {
                "HTTP ${response.status.value} ${response.request.url.encodedPath}: ${text.take(200)}"
            }
            throw MarketException("豌豆荚服务器返回异常状态 HTTP ${response.status.value}")
        }
        return parseWandoujiaObject(text)
    }

    private suspend fun postM90(path: String, body: JsonObject): JsonObject {
        val encrypted = wandoujiaM90EncodeRequest(gzip(body.toString().encodeToByteArray()))
        val response = client.post(config.apiBase.trimEnd('/') + path) {
            contentType(ContentType.Application.FormUrlEncoded)
            header(HttpHeaders.UserAgent, ApiUserAgent)
            setBody(encrypted)
        }
        val bytes = response.body<ByteArray>()
        if (!response.status.isSuccess()) {
            debugLog("WandoujiaApi") {
                "HTTP ${response.status.value} ${response.request.url.encodedPath}: encrypted response ${bytes.size} bytes"
            }
            throw MarketException("豌豆荚服务器返回异常状态 HTTP ${response.status.value}")
        }
        val decoded = gunzip(wandoujiaM90DecodeResponse(bytes)).decodeToString()
        return parseWandoujiaObject(decoded)
    }

    private fun requireSuccess(root: JsonObject, operation: String) {
        val state = root.wdjObject("state")
        val code = state.wdjLong("code", -1L)
        if (code == SuccessCode) return
        val message = state.wdjString("tips").ifBlank { state.wdjString("msg") }
        debugLog("WandoujiaApi") { "$operation rejected: code=$code message=${message.take(120)}" }
        throw MarketException(message.ifBlank { "豌豆荚${operation}失败 ($code)" })
    }

    private fun requestId(): Long =
        Clock.System.now().toEpochMilliseconds() * 1000L + Random.nextInt(1000)
}

internal fun parseWandoujiaSearch(root: JsonObject, page: Int): SearchPage {
    val service = root.wdjArray("data")?.firstOrNull { element ->
        (element as? JsonObject).wdjString("service") == "search.app.list"
    } as? JsonObject ?: return SearchPage(emptyList(), hasMore = false)
    val stateCode = service.wdjObject("state").wdjLong("code", -1L)
    if (stateCode != SuccessCode) return SearchPage(emptyList(), hasMore = false)
    val content = service.wdjObject("data").wdjArray("content")
    val items = (0 until (content?.size ?: 0)).mapNotNull { index ->
        content.wdjObjectAt(index)?.let { parseWandoujiaApp(it, detailSizeInKib = false) }
    }
    // 协议没有可靠的末页标记；空页停止，允许最后一页后多探测一次。
    return SearchPage(items = items, hasMore = content?.isNotEmpty() == true && page < 99)
}

internal fun parseWandoujiaDetail(json: JsonObject): AppDetail {
    val app = parseWandoujiaApp(json, detailSizeInKib = true)
        ?: throw MarketException("豌豆荚未返回该应用的有效信息")
    return AppDetail(
        app = app,
        brief = json.wdjString("editorRecommend"),
        introduction = wandoujiaPlainText(json.wdjString("appDesc")),
        changeLog = wandoujiaPlainText(json.wdjString("verDesc")),
        category = json.wdjString("categoryName"),
        ageClassification = json.wdjString("userAgeLimit"),
        downloadCount = json.wdjLong("downloads"),
        registrationNum = json.wdjString("icpNumber"),
        updateTime = json.wdjLong("updateTime"),
        privacyUrl = json.wdjString("privacyPolicyUrl"),
        screenshots = parseWandoujiaScreenshots(json),
        commentCount = json.wdjLong("commentCount"),
        comments = emptyList(),
        sameDeveloperApps = emptyList(),
    )
}

internal fun parseWandoujiaApp(json: JsonObject, detailSizeInKib: Boolean): MarketAppInfo? {
    val packageName = json.wdjString("packageName")
    if (packageName.isBlank()) return null
    val downloadUrl = json.wdjString("downloadUrl")
    val sizeFromUrl = wandoujiaUrlParameter(downloadUrl, "size")?.toLongOrNull() ?: 0L
    val rawSize = json.wdjLong("totalSize").takeIf { it > 0L }
        ?: sizeFromUrl.takeIf { it > 0L }
        ?: json.wdjLong("size").let { if (detailSizeInKib) it.kibToBytes() else it }
    return MarketAppInfo(
        appId = json.wdjLong("id"),
        packageName = packageName,
        displayName = json.wdjString("name", packageName),
        publisherName = json.wdjString("seller"),
        versionName = json.wdjString("versionName"),
        versionCode = json.wdjLong("versionCode"),
        icon = json.wdjString("iconUrl"),
        apkSize = rawSize,
        ratingScore = (json.wdjDouble("score") / 2.0).coerceIn(0.0, 5.0),
        changeLog = wandoujiaPlainText(json.wdjString("verDesc")),
        source = AppSource.WANDOUJIA,
    )
}

internal fun parseWandoujiaScreenshots(json: JsonObject): List<AppScreenshot> {
    val value = json.wdjString("screenshotsUrl")
    if (value.isBlank()) return emptyList()
    val base = value.substringBefore('!', "").trimEnd('/')
    val paths = if ('!' in value) value.substringAfter('!') else value
    return paths.lines().mapNotNull { raw ->
        val path = raw.trim()
        val url = when {
            path.isBlank() -> return@mapNotNull null
            path.startsWith("https://") -> path
            base.isNotBlank() -> base + "/" + path.trimStart('/')
            else -> return@mapNotNull null
        }
        AppScreenshot(
            url = url,
            expandedUrl = url,
            orientation = ScreenshotOrientation.PORTRAIT,
        )
    }
}

internal fun wandoujiaPlainText(html: String): String =
    html.replace(WandoujiaBreak, "\n")
        .replace(HtmlTag, "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
        .trim()

@OptIn(ExperimentalTime::class)
internal fun wandoujiaDownloadMeta(
    appId: Long,
    packageName: String,
    displayName: String,
    versionName: String,
    versionCode: Long,
    icon: String,
    changeLog: String,
    downloadUrl: String,
    fallbackSize: Long,
): DownloadMeta {
    if (downloadUrl.isBlank()) throw MarketException("豌豆荚未提供该版本的下载地址")
    val url = appendWandoujiaSequence(downloadUrl, Clock.System.now().toEpochMilliseconds())
    val size = wandoujiaUrlParameter(url, "size")?.toLongOrNull()?.takeIf { it > 0L } ?: fallbackSize
    val hash = wandoujiaUrlParameter(url, "md5").orEmpty()
    val part = DownloadPart(name = "", type = "base", url = url, size = size, hash = hash)
    return DownloadMeta(
        appId = appId,
        packageName = packageName,
        displayName = displayName,
        versionName = versionName,
        versionCode = versionCode,
        url = url,
        size = size,
        parts = listOf(part),
        icon = icon,
        changeLog = changeLog,
        requestHeaders = mapOf(
            HttpHeaders.Accept to "image/gif, image/jpeg, image/pjpeg, */*",
            HttpHeaders.AcceptLanguage to "zh-CN",
            HttpHeaders.UserAgent to DownloadUserAgent,
            "Charset" to "UTF-8",
            "p" to Random.nextInt(Int.MAX_VALUE).toString(),
            "u" to Random.nextInt(Int.MAX_VALUE).toString(),
        ),
        source = AppSource.WANDOUJIA,
    )
}

internal fun appendWandoujiaSequence(url: String, sequence: Long): String {
    if (Regex("(?:^|[?&])seq=").containsMatchIn(url)) return url
    return url + if ('?' in url) "&seq=$sequence" else "?seq=$sequence"
}

internal fun wandoujiaUrlParameter(url: String, name: String): String? =
    url.substringAfter('?', "").split('&').firstNotNullOfOrNull { part ->
        val separator = part.indexOf('=')
        if (separator <= 0 || part.substring(0, separator) != name) null else part.substring(separator + 1)
    }

