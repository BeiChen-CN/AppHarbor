package com.app.market.data.store

import com.app.market.data.local.BooleanPreferenceKey
import com.app.market.data.local.PreferenceChanges
import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.StringPreferenceKey
import com.app.market.data.remote.releases.GithubTokenHolder
import com.app.market.data.remote.releases.ReleaseApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseTokenRepositoryTest {
    @Test
    fun loginValidatesStoresAndPublishesState() = runBlocking {
        val client = HttpClient(MockEngine {
            respond("""{"login":"octocat"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val storage = TokenMemoryPreferences()
            val holder = GithubTokenHolder()
            val repository = ReleaseTokenRepositoryImpl(storage, ReleaseApi(client, holder), holder, scope)
            awaitInitialized(repository)

            val login = repository.login("  ghp_token  ")
            assertEquals("octocat", login)
            // 前后空白被裁掉后落盘，holder 同步拿到值供 ReleaseApi 使用。
            assertEquals("ghp_token", storage.puts.single { it.first.name == "github.token" }.second)
            assertEquals("octocat", storage.puts.single { it.first.name == "github.login" }.second)
            assertEquals("ghp_token", holder.token)
            assertTrue(repository.state.value.loggedIn)
            assertEquals("octocat", repository.state.value.login)
        } finally {
            scope.cancel()
            client.close()
        }
    }

    @Test
    fun invalidTokenIsNotPersisted() = runBlocking {
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.Unauthorized) })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val storage = TokenMemoryPreferences()
            val holder = GithubTokenHolder()
            val repository = ReleaseTokenRepositoryImpl(storage, ReleaseApi(client, holder), holder, scope)
            awaitInitialized(repository)

            assertFailsWith<com.app.market.domain.exception.MarketException> { repository.login("ghp_bad") }
            assertTrue(storage.puts.isEmpty())
            assertEquals("", holder.token)
            assertFalse(repository.state.value.loggedIn)
        } finally {
            scope.cancel()
            client.close()
        }
    }

    @Test
    fun blankTokenIsRejectedBeforeAnyRequest() = runBlocking {
        val client = HttpClient(MockEngine { error("must not reach network") })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val storage = TokenMemoryPreferences()
            val holder = GithubTokenHolder()
            val repository = ReleaseTokenRepositoryImpl(storage, ReleaseApi(client, holder), holder, scope)
            awaitInitialized(repository)

            assertFailsWith<IllegalArgumentException> { repository.login("   ") }
            Unit
        } finally {
            scope.cancel()
            client.close()
        }
    }

    @Test
    fun storedTokenIsRestoredOnConstruction() = runBlocking {
        val client = HttpClient(MockEngine { error("must not reach network") })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val storage = TokenMemoryPreferences()
            storage.put(StringPreferenceKey("release_sources", "github.token"), "ghp_saved")
            storage.put(StringPreferenceKey("release_sources", "github.login"), "octocat")
            val holder = GithubTokenHolder()
            val repository = ReleaseTokenRepositoryImpl(storage, ReleaseApi(client, holder), holder, scope)
            awaitInitialized(repository)

            assertEquals("ghp_saved", holder.token)
            assertEquals("octocat", repository.state.value.login)
            assertTrue(repository.state.value.loggedIn)
        } finally {
            scope.cancel()
            client.close()
        }
    }

    @Test
    fun logoutClearsStorageHolderAndState() = runBlocking {
        val client = HttpClient(MockEngine { error("must not reach network") })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val storage = TokenMemoryPreferences()
            storage.put(StringPreferenceKey("release_sources", "github.token"), "ghp_saved")
            val holder = GithubTokenHolder().apply { token = "ghp_saved" }
            val repository = ReleaseTokenRepositoryImpl(storage, ReleaseApi(client, holder), holder, scope)
            awaitInitialized(repository)

            repository.logout()
            assertEquals("", holder.token)
            assertFalse(repository.state.value.loggedIn)
            assertEquals(setOf("github.token", "github.login"), storage.removed.toSet())
        } finally {
            scope.cancel()
            client.close()
        }
    }

    @Test
    fun quotaUsesStoredToken() = runBlocking {
        val seen = mutableListOf<String?>()
        val client = HttpClient(MockEngine { request ->
            seen += request.headers[HttpHeaders.Authorization]
            respond(
                """{"resources":{"core":{"limit":5000,"remaining":4999}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val storage = TokenMemoryPreferences()
            storage.put(StringPreferenceKey("release_sources", "github.token"), "ghp_saved")
            val holder = GithubTokenHolder()
            val repository = ReleaseTokenRepositoryImpl(storage, ReleaseApi(client, holder), holder, scope)
            awaitInitialized(repository)

            assertEquals(4999 to 5000, repository.quota())
            assertEquals("Bearer ghp_saved", seen.single())
        } finally {
            scope.cancel()
            client.close()
        }
    }

    private suspend fun awaitInitialized(repository: ReleaseTokenRepositoryImpl) {
        withTimeout(5_000) {
            while (!repository.state.value.initialized) kotlinx.coroutines.yield()
        }
    }
}

private class TokenMemoryPreferences : PreferencesDataSource {
    private val strings = mutableMapOf<StringPreferenceKey, MutableStateFlow<String?>>()
    private val booleans = mutableMapOf<BooleanPreferenceKey, MutableStateFlow<Boolean>>()
    val puts = mutableListOf<Pair<StringPreferenceKey, String?>>()
    val removed = mutableListOf<String>()

    override fun observe(key: StringPreferenceKey) = strings.getOrPut(key) { MutableStateFlow(null) }
    override fun observe(key: BooleanPreferenceKey) = booleans.getOrPut(key) { MutableStateFlow(key.default) }
    override suspend fun update(namespace: String, changes: PreferenceChanges) {
        changes.strings.forEach { (key, value) ->
            observe(key).value = value
            puts += key to value
        }
        changes.booleans.forEach { (key, value) -> observe(key).value = value ?: key.default }
    }
    override suspend fun remove(key: com.app.market.data.local.PreferenceKey<*>) {
        removed += key.name
        when (key) {
            is StringPreferenceKey -> strings[key]?.value = null
            is BooleanPreferenceKey -> booleans[key]?.value = key.default
        }
    }
}
