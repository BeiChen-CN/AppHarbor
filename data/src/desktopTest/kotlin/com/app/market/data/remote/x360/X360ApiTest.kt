package com.app.market.data.remote.x360

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
 * 360 手机助手接口行为测试。
 *
 * 该源的两个特点决定了这些用例：
 * 1. 搜索接口会按关键词稳定返回 `applist: null`（服务端限流），实现必须重试后明确报错；
 * 2. 下载直链只出现在详情页尾部的内联 `appInfo` 对象里，搜索列表虽然也给了 `down_url`，
 *    但详情页解析失败时要能拿到包名与直链兜底。
 */
class X360ApiTest {

    @Test
    fun searchRetriesThenParsesAppList() = runBlocking {
        var attempts = 0
        val client = HttpClient(
            MockEngine {
                attempts += 1
                if (attempts == 1) {
                    respond("""{"kw":"淘宝","applist":null}""", HttpStatusCode.OK, jsonHeaders())
                } else {
                    respond(searchJson(), HttpStatusCode.OK, jsonHeaders())
                }
            },
        )
        try {
            val api = X360Api(client, X360ApiConfig(searchRetryDelayMillis = 0L))
            val page = api.search("淘宝", 0)
            assertEquals(2, attempts)
            assertEquals(1, page.items.size)
            val item = page.items.single()
            assertEquals(3570L, item.appId)
            assertEquals("com.taobao.taobao", item.packageName)
            assertEquals("淘宝", item.displayName)
            assertEquals("10.68.0", item.versionName)
            assertEquals(864L, item.versionCode)
            assertEquals(77025152L, item.apkSize)
            assertEquals(9.1, item.ratingScore)
            assertEquals(954866431L, item.downloadCount)
            assertEquals(AppSource.X360, item.source)
            assertEquals("https://m.app.so.com/detail/index?pname=com.taobao.taobao&id=3570", item.openLink)
            // 首屏满页即认为还有下一页
            assertEquals(false, page.hasMore)
        } finally {
            client.close()
        }
    }

    @Test
    fun searchGivesUpAfterAllRetriesAndExplainsWhy() = runBlocking {
        var attempts = 0
        val client = HttpClient(
            MockEngine {
                attempts += 1
                respond("""{"kw":"微信","applist":null}""", HttpStatusCode.OK, jsonHeaders())
            },
        )
        try {
            val api = X360Api(client, X360ApiConfig(searchRetries = 3, searchRetryDelayMillis = 0L))
            assertFailsWith<MarketException> { api.search("微信", 0) }
            assertEquals(3, attempts)
        } finally {
            client.close()
        }
    }

