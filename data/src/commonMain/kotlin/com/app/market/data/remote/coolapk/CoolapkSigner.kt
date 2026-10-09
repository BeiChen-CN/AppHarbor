package com.app.market.data.remote.coolapk

import com.app.market.data.platform.md5
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock

internal const val CoolapkUserAgent =
    "Dalvik/2.1.0 (Linux; U; Android 9; MI 8 SE MIUI/9.5.9) (#Build; Xiaomi; MI 8 SE; PKQ1.181121.001; 9) +CoolMarket/12.4.2-2208241-universal"

/**
 * v2 Token 协议参考 Obtainium 的 CoolApk 来源，以及其引用的 Coolapk-UWP / FuckCoolapkTokenV2。
 * https://github.com/ImranR98/Obtainium/blob/main/lib/app_sources/coolapk.dart
 * 客户端版本与请求指纹保持配套；只生成虚拟设备信息，不读取真实设备标识。
 */
internal class CoolapkSigner(private val deviceCode: String = randomDeviceCode()) {
    fun headers(epochSeconds: Long = Clock.System.now().epochSeconds): Map<String, String> {
        val timestamp = epochSeconds.toString()
        val token = "token://com.coolapk.market/dcf01e569c1e3db93a3d0fcf191a622c?" +
            timestamp.md5Hex() + "$" + deviceCode.md5Hex() + "&com.coolapk.market"
        val salt = "\$2a\$10\$${timestamp.base64().take(14)}/${token.md5Hex().take(6)}u"
        val hash = coolapkBcrypt(token.base64().md5Hex(), salt)
        return mapOf(
            "User-Agent" to CoolapkUserAgent,
            "X-App-Id" to "com.coolapk.market",
            "X-Requested-With" to "XMLHttpRequest",
            "X-Sdk-Int" to "30",
            "X-App-Mode" to "universal",
            "X-App-Channel" to "coolapk",
            "X-Sdk-Locale" to "zh-CN",
            "X-App-Version" to "12.4.2",
            "X-Api-Supported" to "2208241",
            "X-App-Code" to "2208241",
            "X-Api-Version" to "12",
            "X-App-Device" to deviceCode,
            "X-Dark-Mode" to "0",
            "X-App-Token" to "v2${hash.replaceFirst("\$2a\$", "\$2y\$").base64()}",
        )
    }
}

internal expect fun coolapkBcrypt(value: String, salt: String): String

private fun String.base64(): String = Base64.encode(encodeToByteArray())
private fun String.md5Hex(): String = md5(encodeToByteArray()).joinToString("") {
    (it.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun randomDeviceCode(): String {
    val androidId = ByteArray(16).also(Random.Default::nextBytes).joinToString("") {
        (it.toInt() and 0xff).toString(16).padStart(2, '0').uppercase()
    }
    val mac = List(6) { Random.nextInt(256).toString(16).padStart(2, '0') }.joinToString(":")
    return "$androidId; ; ; $mac; Google; Google; Pixel 5a; SQ1D.220105.007".base64()
}
