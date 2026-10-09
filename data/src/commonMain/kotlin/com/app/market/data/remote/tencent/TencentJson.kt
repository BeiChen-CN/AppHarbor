package com.app.market.data.remote.tencent

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

internal data class TencentDetail(val detail: AppDetail, val url: String, val checksum: String)

internal fun parseTencentSearch(html: String): SearchPage {
    val script = Regex("<script\\b[^>]*\\bid\\s*=\\s*[\"']__NEXT_DATA__[\"'][^>]*>(.*?)</script>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(html)?.groupValues?.get(1)
        ?: throw MarketException("应用宝搜索页面缺少应用数据")
    val root = jsonObject(script)
    // 旧地址被重定向到首页时不能把首页推荐当作搜索结果。
    if (root.text("page") != "/search") throw MarketException("应用宝未返回搜索页面")
    val response = root.obj("props")?.obj("pageProps")?.obj("dynamicCardResponse")
        ?: throw MarketException("应用宝搜索响应缺少应用列表")
    if (response.text("ret") != "0") throw MarketException(response.text("msg").ifBlank { "应用宝搜索失败" })
    val cards = response.obj("data")?.get("components") as? JsonArray
        ?: throw MarketException("应用宝搜索响应缺少应用列表")
    val items = cards.mapNotNull { it as? JsonObject }
        .filter { it.text("cardId") == "YYB_HOME_SEARCH_NORMAL_GAME" }
        .flatMap { ((it.obj("data")?.get("itemData") as? JsonArray).orEmpty()) }
        .mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val packageName = item.text("pkg_name")
            val id = item.number("app_id")
            // 网页结果还包含云游戏、PC 软件和推荐卡片，只展示 Android 应用。
            if (packageName.isBlank() || id <= 0L || item.text("game_type") in setOf("7", "8")) return@mapNotNull null
            MarketAppInfo(
                appId = id, packageName = packageName,
                displayName = item.text("name").ifBlank { packageName },
                publisherName = item.text("developer"),
                versionName = item.text("version_name"), versionCode = item.number("version_code"),
                icon = tencentHttpsUrl(item.text("icon")), apkSize = item.number("apk_size"),
                ratingScore = item.text("average_rating").toDoubleOrNull()?.coerceIn(0.0, 5.0) ?: 0.0,
                source = AppSource.TENCENT, openLink = "https://sj.qq.com/appdetail/$packageName",
                category = item.text("cate_name_new"),
                subscribeState = if (item.number("is_booking") == 1L) 1 else 0,
            )
        }.distinctBy { it.packageName.lowercase() }
    // 官网一次返回当前完整结果集，未使用未经验证的分页参数。
    return SearchPage(items, hasMore = false)
}

internal fun parseTencentDetail(text: String, packageName: String): TencentDetail {
    val root = jsonObject(text)
    if (root.text("ret") != "0") throw MarketException(root.text("err_msg").ifBlank { "应用宝详情请求失败" })
    val records = root.obj("app_detail_records") ?: throw MarketException("应用宝详情响应缺少应用记录")
    val record = records[packageName] as? JsonObject ?: throw AppNotListedException()
    val info = record.obj("app_info") ?: throw MarketException("应用宝详情缺少应用信息")
    val apk = record.obj("apk_all_data") ?: throw MarketException("应用宝详情缺少安装包信息")
    if (apk.text("package_name") != packageName || info.text("package_name") != packageName) {
        throw MarketException("应用宝返回的应用包名不匹配")
    }
    val url = tencentHttpsUrl(apk.text("url"))
    val unavailable = url.isBlank() || (info["online_status"] as? JsonPrimitive)?.longOrNull == 0L
    val rating = record.obj("app_rating_info")
    val app = MarketAppInfo(
        appId = apk.number("app_id"), packageName = packageName,
        displayName = apk.text("name").ifBlank { info.text("name") }.ifBlank { packageName },
        publisherName = info.text("developer").ifBlank { info.text("author") },
        versionName = apk.text("version_name"), versionCode = apk.number("version_code"),
        icon = tencentHttpsUrl(apk.text("logo256").ifBlank { apk.text("logo_big") }),
        apkSize = apk.number("size_byte"),
        ratingScore = rating?.text("average_rating")?.toDoubleOrNull()?.coerceIn(0.0, 5.0) ?: 0.0,
        changeLog = apk.text("feature"), category = info.text("category_name_new").ifBlank { info.text("category_name") },
        downloadCount = info.number("download_cnt_total"),
        openLink = "https://sj.qq.com/appdetail/$packageName", source = AppSource.TENCENT,
        downloadBlockReason = if (unavailable) "应用宝暂不提供下载" else "",
    )
    val screenshots = (apk["snapshot_bigs"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::tencentHttpsUrl)?.takeIf(String::isNotBlank) }
        .distinct().map { AppScreenshot(it, ScreenshotOrientation.PORTRAIT) }
    return TencentDetail(
        detail = AppDetail(
            app = app, brief = info.text("editor_intro"), introduction = info.text("desc"),
            changeLog = app.changeLog, category = app.category,
            ageClassification = info.number("suitable_age").takeIf { it > 0 }?.toString().orEmpty(),
            downloadCount = app.downloadCount, registrationNum = info.text("icp_number"),
            updateTime = apk.number("update_time") * 1000L,
            privacyUrl = tencentHttpsUrl(info.text("privacy_agreement")), screenshots = screenshots,
            commentCount = rating?.number("rating_count") ?: 0L, comments = emptyList(), sameDeveloperApps = emptyList(),
        ),
        url = url,
        checksum = apk.text("sha256").takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
            ?: apk.text("apk_md5").takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }.orEmpty(),
    )
}

internal fun tencentHttpsUrl(raw: String): String = when {
    raw.startsWith("https://", true) -> raw
    raw.startsWith("http://", true) -> "https://" + raw.substring(7)
    raw.startsWith("//") -> "https:$raw"
    else -> ""
}
private fun jsonObject(text: String): JsonObject =
    runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        ?: throw MarketException("应用宝返回了无效响应")
private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
private fun JsonObject.text(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
private fun JsonObject.number(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