    @Test
    fun detailParsesMetaScreenshotsAndInlineDownloadUrl() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("/detail/index", request.url.encodedPath)
                assertEquals("com.taobao.taobao", request.url.parameters["pname"])
                assertEquals("3570", request.url.parameters["id"])
                respond(detailHtml(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            },
        )
        try {
            val detail = X360Api(client).appDetail(3570L, "com.taobao.taobao")
            assertEquals("com.taobao.taobao", detail.app.packageName)
            assertEquals("淘宝", detail.app.displayName)
            assertEquals("10.68.0", detail.app.versionName)
            assertEquals("浙江淘宝网络有限公司", detail.app.publisherName)
            assertEquals(9.1, detail.app.ratingScore)
            assertEquals(954870000L, detail.app.downloadCount)
            // 详情页只有「73.46Mb」展示值，换算回字节是近似的（精确值来自搜索接口）
            assertEquals(77028392L, detail.app.apkSize)
            assertEquals(2, detail.screenshots.size)
            assertEquals("https://terms.alicdn.com/privacy", detail.privacyUrl)
            assertTrue(detail.introduction.contains("淘宝"))
            assertEquals(1792549962000L, detail.updateTime)
            assertEquals(AppSource.X360, detail.app.source)
        } finally {
            client.close()
        }
    }

    @Test
    fun downloadMetaReadsInlineAppInfo() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond(detailHtml(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            },
        )
        try {
            val app = com.app.market.domain.model.market.MarketAppInfo(
                appId = 3570L,
                packageName = "com.taobao.taobao",
                displayName = "淘宝",
                publisherName = "",
                versionName = "10.68.0",
                versionCode = 864L,
                icon = "",
                apkSize = 0L,
                ratingScore = 0.0,
                source = AppSource.X360,
            )
            val meta = X360Api(client).downloadMeta(app)
            assertEquals("https://s.shouji.qihucdn.com/261019/abc/com.taobao.taobao_864.apk?tok=1&f=z.apk", meta.url)
            // app 未给精确体积时退回详情页的展示值
            assertEquals(77028392L, meta.size)
            assertEquals(864L, meta.versionCode)
            assertEquals(AppSource.X360, meta.source)
            assertTrue(meta.requestHeaders.containsKey(HttpHeaders.UserAgent))
        } finally {
            client.close()
        }
    }

    @Test
    fun downloadMetaWithoutLinkIsReported() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond("<html><body>no appInfo here</body></html>", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/html"))
            },
        )
        try {
            val app = com.app.market.domain.model.market.MarketAppInfo(
                appId = 1L,
                packageName = "com.x.y",
                displayName = "X",
                publisherName = "",
                versionName = "",
                versionCode = 0L,
                icon = "",
                apkSize = 0L,
                ratingScore = 0.0,
                source = AppSource.X360,
            )
            assertFailsWith<MarketException> { X360Api(client).downloadMeta(app) }
            Unit
        } finally {
            client.close()
        }
    }

    @Test
    fun commentsNeedAndroidSuffixOnName() = runBlocking {
        val client = HttpClient(
            MockEngine { request ->
                assertEquals("淘宝android", request.url.parameters["name"])
                respond(commentsJson(), HttpStatusCode.OK, jsonHeaders())
            },
        )
        try {
            val app = com.app.market.domain.model.market.MarketAppInfo(
                appId = 3570L,
                packageName = "com.taobao.taobao",
                displayName = "淘宝",
                publisherName = "",
                versionName = "",
                versionCode = 0L,
                icon = "",
                apkSize = 0L,
                ratingScore = 0.0,
                source = AppSource.X360,
            )
            val comments = X360Api(client).appComments(app)
            assertEquals(1, comments.items.size)
            assertEquals("360U2673226799", comments.items.single().userName)
            assertEquals(5.0, comments.items.single().score)
            // 空内容评论被丢弃
            assertEquals(1L, comments.totalCount)
        } finally {
            client.close()
        }
    }

    @Test
    fun commentsWithoutNameAreReportedInsteadOfEmpty() = runBlocking {
        val client = HttpClient(MockEngine { error("should not hit network") })
        try {
            val app = com.app.market.domain.model.market.MarketAppInfo(
                appId = 1L,
                packageName = "com.x.y",
                displayName = "  ",
                publisherName = "",
                versionName = "",
                versionCode = 0L,
                icon = "",
                apkSize = 0L,
                ratingScore = 0.0,
                source = AppSource.X360,
            )
            assertFailsWith<MarketException> { X360Api(client).appComments(app) }
            Unit
        } finally {
            client.close()
        }
    }

    @Test
    fun timeParsing() {
        assertEquals(1792549962000L, x360TimeMillis("2026-10-21 10:32:42"))
        assertEquals(0L, x360TimeMillis(""))
        assertEquals(0L, x360TimeMillis("not a date"))
    }

    @Test
    fun htmlTextCleanupKeepsLineBreaks() {
        assertEquals("第一行\n第二行", "第一行<br/>第二行".cleanHtmlText())
        assertEquals("a&b", "a&amp;b".cleanHtmlText())
        assertEquals("文字", "<b>文字</b>".cleanHtmlText())
    }

    private fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    private fun searchJson(): String = """
        {"kw":"淘宝","applist":[
          {"id":"3570","apkid":"com.taobao.taobao","name":"淘宝","market_name":"淘宝",
           "version_name":"10.68.0","version_code":"864","size":"77025152","rating":"9.1",
           "download_times":"954866431","logo_url":"https://p0.qhimg.com/logo.png",
           "down_url":"http://s.shouji.qihucdn.com/x.apk"}
        ]}
    """.trimIndent()

    private fun commentsJson(): String = """
        [
          {"content":"为什么安装上","username":"360U2673226799","score":5,"create_time":"2026-10-11 10:17:28",
           "image_url":"http://p1.qhmsg.com/a.jpg","msgid":"64469110","type":"best"},
          {"content":"   ","username":"路人","score":3,"create_time":"2026-10-12 10:17:28","msgid":"1"}
        ]
    """.trimIndent()

    private fun detailHtml(): String = """
        <html><head><title>淘宝_360应用</title></head>
        <body>
        <section class="app-info">
          <div class="app-logo"><img src="https://p0.qhimg.com/logo.png"></div>
          <div class="app-name">淘宝</div>
          <div class="app-detail">
            <span><img src="http://p4.ssl.qhimg.com/a.png"/>9.1分</span>|
            <span><img src="http://p4.ssl.qhimg.com/b.png"/>95487万人</span>|
            <span><img src="http://p4.ssl.qhimg.com/c.png"/>73.46Mb</span>
          </div>
        </section>
        <div class="app-about-full" id="fullDesc">应用详情：淘宝是购物平台。<br><br>支持多端同步。<a href="#" id="fold">
        <div class="app-moreinfo" id="infoMore"><div class="app-moreinfo-v">
          <p>版本：10.68.0</p><p>更新时间：2026-10-21 10:32:42</p></div>
          <div>开发者：浙江淘宝网络有限公司</div>
        </div></div>
        <div class="swiper-container" id="swiper"><div class="swiper-wrapper">
          <div class="swiper-slide"><div class="swiper-image" style="background-image:url(https://p0.qhimg.com/s1.jpg)"></div></div>
          <div class="swiper-slide"><div class="swiper-image" style="background-image:url(https://p0.qhimg.com/s2.jpg)"></div></div>
        </div></div>
        <span>隐私政策：<a href="https://terms.alicdn.com/privacy">隐私详情</a></span>
        <script>
          var appInfo = {
            'name' : '淘宝',
            'url' : 'https://s.shouji.qihucdn.com/261019/abc/com.taobao.taobao_864.apk?tok=1&amp;f=z.apk',
            'logo' : 'https://p0.qhimg.com/logo.png',
            'apkid':'com.taobao.taobao'
          };
        </script>
        </body></html>
    """.trimIndent()
}
