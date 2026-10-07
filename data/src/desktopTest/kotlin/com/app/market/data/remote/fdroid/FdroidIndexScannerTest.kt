package com.app.market.data.remote.fdroid

import com.app.market.domain.exception.MarketException
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * index-v2 顶层流式扫描器的行为测试：逐包切片正确性、转义/嵌套健壮性、
 * repo 等其他顶层键的跳过，以及 gzip/明文两种输入形态。
 */
class FdroidIndexScannerTest {
    private fun scanner(json: String) = FdroidIndexScanner(ByteArrayInputStream(json.toByteArray(StandardCharsets.UTF_8)))

    private fun gzipped(json: String): ByteArray = ByteArrayOutputStream().use { raw ->
        GZIPOutputStream(raw).use { it.write(json.toByteArray(StandardCharsets.UTF_8)) }
        raw.toByteArray()
    }

    private fun scan(json: String): List<Pair<String, String>> {
        val entries = mutableListOf<Pair<String, String>>()
        scanner(json).forEachPackage { name, raw -> entries.add(name to raw) }
        return entries
    }

    @Test
    fun extractsEachPackageAndSkipsOtherTopLevelKeys() {
        val json = """
            {"repo":{"address":"https://f-droid.org/repo","timestamp":123},
             "packages":{
               "org.a":{"metadata":{"name":{"en-US":"A"}},"versions":{"x":{"manifest":{"versionCode":1}}}},
               "org.b":{"metadata":{"name":{"zh-CN":"乙"}},"versions":{}}
             }}
        """.trimIndent()
        val entries = scan(json)
        assertEquals(listOf("org.a", "org.b"), entries.map { it.first })
        assertTrue(entries[0].second.startsWith("""{"metadata"""))
        assertTrue(entries[1].second.endsWith("}"))
    }

    @Test
    fun packageJsonDecodesIntoPartialModel() {
        val json = """
            {"repo":{"ignored":true},
             "packages":{"org.fdroid.fdroid":{
               "metadata":{
                 "name":{"en-US":"F-Droid","zh-CN":"F-Droid 客户端"},
                 "summary":{"zh-CN":"应用商店"},
                 "description":{"en-US":"Long description with {braces} and \"quotes\"."},
                 "icon":{"en-US":{"name":"/org.fdroid.fdroid/en-US/icon.png","sha256":"aa","size":9}},
                 "screenshots":{"phone":{"en-US":[{"name":"/org.fdroid.fdroid/en-US/phoneScreenshots/1.png","sha256":"bb","size":10}]}},
                 "categories":["System"],"license":"GPL-3.0-or-later",
                 "authorName":"F-Droid Limited","preferredSigner":"43238d51",
                 "lastUpdated":1790000000000
               },
               "versions":{
                 "hash2":{"added":200,"file":{"name":"/org.fdroid.fdroid_2.apk","sha256":"f2","size":20},
                          "manifest":{"versionName":"2.0","versionCode":2,"usesSdk":{"minSdkVersion":24},
                                      "signer":{"sha256":["43238d51"]}},"whatsNew":{"zh-CN":"第二版"},"antiFeatures":{"NonFreeNet":{}}},
                 "hash1":{"added":100,"file":{"name":"/org.fdroid.fdroid_1.apk","sha256":"f1","size":10},
                          "manifest":{"versionName":"1.0","versionCode":1,"usesSdk":{"minSdkVersion":21}}}
               }
             }}}
        """.trimIndent()
        val entries = scan(json)
        assertEquals(1, entries.size)
        val app = Json { ignoreUnknownKeys = true; isLenient = true }
            .decodeFromString<FdroidIndexPackage>(entries.single().second)
        assertEquals("F-Droid 客户端", app.metadata.name.fdroidLocalized())
        assertEquals("""Long description with {braces} and "quotes".""", app.metadata.description.fdroidLocalized())
        assertEquals("/org.fdroid.fdroid/en-US/icon.png", app.metadata.icon.fdroidAssetPath())
        assertEquals(
            "/org.fdroid.fdroid/en-US/phoneScreenshots/1.png",
            app.metadata.screenshots?.phone?.get("en-US")?.single()?.name,
        )
        val versions = app.versions.values.mapNotNull { it.toFragmentVersionForTest() }.sortedByDescending { it.versionCode }
        assertEquals(2, versions.size)
        assertEquals("2.0", versions[0].versionName)
        assertEquals(2L, versions[0].versionCode)
        assertEquals(24, versions[0].minSdkVersion)
        assertEquals("/org.fdroid.fdroid_2.apk", versions[0].apkName)
        assertEquals(listOf("NonFreeNet"), versions[0].antiFeatures)
        assertEquals("第二版", versions[0].whatsNew.fdroidLocalized())
    }

    @Test
    fun handlesEscapedSequencesAndUnicode() {
        val json = """{"packages":{"org.u":{"metadata":{"name":{"en-US":"A\nB \u4e2d \"q\" \\ z"}},"versions":{}}}}"""
        val entries = scan(json)
        assertEquals(1, entries.size)
        assertEquals("org.u", entries.single().first)
        val app = Json { ignoreUnknownKeys = true }
            .decodeFromString<FdroidIndexPackage>(entries.single().second)
        assertEquals("A\nB 中 \"q\" \\ z", app.metadata.name.fdroidLocalized())
    }

    @Test
    fun acceptsGzipAndPlainInputIdentically() {
        // gzip 解压由缓存层（魔数嗅探 + GZIPInputStream）负责，扫描器只消费解压后的明文
        val json = """{"packages":{"org.g":{"metadata":{"name":{"en-US":"G"}},"versions":{}}}}"""
        val fromPlain = scan(json)
        val fromGzip = mutableListOf<Pair<String, String>>()
        FdroidIndexScanner(GZIPInputStream(ByteArrayInputStream(gzipped(json)))).use { s ->
            s.forEachPackage { name, raw -> fromGzip.add(name to raw) }
        }
        assertEquals(fromPlain, fromGzip)
    }

    @Test
    fun emptyPackagesAndMissingKeyAreHandled() {
        assertEquals(emptyList(), scan("""{"packages":{}}"""))
        assertFailsWith<MarketException> { scan("""{"repo":{}}""") }
    }

    @Test
    fun malformedInputFailsWithMarketException() {
        assertFailsWith<MarketException> { scan("""{"packages":{"org.x":}}}""") }
        assertFailsWith<MarketException> { scan("""{"packages":{"org.x":{""") }
        assertFailsWith<MarketException> { scan("""{"packages": 12}""") }
    }

    /** 测试辅助：跳过 file 缺失等过滤，便于直接断言解码结果。 */
    private fun FdroidIndexVersion.toFragmentVersionForTest(): FdroidFragmentVersion? {
        val manifest = manifest ?: return null
        val file = file ?: return null
        return FdroidFragmentVersion(
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            apkName = file.name,
            minSdkVersion = manifest.usesSdk?.minSdkVersion ?: 0,
            signers = manifest.signer?.sha256.orEmpty(),
            whatsNew = whatsNew,
            antiFeatures = antiFeatures.keys.toList(),
        )
    }
}
