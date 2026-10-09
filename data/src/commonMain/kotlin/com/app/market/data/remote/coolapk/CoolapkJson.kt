package com.app.market.data.remote.coolapk

import com.app.market.domain.exception.AppNotListedException
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppScreenshot
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.ScreenshotOrientation
import com.app.market.domain.model.market.SearchPage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

internal data class CoolapkDetail(val detail: AppDetail, val md5: String)

internal fun coolapkResponse(text: String): JsonObject {
    val root = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: throw MarketException("酷安返回了无效响应")
    if (root.number("status") == -2L) throw AppNotListedException(root.text("message"))
    if (root.number("status") < 0L || root.number("error") < 0L) {
        throw MarketException(root.text("message").ifBlank { "酷安请求失败" })
    }
    return root
}

internal fun parseCoolapkSearch(text: String): SearchPage {
    val data = coolapkResponse(text)["data"] as? JsonArray
        ?: throw MarketException("酷安搜索响应缺少应用列表")
    fun apps(elements: JsonArray): List<MarketAppInfo> = elements.flatMap { element ->
        val item = element as? JsonObject ?: return@flatMap emptyList()
        when {
            item.text("entityType") == "apk" || item.text("apkname").isNotBlank() ->
                listOfNotNull(item.toCoolapkApp())
            item["entities"] is JsonArray -> apps(item["entities"] as JsonArray)
            else -> emptyList()
        }
    }
    return SearchPage(apps(data).distinctBy { it.packageName.lowercase() }, hasMore = data.isNotEmpty())
}

internal fun parseCoolapkDetail(text: String): CoolapkDetail {
    val data = coolapkResponse(text)["data"] as? JsonObject
        ?: throw MarketException("酷安详情响应缺少应用信息")
    val app = data.toCoolapkApp() ?: throw MarketException("酷安详情缺少包名或应用 id")
    val screens = (data["screenList"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
        ?.takeIf { it.isNotEmpty() } ?: data.text("screenshots").split(',')
    val thumbs = (data["thumbList"] as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull.orEmpty() }
    val detail = AppDetail(
        app = app,
        brief = data.text("subtitle").coolapkPlainText(),
        introduction = data.text("introduce").ifBlank { data.text("description") }.coolapkPlainText(),
        changeLog = app.changeLog,
        category = app.category,
        ageClassification = "",
        downloadCount = app.downloadCount,
        registrationNum = data.text("regnum").ifBlank { data.text("registrationNum") },
        updateTime = data.number("lastupdate") * 1000L,
        privacyUrl = coolapkHttpsUrl(data.text("privacy_url")),
        screenshots = screens.mapIndexedNotNull { index, image ->
            val full = coolapkHttpsUrl(image)
            if (full.isBlank()) null else AppScreenshot(
                url = coolapkHttpsUrl(thumbs.getOrNull(index).orEmpty()).ifBlank { full },
                orientation = ScreenshotOrientation.PORTRAIT,
                expandedUrl = full,
            )
        }.distinctBy { it.expandedUrl },
        commentCount = data.number("commentnum"),
        comments = emptyList(),
        sameDeveloperApps = emptyList(),
    )
    return CoolapkDetail(detail, data.text("apkmd5").takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }.orEmpty())
}

private fun JsonObject.toCoolapkApp(): MarketAppInfo? {
    val packageName = text("apkname").ifBlank { text("packageName") }
    val id = number("id").takeIf { it > 0 } ?: number("entityId")
    if (packageName.isBlank() || id <= 0) return null
    val blocked = (this["is_download_app"] as? JsonPrimitive)?.longOrNull == 0L ||
        (this["status"] as? JsonPrimitive)?.longOrNull?.let { it <= 0 } == true
    return MarketAppInfo(
        appId = id,
        packageName = packageName,
        displayName = text("title").coolapkPlainText().ifBlank { packageName },
        publisherName = text("developername").coolapkPlainText(),
        versionName = text("apkversionname").ifBlank { text("version") },
        versionCode = number("apkversioncode"),
        icon = coolapkHttpsUrl(text("logo")),
        apkSize = number("apklength").takeIf { it > 0 } ?: coolapkSize(text("apksize")),
        ratingScore = (text("score").toDoubleOrNull()?.div(2.0) ?: numberText("rating_star")).coerceIn(0.0, 5.0),
        changeLog = text("changelog").coolapkPlainText(),
        openLink = "https://www.coolapk.com/apk/$packageName",
        downloadBlockReason = if (blocked) text("statusText").ifBlank { "酷安暂不提供下载" } else "",
        source = AppSource.COOLAPK,
        category = text("catName"),
        downloadCount = number("downnum"),
    )
}

internal fun coolapkHttpsUrl(raw: String): String = when {
    raw.startsWith("https://", true) -> raw
    raw.startsWith("http://", true) -> "https://" + raw.substring(7)
    raw.startsWith("//") -> "https:$raw"
    else -> ""
}

private fun coolapkSize(raw: String): Long {
    val match = Regex("(?i)^\\s*([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?)(?:I?B)?\\s*$").matchEntire(raw) ?: return 0L
    val factor = when (match.groupValues[2].uppercase()) {
        "K" -> 1024.0
        "M" -> 1024.0 * 1024
        "G" -> 1024.0 * 1024 * 1024
        "T" -> 1024.0 * 1024 * 1024 * 1024
        else -> 1.0
    }
    return (match.groupValues[1].toDouble() * factor).toLong()
}

private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.number(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
private fun JsonObject.numberText(key: String): Double = text(key).toDoubleOrNull() ?: 0.0

// 酷安简介、日志和搜索高亮带 HTML；详情组件显示纯文本。
private fun String.coolapkPlainText(): String = replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>"), "\n")
    .replace(Regex("<[^>]*>"), "")
    .replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ").replace("&amp;", "&")
    .trim()
