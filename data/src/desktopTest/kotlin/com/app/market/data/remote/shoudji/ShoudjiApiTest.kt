package com.app.market.data.remote.shoudji

import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.market.AppSource
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
 * 手机乐园接口的行为测试。
 *
 * 站点所有页面都挂在阿里云 ESA 边缘，未携带正确 `acw_sc__v2` 时返回 JS 挑战页；
 * 这里用 MockEngine 复现「挑战 → 重算 cookie → 重放」的完整链路。
 */
class ShoudjiApiTest {

    @Test
    fun acwScV2MatchesTheBrowserChallengeResult() {
        // 种子与期望值来自浏览器实际执行挑战脚本的结果，是算法的唯一可信基准。
        assertEquals(
            "6adf508b1a4b6d09f787f42c3d489ceafd98985f",
            acwScV2("EF1AA9EFA1221057D5C7D0FFFBD8D5E449AABBF0"),
        )
    }

    @Test
    fun acwScV2RejectsWrongLengthSeeds() {
        assertEquals("", acwScV2(""))
        assertEquals("", acwScV2("ABC"))
        assertEquals("", acwScV2("EF1AA9EFA1221057D5C7D0FFFBD8D5E449AABBF0FF"))
    }

    @Test
    fun retriesUntilChallengeCookieIsAccepted() = runBlocking {
        val seed = "EF1AA9EFA1221057D5C7D0FFFBD8D5E449AABBF0"
        val expectedCookie = acwScV2(seed)
        val challengePage = "<html><script>var arg1='$seed';(function(a,c){var G=a0j;})(window);</script></html>"
        var hits = 0
        val client = HttpClient(
            MockEngine { request ->
                hits += 1
                if (hits == 1) {
                    assertEquals(null, request.headers[HttpHeaders.Cookie]?.takeIf { it.contains("acw_sc__v2") })
                    respond(challengePage, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                } else {
                    val cookie = request.headers[HttpHeaders.Cookie].orEmpty()
                    assertTrue(cookie.contains("acw_sc__v2=$expectedCookie"), "cookie was $cookie")
                    respond(realDetailPage(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
                }
            },
        )
        try {
            val api = ShoudjiApi(client, ShoudjiApiConfig(challengeDelayMillis = 0L))
            val detail = api.appDetail(1608387L, "com.schunshang.bij.shick")
            assertEquals("com.schunshang.bij.shick", detail.app.packageName)
            assertEquals("1.1.4", detail.app.versionName)
            assertEquals("小柠檬追剧", detail.app.displayName)
            assertTrue(detail.app.apkSize > 0L)
            assertEquals(2, detail.screenshots.size)
            assertEquals(AppSource.SHOUDJI, detail.app.source)
            assertEquals(1792734300000L, detail.updateTime)
        } finally {
            client.close()
        }
    }

    @Test
    fun searchSkipsHitsWithoutPackageName() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/search/" -> respond(
                        searchPageHtml(),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/html"),
                    )

                    "/downs/detail/1608387/2" -> respond(
                        """{"code":1,"data":{"name":"小柠檬追剧","and_pkgname":"com.schunshang.bij.shick",
                            "and_ver":"1.1.4","icon":"https://pic.shouji.com.cn/a.png","type":"多媒体",
                            "and_url":"https://example.test/a.apk","and_size_format":80936.96}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                    // 站外搬运包没有包名，应被跳过而不是产出无法安装的条目。
                    "/downs/detail/1670978/2" -> respond(
                        """{"code":1,"data":{"name":"无包名应用","and_pkgname":"","and_ver":"2.0",
                            "icon":"","type":"其他","and_url":"https://example.test/b.apk","and_size":"1"}}""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "application/json"),
                    )

                    else -> error("Unexpected URL ${request.url}")
                }
            },
        )
        try {
            val page = ShoudjiApi(client).search("微信", 0)
            assertEquals(1, page.items.size)
            val item = page.items.single()
            assertEquals("com.schunshang.bij.shick", item.packageName)
            assertEquals("小柠檬追剧", item.displayName)
            assertEquals(80936L, item.apkSize)
            assertEquals(AppSource.SHOUDJI, item.source)
            assertEquals(false, page.hasMore)
        } finally {
            client.close()
        }
    }

    @Test
    fun searchHasNoPaginationSoLaterPagesAreEmpty() = runBlocking {
        val client = HttpClient(MockEngine { error("should not hit network") })
        try {
            val page = ShoudjiApi(client).search("微信", 1)
            assertTrue(page.items.isEmpty())
            assertEquals(false, page.hasMore)
        } finally {
            client.close()
        }
    }

    @Test
    fun downloadMetaUsesDetailEndpointLink() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond(
                    """{"code":1,"data":{"name":"小柠檬追剧","and_pkgname":"com.schunshang.bij.shick",
                        "and_ver":"1.1.4","icon":"","type":"多媒体",
                        "and_url":"https://example.test/a.apk","and_size_format":80936.96}}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        try {
            val app = com.app.market.domain.model.market.MarketAppInfo(
                appId = 1608387L,
                packageName = "com.schunshang.bij.shick",
                displayName = "小柠檬追剧",
                publisherName = "",
                versionName = "",
                versionCode = 0L,
                icon = "",
                apkSize = 0L,
                ratingScore = 0.0,
                source = AppSource.SHOUDJI,
            )
            val meta = ShoudjiApi(client).downloadMeta(app)
            assertEquals("https://example.test/a.apk", meta.url)
            assertEquals(80936L, meta.size)
            assertEquals(AppSource.SHOUDJI, meta.source)
            assertEquals("com.schunshang.bij.shick", meta.packageName)
        } finally {
            client.close()
        }
    }

    @Test
    fun detailWithoutIdIsReported() = runBlocking {
        val client = HttpClient(MockEngine { error("should not hit network") })
        try {
            assertFailsWith<MarketException> { ShoudjiApi(client).appDetail(0L, "com.x.y") }
            Unit
        } finally {
            client.close()
        }
    }

    @Test
    fun displaySizeParsing() {
        assertEquals(1024L, parseDisplaySize("1KB"))
        assertEquals(1024L * 1024, parseDisplaySize("1M"))
        assertEquals(1024L * 1024, parseDisplaySize("1 MB"))
        assertEquals(1024L * 1024 * 1024, parseDisplaySize("1GB"))
        assertEquals(0L, parseDisplaySize(""))
        assertEquals(0L, parseDisplaySize("暂无"))
    }

    @Test
    fun timeParsingHandlesSiteFormats() {
        assertEquals(1792734300000L, shoudjiTimeMillis("2026/10/23 13:45"))
        assertEquals(1792734300000L, shoudjiTimeMillis("2026-10-23 13:45:00"))
        assertEquals(0L, shoudjiTimeMillis(""))
        assertEquals(0L, shoudjiTimeMillis("暂无资料"))
    }

    private fun realDetailPage(): String = """
        <html><head><title>小柠檬追剧下载-小柠檬追剧手机版下载 - 手机乐园</title></head>
        <body>
        <div class="imgBox"><img src="https://pic.shouji.com.cn/shoujicn/imgfile/1.png"></div>
        <h1>小柠檬追剧</h1>
        <div class="basebox bq_wrap" id="baseInfo"><ul>
          <li>分类：<a href="https://m.shouji.com.cn/applist/soft_3400_1.html">多媒体</a></li>
          <li>大小：<span>0.077M</span></li>
          <li>版本：<span>1.1.4</span></li>
          <li class="cs">更新时间：<span>2026/10/23 13:45</span></li>
          <li class="cs">MD5：<span>ED265071D40AE8DB2DACD316693443DA</span></li>
          <li class="cs">包名：<span>com.schunshang.bij.shick</span></li>
          <li class="cs">厂商：<span>暂无资料</span></li>
        </ul></div>
        <div class="downBtn and_show downbtn" id="1608387" type="2"></div>
        <div class="img_item Qimgs" id="swiperContont"><ul>
          <li><img src="https://pic.shouji.com.cn/shot1.jpg" alt="小柠檬追剧截图1"/></li>
          <li><img src="https://pic.shouji.com.cn/shot2.jpg" alt="小柠檬追剧截图2"/></li>
        </ul></div>
        <div class="txtcont" id="detailCount">小柠檬追剧是一款追剧工具。<br>支持多平台。</div>
        <div class="txt_foot">展开</div>
        </body></html>
    """.trimIndent()

    private fun searchPageHtml(): String = """
        <html><head><title>微信_站内搜索</title></head><body>
        <div class="result f s0" >
          <h3 class="c-title">
            <a rpos="" cpos="title" href="https://www.shouji.com.cn/down/1608387.html" target="_blank"><em>微信</em>手机版下载-小柠檬追剧</a>
          </h3>
        </div>
        <div class="result f s0" >
          <h3 class="c-title">
            <a rpos="" cpos="title" href="https://www.shouji.com.cn/down/1670978.html" target="_blank"><em>微信</em>下载-无包名应用</a>
          </h3>
        </div>
        </body></html>
    """.trimIndent()
}
