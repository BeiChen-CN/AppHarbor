package com.app.market.data.repository

import com.app.market.data.local.*
import com.app.market.di.dataModules
import com.app.market.domain.repository.*
import io.ktor.client.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assume.assumeTrue
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertTrue

class DiscoveryLiveTest {
    @Test
    fun publicRecommendationFeedsResolveThroughSourceGraph() = runBlocking {
        assumeTrue(System.getenv("APP_MARKET_LIVE_DISCOVERY") == "1")
        val graph = koinApplication { modules(dataModules + module {
            single<PreferencesDataSource> { DiscoveryLivePreferences() }
        }) }
        try {
            val repository = graph.koin.get<DiscoveryRepository>()
            for (source in RecommendationSources) {
                val feed = repository.feed(source, 0)
                assertTrue(feed.items.isNotEmpty(), "Empty ${source.token} feed")
                assertTrue(feed.items.flatMap { it.apps }.all { it.source == source }, "Wrong app source for ${source.token}")
                println("${source.token}: ${feed.items.size} recommendation cards")
            }
        } finally {
            graph.koin.get<CoroutineScope>().cancel()
            graph.koin.get<HttpClient>().close()
            graph.close()
        }
    }
}

private class DiscoveryLivePreferences : PreferencesDataSource {
    private val strings = mutableMapOf<StringPreferenceKey, MutableStateFlow<String?>>()
    private val booleans = mutableMapOf<BooleanPreferenceKey, MutableStateFlow<Boolean>>()
    override fun observe(key: StringPreferenceKey) = strings.getOrPut(key) { MutableStateFlow(null) }
    override fun observe(key: BooleanPreferenceKey) = booleans.getOrPut(key) { MutableStateFlow(key.default) }
    override suspend fun update(namespace: String, changes: PreferenceChanges) {
        changes.strings.forEach { (key, value) -> observe(key).value = value }
        changes.booleans.forEach { (key, value) -> observe(key).value = value ?: key.default }
    }
}
