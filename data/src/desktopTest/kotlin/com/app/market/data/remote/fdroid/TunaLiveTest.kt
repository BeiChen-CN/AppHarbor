package com.app.market.data.remote.fdroid

import com.app.market.di.dataModules
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.repository.MarketSourceRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.koin.core.qualifier.named
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 显式开启后验证实际镜像索引、路由及 APK 可达性，不下载完整安装包。 */
class TunaLiveTest {
    @Test
    fun mirrorResolvesThroughSourceGraph() = runBlocking {
        assumeTrue(System.getenv("APP_MARKET_LIVE_TUNA") == "1")
        val root = Files.createTempDirectory("trove-tuna-live-").toFile()
        val graph = koinApplication {
            modules(dataModules + module {
                single<FdroidIndexCache>(named("tuna")) { JvmFdroidIndexCache(root, get(), TunaApiConfig) }
            })
        }
        val client = graph.koin.get<HttpClient>()
        try {
            val sources = graph.koin.get<MarketSourceRepository>()
            val app = sources.search(AppSource.FDROID_TUNA, "org.fdroid.fdroid", 0).items.first()
            assertEquals("org.fdroid.fdroid", app.packageName)
            val detail = sources.appDetail(AppSource.FDROID_TUNA, app.appId, app.packageName)
            val meta = sources.downloadMeta(AppSource.FDROID_TUNA, detail.app)
            assertEquals(AppSource.FDROID_TUNA, meta.source)
            assertTrue(meta.url.startsWith("${TunaApiConfig.repoBase}/"))
            assertTrue(meta.parts.single().hash.matches(Regex("[a-fA-F0-9]{64}")))
            assertTrue(client.head(meta.url).status.isSuccess())
            println("tuna: ${app.packageName} ${app.versionName} (${app.versionCode}), ${meta.size} bytes")
        } finally {
            graph.koin.get<CoroutineScope>().cancel()
            graph.close()
            client.close()
            root.deleteRecursively()
        }
    }
}
