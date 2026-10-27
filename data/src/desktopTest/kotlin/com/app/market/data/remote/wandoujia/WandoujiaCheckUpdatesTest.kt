package com.app.market.data.remote.wandoujia

import com.app.market.data.platform.gunzip
import com.app.market.data.platform.gzip
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateStatus
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WandoujiaCheckUpdatesTest {
    @Test
    fun onlyNewerVersionsAreReportedWithInstalledState() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                assertTrue(request.url.encodedPath.endsWith("/api/resource.app.checkUpdateV1"))
                val body = gunzip(wandoujiaM90DecodeRequest(request.body.toByteArray())).decodeToString()
                assertTrue(body.contains("\"packageName\":\"com.example.newer\""))
                assertTrue(body.contains("\"packageName\":\"com.example.same\""))
                updatesResponse(
                    """
                    {"state":{"code":2000000},"data":{"content":[
                      {"id":11,"packageName":"com.example.newer","name":"Newer","versionName":"2.0",
                       "versionCode":200,"totalSize":1024,"score":8},
                      {"id":12,"packageName":"com.example.same","name":"Same","versionName":"1.0",
                       "versionCode":100,"totalSize":1024,"score":8}
                    ]}}
                    """.trimIndent()
                )
            }
        )
        try {
            val updates = WandoujiaApi(client).checkUpdates(
                listOf(
                    InstalledPackage(
                        packageName = "com.example.newer",
                        versionCode = 100L,
                        isSystemApp = false,
                        versionName = "1.0",
                        baseApkPath = "/data/app/newer/base.apk",
                    ),
                    InstalledPackage(
                        packageName = "com.example.same",
                        versionCode = 100L,
                        isSystemApp = true,
                        versionName = "1.0",
                    ),
                )
            )
            assertEquals(1, updates.size)
            val update = updates.single()
            assertEquals("com.example.newer", update.packageName)
            assertEquals(200L, update.versionCode)
            assertEquals(11L, update.appId)
            assertEquals("1.0", update.installedVersionName)
            assertEquals("/data/app/newer/base.apk", update.installedBaseApkPath)
        } finally {
            client.close()
        }
    }

    @Test
    fun manualUpdateReportsUpgradeAndMissingApp() = runBlocking {
        val client = HttpClient(
            MockEngine {
                updatesResponse(
                    """
                    {"state":{"code":2000000},"data":{"content":[
                      {"id":11,"packageName":"com.example.newer","name":"Newer","versionName":"2.0",
                       "versionCode":200,"totalSize":1024,"score":8}
                    ]}}
                    """.trimIndent()
                )
            }
        )
        try {
            val api = WandoujiaApi(client)
            val upgrade = api.checkManualUpdate(
                ManualUpdateRequest(
                    packageName = "com.example.newer",
                    versionCode = 100L,
                    versionName = "1.0",
                    isSystemApp = false,
                )
            )
            assertEquals(ManualUpdateStatus.UPDATE_AVAILABLE, upgrade.status)
            assertEquals(100L, upgrade.app?.installedVersionCode)
            assertEquals("1.0", upgrade.app?.installedVersionName)

            val absent = api.checkManualUpdate(
                ManualUpdateRequest(packageName = "com.example.absent", versionCode = 1L)
            )
            assertEquals(ManualUpdateStatus.NOT_FOUND, absent.status)
        } finally {
            client.close()
        }
    }

    @Test
    fun manualUpdateReportsNoUpdateWhenStoredVersionIsNotNewer() = runBlocking {
        val client = HttpClient(
            MockEngine {
                updatesResponse(
                    """
                    {"state":{"code":2000000},"data":{"content":[
                      {"id":11,"packageName":"com.example.newer","name":"Newer","versionName":"2.0",
                       "versionCode":200,"totalSize":1024,"score":8}
                    ]}}
                    """.trimIndent()
                )
            }
        )
        try {
            val result = WandoujiaApi(client).checkManualUpdate(
                ManualUpdateRequest(packageName = "com.example.newer", versionCode = 200L)
            )
            assertEquals(ManualUpdateStatus.RECOGNIZED_NO_UPDATE, result.status)
        } finally {
            client.close()
        }
    }

    /** 应答要按服务端密钥重新加密，否则 [wandoujiaM90DecodeResponse] 校验会失败。 */
    private fun MockRequestHandleScope.updatesResponse(json: String) = respond(
        content = wandoujiaM90EncodeResponse(gzip(json.encodeToByteArray())),
        status = HttpStatusCode.OK,
        headers = headersOf("Content-Type", "application/octet-stream"),
    )
}
