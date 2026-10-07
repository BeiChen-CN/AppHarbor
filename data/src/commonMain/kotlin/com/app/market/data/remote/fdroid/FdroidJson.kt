package com.app.market.data.remote.fdroid

import com.app.market.domain.exception.MarketException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.absoluteValue

internal val FdroidJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal fun parseFdroidObject(text: String): JsonObject =
    (FdroidJson.parseToJsonElement(text) as? JsonObject)
        ?: throw MarketException("F-Droid 接口返回了非预期的响应格式")

private fun JsonElement?.primitive(key: String): JsonPrimitive? =
    ((this as? JsonObject)?.get(key)) as? JsonPrimitive

internal fun JsonElement?.fdjString(key: String, default: String = ""): String =
    primitive(key)?.contentOrNull ?: default

internal fun JsonElement?.fdjLong(key: String, default: Long = 0L): Long =
    primitive(key)?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() } ?: default

internal fun JsonElement?.fdjObject(key: String): JsonObject? =
    ((this as? JsonObject)?.get(key)) as? JsonObject

internal fun JsonElement?.fdjArray(key: String): JsonArray? =
    ((this as? JsonObject)?.get(key)) as? JsonArray

/**
 * F-Droid 无站内数字 id，用包名的稳定哈希充当 [com.app.market.domain.model.market.MarketAppInfo.appId]，
 * 保证同一包名在搜索/详情/历史版本之间 id 一致（与 Samsung 源 stableId 同策略）。
 */
internal fun fdroidStableId(packageName: String): Long {
    val hash = packageName.fold(1125899906842597L) { acc, char -> acc * 31 + char.code }
    return hash.absoluteValue.coerceAtLeast(1L)
}
