package com.app.market.data.remote.releases

import com.app.market.domain.exception.MarketException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

/** Reads the ZIP directory and manifest with bounded Range requests; no APK is saved. */
internal class JvmReleaseApkInspector(private val client: HttpClient) : ReleaseApkInspector {
    override suspend fun inspect(url: String, size: Long): ReleaseApkIdentity {
        try {
            val totalSize = if (size > 0) size else range(url, "bytes=0-0", 1).total
            require(totalSize >= 22)
            val tailStart = maxOf(0L, totalSize - 65557)
            val tail = range(url, "bytes=$tailStart-${totalSize - 1}", minOf(totalSize, 65557L).toInt())
            val bytes = tail.bytes
            val end = (bytes.size - 22 downTo 0).firstOrNull {
                bytes.i32(it) == 0x06054b50 && it + 22 + bytes.u16(it + 20) == bytes.size
            } ?: error("APK ZIP directory missing")
            val directorySize = bytes.u32(end + 12)
            val directoryOffset = bytes.u32(end + 16)
            require(directorySize in 46..MAX_BYTES.toLong() && directoryOffset != 0xffffffffL)
            val directory = if (directoryOffset >= tail.start && directoryOffset + directorySize <= tail.start + bytes.size) {
                bytes.copyOfRange((directoryOffset - tail.start).toInt(), (directoryOffset - tail.start + directorySize).toInt())
            } else range(url, "bytes=$directoryOffset-${directoryOffset + directorySize - 1}", directorySize.toInt()).bytes
            var offset = 0
            while (offset + 46 <= directory.size) {
                require(directory.i32(offset) == 0x02014b50)
                val nameSize = directory.u16(offset + 28)
                val extraSize = directory.u16(offset + 30)
                val commentSize = directory.u16(offset + 32)
                val next = offset + 46 + nameSize + extraSize + commentSize
                require(next <= directory.size)
                val name = directory.copyOfRange(offset + 46, offset + 46 + nameSize).toString(Charsets.UTF_8)
                if (name == "AndroidManifest.xml") {
                    val compressedSize = directory.u32(offset + 20)
                    require(compressedSize in 1..MAX_BYTES.toLong())
                    val localOffset = directory.u32(offset + 42)
                    val header = range(url, "bytes=$localOffset-${localOffset + 29}", 30).bytes
                    require(header.i32(0) == 0x04034b50)
                    val start = localOffset + 30 + header.u16(26) + header.u16(28)
                    val compressed = range(url, "bytes=$start-${start + compressedSize - 1}", compressedSize.toInt()).bytes
                    val manifest = when (directory.u16(offset + 10)) {
                        0 -> compressed
                        8 -> {
                            val inflater = Inflater(true)
                            try { InflaterInputStream(ByteArrayInputStream(compressed), inflater).use { it.readNBytesBounded(MAX_BYTES) } }
                            finally { inflater.end() }
                        }
                        else -> error("Unsupported ZIP compression")
                    }
                    require(CRC32().apply { update(manifest) }.value == directory.u32(offset + 16))
                    return parseReleaseManifest(manifest, tail.total)
                }
                offset = next
            }
            error("APK manifest missing")
        } catch (prefix: ArchivePrefix) {
            // Some public asset hosts ignore Range. Read at most 8 MiB of the ZIP prefix.
            ZipInputStream(ByteArrayInputStream(prefix.bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.name == "AndroidManifest.xml") {
                        return parseReleaseManifest(zip.readNBytesBounded(MAX_BYTES), prefix.total.takeIf { it > 0 } ?: size)
                    }
                }
            }
            throw MarketException("安装包服务器不支持分段读取，无法获取 APK 信息")
        }
    }

    private suspend fun range(url: String, range: String, limit: Int): Block =
        client.prepareGet(url) {
            header(HttpHeaders.Range, range)
            header(HttpHeaders.AcceptEncoding, "identity")
        }.execute { response ->
            if (response.status.value !in listOf(200, 206)) throw MarketException("APK 信息读取失败：HTTP ${response.status.value}")
            val full = response.status.value == 200
            val cap = if (full) MAX_BYTES else limit
            val stream = response.bodyAsChannel()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (output.size() < cap) {
                val count = stream.readAvailable(buffer, 0, minOf(buffer.size, cap - output.size()))
                if (count < 0) break
                if (count > 0) output.write(buffer, 0, count)
            }
            if (full) throw ArchivePrefix(output.toByteArray(), response.headers[HttpHeaders.ContentLength]?.toLongOrNull() ?: 0L)
            val contentRange = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.headers[HttpHeaders.ContentRange].orEmpty())
                ?: error("APK server returned an invalid Content-Range")
            val (start, end, total) = contentRange.destructured
            val requested = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range) ?: error("Invalid Range")
            require(start == requested.groupValues[1] && end == requested.groupValues[2] && total.toLong() > end.toLong())
            require(output.size().toLong() == end.toLong() - start.toLong() + 1)
            Block(output.toByteArray(), start.toLong(), total.toLong())
        }

    private data class Block(val bytes: ByteArray, val start: Long, val total: Long)
    private class ArchivePrefix(val bytes: ByteArray, val total: Long) : Exception()
    private companion object { const val MAX_BYTES = 8 * 1024 * 1024 }
}

