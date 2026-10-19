package com.app.market.data.remote.kuaibao

import com.app.market.domain.model.market.AppSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KuaibaoRecommendationsTest {
    @Test
    fun parsesActualHomeCardStructureAndDropsDuplicatesAndNonGameLinks() {
        val html = """
            <ul><li class="cur"><a href="//www.3839.com/a/109567.htm">
            <img class="img" src="//img.71acg.net/cover.png" alt="下一站，我的大学">
            <div class="name"><em>下一站，我的大学</em></div><p class="desc">编辑推荐 &amp; 新游戏</p></a></li>
            <li><a href="//www.3839.com/a/109567.htm"><img src="//img.71acg.net/icon.png" alt="重复"></a></li>
            <li><a href="//bbs.3839.com/t/1"><img src="//img.71acg.net/news.png" alt="资讯"></a></li>
            <li><a href="//www.3839.com/a/92547.htm"><img src="//img.71acg.net/game.png" alt="和平精英"></a></li></ul>
        """.trimIndent()
        val items = parseKuaibaoRecommendations(html)
        assertEquals(listOf("109567", "92547"), items.map { it.rId })
        assertEquals("下一站，我的大学", items.first().title)
        assertEquals("https://img.71acg.net/cover.png", items.first().coverImage)
        assertTrue(items.all { it.app?.source == AppSource.KUAIBAO })
        assertTrue(items.all { it.app!!.packageName.isEmpty() })
    }
}
