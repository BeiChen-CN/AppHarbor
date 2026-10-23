package com.app.market.domain.model.market

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarketLinkParserTest {
    private fun assertLink(uri: String, packageName: String, source: AppSource?, storeAppId: Long = 0L) {
        assertEquals(MarketLink(packageName, source, storeAppId), MarketLinkParser.parse(uri), uri)
    }

    @Test
    fun unifiedFormatRoundTrips() {
        val link = MarketLink("com.tencent.mm", AppSource.OPPO)
        assertEquals("appmarket://details?id=com.tencent.mm&source=oppo", link.toUnifiedUri())
        assertEquals(link, MarketLinkParser.parse(link.toUnifiedUri()))
    }

    @Test
    fun huaweiSchemeWithoutSourceMapsToHuawei() {
        assertLink("appmarket://details?id=com.tencent.mm", "com.tencent.mm", AppSource.HUAWEI)
        assertLink("hiapp://com.huawei.appmarket?pkgName=com.tencent.mm", "com.tencent.mm", AppSource.HUAWEI)
    }

    @Test
    fun storeSchemes() {
        assertLink("market://details?id=com.tencent.mm", "com.tencent.mm", null)
        assertLink("mimarket://details?id=com.tencent.mm&ref=x", "com.tencent.mm", AppSource.XIAOMI)
        assertLink("vivomarket://details?id=com.tencent.mm", "com.tencent.mm", AppSource.VIVO)
        assertLink("oppomarket://details?packagename=com.tencent.mm", "com.tencent.mm", AppSource.OPPO)
        assertLink("oaps://mk/dt?pkg=com.tencent.mm", "com.tencent.mm", AppSource.OPPO)
        assertLink("honormarket://details?id=com.tencent.mm", "com.tencent.mm", AppSource.HONOR)
        assertLink("samsungapps://ProductDetail/com.tencent.mm", "com.tencent.mm", AppSource.SAMSUNG)
    }

    @Test
    fun webLinks() {
        assertLink("https://app.mi.com/details?id=com.tencent.mm", "com.tencent.mm", AppSource.XIAOMI)
        assertLink("https://m.app.mi.com/#page=detail&id=com.tencent.mm", "com.tencent.mm", AppSource.XIAOMI)
        assertLink("https://play.google.com/store/apps/details?id=com.tencent.mm&hl=zh", "com.tencent.mm", null)
        assertLink(
            "https://appgallery.cloud.huawei.com/appDetail?pkgName=com.tencent.mm",
            "com.tencent.mm",
            AppSource.HUAWEI,
        )
        assertLink("https://h5.appstore.vivo.com.cn/#/details?pkgName=com.tencent.mm", "com.tencent.mm", AppSource.VIVO)
        assertLink("https://galaxystore.samsung.com/detail/com.tencent.mm", "com.tencent.mm", AppSource.SAMSUNG)
        assertLink("https://www.wandoujia.com/apps/com.tencent.mm", "com.tencent.mm", AppSource.WANDOUJIA)
        assertLink("https://www.coolapk.com/apk/com.tencent.mm", "com.tencent.mm", null)
    }

    @Test
    fun tapTapLinksResolveByStoreId() {
        assertLink("https://www.taptap.cn/app/168332", "", AppSource.TAPTAP, 168332L)
        assertLink("https://www.taptap.cn/app/168332?os=android", "", AppSource.TAPTAP, 168332L)
        assertLink("taptap://taptap.com/app?app_id=168332", "", AppSource.TAPTAP, 168332L)
    }

    @Test
    fun huaweiLinksWithOnlyAppId() {
        val real = "https://appgallery.huawei.com/app/C100404489?sharePrepath=ag&locale=zh_TW&source=appshare" +
                "&subsource=C100404489&shareTo=copylink&shareFrom=appmarket&callType=SHARE"
        assertLink(real, "", AppSource.HUAWEI, 100404489L)
        assertLink("https://appgallery.cloud.huawei.com/ag/n/app/C100404489?locale=zh_TW", "", AppSource.HUAWEI, 100404489L)
        assertLink("https://appgallery.huawei.com/#/app/C100404489", "", AppSource.HUAWEI, 100404489L)
        // 路径里的数字必须带 C 前缀，不能把任意数字当成应用 id
        assertNull(MarketLinkParser.parse("https://appgallery.huawei.com/app/100404489"))
    }

    @Test
    fun storeAppIdOnlyLinksRoundTripThroughUnifiedFormat() {
        val huawei = MarketLink("", AppSource.HUAWEI, 100404489L)
        assertEquals("appmarket://details?source=huawei&appId=100404489", huawei.toUnifiedUri())
        assertEquals(huawei, MarketLinkParser.parse(huawei.toUnifiedUri()))
        val taptap = MarketLink("", AppSource.TAPTAP, 168332L)
        assertEquals(taptap, MarketLinkParser.parse(taptap.toUnifiedUri()))
    }

    @Test
    fun findsShortLinkInsideShareText() {
        val text = "我在 華為應用程式商店 找到一個很棒的應用程式，試試看吧！https://url.cloud.huawei.com/Cn9gXTtP6o"
        assertEquals("https://url.cloud.huawei.com/Cn9gXTtP6o", MarketLinkParser.findShortLink(text))
        // 短链接本身不能直接解析，需要联网展开；也不会去展开未知域名
        assertNull(MarketLinkParser.parseText(text))
        assertNull(MarketLinkParser.findShortLink("https://www.baidu.com/abc"))
        assertNull(MarketLinkParser.findShortLink("https://url.cloud.huawei.com/"))
    }

    @Test
    fun findsLinkInsideShareText() {
        val text = "【微信】快来下载吧！https://app.mi.com/details?id=com.tencent.mm，点击链接打开"
        assertEquals(MarketLink("com.tencent.mm", AppSource.XIAOMI), MarketLinkParser.parseText(text))
    }

    @Test
    fun rejectsUnrelatedLinksAndFileNames() {
        assertNull(MarketLinkParser.parse("https://www.baidu.com/s?wd=com.tencent.mm"))
        assertNull(MarketLinkParser.parse("https://galaxystore.samsung.com/appquery/appDetail.as"))
        assertNull(MarketLinkParser.parse("https://www.taptap.cn/moment/123"))
        assertNull(MarketLinkParser.parseText("今天天气不错"))
        assertNull(MarketLinkParser.parse("market://details?id=notapackage"))
    }
}
