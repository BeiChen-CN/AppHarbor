package com.app.market.data.remote.appchina

import com.app.market.domain.model.market.AppSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppchinaApiTest {
    @Test
    fun parsesSearchPayloadAndFiltersUnavailableEntries() {
        val page = parseAppchinaSearch(SearchPayload)

        assertEquals(1, page.items.size)
        assertTrue(page.hasMore)
        val app = page.items.single()
        assertEquals(AppSource.APPCHINA, app.source)
        assertEquals(7714376L, app.appId)
        assertEquals("com.tencent.mm", app.packageName)
        assertEquals("微信", app.displayName)
        assertEquals(3200L, app.versionCode)
        assertEquals((272.07 * 1024 * 1024).toLong(), app.apkSize)
        assertEquals(3.75, app.ratingScore)
        assertEquals("https://static.yingyonghui.com/icon/128/7714376.png", app.icon)
    }

    @Test
    fun parsesCommentsFromDetailPage() {
        val comments = parseAppchinaComments(CommentsHtml)

        assertEquals(36509L, comments.totalCount)
        assertEquals(2, comments.items.size)
        assertEquals("浮云烟", comments.items.first().userName)
        assertTrue(comments.items.first().content.contains("本人男17"))
        assertEquals("落花雨F", comments.items.last().userName)
        assertTrue(comments.items.last().content.contains("交友"))
    }

    @Test
    fun parsesHistoricalVersionPayload() {
        val page = parseAppchinaHistory(HistoryPayload, appId = 7714376L, packageName = "com.tencent.mm", offset = 0)

        assertEquals(2, page.items.size)
        assertNull(page.nextOffset)
        val first = page.items.first()
        assertEquals(7707341L, first.versionId)
        assertEquals("com.tencent.mm", first.packageName)
        assertEquals("微信", first.displayName)
        assertEquals("8.0.78", first.versionName)
        assertEquals(3180L, first.versionCode)
        assertEquals(280614450L, first.size)
        assertEquals(24, first.minSdkVersion)
        assertTrue(first.downloadUrl.startsWith("https://"))
        assertTrue(first.createdAt > 0L)
        assertTrue(first.updatedAt > 0L)
    }

    @Test
    fun parsesDetailPageFields() {
        val detail = AppchinaApi.parseDetail(DetailHtml, "com.tencent.mm", 0L)

        assertEquals(7714376L, detail.appId)
        assertEquals("com.tencent.mm", detail.packageName)
        assertEquals("微信", detail.displayName)
        assertEquals("微信，是一个生活方式", detail.brief)
        assertEquals("8.0.79", detail.versionName)
        assertEquals(3200L, detail.versionCode)
        assertEquals((272.07 * 1024 * 1024).toLong(), detail.apkSize)
        assertEquals("深圳市腾讯计算机系统有限公司", detail.publisherName)
        assertEquals("社交网络", detail.category)
        assertEquals(3.75, detail.ratingScore)
        assertEquals(36509L, detail.commentCount)
        assertEquals(2, detail.screenshots.size)
        assertTrue(detail.introduction.contains("音视频通话"))
        assertTrue(detail.changeLog.contains("解决了一些已知问题"))
        assertTrue(detail.updateTime > 0L)
        assertEquals(
            "https://mobile.d.appchina.com/McDonald/e/7714376/0/0/0/1792669721013/package_3200.1792669721013",
            detail.downloadUrl,
        )
        assertFalse(detail.downloadUrl.startsWith("http://"))
    }
}

private const val SearchPayload = """
{"nextPage":"/ajax/search/微信/2","list":[
 {"packageName":"com.tencent.mm","name":"微信","iconUrl":"http://static.yingyonghui.com/icon/128/7714376.png","shortDesc":"微信，是一个生活方式","size":"272.07MB","likePercentage":"75%好评(22549人)","apkUrl":"http://mobile.d.appchina.com/McDonald/e/7714376/0/0/0/1792669721013/package_3200.1792669721013","showOptProps":null,"offShelf":false,"prePublish":"false"},
 {"packageName":"com.off.shelf","name":"已下架","iconUrl":"http://static.yingyonghui.com/icon/128/1.png","size":"1MB","apkUrl":"http://mobile.d.appchina.com/McDonald/e/1/0/0/0/1/package_1.1","offShelf":true,"prePublish":"false"},
 {"packageName":"com.pre.publish","name":"预约中","iconUrl":"http://static.yingyonghui.com/icon/128/2.png","size":"1MB","apkUrl":"http://mobile.d.appchina.com/McDonald/e/2/0/0/0/1/package_1.1","offShelf":false,"prePublish":"true"}
]}
"""

