package com.app.market.data.remote.fdroid

import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.market.AppSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TunaApiTest {
    private val signer = "a".repeat(64)
    private val hash = "b".repeat(64)
    private val version = FdroidFragmentVersion(versionName = "2.0", versionCode = 2,
        apkName = "/com.example.app_2.apk", apkSha256 = hash, apkSize = 1024, signers = listOf(signer))
    private val fragment = FdroidAppFragment(packageName = "com.example.app", name = mapOf("en-US" to "Example"),
        versions = listOf(version), preferredSigner = signer)
    private val index = object : FdroidIndexCache {
        override suspend fun summary() = FdroidIndexSummary(apps = (0..24).associate { n ->
            val pkg = if (n == 0) fragment.packageName else "com.example.app$n"
            pkg to FdroidAppSummary(displayName = "Example $n", latest = FdroidSummaryVersion(
                versionName = "2.0", versionCode = 2, apkName = version.apkName, signers = listOf(signer)), preferredSigner = signer)
        })
        override suspend fun fragment(packageName: String) = fragment.takeIf { it.packageName == packageName }
    }

    @Test
    fun mirrorSearchPaginatesAndAllApkPathsStayOnMirror() = runBlocking {
        val client = HttpClient(MockEngine { error("Mirror source must not fall back to official search or package API") })
        try {
            val api = FdroidApi(client, TunaApiConfig, index)
            val first = api.search("com.example.app", 0)
            assertEquals(20, first.items.size)
            assertEquals(5, api.search("com.example.app", 1).items.size)
            assertEquals(fragment.packageName, first.items.first().packageName)
            assertTrue(first.items.all { it.source == AppSource.FDROID_TUNA })
            val detail = api.appDetail(fragment.packageName)
            assertTrue(detail.app.openLink.startsWith("https://f-droid.org/en/packages/"))
            val meta = api.downloadMeta(detail.app)
            assertEquals("${TunaApiConfig.repoBase}/com.example.app_2.apk", meta.url)
            assertEquals(hash, meta.parts.single().hash)
            assertEquals(AppSource.FDROID_TUNA, meta.source)
            val historical = api.historicalVersions(fragment.packageName, 0).items.single()
            assertEquals(meta.url, api.historicalDownloadMeta(historical).url)
            assertEquals(hash, api.historicalDownloadMeta(historical).parts.single().hash)
            assertFailsWith<MarketException> { api.appDetail("com.missing.app") }
            Unit
        } finally { client.close() }
    }

    @Test
    fun mirrorUpdateCheckPreservesSignatureCompatibility() = runBlocking {
        val client = HttpClient(MockEngine { error("No official API expected") })
        try {
            val api = FdroidApi(client, TunaApiConfig, index)
            val installed = InstalledPackage(fragment.packageName, versionCode = 1, versionName = "1.0", isSystemApp = false, signerSha256 = signer)
            assertEquals(AppSource.FDROID_TUNA, api.checkUpdates(listOf(installed)).single().source)
            assertTrue(api.checkUpdates(listOf(installed.copy(signerSha256 = "c".repeat(64)))).isEmpty())
        } finally { client.close() }
    }
}
