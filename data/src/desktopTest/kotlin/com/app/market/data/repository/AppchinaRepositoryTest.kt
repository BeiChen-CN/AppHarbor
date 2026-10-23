package com.app.market.data.repository

import com.app.market.data.remote.appchina.AppchinaApi
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateStatus
import com.app.market.domain.repository.InstalledPackagesRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppchinaRepositoryTest {
    @Test
    fun checkUpdatesUsesPerPackageSearchVersion() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/ajax/search/com.tencent.mm/1" -> """{"nextPage":null,"list":[{
                    "packageName":"com.tencent.mm","name":"微信","iconUrl":"https://static.example/icon.png",
                    "size":"272.07MB","apkUrl":"https://mobile.d.appchina.com/McDonald/e/7714376/0/0/0/1/package_3200.1"
                }]}"""
                "/app/com.tencent.mm" -> """
                    <html><body>
                    <div class="app-details-appname"><h1>微信</h1></div>
                    <a class="normal-down-btn" href="https://mobile.d.appchina.com/McDonald/e/7714376/0/0/0/1/package_3200.1">下载</a>
                    <script>var packageName = 'com.tencent.mm';</script>
                    </body></html>
                """.trimIndent()
                else -> """{"nextPage":null,"list":[]}"""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val installed = InstalledPackagesRepository {
            listOf(InstalledPackage("com.tencent.mm", versionCode = 100, isSystemApp = false, versionName = "8.0.1"))
        }
        try {
            val repository = AppchinaRepositoryImpl(AppchinaApi(client), installed)
            val update = repository.checkUpdates().single()
            assertEquals("com.tencent.mm", update.packageName)
            assertEquals(3200L, update.versionCode)
            assertEquals(100L, update.installedVersionCode)

            val manual = repository.checkManualUpdate(ManualUpdateRequest("com.tencent.mm", 100))
            assertEquals(ManualUpdateStatus.UPDATE_AVAILABLE, manual.status)
            assertEquals(3200L, manual.app?.versionCode)
            assertEquals(ManualUpdateStatus.RECOGNIZED_NO_UPDATE,
                repository.checkManualUpdate(ManualUpdateRequest("com.tencent.mm", 3200)).status)
            assertTrue(repository.checkManualUpdate(ManualUpdateRequest("not.installed", 1)).status == ManualUpdateStatus.NOT_FOUND)
        } finally {
            client.close()
        }
    }
}
