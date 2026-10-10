package com.app.market.data.remote.releases

import com.app.market.data.local.BooleanPreferenceKey
import com.app.market.data.local.PreferenceChanges
import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.StringPreferenceKey
import com.app.market.data.remote.fdroid.FdroidIndexCache
import com.app.market.data.remote.fdroid.IzzyApiConfig
import com.app.market.data.remote.fdroid.JvmFdroidIndexCache
import com.app.market.di.dataModules
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.repository.MarketSourceRepository
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Explicit opt-in, isolated preferences/cache. GitHub/GitLab fetch only manifest ranges. */
class NewSourcesLiveTest {
    @Test
    fun publicRepositoriesAndIzzyResolveThroughActualSourceGraph() = runBlocking {
        assumeTrue(System.getenv("APP_MARKET_LIVE_NEW_SOURCES") == "1")
        val root = Files.createTempDirectory("appharbor-izzy-live-").toFile()
        val graph = koinApplication {
            modules(dataModules + module {
                single<PreferencesDataSource> { LiveSourcePreferences() }
                single<FdroidIndexCache>(named("izzy")) { JvmFdroidIndexCache(root, get(), IzzyApiConfig) }
            })
        }
        val client = graph.koin.get<HttpClient>()
        try {
            val repository = graph.koin.get<MarketSourceRepository>()
            for ((source, link) in listOf(
                AppSource.GITHUB to "https://github.com/ImranR98/Obtainium",
                AppSource.GITLAB to "https://gitlab.com/fdroid/fdroidclient",
            )) {
                val app = repository.search(source, link, 0).items.single()
                assertEquals(source, app.source)
                assertTrue(app.versionCode > 0L)
                assertTrue(app.packageName.contains('.'))
                val detail = repository.appDetail(source, app.appId, app.packageName)
                assertEquals(app.versionCode, detail.app.versionCode)
                val meta = repository.downloadMeta(source, app)
                assertTrue(meta.url.startsWith("https://"))
                assertTrue(meta.size > 0L)
                println("${source.token}: ${app.packageName} ${app.versionName} (${app.versionCode}), ${meta.size} bytes")
            }
            val izzyApp = repository.search(AppSource.IZZYONDROID, "Obtainium", 0).items.first()
            val detail = repository.appDetail(AppSource.IZZYONDROID, izzyApp.appId, izzyApp.packageName)
            val meta = repository.downloadMeta(AppSource.IZZYONDROID, detail.app)
            assertEquals(AppSource.IZZYONDROID, meta.source)
            assertTrue(meta.parts.single().hash.matches(Regex("[a-fA-F0-9]{64}")))
            assertTrue(meta.url.startsWith(IzzyApiConfig.repoBase))
            println("izzyondroid: ${detail.app.packageName} ${detail.app.versionName} (${detail.app.versionCode})")
        } finally {
            graph.koin.get<CoroutineScope>().cancel()
            graph.close()
            client.close()
            root.deleteRecursively()
        }
    }
}

private class LiveSourcePreferences : PreferencesDataSource {
    private val strings = mutableMapOf<StringPreferenceKey, MutableStateFlow<String?>>()
    override fun observe(key: StringPreferenceKey): Flow<String?> = strings.getOrPut(key) { MutableStateFlow(null) }
    override fun observe(key: BooleanPreferenceKey): Flow<Boolean> = MutableStateFlow(key.default)
    override suspend fun update(namespace: String, changes: PreferenceChanges) {
        changes.strings.forEach { (key, value) -> strings.getOrPut(key) { MutableStateFlow(null) }.value = value }
    }
}
