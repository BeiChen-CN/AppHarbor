package com.app.market.ui.util

import com.app.market.domain.model.market.AppSource
import kotlin.test.Test
import kotlin.test.assertEquals

class AppNameFormatTest {
    @Test
    fun xiaomiAndVivoSplitAtFirstHyphen() {
        assertEquals(
            AppDisplayName("小米汽车", "出行服务-官方版"),
            "小米汽车 - 出行服务-官方版".splitAppDisplayName(AppSource.XIAOMI),
        )
        for (separator in "‐‑‒–—﹘﹣－") {
            val name = "AppMarket${separator}开发版"
            assertEquals(AppDisplayName(name, ""), name.splitAppDisplayName(AppSource.XIAOMI))
        }
        for (name in listOf("应用 (推广语)", "应用（推广语）")) {
            assertEquals(AppDisplayName(name, ""), name.splitAppDisplayName(AppSource.XIAOMI))
        }
    }

    @Test
    fun xiaomiTodayNamesUseAsciiHyphens() {
        assertEquals(
            AppDisplayName("圆周旅迹", "智能旅行规划助手"),
            "圆周旅迹-智能旅行规划助手".splitAppDisplayName(AppSource.XIAOMI),
        )
        assertEquals(
            AppDisplayName("无痛单词", "轻松背四六级考研单词"),
            "无痛单词-轻松背四六级考研单词".splitAppDisplayName(AppSource.XIAOMI),
        )
    }

    @Test
    fun vivoNamesUseAsciiHyphens() {
        assertEquals(
            AppDisplayName("假日乐消消", "欢乐假期"),
            "假日乐消消-欢乐假期".splitAppDisplayName(AppSource.VIVO),
        )
    }

    @Test
    fun todayAwardLabelDoesNotRepeatTheAppName() {
        assertEquals(
            "极光奖 · 第594期",
            todayAwardLabel("极光奖 · 第594期", "假日乐消消", "假日乐消消"),
        )
        assertEquals(
            "至美奖 · 推荐应用",
            todayAwardLabel("至美奖", "推荐应用", "其他应用"),
        )
    }

    @Test
    fun oppoOnlySplitsTrailingChineseParentheses() {
        assertEquals(
            AppDisplayName("造梦西游4", "十周年庆"),
            "造梦西游4（十周年庆）".splitAppDisplayName(AppSource.OPPO),
        )
        assertEquals(
            AppDisplayName("应用—开发版", "推广-活动"),
            "应用—开发版（推广-活动）".splitAppDisplayName(AppSource.OPPO),
        )
        assertEquals(
            AppDisplayName("应用", "推广语"),
            "应用 （推广语）  ".splitAppDisplayName(AppSource.OPPO),
        )
        for (name in listOf(
            "应用-推广语",
            "应用—推广语",
            "应用(推广语)",
            "应用 (推广语)",
            "应用（推广语",
            "应用（版本）工具",
            "应用（推广语)",
            "应用(推广语）"
        )) {
            assertEquals(AppDisplayName(name, ""), name.splitAppDisplayName(AppSource.OPPO))
        }
    }

    @Test
    fun kuaibaoNamesSplitAtFirstHyphen() {
        assertEquals(
            AppDisplayName("原神(官服)", "六周年版本"),
            "原神(官服)-六周年版本".splitAppDisplayName(AppSource.KUAIBAO),
        )
    }

    @Test
    fun otherAndUnknownSourcesKeepTheOriginalName() {
        val sources = AppSource.entries.filter {
            it != AppSource.XIAOMI && it != AppSource.VIVO && it != AppSource.OPPO && it != AppSource.KUAIBAO
        } + null
        for (source in sources) {
            for (name in listOf(" 应用-开发版 (推广语) ", " 应用-开发版（推广语） ", " 应用—开发版（推广语） ")) {
                assertEquals(AppDisplayName(name, ""), name.splitAppDisplayName(source))
            }
        }
    }

    @Test
    fun missingAppNamesAndPlainNamesAreNotClipped() {
        for (source in listOf(AppSource.XIAOMI, AppSource.VIVO, AppSource.OPPO)) {
            for (name in listOf("", "应用", "-推广语", " -推广语", "—推广语", " —推广语", " (推广语)", "（推广语）", " （推广语）")) {
                assertEquals(AppDisplayName(name, ""), name.splitAppDisplayName(source))
            }
        }
    }
}
