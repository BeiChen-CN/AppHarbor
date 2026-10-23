package com.app.market.data.repository

import com.app.market.data.remote.lsposed.LsposedApi
import com.app.market.data.remote.releases.JvmReleaseApkInspector
import com.app.market.domain.repository.InstalledPackagesRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Opt-in live check: APP_MARKET_LIVE_LSPOSED=1 ./gradlew.bat :data:desktopTest --tests ... */
class LsposedLiveTest {
    @Test
    fun fetchChromeXtModuleFromRepository() = runBlocking {
        assumeTrue(System.getenv("APP_MARKET_LIVE_LSPOSED") == "1")
        val client = HttpClient(CIO)
        try {
            val repository = LsposedRepository(
                LsposedApi(client),
                JvmReleaseApkInspector(client),
                InstalledPackagesRepository { emptyList() },
            )
            val found = repository.search("ChromeXt", 0).items.firstOrNull {
                it.packageName == "org.matrix.chromext"
            } ?: error("ChromeXt missing from LSPosed search")
            val detail = repository.appDetail(found.packageName)
            val meta = repository.downloadMeta(detail.app)
            assertEquals("org.matrix.chromext", detail.app.packageName)
            assertTrue(detail.app.versionCode > 0L)
            assertTrue(meta.url.startsWith("https://github.com/Xposed-Modules-Repo/"))
            println("LSPosed: ${detail.app.packageName} ${detail.app.versionName} (${detail.app.versionCode}), ${meta.size} bytes")
        } finally {
            client.close()
        }
    }
}