private fun java.io.InputStream.readNBytesBounded(limit: Int): ByteArray {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(out.size() + count <= limit) { "APK manifest too large" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

private fun ByteArray.u16(offset: Int): Int {
    require(offset >= 0 && offset + 2 <= size)
    return (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
}
private fun ByteArray.i32(offset: Int): Int = u16(offset) or (u16(offset + 2) shl 16)
private fun ByteArray.u32(offset: Int): Long = i32(offset).toLong() and 0xffffffffL

/** Android binary XML string pool and root manifest attributes, including versionCodeMajor. */
internal fun parseReleaseManifest(bytes: ByteArray, size: Long): ReleaseApkIdentity {
    require(bytes.u16(0) == 3 && bytes.i32(4) in 8..bytes.size) { "Invalid binary Android manifest" }
    var offset = bytes.u16(2)
    var strings = emptyList<String>()
    while (offset + 8 <= bytes.size) {
        val type = bytes.u16(offset)
        val headerSize = bytes.u16(offset + 2)
        val chunkSize = bytes.i32(offset + 4)
        require(headerSize >= 8 && chunkSize >= headerSize && offset.toLong() + chunkSize <= bytes.size)
        if (type == 1) {
            val count = bytes.i32(offset + 8)
            val utf8 = bytes.i32(offset + 16) and 0x100 != 0
            val base = offset + bytes.i32(offset + 20)
            require(count in 0..100000 && headerSize.toLong() + count.toLong() * 4 <= chunkSize)
            strings = (0 until count).map { index ->
                var cursor = base + bytes.i32(offset + headerSize + index * 4)
                require(cursor in offset until offset + chunkSize)
                fun length8(): Int {
                    val first = bytes[cursor++].toInt() and 255
                    return if (first and 128 != 0) ((first and 127) shl 8) or (bytes[cursor++].toInt() and 255) else first
                }
                val length = if (utf8) { length8(); length8() } else {
                    val first = bytes.u16(cursor).also { cursor += 2 }
                    if (first and 0x8000 != 0) ((first and 0x7fff) shl 16) or bytes.u16(cursor).also { cursor += 2 } else first
                }
                val byteLength = if (utf8) length.toLong() else length.toLong() * 2
                require(byteLength >= 0 && cursor.toLong() + byteLength <= offset.toLong() + chunkSize)
                bytes.copyOfRange(cursor, cursor + byteLength.toInt()).toString(if (utf8) Charsets.UTF_8 else Charsets.UTF_16LE)
            }
        } else if (type == 0x102) {
            val extension = offset + headerSize
            val name = strings.getOrNull(bytes.i32(extension + 4))
            if (name == "manifest") {
                val attributeStart = bytes.u16(extension + 8)
                val attributeSize = bytes.u16(extension + 10)
                val count = bytes.u16(extension + 12)
                require(attributeSize >= 20 && extension.toLong() + attributeStart + count.toLong() * attributeSize <= offset + chunkSize)
                val attributes = (0 until count).associate { index ->
                    val attr = extension + attributeStart + index * attributeSize
                    val key = strings.getOrNull(bytes.i32(attr + 4)).orEmpty()
                    val raw = strings.getOrNull(bytes.i32(attr + 8))
                    val valueType = bytes[attr + 15].toInt() and 255
                    val value = raw ?: if (valueType == 3) strings.getOrNull(bytes.i32(attr + 16)).orEmpty() else bytes.u32(attr + 16).toString()
                    key to value
                }
                val pkg = attributes["package"].orEmpty()
                require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(pkg))
                val minor = attributes["versionCode"]?.toLongOrNull() ?: 0L
                val major = attributes["versionCodeMajor"]?.toLongOrNull() ?: 0L
                val code = (major shl 32) or minor
                require(code > 0L)
                return ReleaseApkIdentity(pkg, attributes["versionName"].orEmpty(), code, size)
            }
        }
        offset += chunkSize
    }
    error("APK manifest identity missing")
}
