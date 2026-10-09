package com.app.market.data.remote.coolapk

import com.app.market.domain.exception.AppNotListedException
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.market.AppSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal val CoolapkTestApp = """{
    "id":4599,"entityType":"apk","apkname":"com.coolapk.market",
    "title":"<em>酷安</em>","subtitle":"社区 &amp; 应用","developername":"酷安网",
    "apkversionname":"16.6.4","apkversioncode":"2609291","apklength":"116965943",
    "logo":"http://image.coolapk.com/icon.png","score":"7.0","downnum":"153156707",
    "catName":"社交聊天","is_download_app":1,"status":1,
    "changelog":"优化体验<br>修复问题","introduce":"<p>简介 &lt;内容&gt;</p><p>第二段</p>",
    "lastupdate":"1790675588","apkmd5":"a7f362019530968866f9b3c2e4ccd22b",
    "screenList":["http://image.coolapk.com/full.png"],
    "thumbList":["http://image.coolapk.com/thumb.png"],
    "privacy_url":"https://m.coolapk.com/privacy","commentnum":"123"
}"""

class CoolapkParsingTest {
    @Test
    fun searchParsesMixedNumericTypesAndIgnoresNonApps() {
        val page = parseCoolapkSearch("""{"data":[$CoolapkTestApp,{"entityType":"feed","id":1},$CoolapkTestApp]}""")
        assertTrue(page.hasMore)
        val app = page.items.single()
        assertEquals(AppSource.COOLAPK, app.source)
        assertEquals("酷安", app.displayName)
        assertEquals("com.coolapk.market", app.packageName)
        assertEquals(2609291L, app.versionCode)
        assertEquals(116965943L, app.apkSize)
        assertEquals(3.5, app.ratingScore)
        assertEquals("https://image.coolapk.com/icon.png", app.icon)
    }

    @Test
    fun searchHandlesNestedCardsAndEmptyLastPage() {
        val page = parseCoolapkSearch("""{"data":[{"entityType":"card","entities":[$CoolapkTestApp]}]}""")
        assertEquals(1, page.items.size)
        assertFalse(parseCoolapkSearch("""{"data":[]}""").hasMore)
    }

    @Test
    fun detailParsesScreenshotsHtmlAndReleaseTimestamp() {
        val record = parseCoolapkDetail("""{"data":$CoolapkTestApp}""")
        val detail = record.detail
        assertEquals("社区 & 应用", detail.brief)
        assertEquals("简介 <内容>\n第二段", detail.introduction)
        assertEquals("优化体验\n修复问题", detail.changeLog)
        assertEquals(1790675588000L, detail.updateTime)
        assertEquals(123L, detail.commentCount)
        assertEquals("https://image.coolapk.com/thumb.png", detail.screenshots.single().url)
        assertEquals("https://image.coolapk.com/full.png", detail.screenshots.single().expandedUrl)
        assertEquals("a7f362019530968866f9b3c2e4ccd22b", record.md5)
    }

    @Test
    fun blockedAppsAndHumanReadableSizesArePreserved() {
        val appJson = CoolapkTestApp.replace("\"is_download_app\":1", "\"is_download_app\":0")
            .replace("\"apklength\":\"116965943\"", "\"apksize\":\"1.5M\"")
        val app = parseCoolapkDetail("""{"data":$appJson}""").detail.app
        assertTrue(app.downloadBlockReason.isNotBlank())
        assertEquals(1572864L, app.apkSize)
    }

    @Test
    fun serviceFailuresAreDistinctFromMissingPackagesAndEmptySearches() {
        assertFailsWith<AppNotListedException> { parseCoolapkDetail("""{"status":-2,"message":"应用不存在"}""") }
        assertFailsWith<MarketException> { parseCoolapkDetail("""{"status":-1,"message":"认证失败"}""") }
        assertFailsWith<MarketException> { parseCoolapkSearch("<html>Captcha</html>") }
        assertFailsWith<MarketException> { parseCoolapkSearch("""{"data":null}""") }
        assertFailsWith<MarketException> { parseCoolapkDetail("""{"data":{"title":"missing package"}}""") }
    }
}
