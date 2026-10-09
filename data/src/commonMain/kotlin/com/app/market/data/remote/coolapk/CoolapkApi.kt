package com.app.market.data.remote.coolapk

import com.app.market.domain.exception.AppNotListedException
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPart
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class CoolapkApiConfig(val apiBase: String = "https://api2.coolapk.com")

internal class CoolapkApi(
    client: HttpClient,
    private val signer: CoolapkSigner,
    private val config: CoolapkApiConfig = CoolapkApiConfig(),
) {
    // 下载接口只读取 Location，避免解析元数据时跟随重定向下载整个 APK。
    private val client = client.config { followRedirects = false; expectSuccess = false }

    fun close() = client.close()

    suspend fun search(keyword: String, page: Int): SearchPage {
        if (keyword.isBlank() || page !in 0..99) return SearchPage(emptyList(), false)
        val headers = requestHeaders()
        val response = client.get(config.apiBase.trimEnd('/') + "/v6/search") {
            headers.forEach { (key, value) -> header(key, value) }
            parameter("type", "apk")
            parameter("searchValue", keyword.trim())
            parameter("page", page + 1)
        }
        if (!response.status.isSuccess()) throw MarketException("酷安搜索请求失败 HTTP ${response.status.value}")
        val parsed = parseCoolapkSearch(response.bodyAsText())
        // 原生关键词搜索不保证命中完整包名；首页精确包名查询补充自身详情，仍使用酷安数据。
        val packageName = keyword.trim()
        val exact = if (page == 0 && packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+")) &&
            parsed.items.none { it.packageName.equals(packageName, true) }
        ) {
            try { detail(packageName).detail.app } catch (_: AppNotListedException) { null }
        } else null
        return parsed.copy(
            items = exact?.let { listOf(it) + parsed.items } ?: parsed.items,
            hasMore = parsed.hasMore && page < 99,
        )
    }

    suspend fun detail(packageName: String, appId: Long = 0L): CoolapkDetail {
        val id = packageName.ifBlank { appId.takeIf { it > 0 }?.toString().orEmpty() }
        if (id.isBlank()) throw MarketException("酷安缺少包名或应用 id")
        val headers = requestHeaders()
        val response = client.get(config.apiBase.trimEnd('/') + "/v6/apk/detail") {
            headers.forEach { (key, value) -> header(key, value) }
            parameter("id", id)
        }
        if (response.status.value == 404) throw AppNotListedException()
        if (!response.status.isSuccess()) throw MarketException("酷安详情请求失败 HTTP ${response.status.value}")
        val detail = parseCoolapkDetail(response.bodyAsText())
        if (packageName.isNotBlank() && !detail.detail.app.packageName.equals(packageName, true)) {
            throw MarketException("酷安返回的应用包名不匹配")
        }
        return detail
    }

    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        // 重新解析当前版本，保证地址、版本号和校验值属于同一个安装包。
        val record = detail(app.packageName, app.appId)
        val current = record.detail.app
        if (current.downloadBlockReason.isNotBlank()) throw MarketException(current.downloadBlockReason)
        val headers = requestHeaders()
        val response = client.get(config.apiBase.trimEnd('/') + "/v6/apk/download") {
            headers.forEach { (key, value) -> header(key, value) }
            parameter("pn", current.packageName)
            parameter("aid", current.appId)
        }
        if (response.status.value !in 300..399) {
            if (response.status.isSuccess()) coolapkResponse(response.bodyAsText())
            throw MarketException("酷安未提供可用的 APK 下载地址 HTTP ${response.status.value}")
        }
        val location = response.headers[HttpHeaders.Location].orEmpty()
        val url = coolapkHttpsUrl(location)
        if (url.isBlank() || runCatching { Url(url).host.isNotBlank() }.getOrDefault(false).not()) {
            throw MarketException("酷安返回的 APK 下载地址无效")
        }
        return DownloadMeta(
            appId = current.appId,
            packageName = current.packageName,
            displayName = current.displayName,
            versionName = current.versionName,
            versionCode = current.versionCode,
            url = url,
            size = current.apkSize,
            parts = listOf(DownloadPart("", "base", url, current.apkSize, hash = record.md5)),
            installedBaseApkPath = app.installedBaseApkPath,
            icon = current.icon,
            changeLog = current.changeLog,
            // Token 仅用于 API；CDN 下载不需要携带设备指纹。
            requestHeaders = mapOf(HttpHeaders.UserAgent to CoolapkUserAgent),
            source = current.source,
        )
    }

    private suspend fun requestHeaders(): Map<String, String> = withContext(Dispatchers.Default) { signer.headers() }
}
