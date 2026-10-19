package com.app.market.domain.model.update

import com.app.market.domain.exception.InstalledPackagesUnavailableException
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.repository.MarketSourceRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class AggregateUpdatesTest {
    @Test
    fun comparesPublicVersionsBeforeSourceSpecificBuildCountersAndPreservesWinner() {
        val old = app("1.6.17", 23563, AppSource.IZZYONDROID)
        val latest = app("1.6.18", 2360, AppSource.GITHUB).copy(appId = 77, apkSize = 200, changeLog = "New")
        assertEquals(listOf(latest), selectLatestUpdates(listOf(old, latest)))
    }

    @Test
    fun excludesOlderPublicVersionAndUsesCodeForUnparseableVersionNames() {
        val old = app("1.6.17", 23563, AppSource.IZZYONDROID).copy(installedVersionName = "1.6.18")
        val newer = app("build future", 400, AppSource.XIAOMI)
        assertEquals(listOf(newer), selectLatestUpdates(listOf(old, newer)))
        assertTrue(selectLatestUpdates(listOf(newer.copy(installedVersionCode = 400))).isEmpty())
    }

    @Test
    fun samePublicVersionUsesBuildCodeAndNeverCombinesSourceOwnedMetadata() {
        val a = app("2.0", 30, AppSource.TENCENT)
        val b = app("2.0.0", 40, AppSource.COOLAPK).copy(appId = 123, deltaSize = 44)
        assertEquals(listOf(b), selectLatestUpdates(listOf(a, b)))
    }

    @Test
    fun partialFailureDoesNotHideSuccessfulResults() = runBlocking {
        val expected = app("2.0", 30, AppSource.TENCENT)
        val repository = fake { source -> if (source == AppSource.TENCENT) flowOf(listOf(expected)) else flow { error("offline") } }
        val results = repository.aggregateUpdatesFlow(listOf(AppSource.TENCENT, AppSource.COOLAPK)).toList()
        assertEquals(listOf(expected), results.last().updates)
        assertEquals(setOf(AppSource.COOLAPK), results.last().failedSources)
        assertEquals(2, results.last().completed)
        assertEquals(2, results.last().total)
    }

    @Test
    fun limitsConcurrentProvidersAndPropagatesAppListPermissionFailure() = runBlocking {
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val repository = fake { flow {
            val count = active.incrementAndGet()
            peak.updateAndGet { maxOf(it, count) }
            try { delay(20); emit(emptyList()) } finally { active.decrementAndGet() }
        } }
        val selected = AppSource.entries.filter { it.capabilities.supportsUpdates }
        assertEquals(selected.size, repository.aggregateUpdatesFlow(selected).last().completed)
        assertTrue(peak.get() in 2..3)
        val denied = fake { flow { throw InstalledPackagesUnavailableException() } }
        assertFailsWith<InstalledPackagesUnavailableException> { denied.aggregateUpdatesFlow(listOf(AppSource.XIAOMI)).last() }
        Unit
    }

    @Test
    fun timeoutIsReportedAndRetryKeepsSuccessfulSources() = runBlocking {
        val expected = app("2.0", 30, AppSource.TENCENT)
        val repository = fake { flow { delay(1000); emit(emptyList()) } }
        val result = repository.aggregateUpdatesFlow(listOf(AppSource.COOLAPK, AppSource.COOLAPK),
            initialResults = mapOf(AppSource.TENCENT to listOf(expected)), timeoutMillis = 10).last()
        assertEquals(listOf(expected), result.updates)
        assertEquals(1, result.total)
        assertEquals(SourceCheckFailure.TIMEOUT, result.failures[AppSource.COOLAPK])
    }

    @Test
    fun manualTimeoutIsAFailureInsteadOfCancellingCallerAndDisabledPinIsReported() = runBlocking {
        val repository = object : MarketSourceRepository by fake({ emptyFlow() }) {
            override suspend fun checkManualUpdate(source: AppSource, request: ManualUpdateRequest): ManualUpdateResult {
                delay(1000)
                return ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
            }
        }
        val request = ManualUpdateRequest("com.example.app", 1)
        assertFailsWith<UpdateSourceTimeoutException> {
            repository.aggregateManualUpdate(request, listOf(AppSource.XIAOMI), timeoutMillis = 1)
        }
        assertTrue(currentCoroutineContext().isActive)
        assertFailsWith<DisabledUpdateSourceException> {
            repository.aggregateManualUpdate(request, listOf(AppSource.XIAOMI),
                UpdateSelectionPolicy(preferredSources = mapOf(request.packageName to AppSource.COOLAPK)))
        }
        Unit
    }

    @Test
    fun manualAggregateSelectsNewestPublicVersionDespiteOneProviderFailure() = runBlocking {
        val winner = app("2.0", 20, AppSource.GITHUB)
        val repository = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MarketSourceRepository::class.java)) { _, method, args ->
            check(method.name == "checkManualUpdate")
            when (args!![0] as AppSource) {
                AppSource.XIAOMI -> error("offline")
                AppSource.TENCENT -> ManualUpdateResult(ManualUpdateStatus.UPDATE_AVAILABLE, app("1.9", 100000, AppSource.TENCENT))
                AppSource.GITHUB -> ManualUpdateResult(ManualUpdateStatus.UPDATE_AVAILABLE, winner)
                else -> ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
            }
        } as MarketSourceRepository
        assertEquals(winner, repository.aggregateManualUpdate(ManualUpdateRequest("com.example.app", 1)).app)
    }

    private fun app(version: String, code: Long, source: AppSource) = MarketAppInfo(1, "com.example.app", "Example", "",
        version, code, "", 100, ratingScore = 0.0, installedVersionCode = 1, source = source)

    private fun fake(provider: (AppSource) -> Flow<List<MarketAppInfo>>): MarketSourceRepository =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(MarketSourceRepository::class.java)) { _, method, args ->
            check(method.name == "checkUpdatesFlow") { "Unexpected ${method.name}" }
            provider(args!![0] as AppSource)
        } as MarketSourceRepository
}
