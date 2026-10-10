package com.app.market.data.remote.releases

import com.app.market.data.remote.fdroid.fdjArray
import com.app.market.data.remote.fdroid.fdjLong
import com.app.market.data.remote.fdroid.fdjObject
import com.app.market.data.remote.fdroid.fdjString
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.market.AppSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class ReleaseRepositoryRef(val source: AppSource, val path: String) {
    val link: String get() = "https://${if (source == AppSource.GITHUB) "github.com" else "gitlab.com"}/$path"
}

internal data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String = "")
internal data class RepositoryRelease(
    val ref: ReleaseRepositoryRef, val description: String, val notes: String,
    val icon: String, val assets: List<ReleaseAsset>,
)

internal class ReleaseApi(private val client: HttpClient) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(source: AppSource, keyword: String, page: Int): Pair<List<ReleaseRepositoryRef>, Boolean> {
        val root = if (source == AppSource.GITHUB) {
            get("https://api.github.com/search/repositories") {
                parameter("q", "$keyword android"); parameter("per_page", PAGE_SIZE); parameter("page", page + 1)
            }
        } else {
            get("https://gitlab.com/api/v4/projects") {
                parameter("search", keyword); parameter("simple", "true"); parameter("visibility", "public")
                parameter("order_by", "star_count"); parameter("sort", "desc")
                parameter("per_page", PAGE_SIZE); parameter("page", page + 1)
            }
        }
        val entries = if (source == AppSource.GITHUB) root.fdjArray("items").orEmpty() else (root as? JsonArray).orEmpty()
        val refs = entries.mapNotNull {
            parseReleaseRepository(source, if (source == AppSource.GITHUB) it.fdjString("html_url") else it.fdjString("web_url"))
        }
        val hasMore = if (source == AppSource.GITHUB) (page + 1) * PAGE_SIZE < root.fdjLong("total_count")
            else entries.size >= PAGE_SIZE
        return refs to hasMore
    }

    suspend fun latest(ref: ReleaseRepositoryRef): RepositoryRelease? {
        val project = if (ref.source == AppSource.GITHUB) "https://api.github.com/repos/${ref.path}"
            else "https://gitlab.com/api/v4/projects/${ref.path.encodeURLParameter()}"
        val metadata = get(project) ?: return null
        val releases = get("$project/releases") {
            parameter("per_page", 10); parameter("page", 1)
        } as? JsonArray ?: return null
        val release = releases.firstOrNull {
            if (ref.source == AppSource.GITHUB) {
                it.fdjString("draft") != "true" && it.fdjString("prerelease") != "true" && githubAssets(it).isNotEmpty()
            } else gitlabAssets(it).isNotEmpty() && it.fdjString("upcoming_release") != "true" &&
                !Regex("(?i)(alpha|beta|rc|nightly|snapshot)([._-]?\\d*)$").containsMatchIn(it.fdjString("tag_name"))
        } ?: return null
        return RepositoryRelease(
            ref = ref, description = metadata.fdjString("description"),
            notes = release.fdjString(if (ref.source == AppSource.GITHUB) "body" else "description"),
            icon = if (ref.source == AppSource.GITHUB) metadata.fdjObject("owner").fdjString("avatar_url") else metadata.fdjString("avatar_url"),
            assets = if (ref.source == AppSource.GITHUB) githubAssets(release) else gitlabAssets(release),
        )
    }

    private suspend fun get(url: String, configure: io.ktor.client.request.HttpRequestBuilder.() -> Unit = {}): JsonElement? {
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, "AppHarbor")
            header(HttpHeaders.Accept, "application/json")
            configure()
        }
        if (response.status.value == 404) return null
        if (response.status.value == 429 || response.status.value == 403 && response.headers["X-RateLimit-Remaining"] == "0") {
            throw MarketException("来源 API 请求次数已达上限，请稍后重试")
        }
        if (response.status.value !in 200..299) throw MarketException("来源请求失败：HTTP ${response.status.value}")
        return json.parseToJsonElement(response.bodyAsText())
    }

    private companion object { const val PAGE_SIZE = 8 }
}

internal fun parseReleaseRepository(source: AppSource, input: String): ReleaseRepositoryRef? {
    if (source != AppSource.GITHUB && source != AppSource.GITLAB) return null
    val host = if (source == AppSource.GITHUB) "github.com" else "gitlab.com"
    val candidate = input.trim()
    val path = if (candidate.startsWith("https://", true)) {
        val url = runCatching { Url(candidate) }.getOrNull() ?: return null
        if (!url.host.equals(host, true) || url.port != 443 || !url.user.isNullOrEmpty() || !url.password.isNullOrEmpty()) return null
        url.encodedPath.trim('/')
    } else {
        if (candidate.contains("://") || candidate.startsWith('/')) return null
        candidate.removePrefix("$host/").trim('/')
    }
    var parts = path.split('/').takeWhile { it != "-" }
    if (source == AppSource.GITHUB) parts = parts.take(2)
    if (parts.size < 2) return null
    parts = parts.dropLast(1) + parts.last().removeSuffix(".git")
    if (parts.any { !Regex("[A-Za-z0-9_][A-Za-z0-9_.-]*").matches(it) || it == "." || it == ".." }) return null
    return ReleaseRepositoryRef(source, parts.joinToString("/"))
}

internal fun githubAssets(release: JsonElement): List<ReleaseAsset> = release.fdjArray("assets").orEmpty().mapNotNull {
    val name = it.fdjString("name")
    val url = it.fdjString("browser_download_url")
    if (!name.endsWith(".apk", true) || !isPublicAssetUrl(url) || it.fdjString("state", "uploaded") != "uploaded") return@mapNotNull null
    val digest = it.fdjString("digest").removePrefix("sha256:")
    ReleaseAsset(name, url, it.fdjLong("size"), digest.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }.orEmpty())
}

internal fun gitlabAssets(release: JsonElement): List<ReleaseAsset> = release.fdjObject("assets").fdjArray("links").orEmpty().mapNotNull {
    val url = it.fdjString("direct_asset_url").ifBlank { it.fdjString("url") }
    val name = it.fdjString("name").takeIf { it.endsWith(".apk", true) }
        ?: runCatching { Url(url).encodedPath.substringAfterLast('/') }.getOrNull().orEmpty()
    if (!name.endsWith(".apk", true) || !isPublicAssetUrl(url)) return@mapNotNull null
    ReleaseAsset(name, url, 0L)
}

private fun isPublicAssetUrl(value: String): Boolean = runCatching {
    val url = Url(value)
    url.protocol.name == "https" && url.user.isNullOrEmpty() && url.password.isNullOrEmpty() && url.host.isNotBlank()
}.getOrDefault(false)
