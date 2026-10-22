package com.app.market.data.remote.taptap

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TapTapApiTest {
    @Test
    fun parsesReviewsFromWebResponse() {
        val root = Json.parseToJsonElement(ReviewPayload).jsonObject
        val comments = parseTapTapComments(root)

        assertEquals(2, comments.items.size)
        assertEquals(2L, comments.totalCount)
        assertEquals("清珍", comments.items.first().userName)
        assertEquals(4.0, comments.items.first().score)
        assertEquals("游戏很好玩", comments.items.first().content)
        assertEquals("TapTap 用户", comments.items.last().userName)
        assertTrue(comments.items.last().content.contains("五星"))
    }
}

private const val ReviewPayload = """
{"data":{"list":[
 {"moment":{"author":{"user":{"name":"清珍"}},"review":{"score":4,"contents":{"text":"游戏很好玩"}}}},
 {"moment":{"author":{"user":{"name":""}},"review":{"score":5,"contents":{"text":"<b>五星</b>"}}}}
]}}
"""
