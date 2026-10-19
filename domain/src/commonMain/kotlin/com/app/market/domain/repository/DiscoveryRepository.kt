package com.app.market.domain.repository

import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.today.TodayFeedPage

interface DiscoveryRepository {
    suspend fun feed(source: AppSource, page: Int): TodayFeedPage
}
