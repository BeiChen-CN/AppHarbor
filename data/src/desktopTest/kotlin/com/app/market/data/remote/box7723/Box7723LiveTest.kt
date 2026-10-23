package com.app.market.data.remote.box7723

import com.app.market.domain.model.market.AppSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Explicit opt-in live smoke for the public 7723 pages. */
class Box7723LiveTest {
    @Test
    fun resolvesSearchDetailAndDownload() = runBlocking {
        assumeTrue(System.getenv("APP_MARKET_LIVE_7723") == "1")
        val client = HttpClient(CIO)
        try {
            val api = Box7723Api(client)
            val page = api.search("微信", 0)
            assertTrue(page.items.isNotEmpty())
            val hit = page.items.first()
            assertEquals(AppSource.BOX7723, hit.source)
            assertTrue(hit.packageName.isNotBlank())

            val detail = api.appDetail(hit.appId)
            assertEquals(AppSource.BOX7723, detail.app.source)
            assertTrue(detail.app.packageName.contains('.'))
            assertTrue(detail.app.versionCode > 0L)

            val meta = api.downloadMeta(detail.app)
            assertTrue(meta.url.startsWith("https://"))
            assertTrue(meta.size > 0L)
            assertEquals(AppSource.BOX7723, meta.source)
            println("7723: ${detail.app.packageName} ${detail.app.versionName} (${detail.app.versionCode}), ${meta.size} bytes")
        } finally {
            client.close()
        }
    }
}
