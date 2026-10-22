package com.app.market.data.remote.tencent

import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPart
import com.app.market.domain.model.market.AppComment
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal data class TencentApiConfig(
    val webBase: String = "https://sj.qq.com",
    val detailUrl: String = "https://upage.html5.qq.com/wechat-apkinfo",
)

internal class TencentApi(private val client: HttpClient, private val config: TencentApiConfig = TencentApiConfig()) {
    suspend fun search(keyword: String, page: Int): SearchPage {
        if (keyword.isBlank() || page != 0) return SearchPage(emptyList(), false)
        val response = client.get(config.webBase.trimEnd('/') + "/search") {
            header(HttpHeaders.UserAgent, UserAgent)
            parameter("q", keyword.trim())
        }
        if (!response.status.isSuccess()) throw MarketException("应用宝搜索请求失败 HTTP ${response.status.value}")
        return parseTencentSearch(response.bodyAsText())
    }

    suspend fun detail(packageName: String): TencentDetail {
        if (packageName.isBlank()) throw MarketException("应用宝缺少应用包名")
        // 腾讯应用元数据接口提供网页没有暴露的真实 version_code，避免按版本名称猜测更新。
        // 接口参考：https://github.com/ImranR98/Obtainium/issues/1848
        val response = client.post(config.detailUrl) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.UserAgent, UserAgent)
            setBody(buildJsonObject { put("packagename", packageName) }.toString())
        }
        if (!response.status.isSuccess()) throw MarketException("应用宝详情请求失败 HTTP ${response.status.value}")
        return parseTencentDetail(response.bodyAsText(), packageName)
    }

    suspend fun appComments(app: MarketAppInfo): AppComments {
        val packageName = app.packageName.ifBlank { throw MarketException("应用宝缺少应用包名") }
        val response = client.get(config.webBase.trimEnd('/') + "/appdetail/${packageName.encodeURLPath()}/review") {
            header(HttpHeaders.UserAgent, UserAgent)
        }
        if (!response.status.isSuccess()) throw MarketException("应用宝评论请求失败 HTTP ${response.status.value}")
        return parseTencentComments(response.bodyAsText())
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val record = detail(app.packageName)
        val current = record.detail.app
        if (current.downloadBlockReason.isNotBlank()) throw MarketException(current.downloadBlockReason)
        return DownloadMeta(
            appId = current.appId, packageName = current.packageName, displayName = current.displayName,
            versionName = current.versionName, versionCode = current.versionCode, url = record.url, size = current.apkSize,
            parts = listOf(DownloadPart("", "base", record.url, current.apkSize, hash = record.checksum)),
            installedBaseApkPath = app.installedBaseApkPath, icon = current.icon, changeLog = current.changeLog,
            source = current.source, requestHeaders = mapOf(HttpHeaders.UserAgent to UserAgent),
        )
    }

    private companion object {
        const val UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0 Safari/537.36"
    }
}

private val TencentJson = Json { ignoreUnknownKeys = true; isLenient = true }

/** 应用宝评论页内嵌 schema.org Review 列表；总数取 Next 数据中的评论总数。 */
internal fun parseTencentComments(html: String): AppComments {
    val raw = TencentReviewJsonLdRegex.find(html)?.groupValues?.get(1)
        ?: return AppComments(emptyList(), 0L)
    val reviews = runCatching { TencentJson.decodeFromString<List<TencentReview>>(raw) }.getOrDefault(emptyList())
    val items = reviews.mapNotNull { review ->
        val content = review.reviewBody.replace("\r\n", "\n").trim()
        if (content.isBlank()) return@mapNotNull null
        AppComment(
            userName = review.author.name.trim().ifBlank { "应用宝用户" },
            content = content,
            score = review.reviewRating.ratingValue.coerceIn(0.0, 5.0),
        )
    }
    val total = TencentReviewTotalRegex.find(html)?.groupValues?.get(1)?.toLongOrNull()
        ?.takeIf { it > 0L } ?: items.size.toLong()
    return AppComments(items = items, totalCount = total)
}

@Serializable
private data class TencentReview(
    val author: TencentReviewAuthor = TencentReviewAuthor(),
    val reviewRating: TencentReviewRating = TencentReviewRating(),
    val reviewBody: String = "",
)

@Serializable
private data class TencentReviewAuthor(val name: String = "")

@Serializable
private data class TencentReviewRating(val ratingValue: Double = 0.0)

private val TencentReviewJsonLdRegex = Regex("""<script type="application/ld\+json"[^>]*>([\s\S]*?)</script>""")
private val TencentReviewTotalRegex = Regex(""""total":(\d+),"recommend_topic_list"""")
