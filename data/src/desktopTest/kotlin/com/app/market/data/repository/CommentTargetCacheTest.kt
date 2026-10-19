package com.app.market.data.repository

import com.app.market.domain.exception.CommentSourceResolutionException
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class CommentTargetCacheTest {
    @Test fun mappingIsSourceSpecificExpiresAndCanBeInvalidatedAfterFailure() = runBlocking {
        var time = 0L
        var calls = 0
        val cache = CommentTargetCache(ttlMillis = 100, now = { time })
        suspend fun resolve(source: AppSource) = cache.resolve(source, "com.example.app") { calls++; app(source) }
        resolve(AppSource.XIAOMI)
        resolve(AppSource.XIAOMI)
        resolve(AppSource.KUAIBAO)
        assertEquals(2, calls)
        cache.invalidate(AppSource.XIAOMI, "com.example.app")
        resolve(AppSource.XIAOMI)
        assertEquals(3, calls)
        time = 101
        resolve(AppSource.KUAIBAO)
        assertEquals(4, calls)
    }

    @Test fun differentPackageAndUnverifiedSourceAreNeverCached() = runBlocking {
        val cache = CommentTargetCache()
        assertFailsWith<CommentSourceResolutionException> {
            cache.resolve(AppSource.XIAOMI, "com.other.app") { app(AppSource.XIAOMI) }
        }
        assertFailsWith<CommentSourceResolutionException> {
            cache.resolve(AppSource.XIAOMI, "com.example.app") { app(AppSource.KUAIBAO) }
        }
        assertEquals(AppSource.XIAOMI, cache.resolve(AppSource.XIAOMI, "com.example.app") { app(AppSource.XIAOMI) }.source)
    }

    private fun app(source: AppSource) = MarketAppInfo(1, "com.example.app", "Example", "", "1", 1, "", 0, ratingScore = 0.0, source = source)
}
