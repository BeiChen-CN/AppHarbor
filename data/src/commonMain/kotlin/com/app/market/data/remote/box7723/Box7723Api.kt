package com.app.market.data.remote.box7723

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
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal data class Box7723ApiConfig(
    val searchBaseUrl: String = "https://www.7723.cn",
    val detailBaseUrl: String = "https://3g.7723.cn",
)

private const val DefaultPageLimit = 20
private const val UserAgentDesktop =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
private const val UserAgentMobile =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

private val HtmlTag = Regex("<[^>]+>")
private val ItemPairPattern = Regex("""<p class="item-l">([^<]+)</p>\s*<p class="item-r">([^<]*)</p>""")
private val ScreenshotPattern = Regex("""<img class="img" src="([^"]+attachments/jietu[^"]+)"""")
private val EditorIntroPattern = Regex("""<p class="detail-phrase__des">([\s\S]*?)</p>""")
private val AppDescPattern = Regex("""<div class="detail-desc__info"[\s\S]*?<p[\s\S]*?>([\s\S]*?)</p>""")
private val TitlePattern = Regex("""<h1 class="words"[^>]*>([^<]+)</h1>""")
private val IconPattern = Regex("""<img class="m-img" src="([^"]+)"""")

internal class Box7723Api(
    private val client: HttpClient,
    private val config: Box7723ApiConfig = Box7723ApiConfig(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 搜索 7723 应用/游戏（严格过滤掉 UP 资源）。
     */
    suspend fun search(keyword: String, page: Int = 0): SearchPage {
        val query = keyword.trim()
        if (query.isBlank()) return SearchPage(items = emptyList(), hasMore = false)

        val targetPage = (page + 1).coerceAtLeast(1)
        val url = "${config.searchBaseUrl}/search?keyword=${query.encodeURLParameter()}&page=$targetPage&limit=$DefaultPageLimit"

        val response = client.get(url) {
            header(HttpHeaders.UserAgent, UserAgentDesktop)
            header("X-Requested-With", "XMLHttpRequest")
            header("Accept", "application/json")
        }

        if (!response.status.isSuccess()) {
            throw MarketException("7723 搜索失败：HTTP ${response.status.value}")
        }

        val text = response.bodyAsText()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw MarketException("7723 搜索结果解析失败")

        val dataList = root["data"]?.jsonArray ?: JsonArray(emptyList())
        val apps = mutableListOf<MarketAppInfo>()

        for (elem in dataList) {
            val obj = elem.jsonObject
            val detailUrl = obj["detail_url"]?.jsonPrimitive?.contentOrNull.orEmpty()

            // 严格排除 UP 资源：UP 资源为 /ups/{id}，官方应用为 /apps/{id}
            if (detailUrl.contains("/ups/")) {
                continue
            }

            val id = obj["id"]?.jsonPrimitive?.longOrNull
                ?: detailUrl.substringAfterLast('/').toLongOrNull()
                ?: continue

            val title = obj["title"]?.jsonPrimitive?.contentOrNull.orEmpty().cleanHtmlText()
            val icon = obj["icon"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val intro = obj["intro"]?.jsonPrimitive?.contentOrNull.orEmpty().cleanHtmlText()
            val sizeStr = obj["size"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val size = parseDisplaySize(sizeStr)

            apps.add(
                MarketAppInfo(
                    appId = id,
                    packageName = "com.box7723.app$id", // 详情页中会更新真实包名
                    displayName = title,
                    publisherName = "",
                    versionName = "",
                    versionCode = 0L,
                    icon = icon,
                    apkSize = size,
                    ratingScore = 0.0,
                    changeLog = intro,
                    openLink = "https://www.7723.cn/apps/$id",
                    source = AppSource.BOX7723,
                )
            )
        }

        return SearchPage(
            items = apps,
            hasMore = dataList.size >= DefaultPageLimit,
        )
    }

    /**
     * 获取应用详情，解析包名、版本、截圖、描述等。
     */
    suspend fun appDetail(appId: Long): AppDetail {
        val url = "${config.detailBaseUrl}/apps/$appId"
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, UserAgentMobile)
        }

        if (!response.status.isSuccess()) {
            throw MarketException("获取 7723 应用详情失败：HTTP ${response.status.value}")
        }

        val html = response.bodyAsText()

        // 提取 key-value 属性
        val properties = mutableMapOf<String, String>()
        for (match in ItemPairPattern.findAll(html)) {
            val key = match.groupValues[1].cleanHtmlText()
            val value = match.groupValues[2].cleanHtmlText()
            properties[key] = value
        }

        val packageName = properties["APK包名"]?.takeIf { it.isNotBlank() }
            ?: "com.box7723.app$appId"
        val versionName = properties["游戏版本"]?.takeIf { it.isNotBlank() }
            ?: properties["版本"]?.takeIf { it.isNotBlank() }
            ?: "1.0"
        val sizeStr = properties["游戏大小"]?.takeIf { it.isNotBlank() }
            ?: properties["大小"]?.takeIf { it.isNotBlank() }
            ?: ""
        val developer = properties["供应商"]?.takeIf { it.isNotBlank() }
            ?: properties["厂商"]?.takeIf { it.isNotBlank() }
            ?: ""
        val updateTime = properties["更新时间"]?.takeIf { it.isNotBlank() }
            ?: properties["发布时间"]?.takeIf { it.isNotBlank() }
            ?: ""

        val title = TitlePattern.find(html)?.groupValues?.get(1)?.cleanHtmlText()
            ?: "App $appId"
        val icon = IconPattern.find(html)?.groupValues?.get(1).orEmpty()

        val editorNote = EditorIntroPattern.find(html)?.groupValues?.get(1)?.cleanHtmlText().orEmpty()
        val desc = AppDescPattern.find(html)?.groupValues?.get(1)?.cleanHtmlText()
            ?.ifBlank { editorNote }
            ?: editorNote

        val screenshots = ScreenshotPattern.findAll(html).map { match ->
            val src = match.groupValues[1]
            AppScreenshot(
                url = src,
                expandedUrl = src.substringBefore('?'),
                orientation = ScreenshotOrientation.PORTRAIT,
            )
        }.toList()

        val appInfo = MarketAppInfo(
            appId = appId,
            packageName = packageName,
            displayName = title,
            publisherName = developer,
            versionName = versionName,
            versionCode = 1L,
            icon = icon,
            apkSize = parseDisplaySize(sizeStr),
            ratingScore = 0.0,
            changeLog = editorNote,
            openLink = "https://www.7723.cn/apps/$appId",
            source = AppSource.BOX7723,
        )

        return AppDetail(
            app = appInfo,
            brief = editorNote,
            introduction = desc,
            changeLog = editorNote,
            category = properties["分类"].orEmpty(),
            ageClassification = "",
            downloadCount = 0L,
            registrationNum = "",
            privacyUrl = "",
            screenshots = screenshots,
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
        )
    }

    /**
     * 获取 7723 官方直链下载元数据。
     */
    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val formUrl = "${config.searchBaseUrl}/game/down"
        val response = client.post(formUrl) {
            header(HttpHeaders.UserAgent, UserAgentDesktop)
            header("X-Requested-With", "XMLHttpRequest")
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("id=${app.appId}")
        }

        if (!response.status.isSuccess()) {
            throw MarketException("7723 下载解析失败：HTTP ${response.status.value}")
        }

        val text = response.bodyAsText()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw MarketException("7723 下载响应解析失败")

        val downloadUrl = root["downsurl"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (downloadUrl.isBlank()) {
            throw MarketException("7723 未提供该应用的有效下载链接")
        }

        return box7723DownloadMeta(
            appId = app.appId,
            packageName = app.packageName,
            displayName = app.displayName,
            versionName = app.versionName,
            versionCode = app.versionCode.takeIf { it > 0L } ?: 1L,
            icon = app.icon,
            downloadUrl = downloadUrl,
            fallbackSize = app.apkSize,
        )
    }
}

internal fun box7723DownloadMeta(
    appId: Long,
    packageName: String,
    displayName: String,
    versionName: String,
    versionCode: Long,
    icon: String,
    downloadUrl: String,
    fallbackSize: Long,
): DownloadMeta {
    return DownloadMeta(
        appId = appId,
        packageName = packageName,
        displayName = displayName,
        versionName = versionName,
        versionCode = versionCode,
        url = downloadUrl,
        size = fallbackSize,
        parts = listOf(
            DownloadPart(
                name = "",
                type = "base",
                url = downloadUrl,
                size = fallbackSize,
                hash = "",
            )
        ),
        icon = icon,
        source = AppSource.BOX7723,
    )
}

private fun String.cleanHtmlText(): String =
    replace("&middot;", "·")
        .replace("&nbsp;", " ")
        .replace("&reg;", "®")
        .replace("&copy;", "©")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(HtmlTag, "")
        .trim()

private fun parseDisplaySize(raw: String): Long {
    val clean = raw.trim().uppercase()
    if (clean.isBlank()) return 0L
    return when {
        clean.endsWith("GB") -> {
            val num = clean.removeSuffix("GB").trim().toDoubleOrNull() ?: 0.0
            (num * 1024 * 1024 * 1024).toLong()
        }
        clean.endsWith("MB") -> {
            val num = clean.removeSuffix("MB").trim().toDoubleOrNull() ?: 0.0
            (num * 1024 * 1024).toLong()
        }
        clean.endsWith("KB") -> {
            val num = clean.removeSuffix("KB").trim().toDoubleOrNull() ?: 0.0
            (num * 1024).toLong()
        }
        clean.endsWith("B") -> {
            clean.removeSuffix("B").trim().toLongOrNull() ?: 0L
        }
        else -> 0L
    }
}
