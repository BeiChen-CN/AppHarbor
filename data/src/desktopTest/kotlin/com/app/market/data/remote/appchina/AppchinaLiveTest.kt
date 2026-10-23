package com.app.market.data.remote.appchina

import com.app.market.data.repository.AppchinaRepositoryImpl
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateStatus
import com.app.market.domain.repository.InstalledPackagesRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Explicit opt-in live smoke against the public AppChina pages. */
class AppchinaLiveTest {
    @Test
    fun resolvesSearchDetailAndDownloadThroughPublicPages() = runBlocking {
        assumeTrue(System.getenv("APP_MARKET_LIVE_APPCHINA") == "1")
        val client = HttpClient(CIO)
        try {
            val api = AppchinaApi(client)
            val page = api.search("微信", 0)
            val app = page.items.first { it.packageName == "com.tencent.mm" }
            assertEquals(AppSource.APPCHINA, app.source)
            assertTrue(app.appId > 0L)
            assertTrue(app.versionCode > 0L)

            val detail = api.appDetail(app.appId, app.packageName).app
            assertEquals("com.tencent.mm", detail.packageName)
            assertTrue(detail.versionName.isNotBlank())
            assertTrue(detail.versionCode > 0L)
            assertTrue(detail.apkSize > 0L)

            val meta = api.downloadMeta(detail)
            assertTrue(meta.url.startsWith("https://"))
            assertTrue(meta.size > 0L)
            assertEquals(AppSource.APPCHINA, meta.source)
            println("appchina: ${detail.packageName} ${detail.versionName} (${detail.versionCode}), ${meta.size} bytes")

            val repository = AppchinaRepositoryImpl(api, InstalledPackagesRepository {
                listOf(InstalledPackage("com.tencent.mm", versionCode = 1, isSystemApp = false, versionName = "old"))
            })
            val updates = repository.checkUpdates()
            assertTrue(updates.any { it.packageName == "com.tencent.mm" })
            assertEquals(ManualUpdateStatus.UPDATE_AVAILABLE,
                repository.checkManualUpdate(ManualUpdateRequest("com.tencent.mm", 1)).status)
            println("appchina update: ${updates.first().versionName} (${updates.first().versionCode})")

            val comments = api.appComments(detail)
            assertTrue(comments.items.isNotEmpty())
            assertTrue(comments.totalCount > 0L)
            println("appchina comments: ${comments.items.size}, total ${comments.totalCount}")

            val history = api.historicalVersions(detail.appId, detail.packageName, 0)
            assertTrue(history.items.isNotEmpty())
            val previous = history.items.first()
            assertTrue(previous.versionCode > 0L)
            assertTrue(previous.versionName.isNotBlank())
            val previousMeta = api.historicalDownloadMeta(previous)
            assertTrue(previousMeta.url.startsWith("https://"))
            assertTrue(previousMeta.size > 0L)
            println("appchina history: ${previous.versionName} (${previous.versionCode}), ${previousMeta.size} bytes")
        } finally {
            client.close()
        }
    }
}