private const val CommentsHtml = """
<div class="app-details-like"><div><img alt="评论" src="icon_04.png" /><span>36509</span></div></div>
<div class="App_SpreadContent app-details-comment">
  <ul>
    <li><table>
      <tr><td style="width:230px;">浮云烟</td><td>4小时前</td></tr>
      <tr><td colspan="2" style="color:#44423C;">本人男17,要求有微信或者有QQ的</td></tr>
    </table></li>
    <li><table>
      <tr><td style="width:230px;">落花雨F</td><td>5小时前</td></tr>
      <tr><td colspan="2" style="color:#44423C;">男高<br />交友，可聊可深交</td></tr>
    </table></li>
  </ul>
</div>
"""

private const val HistoryPayload = """
{"total":2,"listSize":2,"list":[
 {"id":7707341,"name":"微信","lastUpdate":"2026-09-14","size":280614450,"versionCode":3180,"versionName":"8.0.78","sdkVersion":24,"packageName":"com.tencent.mm","iconUrl":"http://static.yingyonghui.com/is/7707/7707341/256i.webp","apkUrl":"https://mobile.d.appchina.com/McDonald/e/7707341/0/0/0/1792675370496/package_3180.1792675370496","createTime":"2026-09-14"},
 {"id":7545826,"name":"微信","lastUpdate":"2025-11-11","size":255216422,"versionCode":2960,"versionName":"8.0.65","sdkVersion":24,"packageName":"com.tencent.mm","iconUrl":"http://static.yingyonghui.com/is/7545/7545826/256i.webp","apkUrl":"https://mobile.d.appchina.com/McDonald/e/7545826/0/0/0/1792675370547/package_2960.1792675370547","createTime":"2025-11-11"}
]}
"""

private const val DetailHtml = """
<div class="app-details">
  <img class="app-details-icon" height="51px" width="51px" alt="微信" title="微信" src="http://static.yingyonghui.com/icon/128/7714376.png" />
  <div class="app-details-det">
    <div class="app-details-appname"><h1>微信</h1></div>
    <div class="app-details-appdes"><h3>微信，是一个生活方式</h3></div>
    <div class="app-details-appinfo"><div><span>272.07MB</span></div><div>|</div><div><span>版本&nbsp;8.0.79</span></div></div>
    <div class="app-details-like">
      <div><img alt="喜欢" src="like.png" /><span>75%好评(22549人)</span></div>
      <div><img alt="评论" src="icon_04.png" /><span>36509</span></div>
    </div>
  </div>
</div>
<a class="normal-down-btn" href="http://mobile.d.appchina.com/McDonald/e/7714376/0/0/0/1792669721013/package_3200.1792669721013" onclick="return ckNormalDownBtn(&#39;com.tencent.mm&#39;,&#39;Down_A&#39;)">下载APK</a>
<script>var packageName = 'com.tencent.mm';</script>
<div class="App_ImgBox">
  <img alt="微信截图0" src="http://static.yingyonghui.com/screenshots/7714/7714376_0.jpg" />
  <img alt="微信截图1" src="http://static.yingyonghui.com/screenshots/7714/7714376_1.jpg" />
</div>
<ul class="App_SpreadContent">
  <li><span class="fl">软件大小:</span><span class="fl">272.07MB</span></li>
  <li><span class="fl">最后更新:</span><span class="fl">2026-10-10</span></li>
  <li><span class="fl">最新版本:</span><span class="fl">8.0.79</span></li>
  <li><span class="fl">应用分类:</span><span class="fl"><a class="CategoryLink" href="/soft/308">社交网络</a></span></li>
  <li><span class="fl">开发者:</span><a class="fl" href="/developer/info/packageName/com.tencent.mm">深圳市腾讯计算机系统有限公司</a></li>
</ul>
<div class="App_SpreadContent App_Introduce"><span>你可以通过音视频通话、群聊、朋友圈来和朋友们分享生活。<br />还可以开启「关怀模式」。</span><div class="get_more">加载更多</div></div>
<div class="App_SpreadContent App_Update"><span>解决了一些已知问题。</span><div class="get_more">加载更多</div></div>
"""
