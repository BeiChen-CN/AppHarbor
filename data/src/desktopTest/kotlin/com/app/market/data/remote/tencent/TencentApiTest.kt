package com.app.market.data.remote.tencent

import com.app.market.data.repository.TencentRepositoryImpl
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateStatus
import com.app.market.domain.repository.InstalledPackagesRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TencentApiTest {
    @Test
    fun searchEncodesKeywordAndRefreshesInstalledResultsBeforeShowingUpdateAction() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/search") {
                assertEquals("微信 & tools", request.url.parameters["q"])
                respond(searchHtml())
            } else {
                assertEquals(HttpMethod.Post, request.method)
                assertEquals("""{"packagename":"com.tencent.mm"}""", (request.body as TextContent).text)
                respond(TencentTestDetail)
            }
        })
        try {
            val api = TencentApi(client)
            val repository = TencentRepositoryImpl(api, InstalledPackagesRepository { listOf(InstalledPackage(TencentPackage, 1, false)) })
            val page = repository.search("微信 & tools", 0)
            assertEquals(3180L, page.items.single().versionCode)
            assertFalse(page.hasMore)
            assertTrue(repository.search("微信", 1).items.isEmpty())
        } finally { client.close() }
    }

    @Test
    fun exactPackageSearchResolvesAppsAbsentFromWebsiteResults() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            if (request.url.encodedPath == "/search") respond(searchHtml("")) else respond(TencentTestDetail)
        })
        try {
            val repository = TencentRepositoryImpl(TencentApi(client), InstalledPackagesRepository { emptyList() })
            assertEquals(TencentPackage, repository.search(TencentPackage, 0).items.single().packageName)
        } finally { client.close() }
    }

    @Test
    fun downloadRefreshesStaleVersionAndChecksumWithoutFetchingApk() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/wechat-apkinfo", request.url.encodedPath)
            respond(TencentTestDetail)
        })
        try {
            val stale = parseTencentDetail(TencentTestDetail, TencentPackage).detail.app.copy(versionCode = 1)
            val meta = TencentApi(client).downloadMeta(stale)
            assertEquals(3180L, meta.versionCode)
            assertEquals("a".repeat(64), meta.parts.single().hash)
            assertTrue(meta.url.startsWith("https://"))
            assertEquals(280614450L, meta.size)
        } finally { client.close() }
    }

    @Test
    fun updateChecksCompareRealCodesAndRetainLocalMetadata() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            if ((request.body as TextContent).text.contains(TencentPackage)) respond(TencentTestDetail)
            else respond("""{"ret":0,"app_detail_records":{}}""")
        })
        val local = InstalledPackage(TencentPackage, 1, true, "old", baseApkPath = "/base.apk", splits = "split")
        try {
            val repository = TencentRepositoryImpl(TencentApi(client), InstalledPackagesRepository { listOf(local, InstalledPackage("missing.app", 1, false)) })
            val update = repository.checkUpdates().single()
            assertEquals(1L, update.installedVersionCode)
            assertEquals("old", update.installedVersionName)
            assertEquals("/base.apk", update.installedBaseApkPath)
            assertEquals("split", update.installedSplits)
            assertTrue(update.isSystemApp)
            assertEquals(ManualUpdateStatus.UPDATE_AVAILABLE, repository.checkManualUpdate(ManualUpdateRequest(TencentPackage, 1)).status)
            assertEquals(ManualUpdateStatus.RECOGNIZED_NO_UPDATE, repository.checkManualUpdate(ManualUpdateRequest(TencentPackage, 3180)).status)
            assertEquals(ManualUpdateStatus.NOT_FOUND, repository.checkManualUpdate(ManualUpdateRequest("missing.app", 1)).status)
        } finally { client.close() }
    }

    @Test
    fun unavailableAppsDoNotGenerateUpdatesAndDownloadsFail() = runBlocking {
        val client = HttpClient(MockEngine { respond(TencentTestDetail.replace("\"online_status\":1", "\"online_status\":0")) })
        try {
            val api = TencentApi(client)
            val repository = TencentRepositoryImpl(api, InstalledPackagesRepository { listOf(InstalledPackage(TencentPackage, 1, false)) })
            assertTrue(repository.checkUpdates().isEmpty())
            assertEquals(ManualUpdateStatus.RECOGNIZED_NO_UPDATE, repository.checkManualUpdate(ManualUpdateRequest(TencentPackage, 1)).status)
            assertFailsWith<MarketException> { api.downloadMeta(parseTencentDetail(TencentTestDetail, TencentPackage).detail.app) }
            Unit
        } finally { client.close() }
    }

    @Test
    fun serviceFailuresDoNotMasqueradeAsNoUpdates() = runBlocking {
        val client = HttpClient(MockEngine { respond("""{"ret":1,"err_msg":"服务异常"}""") })
        try {
            val repository = TencentRepositoryImpl(TencentApi(client), InstalledPackagesRepository { listOf(InstalledPackage(TencentPackage, 1, false)) })
            assertFailsWith<MarketException> { repository.checkUpdates() }
            Unit
        } finally { client.close() }
    }
}
