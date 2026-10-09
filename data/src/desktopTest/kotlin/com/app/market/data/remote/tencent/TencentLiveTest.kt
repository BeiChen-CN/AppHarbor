package com.app.market.data.remote.tencent

import com.app.market.di.dataModules
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.repository.MarketSourceRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 显式开启后验证实际 DI、来源路由与 CDN；只请求元数据和 HEAD，不下载完整 APK。 */
class TencentLiveTest {
    @Test
    fun sourceGraphResolvesSearchDetailAndDownload() = runBlocking {
        assumeTrue(System.getenv("APP_MARKET_LIVE_TENCENT") == "1")
        val graph = koinApplication { modules(dataModules) }
        val client = graph.koin.get<HttpClient>()
        try {
            val source = graph.koin.get<MarketSourceRepository>()
            val search = source.search(AppSource.TENCENT, "微信", 0)
            assertTrue(search.items.any { it.packageName == "com.tencent.mm" })
            val detail = source.appDetail(AppSource.TENCENT, 0, "com.tencent.mm")
            assertEquals(AppSource.TENCENT, detail.app.source)
            assertTrue(detail.app.versionCode > 0)
            assertTrue(detail.screenshots.isNotEmpty())
            val meta = source.downloadMeta(AppSource.TENCENT, detail.app, "")
            assertEquals(detail.app.versionCode, meta.versionCode)
            assertEquals(AppSource.TENCENT, meta.source)
            assertTrue(meta.parts.single().hash.matches(Regex("[a-fA-F0-9]{64}")))
            val response = client.head(meta.url) {
                meta.requestHeaders.forEach { (name, value) -> header(name, value) }
            }
            assertTrue(response.status.isSuccess(), "CDN status: ${response.status}")
            assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().contains("android.package-archive"))
            assertEquals(meta.size, response.headers[HttpHeaders.ContentLength]?.toLongOrNull())
        } finally {
            graph.koin.get<CoroutineScope>().cancel()
            graph.close()
            client.close()
        }
    }
}
