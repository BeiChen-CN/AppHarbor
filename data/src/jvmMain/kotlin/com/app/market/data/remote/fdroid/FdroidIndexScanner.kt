package com.app.market.data.remote.fdroid

import com.app.market.domain.exception.MarketException
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/**
 * index-v2 顶层流式扫描器。索引原始 JSON 高达 60MB+，整体解码会产生数百 MB 的中间对象，
 * 因此在词法层逐字符扫描：顶层只关心 `packages` 键，逐包提取该包对象的原始 JSON 切片，
 * 其余键（repo 等）整段跳过。内存峰值仅为单个包的切片文本（通常 10~50KB）。
 *
 * 只假设输入是合法 JSON（F-Droid 官方产物，无注释/宽松语法），词法处理涵盖：
 * 字符串转义、嵌套对象/数组的深度配对、原始值（数字/布尔/null）。
 */
internal class FdroidIndexScanner(input: InputStream) : Closeable {
    private val reader = BufferedReader(InputStreamReader(input, Charsets.UTF_8), BUFFER_CHARS)
    private val capture = StringBuilder(CAPTURE_CHARS)
    private val discard = StringBuilder(64)

    /** 已读出的待还字符（peek 语义），-1 表示流结束。 */
    private var pending: Int = -1

    /** 是否已经通过 [peekChar] 预读过字符。 */
    private var hasPending = false

    /**
     * 遍历顶层 `packages` 映射，对每个包回调 `[包名, 该包对象的原始 JSON 文本]`。
     * 未出现 `packages` 键或结构非法时抛 [MarketException]。
     */
    fun forEachPackage(action: (packageName: String, packageJson: String) -> Unit) {
        skipWhitespace()
        expect('{')
        var packagesSeen = false
        while (true) {
            skipWhitespace()
            when (peekChar()) {
                '}'.code -> {
                    readChar()
                    break
                }
                // 顶层键之间的逗号
                ','.code -> {
                    readChar()
                    continue
                }
                -1 -> break
                '"'.code -> {}
                else -> throw indexFormatException()
            }
            val key = readString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            if (key == PACKAGES_KEY) {
                packagesSeen = true
                expect('{')
                scanPackages(action)
            } else {
                // repo 等其他顶层键：整段跳过，不占用调用方内存
                readValueInto(discard)
            }
        }
        if (!packagesSeen) throw indexFormatException()
    }

    override fun close() {
        reader.close()
    }

    /** 扫描 `packages` 对象体：键为包名，值为包对象（以原始文本切片回调）。 */
    private fun scanPackages(action: (packageName: String, packageJson: String) -> Unit) {
        while (true) {
            skipWhitespace()
            when (peekChar()) {
                '}'.code -> {
                    readChar()
                    return
                }
                ','.code -> {
                    readChar()
                    continue
                }
                '"'.code -> {}
                -1 -> throw indexFormatException()
                else -> throw indexFormatException()
            }
            val packageName = readString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            action(packageName, readRawValue())
        }
    }

    /** 读取下一个完整 JSON 值并返回其原始文本。 */
    private fun readRawValue(): String {
        capture.setLength(0)
        readValueInto(capture)
        // 空切片意味着值位置出现结构分隔符（如 ":}"），属于非法输入
        if (capture.isEmpty()) throw indexFormatException()
        return capture.toString()
    }

    /** 读取一个 JSON 值（对象/数组/字符串/原始值），原文写入 [out]。 */
    private fun readValueInto(out: StringBuilder) {
        when (peekChar()) {
            '"'.code -> readStringInto(out, withQuotes = true)
            '{'.code -> readDelimitedInto(out, '{', '}')
            '['.code -> readDelimitedInto(out, '[', ']')
            -1 -> throw indexFormatException()
            else -> {
                // 数字 / true / false / null：读到结构性分隔符为止
                while (true) {
                    val ch = peekChar()
                    if (ch == -1 || ch == ','.code || ch == '}'.code || ch == ']'.code ||
                        ch == ' '.code || ch == '\t'.code || ch == '\r'.code || ch == '\n'.code
                    ) break
                    out.append(readChar().toChar())
                }
            }
        }
    }

    /**
     * 读取一个以 [open]/[close] 定界的嵌套结构（对象或数组）原文。
     * 深度在字符串外计数；字符串内的引号/转义不参与配对。
     */
    private fun readDelimitedInto(out: StringBuilder, open: Char, close: Char) {
        expect(open)
        out.append(open)
        var depth = 1
        var inString = false
        while (depth > 0) {
            val c = readChar()
            if (c == -1) throw indexFormatException()
            val ch = c.toChar()
            out.append(ch)
            when {
                inString && ch == '\\' -> {
                    // 转义序列：下一个字符原样跟随（\" 不关闭字符串）
                    val next = readChar()
                    if (next == -1) throw indexFormatException()
                    out.append(next.toChar())
                }
                ch == '"' -> inString = !inString
                inString -> {}
                ch == open -> depth++
                ch == close -> depth--
            }
        }
    }

    /** 读取一个 JSON 字符串（要求当前字符为引号）并返回转义还原后的内容。 */
    private fun readString(): String {
        capture.setLength(0)
        readStringInto(capture, withQuotes = false)
        return capture.toString()
    }

    /** 读取字符串原文；[withQuotes] 决定输出是否带引号，统一处理转义。 */
    private fun readStringInto(out: StringBuilder, withQuotes: Boolean) {
        expect('"')
        if (withQuotes) out.append('"')
        while (true) {
            val c = readChar()
            if (c == -1) throw indexFormatException()
            when (val ch = c.toChar()) {
                '"' -> {
                    if (withQuotes) out.append('"')
                    return
                }
                '\\' -> {
                    val next = readChar()
                    if (next == -1) throw indexFormatException()
                    when (val escaped = next.toChar()) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> {
                            var value = 0
                            repeat(4) {
                                val hex = readChar()
                                if (hex == -1) throw indexFormatException()
                                val digit = hex.toChar().digitToIntOrNull(16)
                                    ?: throw indexFormatException()
                                value = (value shl 4) or digit
                            }
                            out.append(value.toChar())
                        }
                        else -> throw indexFormatException()
                    }
                }
                else -> out.append(ch)
            }
        }
    }

    private fun skipWhitespace() {
        while (true) {
            when (peekChar()) {
                ' '.code, '\t'.code, '\r'.code, '\n'.code -> readChar()
                else -> return
            }
        }
    }

    private fun expect(expected: Char) {
        val c = readChar()
        if (c == -1 || c.toChar() != expected) throw indexFormatException()
    }

    private fun peekChar(): Int {
        if (!hasPending) {
            pending = try {
                reader.read()
            } catch (e: IOException) {
                throw MarketException("读取 F-Droid 索引失败：${e.message}", e)
            }
            hasPending = true
        }
        return pending
    }

    private fun readChar(): Int {
        val c = peekChar()
        hasPending = false
        pending = -1
        return c
    }

    private fun indexFormatException(): MarketException =
        MarketException("F-Droid 索引格式异常，无法解析")

    private companion object {
        const val PACKAGES_KEY = "packages"
        const val BUFFER_CHARS = 1 shl 16
        const val CAPTURE_CHARS = 1 shl 19
    }
}
