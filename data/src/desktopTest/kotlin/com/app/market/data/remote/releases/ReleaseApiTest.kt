package com.app.market.data.remote.releases

import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.market.AppSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ReleaseApiTest {
    @Test
    fun gitlabSearchUsesPublicProjectsAndEncodedSubgroupReleasePath() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/api/v4/projects" -> {
                    assertEquals("example", request.url.parameters["search"])
                    assertEquals("3", request.url.parameters["page"])
                    """[{"web_url":"https://gitlab.com/group/sub/app"}]"""
                }
                "/api/v4/projects/group%2Fsub%2Fapp" -> """{"description":"Example","avatar_url":""}"""
                "/api/v4/projects/group%2Fsub%2Fapp/releases" -> """[
                    {"tag_name":"v2-rc1","assets":{"links":[{"name":"app.apk","url":"https://example.test/rc.apk"}]}},
                    {"tag_name":"v1","description":"Stable","assets":{"links":[{"name":"app.apk","url":"https://example.test/app.apk"}]}}
                ]"""
                else -> error("Unexpected URL ${request.url}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val api = ReleaseApi(client)
            val (refs, more) = api.search(AppSource.GITLAB, "example", 2)
            assertEquals(false, more)
            val release = requireNotNull(api.latest(refs.single()))
            assertEquals("Stable", release.notes)
            assertEquals("https://example.test/app.apk", release.assets.single().url)
        } finally { client.close() }
    }

    @Test
    fun rateLimitIsReportedInsteadOfReturningEmptyResults() = runBlocking {
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.Forbidden, headersOf("X-RateLimit-Remaining", "0")) })
        try {
            assertFailsWith<MarketException> { ReleaseApi(client).search(AppSource.GITHUB, "example", 0) }
            Unit
        } finally { client.close() }
    }

    @Test
    fun githubRequestsCarryBearerTokenButGitlabRequestsDoNot() = runBlocking {
        val seen = mutableListOf<Pair<String, String?>>()
        val client = HttpClient(MockEngine { request ->
            seen += request.url.encodedPath to request.headers[HttpHeaders.Authorization]
            val body = when (request.url.encodedPath) {
                "/search/repositories" -> """{"items":[],"total_count":0}"""
                "/api/v4/projects" -> "[]"
                else -> error("Unexpected URL ${request.url}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val holder = GithubTokenHolder().apply { token = "ghp_secret" }
            val api = ReleaseApi(client, holder)
            api.search(AppSource.GITHUB, "example", 0)
            api.search(AppSource.GITLAB, "example", 0)
            assertEquals("Bearer ghp_secret", seen[0].second)
            assertEquals(null, seen[1].second)
        } finally { client.close() }
    }

    @Test
    fun expiredTokenAsksUserToSignInAgain() = runBlocking {
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.Unauthorized) })
        try {
            val holder = GithubTokenHolder().apply { token = "ghp_stale" }
            val error = assertFailsWith<MarketException> { ReleaseApi(client, holder).search(AppSource.GITHUB, "example", 0) }
            assertEquals("GitHub 令牌已失效，请在设置中重新登录", error.message)
        } finally { client.close() }
    }

    @Test
    fun anonymousRateLimitSuggestsSigningIn() = runBlocking {
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.Forbidden, headersOf("X-RateLimit-Remaining", "0")) })
        try {
            val error = assertFailsWith<MarketException> { ReleaseApi(client).search(AppSource.GITHUB, "example", 0) }
            assertEquals("来源 API 请求次数已达上限，请在设置中登录 GitHub 以提升额度", error.message)
        } finally { client.close() }
    }

    @Test
    fun viewerLoginReturnsLoginName() = runBlocking {
        val client = HttpClient(MockEngine { request ->
            assertEquals("/user", request.url.encodedPath)
            assertEquals("Bearer ghp_ok", request.headers[HttpHeaders.Authorization])
            respond("""{"login":"octocat"}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            assertEquals("octocat", ReleaseApi(client).viewerLogin("ghp_ok"))
        } finally { client.close() }
    }

    @Test
    fun viewerLoginRejectsInvalidToken() = runBlocking {
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.Unauthorized) })
        try {
            val error = assertFailsWith<MarketException> { ReleaseApi(client).viewerLogin("bad") }
            assertEquals("GitHub 令牌无效或已过期", error.message)
        } finally { client.close() }
    }

    @Test
    fun rateLimitReadsCoreQuota() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(
                """{"resources":{"core":{"limit":5000,"remaining":4321}}}""",
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
        try {
            assertEquals(4321 to 5000, ReleaseApi(client).rateLimit("ghp_ok"))
        } finally { client.close() }
    }
}
