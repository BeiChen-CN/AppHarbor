package com.app.market.data.remote.wandoujia

import com.app.market.domain.exception.MarketException
import kotlin.random.Random

private val RequestKey = "d861a5214f1849a1".encodeToByteArray()
private val ResponseKey = "c84b3226ce0673d4".encodeToByteArray()

internal fun wandoujiaM90EncodeRequest(data: ByteArray, seed: Int = Random.nextInt()): ByteArray =
    wandoujiaM90Encode(type = 31, data = data, key = RequestKey, seed = seed)

internal fun wandoujiaM90DecodeResponse(data: ByteArray): ByteArray =
    wandoujiaM90Decode(data = data, key = ResponseKey)

/** 按请求密钥解密，仅用于测试检查发出去的报文。 */
internal fun wandoujiaM90DecodeRequest(data: ByteArray): ByteArray =
    wandoujiaM90Decode(data = data, key = RequestKey)

/** 按服务端响应密钥加密，仅用于测试构造应答。 */
internal fun wandoujiaM90EncodeResponse(data: ByteArray, seed: Int = 0): ByteArray =
    wandoujiaM90Encode(type = 31, data = data, key = ResponseKey, seed = seed)

internal fun wandoujiaM90Encode(type: Int, data: ByteArray, key: ByteArray, seed: Int): ByteArray {
    require(key.size == 16)
    val state = IntArray(8) { key[it].toInt() and 0xff }
    val increment = IntArray(8) { key[it + 8].toInt() and 0xff }
    val random = intArrayOf(
        seed ushr 24 and 0xff,
        seed ushr 16 and 0xff,
        seed ushr 8 and 0xff,
        seed and 0xff,
        (seed ushr 24 and 0xff) + 87 and 0xff,
        (seed ushr 16 and 0xff) + 29 and 0xff,
        (seed ushr 8 and 0xff) + 171 and 0xff,
        (seed and 0xff) + 148 and 0xff,
    )
    val output = ByteArray(data.size + 10)
    output[0] = 'm'.code.toByte()
    output[1] = '9'.code.toByte()
    output[2] = '0'.code.toByte()
    output[3] = type.toByte()
    for (index in 0 until 4) output[index + 4] = random[index].toByte()
    var checksum = 0
    data.forEachIndexed { index, byte ->
        val position = index % 8
        if (position == 0) advanceM90State(state, increment, random)
        val plain = byte.toInt() and 0xff
        output[index + 8] = (plain xor state[position]).toByte()
        checksum = checksum xor plain
    }
    output[data.size + 8] = (checksum xor state[0]).toByte()
    output[data.size + 9] = (checksum xor state[1]).toByte()
    return output
}

internal fun wandoujiaM90Decode(data: ByteArray, key: ByteArray): ByteArray {
    if (data.size < 10 || data[0] != 'm'.code.toByte() || data[1] != '9'.code.toByte() ||
        data[2] != '0'.code.toByte()
    ) {
        throw MarketException("豌豆荚更新接口返回了未知的加密格式")
    }
    require(key.size == 16)
    val state = IntArray(8) { key[it].toInt() and 0xff }
    val increment = IntArray(8) { key[it + 8].toInt() and 0xff }
    val random = intArrayOf(
        data[4].toInt() and 0xff,
        data[5].toInt() and 0xff,
        data[6].toInt() and 0xff,
        data[7].toInt() and 0xff,
        ((data[4].toInt() and 0xff) + 87) and 0xff,
        ((data[5].toInt() and 0xff) + 29) and 0xff,
        ((data[6].toInt() and 0xff) + 171) and 0xff,
        ((data[7].toInt() and 0xff) + 148) and 0xff,
    )
    val output = ByteArray(data.size - 10)
    var checksum = 0
    output.indices.forEach { index ->
        val position = index % 8
        if (position == 0) advanceM90State(state, increment, random)
        val plain = (data[index + 8].toInt() and 0xff) xor state[position]
        output[index] = plain.toByte()
        checksum = checksum xor plain
    }
    val expected0 = (checksum xor state[0]) and 0xff
    val expected1 = (checksum xor state[1]) and 0xff
    if ((data[data.lastIndex - 1].toInt() and 0xff) != expected0 ||
        (data[data.lastIndex].toInt() and 0xff) != expected1
    ) {
        throw MarketException("豌豆荚更新响应校验失败")
    }
    return output
}

private fun advanceM90State(state: IntArray, increment: IntArray, random: IntArray) {
    for (index in 0 until 8) {
        state[index] = (state[index] + increment[index] + random[index]) and 0xff
    }
}
