package com.app.market.data.repository

import com.app.market.domain.exception.CommentSourceResolutionException
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/** 只缓存已核对包名的跨源映射；不缓存评论内容或下载地址。 */
internal class CommentTargetCache(
    private val ttlMillis: Long = 15 * 60_000,
    private val capacity: Int = 128,
    private val now: () -> Long = monotonicMillis(),
) {
    private data class Entry(val app: MarketAppInfo, val at: Long)
    private val entries = linkedMapOf<Pair<AppSource, String>, Entry>()
    private val mutex = Mutex()

    suspend fun resolve(source: AppSource, packageName: String, fetch: suspend () -> MarketAppInfo): MarketAppInfo {
        if (packageName.isBlank()) throw CommentSourceResolutionException()
        val key = source to packageName
        mutex.withLock { entries[key]?.takeIf { now() - it.at in 0 until ttlMillis }?.app }?.let { return it }
        val app = fetch()
        if (app.packageName != packageName || app.source != source) throw CommentSourceResolutionException()
        mutex.withLock {
            entries.remove(key)
            entries[key] = Entry(app, now())
            while (entries.size > capacity) entries.remove(entries.keys.first())
        }
        return app
    }

    suspend fun invalidate(source: AppSource, packageName: String) {
        mutex.withLock { entries.remove(source to packageName) }
    }
}

private fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
