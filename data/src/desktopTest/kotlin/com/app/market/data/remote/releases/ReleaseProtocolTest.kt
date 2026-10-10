package com.app.market.data.remote.releases

import com.app.market.data.repository.preferredReleaseAssets
import com.app.market.domain.model.market.AppSource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleaseProtocolTest {
    @Test
    fun recognizesRepositoryReleaseAndCloneLinks() {
        assertEquals("owner/app", parseReleaseRepository(AppSource.GITHUB, "https://github.com/owner/app/releases/tag/v1")?.path)
        assertEquals("owner/app", parseReleaseRepository(AppSource.GITHUB, "owner/app.git")?.path)
        assertEquals("group/subgroup/app", parseReleaseRepository(AppSource.GITLAB, "https://gitlab.com/group/subgroup/app/-/releases")?.path)
        assertNull(parseReleaseRepository(AppSource.GITHUB, "https://github.com.evil.test/owner/app"))
        assertNull(parseReleaseRepository(AppSource.GITHUB, "https://user:password@github.com/owner/app"))
        assertNull(parseReleaseRepository(AppSource.GITHUB, "https://gitlab.com/owner/app"))
        assertNull(parseReleaseRepository(AppSource.GITHUB, "a/../b"))
        assertNull(parseReleaseRepository(AppSource.GITHUB, "android app"))
    }

    @Test
    fun githubAssetsKeepOnlyUploadedApksAndValidDigest() {
        val hash = "a".repeat(64)
        val release = Json.parseToJsonElement("""{"assets":[
            {"name":"app.apk","browser_download_url":"https://cdn.example/app.apk","size":123,"digest":"sha256:$hash","state":"uploaded"},
            {"name":"source.zip","browser_download_url":"https://cdn.example/source.zip"},
            {"name":"bad.apk","browser_download_url":"http://cdn.example/bad.apk"},
            {"name":"pending.apk","browser_download_url":"https://cdn.example/pending.apk","state":"starter"}
        ]}""")
        assertEquals(listOf(ReleaseAsset("app.apk", "https://cdn.example/app.apk", 123, hash)), githubAssets(release))
    }

    @Test
    fun gitlabUsesDirectAssetAndUrlFilename() {
        val release = Json.parseToJsonElement("""{"assets":{"links":[
            {"name":"Android download","url":"https://cdn.example/app.apk","direct_asset_url":"https://gitlab.com/o/r/-/releases/v1/downloads/app.apk"},
            {"name":"source.zip","url":"https://cdn.example/source.zip"}
        ]}}""")
        val asset = gitlabAssets(release).single()
        assertEquals("app.apk", asset.name)
        assertTrue(asset.url.startsWith("https://gitlab.com/"))
    }

    @Test
    fun prefersUniversalAndFiltersWrongCpuAndDebugBuilds() {
        val names = listOf("debug.apk", "app-x86.apk", "app-arm64.apk", "app-universal.apk", "app-armeabi-v7a.apk")
        val assets = names.map { ReleaseAsset(it, "https://example.test/$it", 1) }
        assertEquals(listOf("app-universal.apk", "app-arm64.apk", "app-armeabi-v7a.apk"), preferredReleaseAssets(assets).map { it.name })
        assertEquals(listOf("app-universal.apk", "app-armeabi-v7a.apk"), preferredReleaseAssets(assets, "armeabi-v7a").map { it.name })
        val flavors = listOf("app-arm64-fdroid.apk", "app-arm64.apk").map { ReleaseAsset(it, "https://example.test/$it", 1) }
        assertEquals("app-arm64.apk", preferredReleaseAssets(flavors).first().name)
    }
}
