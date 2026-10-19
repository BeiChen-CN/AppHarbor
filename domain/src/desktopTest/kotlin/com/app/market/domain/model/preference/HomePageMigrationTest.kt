package com.app.market.domain.model.preference

import kotlin.test.Test
import kotlin.test.assertEquals

class HomePageMigrationTest {
    @Test
    fun removedTabsAndEmptyPreferencesOpenRecommendations() {
        for (token in listOf("today", "games", "recommendations", "", null)) {
            assertEquals(HomePage.RECOMMENDATIONS, HomePage.fromToken(token))
        }
        assertEquals(HomePage.UPDATES, HomePage.fromToken("updates"))
        assertEquals(HomePage.SEARCH, HomePage.fromToken("search"))
    }
}
