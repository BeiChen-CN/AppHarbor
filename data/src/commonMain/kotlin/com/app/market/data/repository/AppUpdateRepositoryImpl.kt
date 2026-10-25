package com.app.market.data.repository

import com.app.market.data.remote.releases.ReleaseApkInspector
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.update.AppUpdateInfo
import com.app.market.domain.repository.AppUpdateRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException

/**
 * Reads the app's own public release feed. The release asset is inspected only when its name does
 * not already carry a versionCode, so a check normally costs one API request.
 */
internal class AppUpdateRepositoryImpl(
    private val client: HttpClient,
    private val inspector: ReleaseApkInspector,
) : AppUpdateRepository {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    override suspend fun latest(): AppUpdateInfo? {
        val response = client.get(LATEST_RELEASE_URL) {
            header(HttpHeaders.UserAgent, "Trove")
            header(HttpHeaders.Accept, "application/vnd.github+json")
            header("X-GitHub-Api-Version", "2022-11-28")
        }
        if (response.status.value == 404) return null
        if (response.status.value == 403 || response.status.value == 429) {
            throw MarketException("GitHub 请求次数已达上限，请稍后重试")
        }
        if (response.status.value !in 200..299) {
            throw MarketException("检查更新失败：HTTP ${response.status.value}")
        }
        val release = json.decodeFromString<GithubRelease>(response.bodyAsText())
        if (release.draft || release.prerelease) return null
        val asset = release.assets
            .filter { it.name.endsWith(".apk", ignoreCase = true) }
            .filterNot { Regex("(?i)(debug|unsigned|unaligned)").containsMatchIn(it.name) }
            .maxByOrNull { if (it.name.contains("trove", ignoreCase = true)) 1 else 0 }
            ?: return null
        val tagVersion = release.tagNamed.removePrefix("v").removePrefix("V")
        val assetCode = VERSION_CODE_IN_ASSET.find(asset.name)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val identity = if (assetCode > 0L) null else inspect(asset.browserDownloadUrl, asset.size)
        val versionCode = assetCode.takeIf { it > 0L } ?: identity?.versionCode ?: return null
        return AppUpdateInfo(
            versionName = identity?.versionName?.takeIf { it.isNotBlank() } ?: tagVersion,
            versionCode = versionCode,
            changelog = release.body.orEmpty(),
            releaseUrl = release.htmlUrl,
            assetName = asset.name,
            downloadUrl = asset.browserDownloadUrl,
            size = asset.size.takeIf { it > 0L } ?: identity?.size ?: 0L,
        )
    }

    private suspend fun inspect(url: String, size: Long): com.app.market.data.remote.releases.ReleaseApkIdentity? =
        try {
            inspector.inspect(url, size)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }

    @Serializable
    private data class GithubRelease(
        @SerialName("tag_name") val tagNamed: String = "",
        val name: String = "",
        val body: String? = null,
        @SerialName("html_url") val htmlUrl: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<GithubAsset> = emptyList(),
    )

    @Serializable
    private data class GithubAsset(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
        val size: Long = 0L,
    )

    private companion object {
        const val LATEST_RELEASE_URL = "https://api.github.com/repos/BeiChen-CN/AppHarbor/releases/latest"
        val VERSION_CODE_IN_ASSET = Regex("""-(\d+)\.apk$""", RegexOption.IGNORE_CASE)
    }
}
