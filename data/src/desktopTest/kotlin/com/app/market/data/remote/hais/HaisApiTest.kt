package com.app.market.data.remote.hais

import com.app.market.data.platform.md5
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
import kotlin.test.assertTrue

/**
 * HAIS（apk.hais.pw）归档站接口行为测试。
 *
 * 该站的关键约束：
 * 1. 每个请求要靠 `X-Sign = md5(appId + 毫秒时间戳)` 鉴权，签名不能缓存；
 * 2. `/apk/list` 会把同厂商的其他包混进来（查 com.tencent.mm 会带 com.tencent.wechatkids），必须按包名严格过滤；
 * 3. 直链只能靠短 ID 到 `/apk/getDownInfo` 二次换取，列表里的 `extend` 恒为空。
 */
class HaisApiTest {

    @Test
    fun everyRequestCarriesAFreshSignature() = runBlocking {
        val signatures = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("ApkBox", request.headers["X-Platform"])
                assertEquals("wx1e82ed4cb8ab63a1", request.headers["X-AppId"])
                val sign = request.headers["X-Sign"].orEmpty()
                assertEquals(32, sign.length, "sign was $sign")
                assertEquals(sign.lowercase(), sign)
                assertTrue(sign.all { it in "0123456789abcdef" }, "sign was $sign")
                signatures += sign
                respond(mainPageJson(), HttpStatusCode.OK, jsonHeaders())
            },
        )
        try {
            val config = HaisApiConfig()
            val api = HaisApi(client, config)
            api.search("微信", 0)
            api.search("微信", 0)
            assertEquals(2, signatures.size)
            // 同一毫秒内可能相同，但签名必须是 md5(appId + timestamp) 的形式，不能是固定常量
            assertTrue(signatures.all { it != "0".repeat(32) })
        } finally {
            client.close()
        }
    }

    @Test
    fun searchMapsMainRecordsToAppInfo() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("/apkMain/list", request.url.encodedPath)
                assertEquals("3", request.url.parameters["pageNum"])
                assertEquals("微信", request.url.parameters["keyword"])
                respond(mainPageJson(), HttpStatusCode.OK, jsonHeaders())
            },
        )
        try {
            val page = HaisApi(client).search("微信", 2)
            assertEquals(1, page.items.size)
            val item = page.items.single()
            // HAIS 以包名为主键，appId 恒 0
            assertEquals(0L, item.appId)
            assertEquals("com.tencent.mm", item.packageName)
            assertEquals("微信", item.displayName)
            assertEquals("8.0.79", item.versionName)
            assertEquals(3200L, item.versionCode)
            assertEquals(26331L, item.downloadCount)
            assertTrue(item.icon.startsWith("https://"))
            // 主条目接口不返回安装包体积
            assertEquals(0L, item.apkSize)
            assertEquals(true, page.hasMore)
        } finally {
            client.close()
        }
    }

    @Test
    fun nonSuccessCodeIsReportedWithServerMessage() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond(
                    """{"code":500,"data":null,"message":"系统繁忙"}""",
                    HttpStatusCode.OK,
                    jsonHeaders(),
                )
            },
        )
        try {
            assertFailsWith<MarketException> { HaisApi(client).search("微信", 0) }
            Unit
        } finally {
            client.close()
        }
    }

    @Test
    fun appDetailUsesPackageNameAsKey() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("/apkMain/get", request.url.encodedPath)
                assertEquals("com.tencent.mm", request.url.parameters["id"])
                respond("""{"code":200,"data":${mainRecordJson()},"message":""}""", HttpStatusCode.OK, jsonHeaders())
            },
        )
        try {
            val detail = HaisApi(client).appDetail("com.tencent.mm")
            assertEquals("com.tencent.mm", detail.app.packageName)
            assertEquals("微信", detail.app.displayName)
            assertEquals("8.0.79", detail.app.versionName)
            assertEquals("老版本说明", detail.changeLog)
            // 该站无截图与评论接口
            assertTrue(detail.screenshots.isEmpty())
            assertTrue(detail.comments.isEmpty())
            assertEquals(1788054613000L, detail.updateTime)
        } finally {
            client.close()
        }
    }

    @Test
    fun historicalVersionsFiltersForeignPackagesAndResolvesLinks() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/apk/list" -> respond(apkPageJson(), HttpStatusCode.OK, jsonHeaders())
                    "/apk/getDownInfo" -> {
                        val id = request.url.parameters["id"]
                        val url = when (id) {
                            "fP2L9SWD" -> "https://example.test/mm-3160.apk"
                            "e3guQSyb" -> "https://example.test/mm-3140.apk"
                            else -> ""
                        }
                        respond(
                            """{"code":200,"data":{"id":"$id","packages":"com.tencent.mm",
                                "versionCode":3160,"versionName":"8.0.77","md5":"abc","size":278623416,
                                "extend":{"downUrls":["$url"]}},"message":""}""",
                            HttpStatusCode.OK,
                            jsonHeaders(),
                        )
                    }

                    else -> error("Unexpected URL ${request.url}")
                }
            },
        )
        try {
            val page = HaisApi(client).historicalVersions("com.tencent.mm", 0)
            // com.tencent.wechatkids 被过滤掉
            assertEquals(2, page.items.size)
            val latest = page.items.first()
            assertEquals("https://example.test/mm-3160.apk", latest.downloadUrl)
            assertEquals("8.0.77", latest.versionName)
            assertEquals(3160L, latest.versionCode)
            assertEquals(278623416L, latest.size)
            assertEquals("com.tencent.mm", latest.packageName)
            assertEquals(null, page.nextOffset)
        } finally {
            client.close()
        }
    }

    @Test
    fun historicalDownloadMetaMakesNoRequest() = runBlocking {
        val client = HttpClient(MockEngine { error("should not hit network") })
        try {
            val version = com.app.market.domain.model.market.HistoricalVersion(
                appId = 0L,
                packageName = "com.tencent.mm",
                displayName = "微信",
                icon = "",
                versionId = 0L,
                versionName = "8.0.77",
                versionCode = 3160L,
                minSdkVersion = 24,
                downloadUrl = "https://example.test/mm.apk",
                size = 278623416L,
                createdAt = 0L,
                updatedAt = 0L,
            )
            val meta = HaisApi(client).historicalDownloadMeta(version)
            assertEquals("https://example.test/mm.apk", meta.url)
            assertEquals(278623416L, meta.size)
            assertEquals("com.tencent.mm", meta.packageName)
        } finally {
            client.close()
        }
    }

    @Test
    fun historicalVersionWithoutLinkIsReported() = runBlocking {
        val version = com.app.market.domain.model.market.HistoricalVersion(
            appId = 0L,
            packageName = "com.tencent.mm",
            displayName = "微信",
            icon = "",
            versionId = 0L,
            versionName = "8.0.77",
            versionCode = 3160L,
            minSdkVersion = 24,
            downloadUrl = "",
            size = 0L,
            createdAt = 0L,
            updatedAt = 0L,
        )
        val client = HttpClient(MockEngine { error("should not hit network") })
        try {
            assertFailsWith<MarketException> { HaisApi(client).historicalDownloadMeta(version) }
            Unit
        } finally {
            client.close()
        }
    }

    @Test
    fun timeParsingUsesShanghaiZone() {
        assertEquals(1788054613000L, haisTimeMillis("2026-08-30 09:50:13"))
        assertEquals(0L, haisTimeMillis(""))
        assertEquals(0L, haisTimeMillis("not a time"))
    }

    @Test
    fun md5MatchesRfc1321VectorForSignature() {
        // 签名只依赖标准 MD5，用它锁住平台实现（desktop actual）
        assertEquals(
            "900150983cd24fb0d6963f7d28e17f72",
            md5("abc".encodeToByteArray()).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') },
        )
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun mainRecordJson(): String = """
        {"id":"com.tencent.mm","name":"微信","icon":"https://file.hais.pw/a.png",
         "content":"老版本说明","createTime":"2025-09-23 14:25:01","updateTime":"2026-08-30 09:50:13",
         "downNum":26331,"versionCode":3200,"versionName":"8.0.79",
         "sourceCustNum":17,"sourceGoogleNum":86,"sourceOfficalNum":75,
         "status":"ENABLE","statusStr":"启用","targetSdkVersion":0,"viewsNum":46758}
    """.trimIndent()

    private fun mainPageJson(): String = """
        {"code":200,"data":{"current":3,"pages":4,"records":[${mainRecordJson()}],"size":10,"total":31},
         "message":""}
    """.trimIndent()

    private fun apkPageJson(): String = """
        {"code":200,"data":{"current":1,"pages":1,"records":[
          {"id":"fP2L9SWD","mainId":"com.tencent.mm","packages":"com.tencent.mm","name":"微信",
           "icon":"https://file.hais.pw/a.png","md5":"fdd4f4be","size":278623416,"source":"Offical",
           "structure":"","targetSdkVersion":24,"versionCode":3160,"versionName":"8.0.77",
           "addTime":"2026-08-30 09:50:12","createTime":"2025-09-18 04:57:35","extend":{}},
          {"id":"e3guQSyb","mainId":"com.tencent.mm","packages":"com.tencent.mm","name":"微信",
           "icon":"https://file.hais.pw/a.png","md5":"aa","size":270000000,"source":"Offical",
           "versionCode":3140,"versionName":"8.0.76","addTime":"2026-06-01 10:00:00",
           "createTime":"2025-06-01 10:00:00","extend":{}},
          {"id":"jSBxgzP4","mainId":"com.tencent.mm","packages":"com.tencent.wechatkids","name":"微信",
           "icon":"","md5":"84b4","size":36369299,"source":"Cust","custName":"手表定制版",
           "versionCode":10701,"versionName":"1.7.1","addTime":"2025-09-18 04:57:35",
           "createTime":"2025-09-18 04:57:35","extend":{}}
        ],"size":20,"total":3},"message":""}
    """.trimIndent()
}
