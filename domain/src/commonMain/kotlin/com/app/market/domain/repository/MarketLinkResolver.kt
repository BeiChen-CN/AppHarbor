package com.app.market.domain.repository

import com.app.market.domain.model.market.MarketLink

/** 从任意文本（如剪贴板）还原商店链接；短链接需要联网展开，因此是挂起函数。 */
interface MarketLinkResolver {
    suspend fun resolve(text: String): MarketLink?
}
