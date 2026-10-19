package com.app.market.domain.model.preference

/** The tab opened on launch and returned to by Back. */
enum class HomePage(val token: String) {
    RECOMMENDATIONS("recommendations"),
    UPDATES("updates"),
    SEARCH("search");

    companion object {
        /** 旧今日页与游戏页的启动设置自动归并到推荐页。 */
        fun fromToken(token: String?): HomePage = entries.firstOrNull { it.token == token } ?: RECOMMENDATIONS
    }
}
