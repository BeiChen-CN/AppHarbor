package com.app.market.data.repository

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.request
import kotlinx.coroutines.CancellationException
import com.app.market.data.platform.debugLog
import com.app.market.domain.model.market.MarketLink
import com.app.market.domain.model.market.MarketLinkParser
import com.app.market.domain.repository.MarketLinkResolver

internal class MarketLinkResolverImpl(
    private val client: HttpClient,
) : MarketLinkResolver {
    override suspend fun resolve(text: String): MarketLink? {
        MarketLinkParser.parseText(text)?.let { return it }
        val shortLink = MarketLinkParser.findShortLink(text) ?: return null
        // 短链接（如 url.cloud.huawei.com）经 302 跳到真实详情页，取最终地址再解析
        val expanded = try {
            client.get(shortLink).request.url.toString()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            debugLog("MarketLink") { "Failed to expand short link: ${error.message}" }
            return null
        }
        return MarketLinkParser.parse(expanded)
    }
}
