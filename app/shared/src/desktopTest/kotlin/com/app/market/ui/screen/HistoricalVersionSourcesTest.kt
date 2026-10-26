package com.app.market.ui.screen

import com.app.market.domain.model.market.AppSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoricalVersionSourcesTest {
    @Test
    fun optionsOnlyContainSourcesDeclaringHistoricalVersions() {
        assertEquals(
            AppSource.entries.filter { it.capabilities.supportsHistoricalVersions },
            HistoricalVersionSources,
        )
        assertTrue(HistoricalVersionSources.isNotEmpty())
    }

    @Test
    fun unsupportedSourcesAreNotOffered() {
        val offered = HistoricalVersionSources.toSet()
        for (unsupported in listOf(AppSource.XIAOMI, AppSource.OPPO, AppSource.VIVO, AppSource.COOLAPK)) {
            assertTrue(unsupported !in offered, "$unsupported should not offer historical versions")
        }
    }

    @Test
    fun everyOfferedSourceCanBeRoutedByPackageName() {
        // 换源进入时 appId 一律归零，只有包名可用：所有被列出的源都必须支持仅凭包名取版本。
        val packageOnly = setOf(
            AppSource.FDROID,
            AppSource.IZZYONDROID,
            AppSource.FDROID_TUNA,
            AppSource.HAIS,
        )
        val resolvable = packageOnly + setOf(AppSource.WANDOUJIA, AppSource.APPCHINA)
        for (source in HistoricalVersionSources.filterNotNull()) {
            assertTrue(source in resolvable, "$source cannot load historical versions by package name")
        }
    }
}
