package com.app.market.data.download

import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPhase
import com.app.market.domain.model.download.DownloadState
import com.app.market.domain.model.download.DownloadTaskKey
import com.app.market.domain.model.download.taskKey
import com.app.market.domain.model.install.DeltaFallback
import com.app.market.domain.model.install.InstallUserAction
import com.app.market.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MeteredDownloadRepositoryTest {
    @Test
    fun unmeteredNetworkStartsWithoutConfirmation() {
        val target = RecordingTarget()
        val repository = MeteredDownloadRepository(target, NetworkMetering { false })
        repository.start(meta("one"), false)
        assertEquals(listOf(meta("one") to false), target.started)
        assertTrue(repository.pendingDownloads.value.isEmpty())
    }

    @Test
    fun meteredBatchWaitsAndConfirmsEachTaskOnlyOnceWithOriginalInstallMode() {
        val target = RecordingTarget()
        val repository = MeteredDownloadRepository(target, NetworkMetering { true })
        repository.start(meta("one"), true)
        repository.start(meta("two"), false)
        repository.start(meta("one"), true)
        assertTrue(target.started.isEmpty())
        assertEquals(2, repository.pendingDownloads.value.size)
        repository.confirmDownloads()
        repository.confirmDownloads()
        assertEquals(setOf(meta("one") to true, meta("two") to false), target.started.toSet())
        assertEquals(2, target.started.size)
        assertTrue(repository.pendingDownloads.value.isEmpty())
        repository.start(meta("three"), true)
        assertEquals(2, target.started.size)
        assertEquals("three", repository.pendingDownloads.value.single().meta.packageName)
    }

    @Test
    fun dismissCancelAndClearNeverStartPendingDownloads() {
        val target = RecordingTarget()
        val repository = MeteredDownloadRepository(target, NetworkMetering { true })
        repository.start(meta("one"), true)
        repository.start(meta("one").copy(versionCode = 2), true)
        repository.start(meta("two"), false)
        repository.cancel("one", 1)
        assertEquals(listOf(2L), repository.pendingDownloads.value.filter { it.meta.packageName == "one" }.map { it.meta.versionCode })
        repository.clear("two")
        repository.dismissDownloads()
        repository.confirmDownloads()
        assertTrue(target.started.isEmpty())
        assertTrue(repository.pendingDownloads.value.isEmpty())
    }

    @Test
    fun networkIsCheckedAgainAndActiveTasksDoNotPrompt() {
        val target = RecordingTarget()
        var metered = true
        val repository = MeteredDownloadRepository(target, NetworkMetering { metered })
        repository.start(meta("one"), true)
        metered = false
        repository.start(meta("one"), true)
        assertTrue(repository.pendingDownloads.value.isEmpty())
        assertEquals(1, target.started.size)
        metered = true
        target.taskStates.value = mapOf(meta("one").taskKey to DownloadState(1, "one", "one", 0, DownloadPhase.DOWNLOADING))
        repository.start(meta("one"), true)
        assertTrue(repository.pendingDownloads.value.isEmpty())
        assertEquals(1, target.started.size)
    }

    private fun meta(pkg: String) = DownloadMeta(1, pkg, pkg, "1", 1, "https://example.test/$pkg.apk", 1024)
}

private class RecordingTarget : DownloadRepository {
    override val states = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    override val taskStates = MutableStateFlow<Map<DownloadTaskKey, DownloadState>>(emptyMap())
    override val installedPackages = MutableSharedFlow<String>()
    override val deltaFallbacks = MutableSharedFlow<DeltaFallback>()
    override val pendingUserAction = MutableStateFlow<InstallUserAction?>(null)
    val started = mutableListOf<Pair<DownloadMeta, Boolean>>()
    override fun start(meta: DownloadMeta, installAfterDownload: Boolean) { started += meta to installAfterDownload }
    override fun install(packageName: String) = Unit
    override fun cancel(packageName: String) = Unit
    override fun cancel(packageName: String, versionCode: Long) = Unit
    override fun clear(packageName: String) = Unit
    override fun consumePendingUserAction() = Unit
}
