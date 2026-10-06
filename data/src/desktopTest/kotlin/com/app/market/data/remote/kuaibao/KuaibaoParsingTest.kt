package com.app.market.data.remote.kuaibao

import com.app.market.domain.model.market.AppSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KuaibaoParsingTest {
    @Test
    fun searchResponseParsesItemsAndPaging() {
        val fragment = """
            <li source="1"><a href="//www.3839.com/a/106235.htm"><img src="//img.71acg.net/kbdev/opensj/20260922/19364959849"></a><div class="list-1"><p class="list-1-1 beta"><a href="//www.3839.com/a/106235.htm"><span class="sp-name">原神(官服)-六周年版本</span></a></p><div class="list-1-2"><p class="star-bar"><span class="star-bar-d"></span><span class="star-bar-a" style="width: 85%"></span></p>8.5分</div><p class="list-1-3">在七种元素交汇的大陆——「提瓦特」，每个人都可以成为神。</p><p class="list-1-4">2141.1万下载 | 489.86M | 中文</p><p class="list-1-5"><span>编辑推荐</span><span>多平台</span><span>开放世界</span></p></div></li>
        """.trimIndent()
        val response = """{"result":${jsonQuote(fragment)},"nextpage":true}"""
        val page = parseKuaibaoSearchResponse(response, page = 0)

        assertEquals(1, page.items.size)
        assertTrue(page.hasMore)
        val app = page.items.single()
        assertEquals(106235L, app.appId)
        assertEquals("原神(官服)-六周年版本", app.displayName)
        assertEquals("https://img.71acg.net/kbdev/opensj/20260922/19364959849", app.icon)
        assertEquals(4.25, app.ratingScore)
        assertEquals(21_411_000L, app.downloadCount)
        assertEquals((489.86 * 1024 * 1024).toLong(), app.apkSize)
        assertEquals(AppSource.KUAIBAO, app.source)
        assertEquals("编辑推荐", app.category)
    }

    @Test
    fun emptySearchStopsPaging() {
        val page = parseKuaibaoSearchResponse("""{"result":"","nextpage":false}""", page = 5)
        assertEquals(0, page.items.size)
        assertEquals(false, page.hasMore)
    }

    @Test
    fun downInfoAndSectionsParse() {
        val html = """
            <html><title>原神(官服)_原神官方下载-好游快爆APP</title>
            <div class="sp-val">8.5</div>
            <div>开发商</div><div>米哈游科技（上海）有限公司</div>
            <div>适龄范围</div><div>12+</div>
            <div>游戏介绍</div>
            <div># 多平台 # 开放世界</div>
            <p>在七种元素交汇的大陆——「提瓦特」，每个人都可以成为神。<br>你从世界之外漂流而来。</p>
            <a>更多</a>
            <div>更新日志</div>
            <div>全新7.1版本「往冥府的安魂歌」现已推出！<br>【新角色】薇斯纳。</div>
            <div>历史日志</div><div>旧内容</div>
            <script>var downInfo = {"kb_id":"106235","apkurl":"https:\/\/hot.71acg.com\/release\/hykb\/wt\/202609\/20260915Yuanshen1241_619.apk","package":"com.miHoYo.Yuanshen","appname":"原神(官服)-六周年版本","icon":"\/\/img.71acg.net\/kbdev\/opensj\/20260922\/19364959849","md5":"5987768c1a1eba01d01ee3b6898e64e3"},</script>
            </html>
        """.trimIndent()
        val downInfo = parseKuaibaoDownInfo(html)
        assertNotNull(downInfo)
        assertEquals(106235L, downInfo.appId)
        assertEquals("https://hot.71acg.com/release/hykb/wt/202609/20260915Yuanshen1241_619.apk", downInfo.downloadUrl)
        assertEquals("com.miHoYo.Yuanshen", downInfo.packageName)
        assertEquals("5987768c1a1eba01d01ee3b6898e64e3", downInfo.md5)

        val detail = parseKuaibaoDetail(html, appId = 106235L, downInfo = downInfo)
        assertEquals("com.miHoYo.Yuanshen", detail.app.packageName)
        assertEquals(4.25, detail.app.ratingScore)
        assertEquals("米哈游科技（上海）有限公司", detail.app.publisherName)
        assertEquals("12+", detail.ageClassification)
        assertEquals("在七种元素交汇的大陆——「提瓦特」，每个人都可以成为神。\n你从世界之外漂流而来。", detail.introduction)
        assertEquals("全新7.1版本「往冥府的安魂歌」现已推出！\n【新角色】薇斯纳。", detail.changeLog)
    }

    @Test
    fun commentsResponseParses() {
        val response = """
            {"code":100,"msg":"ok","result":{"page":1,"count":"3191","page_count":480,"data":[
                {"id":"1","star":"4","good":"70","location":"福建","content":"很好玩的游戏<br />推荐","user":{"uid":"1","nickname":"玩家甲","avatar":"//imga.3839.com/1"}},
                {"id":"2","star":"5","content":"","user":{"nickname":"玩家乙"}},
                {"id":"3","star":"0","content":"内容缺失用户名的评论","user":null}
            ]}}
        """.trimIndent()
        val comments = parseKuaibaoComments(response)
        assertEquals(3191L, comments.totalCount)
        assertEquals(2, comments.items.size)
        val first = comments.items.first()
        assertEquals("玩家甲", first.userName)
        assertEquals("很好玩的游戏\n推荐", first.content)
        assertEquals(4.0, first.score)
        assertEquals("快爆玩家", comments.items[1].userName)
    }

    @Test
    fun commentsErrorResponseReturnsEmpty() {
        val comments = parseKuaibaoComments("""{"code":0,"msg":"出错啦"}""")
        assertEquals(0, comments.items.size)
        assertEquals(0L, comments.totalCount)
    }

    @Test
    fun versionEntriesParse() {
        val html = """
            <ul class="lb-list">
                <li><div class="lb-info">版本  0.8.2 2026-07-31</div><div class="lb-text"><p>-修复部分问题<br/>-优化体验</p></div></li>
                <li><div class="lb-info">版本  0.8.1 2026-07-19</div><div class="lb-text"><p>-上一个版本</p></div></li>
            </ul>
        """.trimIndent()
        val entries = parseKuaibaoVersionEntries(html)
        assertEquals(2, entries.size)
        assertEquals("0.8.2", entries[0].versionName)
        assertEquals("2026-07-31", entries[0].dateText)
        assertEquals("-修复部分问题\n-优化体验", entries[0].changeLog)
        assertTrue(entries[0].epochMillis() > 0L)
        assertEquals("0.8.1", entries[1].versionName)
    }

    @Test
    fun ratingParsesFromStarInfo() {
        val response = """
            {"code":100,"msg":"ok","result":{"count":"3191","data":[],
             "star_info":{"star":8,"recent_user_star":"9","star_usernum":2271}}}
        """.trimIndent()
        val rating = parseKuaibaoRating(response)
        assertNotNull(rating)
        assertEquals(4.0, rating.ratingScore)
        assertEquals(2271L, rating.commentCount)
    }

    @Test
    fun ratingMissingReturnsNull() {
        assertEquals(null, parseKuaibaoRating("""{"code":0,"msg":"出错啦"}"""))
        assertEquals(null, parseKuaibaoRating("""{"code":100,"result":{"star_info":{"star":0,"star_usernum":5}}}"""))
    }

    private fun jsonQuote(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        return "\"$escaped\""
    }
}
