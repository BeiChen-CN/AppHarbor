package com.app.market.data.remote.coolapk

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
class CoolapkLiveTest {
    @Test
    fun sourceGraphResolvesSearchDetailAndDownload() = runBlocking {
        assumeTrue(System.getenv("APP_MARKET_LIVE_COOLAPK") == "1")
        val graph = koinApplication { modules(dataModules) }
        val client = graph.koin.get<HttpClient>()
        try {
            val source = graph.koin.get<MarketSourceRepository>()
            val search = source.search(AppSource.COOLAPK, "com.coolapk.market", 0)
            assertTrue(search.items.any { it.packageName == "com.coolapk.market" })
            val detail = source.appDetail(AppSource.COOLAPK, 0, "com.coolapk.market")
            assertEquals(AppSource.COOLAPK, detail.app.source)
            assertTrue(detail.app.versionCode > 0)
            assertTrue(detail.screenshots.isNotEmpty())
            val meta = source.downloadMeta(AppSource.COOLAPK, detail.app, "")
            assertEquals(detail.app.versionCode, meta.versionCode)
            assertEquals(AppSource.COOLAPK, meta.source)
            assertTrue(meta.parts.single().hash.matches(Regex("[a-fA-F0-9]{32}")))
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
