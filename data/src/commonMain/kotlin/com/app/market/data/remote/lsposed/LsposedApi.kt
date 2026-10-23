package com.app.market.data.remote.lsposed

import com.app.market.domain.exception.MarketException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * Xposed Module Repository (modules.lsposed.org) client.
 *
 * The site publishes a search index plus one JSON document per module. The JSON document carries
 * release assets; the actual APK identity is read by [com.app.market.data.remote.releases.ReleaseApkInspector].
 */
internal class LsposedApi(private val client: HttpClient) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }
    private val indexMutex = Mutex()
    private var indexCache: Cache<List<LsposedSearchEntry>>? = null

    suspend fun searchIndex(force: Boolean = false): List<LsposedSearchEntry> = indexMutex.withLock {
        val now = Clock.System.now().toEpochMilliseconds()
        indexCache?.takeIf { !force && now - it.fetchedAt < INDEX_TTL_MILLIS }?.value?.let { return@withLock it }
        val entries = json.decodeFromString<List<LsposedSearchEntry>>(
            getText("$BASE/search.json") ?: throw MarketException("LSPosed 模块仓库搜索索引为空"),
        )
        indexCache = Cache(entries, now)
        entries
    }

    suspend fun module(packageName: String): LsposedModule? {
        require(PACKAGE_NAME.matches(packageName)) { "Invalid LSPosed module package name" }
        val body = getText("$BASE/module/$packageName.json", allowNotFound = true) ?: return null
        return json.decodeFromString<LsposedModule>(body)
    }

    private suspend fun getText(url: String, allowNotFound: Boolean = false): String? {
        val response = client.get(url) {
            header(HttpHeaders.UserAgent, USER_AGENT)
            header(HttpHeaders.Accept, "application/json")
        }
        if (response.status.value == 404 && allowNotFound) return null
        if (response.status.value == 429 || response.status.value == 403) {
            throw MarketException("LSPosed 模块仓库请求受限，请稍后重试")
        }
        if (response.status.value !in 200..299) {
            throw MarketException("LSPosed 模块仓库请求失败：HTTP ${response.status.value}")
        }
        return response.bodyAsText()
    }

    private data class Cache<T>(val value: T, val fetchedAt: Long)

    private companion object {
        const val BASE = "https://modules.lsposed.org"
        const val USER_AGENT = "AppHarbor (LSPosed module source)"
        const val INDEX_TTL_MILLIS = 10 * 60_000L
        val PACKAGE_NAME = Regex("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")
    }
}

@Serializable
internal data class LsposedSearchEntry(
    val name: String,
    val description: String = "",
    val summary: String = "",
    val readmeExcerpt: String = "",
    val authors: String = "",
)

@Serializable
internal data class LsposedModule(
    val name: String,
    val description: String = "",
    val summary: String = "",
    val homepageUrl: String? = null,
    val sourceUrl: String? = null,
    val collaborators: List<LsposedCollaborator> = emptyList(),
    val releases: List<LsposedRelease> = emptyList(),
    val latestRelease: String? = null,
    val latestReleaseTime: String? = null,
    val readmeHTML: String = "",
    val stargazerCount: Long = 0L,
)

@Serializable
internal data class LsposedCollaborator(
    val login: String = "",
    val name: String = "",
)

@Serializable
internal data class LsposedRelease(
    val name: String = "",
    val tagName: String = "",
    val isDraft: Boolean = false,
    val isPrerelease: Boolean = false,
    val descriptionHTML: String = "",
    val releaseAssets: List<LsposedAsset> = emptyList(),
)

@Serializable
internal data class LsposedAsset(
    val name: String,
    val contentType: String = "",
    val downloadUrl: String = "",
    val originalDownloadUrl: String = "",
    val size: Long = 0L,
) {
    val isApk: Boolean get() = name.endsWith(".apk", ignoreCase = true) ||
        contentType.contains("android.package-archive", ignoreCase = true)

    /** Prefer the GitHub URL: it does not depend on the repository's signed CDN URL expiring. */
    val preferredUrl: String get() = originalDownloadUrl.ifBlank { downloadUrl }
}

internal fun LsposedModule.latestStableRelease(): LsposedRelease? =
    releases.firstOrNull { release ->
        !release.isDraft && !release.isPrerelease && release.releaseAssets.any { it.isApk }
    }
