package com.app.market.data.remote.releases

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

class ReleaseApkInspectorTest {
    private val manifest: ByteArray get() = requireNotNull(javaClass.getResourceAsStream("/releases/appharbor-manifest.axml")).use { it.readBytes() }

    @Test
    fun readsActualBinaryManifestInsteadOfReleaseTag() {
        val identity = parseReleaseManifest(manifest, 123)
        assertEquals("com.app.market", identity.packageName)
        assertEquals("2.5.0", identity.versionName)
        assertEquals(231L, identity.versionCode)
        assertEquals(123L, identity.size)
    }

    @Test
    fun rejectsTruncatedAndInvalidManifest() {
        assertFails { parseReleaseManifest(manifest.copyOf(20), 0) }
        assertFails { parseReleaseManifest("<manifest/>".toByteArray(), 0) }
    }

    @Test
    fun rangeReadsManifestWithoutFetchingLargeApk() = runBlocking {
        val apk = archive(large = true)
        var transferred = 0
        val client = HttpClient(MockEngine { request ->
            val value = request.headers[HttpHeaders.Range].orEmpty().removePrefix("bytes=")
            val start = if (value.startsWith('-')) maxOf(0, apk.size - value.drop(1).toInt()) else value.substringBefore('-').toInt()
            val end = if (value.startsWith('-')) apk.lastIndex else value.substringAfter('-').toInt()
            val data = apk.copyOfRange(start, end + 1)
            transferred += data.size
            respond(data, HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes $start-$end/${apk.size}"))
        })
        try {
            val identity = JvmReleaseApkInspector(client).inspect("https://example.test/app.apk", apk.size.toLong())
            assertEquals("com.app.market", identity.packageName)
            assertEquals(apk.size.toLong(), identity.size)
            assertTrue(transferred < 100_000, "Transferred $transferred bytes")
        } finally { client.close() }
    }

    @Test
    fun handlesServerIgnoringRange() = runBlocking {
        val apk = archive(large = false)
        val client = HttpClient(MockEngine { respond(apk, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, apk.size.toString())) })
        try {
            assertEquals(231L, JvmReleaseApkInspector(client).inspect("https://example.test/app.apk").versionCode)
        } finally { client.close() }
    }

    private fun archive(large: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifest)
            zip.closeEntry()
            if (large) {
                val filler = ByteArray(10 * 1024 * 1024)
                val crc = java.util.zip.CRC32().apply { update(filler) }
                zip.putNextEntry(ZipEntry("assets/large.bin").apply { method = ZipEntry.STORED; size = filler.size.toLong(); compressedSize = size; this.crc = crc.value })
                zip.write(filler)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
