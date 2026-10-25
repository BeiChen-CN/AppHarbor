package com.app.market.data.repository

import com.app.market.data.remote.releases.ReleaseApkIdentity
import com.app.market.data.remote.releases.ReleaseApkInspector
import com.app.market.domain.exception.MarketException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AppUpdateRepositoryTest {
    private val inspector = object : ReleaseApkInspector {
        override suspend fun inspect(url: String, size: Long) =
            ReleaseApkIdentity("com.app.market", "9.9.9", 999, size)
    }

    @Test
    fun parsesLatestReleaseAndVersionCodeFromAssetName() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(
                """{
                    "tag_name":"v2.14.0","name":"Trove v2.14.0","body":"## Fixes",
                    "html_url":"https://github.com/BeiChen-CN/AppHarbor/releases/tag/v2.14.0",
                    "draft":false,"prerelease":false,
                    "assets":[{"name":"Trove-v2.14.0-242.apk","browser_download_url":"https://example.test/Trove.apk","size":12345}]
                }""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        try {
            val info = AppUpdateRepositoryImpl(client, inspector).latest()!!
            assertEquals("2.14.0", info.versionName)
            assertEquals(242L, info.versionCode)
            assertEquals("Trove-v2.14.0-242.apk", info.assetName)
            assertEquals("https://example.test/Trove.apk", info.downloadUrl)
            assertEquals(12345L, info.size)
            assertEquals("## Fixes", info.changelog)
        } finally {
            client.close()
        }
    }

    @Test
    fun rateLimitIsReportedAndMissingApkReturnsNull() = runBlocking {
        val limited = HttpClient(MockEngine {
            respond("", status = HttpStatusCode.Forbidden)
        })
        try {
            assertFailsWith<MarketException> { AppUpdateRepositoryImpl(limited, inspector).latest() }
        } finally {
            limited.close()
        }
        val empty = HttpClient(MockEngine {
            respond(
                """{"tag_name":"v9.9.9","assets":[]}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        try {
            assertNull(AppUpdateRepositoryImpl(empty, inspector).latest())
        } finally {
            empty.close()
        }
    }
}
