package com.app.market.data.remote.tencent

import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPart
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
import io.ktor.http.isSuccess
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
