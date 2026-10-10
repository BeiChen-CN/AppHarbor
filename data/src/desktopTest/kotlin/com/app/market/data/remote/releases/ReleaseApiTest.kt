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
}
