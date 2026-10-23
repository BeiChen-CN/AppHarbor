package com.app.market.data.repository

import com.app.market.data.remote.lsposed.LsposedApi
import com.app.market.data.remote.releases.ReleaseApkIdentity
import com.app.market.data.remote.releases.ReleaseApkInspector
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateStatus
import com.app.market.domain.repository.InstalledPackagesRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LsposedRepositoryTest {
    @Test
    fun searchResolvesReleaseAndChecksUpdateByApkVersionCode() = runBlocking {
        var inspected = 0
        val client = HttpClient(MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/search.json" -> """[{"name":"org.matrix.chromext","description":"ChromeXt","summary":"Chromium userscript support","authors":"JingMatrix"}]"""
                "/module/org.matrix.chromext.json" -> """{
                    "name":"org.matrix.chromext","description":"ChromeXt","summary":"Chromium userscript support",
                    "homepageUrl":"https://github.com/JingMatrix/ChromeXt",
                    "collaborators":[{"login":"JingMatrix","name":"JingMatrix"}],
                    "latestRelease":"18-3.9.1","latestReleaseTime":"2026-10-21T01:37:25Z",
                    "readmeHTML":"<p>Readme</p><img src=\"https://example.test/shot.png\">",
                    "releases":[{"tagName":"18-3.9.1","descriptionHTML":"<p>Fixes</p>",
                        "releaseAssets":[{"name":"ChromeXt.apk","contentType":"application/vnd.android.package-archive",
                        "originalDownloadUrl":"https://github.com/Xposed-Modules-Repo/org.matrix.chromext/releases/download/18-3.9.1/ChromeXt.apk",
                        "size":129383}]}]
                }"""
                else -> error("Unexpected request ${request.url}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val inspector = object : ReleaseApkInspector {
            override suspend fun inspect(url: String, size: Long): ReleaseApkIdentity {
                inspected++
                return ReleaseApkIdentity("org.matrix.chromext", "3.9.1", 18, size)
            }
        }
        val packages = InstalledPackagesRepository {
            listOf(InstalledPackage("org.matrix.chromext", versionCode = 17, isSystemApp = false, versionName = "3.9.0"))
        }
        try {
            val repository = LsposedRepository(LsposedApi(client), inspector, packages)
            val search = repository.search("chromext", 0)
            assertEquals(AppSource.LSPOSED, search.items.single().source)
            assertEquals("org.matrix.chromext", search.items.single().packageName)
            assertEquals(0, inspected, "Search must not download APK metadata")

            val detail = repository.appDetail("org.matrix.chromext")
            assertEquals(18L, detail.app.versionCode)
            assertEquals("3.9.1", detail.app.versionName)
            assertEquals("Fixes", detail.changeLog)
            assertEquals("https://example.test/shot.png", detail.screenshots.single().url)
            assertEquals(1, inspected)

            val meta = repository.downloadMeta(detail.app)
            assertEquals(AppSource.LSPOSED, meta.source)
            assertEquals("https://github.com/Xposed-Modules-Repo/org.matrix.chromext/releases/download/18-3.9.1/ChromeXt.apk", meta.url)

            val update = repository.checkUpdates().single()
            assertEquals("org.matrix.chromext", update.packageName)
            assertEquals(17L, update.installedVersionCode)
            assertEquals(ManualUpdateStatus.UPDATE_AVAILABLE,
                repository.checkManualUpdate(ManualUpdateRequest("org.matrix.chromext", 17)).status)
            assertEquals(ManualUpdateStatus.RECOGNIZED_NO_UPDATE,
                repository.checkManualUpdate(ManualUpdateRequest("org.matrix.chromext", 18)).status)
            assertTrue(repository.search("missing", 0).items.isEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun htmlConversionKeepsReadableText() {
        assertEquals("Hello\nworld & more", htmlToText("<p>Hello</p><p>world &amp; more</p>"))
    }
}
