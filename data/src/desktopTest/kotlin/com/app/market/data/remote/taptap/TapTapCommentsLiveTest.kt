package com.app.market.data.remote.taptap

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

/** Explicit opt-in live smoke for the public TapTap web review endpoint. */
class TapTapCommentsLiveTest {
    @Test
    fun fetchesReviewsForPublicApp() = runBlocking {
        assumeTrue(System.getenv("APP_MARKET_LIVE_TAPTAP") == "1")
        val client = HttpClient(CIO)
        try {
            val api = TapTapApi(client, Json { ignoreUnknownKeys = true; isLenient = true })
            val comments = api.appComments(187168L)
            assertTrue(comments.items.isNotEmpty())
            assertTrue(comments.items.first().content.isNotBlank())
            println("taptap comments: ${comments.items.size}, total ${comments.totalCount}")
        } finally {
            client.close()
        }
    }
}
