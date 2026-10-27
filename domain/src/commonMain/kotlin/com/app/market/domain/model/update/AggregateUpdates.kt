package com.app.market.domain.model.update

import com.app.market.domain.exception.InstalledPackagesUnavailableException
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.repository.MarketSourceRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

data class AggregateUpdates(
    val updates: List<MarketAppInfo>,
    val failedSources: Set<AppSource> = emptySet(),
    val completed: Int = 0,
    val total: Int = 0,
    val sourceResults: Map<AppSource, List<MarketAppInfo>> = emptyMap(),
    val failures: Map<AppSource, SourceCheckFailure> = emptyMap(),
    /** 正在请求（已拿到并发槽位、尚未返回）的来源，用于界面显示「正在检查 X」。 */
    val activeSources: Set<AppSource> = emptySet(),
)

enum class SourceCheckFailure { TIMEOUT, REQUEST_FAILED }

class UpdateSourceTimeoutException(val source: AppSource, cause: Throwable) : RuntimeException("Update source timed out: ${source.token}", cause)
class DisabledUpdateSourceException(val source: AppSource) : RuntimeException("Pinned update source is disabled: ${source.token}")

val UpdateSources: List<AppSource> = AppSource.entries.filter { it.capabilities.supportsUpdates }

/** 来源间限流，单源错误独立报告；每次发射都保留真正提供安装包的来源。 */
fun MarketSourceRepository.aggregateUpdatesFlow(
    sources: List<AppSource> = UpdateSources,
    policy: UpdateSelectionPolicy = UpdateSelectionPolicy(),
    initialResults: Map<AppSource, List<MarketAppInfo>> = emptyMap(),
    timeoutMillis: Long = 90_000,
): Flow<AggregateUpdates> = channelFlow {
    val selected = sources.distinct()
    val slots = Semaphore(3)
    val lock = Mutex()
    val latest = initialResults.toMutableMap()
    val failures = mutableMapOf<AppSource, SourceCheckFailure>()
    // 已拿到并发槽位、仍在请求中的来源；界面据此显示「正在检查 X」。
    val active = mutableSetOf<AppSource>()
    var completed = 0
    suspend fun publish() {
        send(AggregateUpdates(selectLatestUpdates(latest.values.flatten(), policy), failures.keys.toSet(), completed,
            selected.size, latest.toMap(), failures.toMap(), active.toSet()))
    }
    publish()
    selected.forEach { source ->
        launch {
            slots.withPermit {
                lock.withLock { active += source; publish() }
                try {
                    withTimeout(timeoutMillis) {
                        checkUpdatesFlow(source).collect { apps ->
                            lock.withLock { latest[source] = apps; publish() }
                        }
                    }
                } catch (_: TimeoutCancellationException) {
                    lock.withLock { failures[source] = SourceCheckFailure.TIMEOUT }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: InstalledPackagesUnavailableException) {
                    throw error
                } catch (_: Exception) {
                    lock.withLock { failures[source] = SourceCheckFailure.REQUEST_FAILED }
                }
                lock.withLock { active -= source; completed++; publish() }
            }
        }
    }
}

/** 手动查询同样尊重聚合选项；没有结果且有源失败时，不将不完整检查误报为无更新。 */
suspend fun MarketSourceRepository.aggregateManualUpdate(
    request: ManualUpdateRequest,
    sources: List<AppSource> = UpdateSources,
    policy: UpdateSelectionPolicy = UpdateSelectionPolicy(),
    timeoutMillis: Long = 90_000,
): ManualUpdateResult = coroutineScope {
    val slots = Semaphore(3)
    val selected = policy.preferredSources[request.packageName]?.let { listOf(it).filter { it in sources } } ?: sources
    if (selected.isEmpty()) policy.preferredSources[request.packageName]?.let { throw DisabledUpdateSourceException(it) }
    val results = selected.distinct().map { source -> async {
        slots.withPermit {
            try { Result.success(withTimeout(timeoutMillis) { checkManualUpdate(source, request) }) }
            catch (error: TimeoutCancellationException) { Result.failure(UpdateSourceTimeoutException(source, error)) }
            catch (error: CancellationException) { throw error }
            catch (error: InstalledPackagesUnavailableException) { throw error }
            catch (error: Exception) { Result.failure(error) }
        }
    } }.map { it.await() }
    val winner = selectLatestUpdates(results.mapNotNull { it.getOrNull()?.app }.filter {
        it.packageName == request.packageName && it.versionCode > request.versionCode
    }, policy).firstOrNull()
    if (winner != null) return@coroutineScope ManualUpdateResult(ManualUpdateStatus.UPDATE_AVAILABLE, winner)
    results.firstOrNull { it.isFailure }?.exceptionOrNull()?.let { throw it }
    ManualUpdateResult(if (results.any { it.getOrNull()?.status == ManualUpdateStatus.RECOGNIZED_NO_UPDATE })
        ManualUpdateStatus.RECOGNIZED_NO_UPDATE else ManualUpdateStatus.NOT_FOUND)
}
