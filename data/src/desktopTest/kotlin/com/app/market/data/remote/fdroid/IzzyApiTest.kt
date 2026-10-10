package com.app.market.data.remote.fdroid

import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.market.AppSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IzzyApiTest {
    private val signer = "a".repeat(64)
    private val hash = "b".repeat(64)
    private val version = FdroidFragmentVersion(versionName = "2.0", versionCode = 2,
        apkName = "/com.example.app_2.apk", apkSha256 = hash, apkSize = 123, signers = listOf(signer))
    private val fragment = FdroidAppFragment(packageName = "com.example.app", name = mapOf("en-US" to "Example App"),
        versions = listOf(version), preferredSigner = signer)
    private val index = object : FdroidIndexCache {
        override suspend fun summary(): FdroidIndexSummary = FdroidIndexSummary(apps = (0..24).associate { number ->
            val pkg = if (number == 0) fragment.packageName else "com.example.app$number"
            pkg to FdroidAppSummary(displayName = "Example $number", latest = FdroidSummaryVersion(
                versionName = "2.0", versionCode = 2, apkName = version.apkName, signers = listOf(signer)), preferredSigner = signer)
        })
        override suspend fun fragment(packageName: String): FdroidAppFragment? = fragment.takeIf { it.packageName == packageName }
    }

    @Test
    fun indexedSearchPaginatesAndPrioritizesExactPackage() = runBlocking {
        val client = HttpClient(MockEngine { error("Izzy must use its own index, never F-Droid search") })
        try {
            val api = FdroidApi(client, IzzyApiConfig, index)
            val first = api.search("com.example.app", 0)
            val second = api.search("com.example.app", 1)
            assertEquals(20, first.items.size)
            assertEquals("com.example.app", first.items.first().packageName)
            assertTrue(first.hasMore)
            assertEquals(5, second.items.size)
            assertEquals(false, second.hasMore)
            assertTrue((first.items + second.items).all { it.source == AppSource.IZZYONDROID })
        } finally { client.close() }
    }

    @Test
    fun detailsDownloadsHistoryAndUpdatesRetainIzzyIdentity() = runBlocking {
        val client = HttpClient(MockEngine { error("No web API expected") })
        try {
            val api = FdroidApi(client, IzzyApiConfig, index)
            val detail = api.appDetail(fragment.packageName)
            assertEquals(AppSource.IZZYONDROID, detail.app.source)
            val meta = api.downloadMeta(detail.app)
            assertEquals("https://apt.izzysoft.de/fdroid/repo/com.example.app_2.apk", meta.url)
            assertEquals(hash, meta.parts.single().hash)
            assertEquals(AppSource.IZZYONDROID, meta.source)
            val historical = api.historicalVersions(fragment.packageName, 0).items.single()
            assertEquals(hash, api.historicalDownloadMeta(historical).parts.single().hash)
            val local = InstalledPackage(fragment.packageName, versionCode = 1, versionName = "1.0", isSystemApp = false, signerSha256 = signer)
            assertEquals(AppSource.IZZYONDROID, api.checkUpdates(listOf(local)).single().source)
            assertTrue(api.checkUpdates(listOf(local.copy(signerSha256 = "c".repeat(64)))).isEmpty())
        } finally { client.close() }
    }
}
