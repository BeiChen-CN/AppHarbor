package com.app.market.data.remote.coolapk

import com.app.market.data.repository.CoolapkRepositoryImpl
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateStatus
import com.app.market.domain.repository.InstalledPackagesRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoolapkApiTest {
    @Test
    fun exactPackageSearchUsesDetailWhenNativeKeywordSearchMissesIt() = runBlocking {
        val engine = MockEngine { request ->
            when (request.url.encodedPath) {
                "/v6/search" -> respond("""{"data":[]}""")
                "/v6/apk/detail" -> {
                    assertEquals("com.coolapk.market", request.url.parameters["id"])
                    respond("""{"data":$CoolapkTestApp}""")
                }
                else -> error("Unexpected endpoint")
            }
        }
        val client = HttpClient(engine)
        val api = CoolapkApi(client, CoolapkSigner())
        try {
            val page = api.search("com.coolapk.market", 0)
            assertEquals("com.coolapk.market", page.items.single().packageName)
            assertFalse(page.hasMore)
        } finally { api.close(); client.close() }
    }
    @Test
    fun searchesUseAppEndpointWithEncodedKeywordAndOneBasedPages() = runBlocking {
        val engine = MockEngine { request ->
            assertEquals("/v6/search", request.url.encodedPath)
            assertEquals("apk", request.url.parameters["type"])
            assertEquals("酷安 & tools", request.url.parameters["searchValue"])
            assertEquals("2", request.url.parameters["page"])
            assertTrue(request.headers["X-App-Token"].orEmpty().startsWith("v2"))
            respond("""{"data":[$CoolapkTestApp]}""")
        }
        val client = HttpClient(engine)
        val api = CoolapkApi(client, CoolapkSigner())
        try {
            assertEquals(AppSource.COOLAPK, api.search("酷安 & tools", 1).items.single().source)
        } finally { api.close(); client.close() }
    }

    @Test
    fun downloadResolvesRedirectWithoutFetchingApkAndRefreshesVersionAndChecksum() = runBlocking {
        val paths = mutableListOf<String>()
        val engine = MockEngine { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/v6/apk/detail" -> respond("""{"data":$CoolapkTestApp}""")
                "/v6/apk/download" -> {
                    assertEquals("com.coolapk.market", request.url.parameters["pn"])
                    assertEquals("4599", request.url.parameters["aid"])
                    respond("", HttpStatusCode.Found, headersOf("Location", "https://dl.coolapk.com/package.apk"))
                }
                else -> error("APK must not be fetched during metadata resolution")
            }
        }
        val client = HttpClient(engine)
        val api = CoolapkApi(client, CoolapkSigner())
        try {
            val stale = parseCoolapkDetail("""{"data":$CoolapkTestApp}""").detail.app.copy(versionCode = 1)
            val meta = api.downloadMeta(stale)
            assertEquals(2609291L, meta.versionCode)
            assertEquals("https://dl.coolapk.com/package.apk", meta.url)
            assertEquals("a7f362019530968866f9b3c2e4ccd22b", meta.parts.single().hash)
            assertEquals(listOf("/v6/apk/detail", "/v6/apk/download"), paths)
            assertFalse("X-App-Token" in meta.requestHeaders)
        } finally { api.close(); client.close() }
    }

    @Test
    fun downloadRejectsMissingAndNonHttpRedirects() = runBlocking {
        for (location in listOf("", "intent://open-app", "file:///package.apk")) {
            val engine = MockEngine { request ->
                if (request.url.encodedPath.endsWith("detail")) respond("""{"data":$CoolapkTestApp}""")
                else respond("", HttpStatusCode.Found, headersOf("Location", location))
            }
            val client = HttpClient(engine)
            val api = CoolapkApi(client, CoolapkSigner())
            try {
                val app = parseCoolapkDetail("""{"data":$CoolapkTestApp}""").detail.app
                assertFailsWith<MarketException> { api.downloadMeta(app) }
            } finally { api.close(); client.close() }
        }
    }

    @Test
    fun updatesOnlyIncludeNewerInstalledPackagesAndRetainLocalMetadata() = runBlocking {
        val engine = MockEngine { request ->
            when (request.url.parameters["id"]) {
                "com.coolapk.market" -> respond("""{"data":$CoolapkTestApp}""")
                else -> respond("""{"status":-2,"message":"应用不存在"}""")
            }
        }
        val client = HttpClient(engine)
        val api = CoolapkApi(client, CoolapkSigner())
        val local = InstalledPackage("com.coolapk.market", 1L, true, "old", baseApkPath = "/base.apk", splits = "split")
        val repository = CoolapkRepositoryImpl(api, InstalledPackagesRepository {
            listOf(local, InstalledPackage("missing.package", 1L, false))
        })
        try {
            val update = repository.checkUpdates().single()
            assertEquals(AppSource.COOLAPK, update.source)
            assertEquals(1L, update.installedVersionCode)
            assertEquals("old", update.installedVersionName)
            assertEquals("/base.apk", update.installedBaseApkPath)
            assertEquals("split", update.installedSplits)
            assertTrue(update.isSystemApp)
            assertEquals(ManualUpdateStatus.UPDATE_AVAILABLE, repository.checkManualUpdate(ManualUpdateRequest(local.packageName, 1)).status)
            assertEquals(ManualUpdateStatus.RECOGNIZED_NO_UPDATE, repository.checkManualUpdate(ManualUpdateRequest(local.packageName, 2609291)).status)
            assertEquals(ManualUpdateStatus.NOT_FOUND, repository.checkManualUpdate(ManualUpdateRequest("missing.package", 1)).status)
        } finally { api.close(); client.close() }
    }

    @Test
    fun authenticationFailuresDoNotMasqueradeAsNoUpdates() = runBlocking {
        val engine = MockEngine { respond("""{"status":-1,"message":"认证失败"}""") }
        val client = HttpClient(engine)
        val api = CoolapkApi(client, CoolapkSigner())
        val repository = CoolapkRepositoryImpl(api, InstalledPackagesRepository {
            listOf(InstalledPackage("com.coolapk.market", 1L, false))
        })
        try {
            assertFailsWith<MarketException> { repository.checkUpdates() }
            Unit
        } finally { api.close(); client.close() }
    }
}
