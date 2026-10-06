package com.app.market.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import com.app.market.domain.model.market.AppSource

data class AppDisplayName(
    val appName: String,
    val description: String,
)

private val OppoAppDescription = Regex("（([^（）]*)）$")

fun String.splitAppDisplayName(source: AppSource?): AppDisplayName {
    val unchanged = AppDisplayName(this, "")
    return when (source) {
        // 好游快爆名称形如「原神(官服)-六周年版本」，副标题承载版本营销语
        AppSource.XIAOMI, AppSource.VIVO, AppSource.KUAIBAO -> {
            val separator = indexOf('-')
            if (separator <= 0) return unchanged
            val appName = substring(0, separator).trim()
            if (appName.isEmpty()) return unchanged
            AppDisplayName(appName, substring(separator + 1).trim())
        }

        AppSource.OPPO -> {
            val match = OppoAppDescription.find(trimEnd()) ?: return unchanged
            val appName = substring(0, match.range.first).trim()
            if (appName.isEmpty()) return unchanged
            AppDisplayName(appName, match.groupValues[1].trim())
        }

        else -> unchanged
    }
}

val LocalStripAppNameSubtitle = compositionLocalOf { false }

@Composable
@ReadOnlyComposable
fun appDisplayName(raw: String, source: AppSource?): String =
    if (LocalStripAppNameSubtitle.current) raw.splitAppDisplayName(source).appName else raw
