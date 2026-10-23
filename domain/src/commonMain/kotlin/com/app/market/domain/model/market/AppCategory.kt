package com.app.market.domain.model.market

enum class AppCategory { GAMES, APPS }

enum class AppSubCategory(val xiaomiCategoryId: Int) {
    TOOLS(5),
    MEDIA(27),
    SOCIAL(2),
    OFFICE(10),
    EDUCATION(12),
    SHOPPING(9),
    SPORTS(8),
}

const val XIAOMI_GAME_CATEGORY_ID = 15

enum class GameRanking(val tapTapType: String) {
    HOT("hot"),
    NEW("new"),
    SELL("sell"),
}

enum class GameSubCategory(val xiaomiCategoryId: Int) {
    ALL(XIAOMI_GAME_CATEGORY_ID),
    STRATEGY(16),
    ACTION(17),
    RACING(18),
    RPG(19),
    CARD(20),
    FIGHTING(21),
    KIDS(22),
    CASUAL(23),
    FLIGHT(25),
    RUNNER(26),
}

data class CategoryOption(val id: String, val name: String)
