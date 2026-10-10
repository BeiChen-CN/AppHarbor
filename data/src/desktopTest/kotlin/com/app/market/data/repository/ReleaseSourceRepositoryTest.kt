package com.app.market.data.repository

import com.app.market.data.local.BooleanPreferenceKey
import com.app.market.data.local.PreferenceChanges
import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.StringPreferenceKey
import com.app.market.data.remote.releases.ReleaseApi
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReleaseSourceRepositoryTest {
    @Test
    fun searchDownloadAndRestartedUpdatesUseApkVersionAndPersistedRepository() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/search/repositories" -> """{"total_count":1,"items":[{"html_url":"https://github.com/owner/app"}]}"""
                "/repos/owner/app" -> """{"description":"Android app","owner":{"avatar_url":"https://example.test/icon.png"}}"""
                "/repos/owner/app/releases" -> """[{"tag_name":"v999.0","body":"Changes","assets":[{"name":"app.apk","browser_download_url":"https://example.test/app.apk","size":123,"digest":"sha256:${"a".repeat(64)}"}]}]"""
                else -> error("Unexpected request ${request.url}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val preferences = ReleaseTestPreferences()
        val packages = InstalledPackagesRepository { listOf(InstalledPackage("com.example.app", versionCode = 10, isSystemApp = false, versionName = "1.0")) }
        val inspector = object : ReleaseApkInspector {
            override suspend fun inspect(url: String, size: Long) = ReleaseApkIdentity("com.example.app", "1.1", 11, 123)
        }
        try {
            val repository = ReleaseSourceRepository(ReleaseApi(client), inspector, preferences, packages)
            val search = repository.search(AppSource.GITHUB, "example", 0)
            val app = search.items.single()
            assertEquals(11L, app.versionCode)
            assertEquals("1.1", app.versionName)
            assertEquals("com.example.app", app.packageName)
            assertEquals(false, search.hasMore)
            assertTrue(repository.checkUpdates(AppSource.GITHUB).isEmpty(), "Search alone must not bind update repositories")
            assertEquals("a".repeat(64), repository.downloadMeta(AppSource.GITHUB, app).parts.single().hash)
            val restored = ReleaseSourceRepository(ReleaseApi(client), inspector, preferences, packages)
            assertEquals(11L, restored.appDetail(AppSource.GITHUB, app.appId, app.packageName).app.versionCode)
            assertEquals(app.packageName, restored.checkUpdates(AppSource.GITHUB).single().packageName)
            assertEquals(ManualUpdateStatus.UPDATE_AVAILABLE,
                restored.checkManualUpdate(AppSource.GITHUB, ManualUpdateRequest(app.packageName, 10)).status)
            assertEquals(ManualUpdateStatus.RECOGNIZED_NO_UPDATE,
                restored.checkManualUpdate(AppSource.GITHUB, ManualUpdateRequest(app.packageName, 11)).status)
            assertTrue(restored.checkUpdates(AppSource.GITLAB).isEmpty())
        } finally { client.close() }
    }
}

private class ReleaseTestPreferences : PreferencesDataSource {
    private val strings = mutableMapOf<StringPreferenceKey, MutableStateFlow<String?>>()
    override fun observe(key: StringPreferenceKey): Flow<String?> = strings.getOrPut(key) { MutableStateFlow(null) }
    override fun observe(key: BooleanPreferenceKey): Flow<Boolean> = MutableStateFlow(key.default)
    override suspend fun update(namespace: String, changes: PreferenceChanges) {
        changes.strings.forEach { (key, value) -> strings.getOrPut(key) { MutableStateFlow(null) }.value = value }
    }
}
