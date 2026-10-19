package com.app.market.data.repository

import com.app.market.data.remote.kuaibao.KuaibaoApi
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.today.TodayFeedPage
import com.app.market.domain.repository.*

internal class DiscoveryRepositoryImpl(
    private val sources: MarketSourceRepository,
    private val kuaibao: KuaibaoApi,
) : DiscoveryRepository {
    override suspend fun feed(source: AppSource, page: Int): TodayFeedPage {
        require(source in RecommendationSources)
        return if (source == AppSource.KUAIBAO) kuaibao.recommendations(page)
        else sources.goldMiFeed(source, page)
    }
}
