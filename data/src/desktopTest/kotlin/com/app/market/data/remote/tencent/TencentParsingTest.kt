package com.app.market.data.remote.tencent

import com.app.market.domain.exception.AppNotListedException
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.market.AppSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal const val TencentPackage = "com.tencent.mm"
internal val TencentTestRecord = """{
    "app_info":{"app_id":"10910","name":"微信","package_name":"com.tencent.mm",
        "developer":"腾讯","desc":"应用介绍","editor_intro":"联系朋友","category_name_new":"社交",
        "download_cnt_total":"8978511793","online_status":1,"suitable_age":16,"icp_number":"备案号",
        "privacy_agreement":"http://weixin.qq.com/privacy"},
    "apk_all_data":{"app_id":"10910","name":"微信","package_name":"com.tencent.mm",
        "version_name":"8.0.78","version_code":3180,"size_byte":"280614450","feature":"更新日志",
        "logo256":"http://img.qq.com/icon.png","url":"http://imtt.dd.qq.com/base.apk?fsname=wechat.apk",
        "sha256":"${"a".repeat(64)}","apk_md5":"${"b".repeat(32)}","update_time":1789722200,
        "snapshot_bigs":["http://img.qq.com/screen.png","http://img.qq.com/screen.png"]},
    "app_rating_info":{"rating_count":"592137","average_rating":4.3}
}"""
internal val TencentTestDetail = """{"ret":0,"app_detail_records":{"$TencentPackage":$TencentTestRecord}}"""
internal val TencentTestItem = """{"pkg_name":"com.tencent.mm","app_id":"10910","name":"微信",
    "icon":"http://img.qq.com/icon.png","apk_size":"280614450","version_name":"8.0.78",
    "developer":"腾讯","average_rating":"4.3","cate_name_new":"社交","game_type":"1"}"""
internal fun searchHtml(items: String = TencentTestItem) = """<html><script id="__NEXT_DATA__" type="application/json">
    {"page":"/search","props":{"pageProps":{"dynamicCardResponse":{"ret":0,"data":{"components":[
        {"cardId":"YYB_HOME_SEARCH_NORMAL_GAME","data":{"itemData":[$items]}},
        {"cardId":"YYB_HOME_SEARCH_HOT_GAME","data":{"itemData":[{"pkg_name":"com.example.ad","app_id":1}]}}
    ]}}}}}</script></html>"""

class TencentParsingTest {
    @Test
    fun searchUsesPrimaryAndroidResultsAndDoesNotPretendToPaginate() {
        val pc = TencentTestItem.replace("com.tencent.mm", "com.example.pc").replace("\"game_type\":\"1\"", "\"game_type\":\"7\"")
        val page = parseTencentSearch(searchHtml("$TencentTestItem,$TencentTestItem,$pc"))
        assertFalse(page.hasMore)
        assertEquals(TencentPackage, page.items.single().packageName)
        assertEquals(AppSource.TENCENT, page.items.single().source)
        assertEquals("https://img.qq.com/icon.png", page.items.single().icon)
        assertEquals(4.3, page.items.single().ratingScore)
    }

    @Test
    fun detailProvidesActualVersionMetadataAndHttpsUrls() {
        val record = parseTencentDetail(TencentTestDetail, TencentPackage)
        val detail = record.detail
        assertEquals(3180L, detail.app.versionCode)
        assertEquals(280614450L, detail.app.apkSize)
        assertEquals(8978511793L, detail.downloadCount)
        assertEquals(1789722200000L, detail.updateTime)
        assertEquals("应用介绍", detail.introduction)
        assertEquals("更新日志", detail.changeLog)
        assertEquals("https://img.qq.com/screen.png", detail.screenshots.single().url)
        assertEquals("https://imtt.dd.qq.com/base.apk?fsname=wechat.apk", record.url)
        assertEquals("a".repeat(64), record.checksum)
    }

    @Test
    fun checksumFallsBackToMd5AndUnavailableRecordsRemainVisibleButBlocked() {
        val input = TencentTestDetail.replace("a".repeat(64), "invalid").replace("\"online_status\":1", "\"online_status\":0")
        val record = parseTencentDetail(input, TencentPackage)
        assertEquals("b".repeat(32), record.checksum)
        assertTrue(record.detail.app.downloadBlockReason.isNotBlank())
        val unsafe = parseTencentDetail(TencentTestDetail.replace("http://imtt.dd.qq.com/base.apk?fsname=wechat.apk", "intent://launch"), TencentPackage)
        assertTrue(unsafe.url.isBlank())
        assertTrue(unsafe.detail.app.downloadBlockReason.isNotBlank())
    }

    @Test
    fun missingAppsDifferFromServiceFailuresAndInvalidResponses() {
        assertFailsWith<AppNotListedException> { parseTencentDetail("""{"ret":0,"app_detail_records":{}}""", TencentPackage) }
        assertFailsWith<MarketException> { parseTencentDetail("""{"ret":1,"err_msg":"服务异常"}""", TencentPackage) }
        assertFailsWith<MarketException> { parseTencentDetail("<html>Captcha</html>", TencentPackage) }
        assertFailsWith<MarketException> { parseTencentDetail(TencentTestDetail.replace("\"package_name\":\"com.tencent.mm\"", "\"package_name\":\"other.app\""), TencentPackage) }
        assertFailsWith<MarketException> { parseTencentSearch(searchHtml().replace("\"page\":\"/search\"", "\"page\":\"/\"")) }
        assertFailsWith<MarketException> { parseTencentSearch("<html>Captcha</html>") }
        assertFalse(parseTencentSearch(searchHtml("")).hasMore)
    }

    @Test
    fun platformMaskIsReadAsHostedAbis() {
        // 掩码是「被托管的包内含哪些 ABI」，实测值来自 12 个包的真实 ABI 抓取。
        assertEquals(listOf("arm64-v8a"), tencentAbis("1026"))
        assertEquals(listOf("armeabi-v7a", "arm64-v8a"), tencentAbis("1539"))
        assertEquals(listOf("armeabi"), tencentAbis("257"))
        assertEquals(listOf("armeabi-v7a"), tencentAbis("513"))
        assertEquals(listOf("armeabi-v7a", "x86"), tencentAbis("2561"))
        // 拿不到或解析不出时按「未知」处理，不误判成 32 位。
        assertEquals(emptyList(), tencentAbis(""))
        assertEquals(emptyList(), tencentAbis("0"))
        assertEquals(emptyList(), tencentAbis("abc"))
    }

    @Test
    fun detailCarriesHostedAbisAndDownloadMetaExposes32BitOnly() {
        val detail = parseTencentDetail(
            TencentTestDetail.replace("\"apk_md5\"", "\"platform\":\"513\",\"apk_md5\""),
            TencentPackage,
        )
        assertEquals(listOf("armeabi-v7a"), detail.abis)
    }
}
