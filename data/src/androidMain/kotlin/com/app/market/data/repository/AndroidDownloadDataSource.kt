package com.app.market.data.repository

import android.content.pm.PackageInstaller
import com.app.market.data.download.PlatformDownloadDataSource
import com.app.market.data.install.DeltaFallbackBus
import com.app.market.data.install.RemotePackageDownloader
import com.app.market.data.install.platform.InstallResultHandler
import com.app.market.data.install.platform.InstallTaskLauncher
import com.app.market.data.install.platform.InstallTaskProcessor
import com.app.market.data.install.platform.PackageChangeHandler
import com.app.market.data.install.platform.SavedPackageInstallLauncher
import com.app.market.data.install.storage.ArtifactStagingStore
import com.app.market.data.install.storage.MediaStorePackageStore
import com.app.market.data.install.task.InstallTaskRecord
import com.app.market.data.install.task.InstallTaskStore
import com.app.market.data.platform.debugLog
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPart
import com.app.market.domain.model.download.DownloadPhase
import com.app.market.domain.model.download.DownloadState
import com.app.market.domain.model.download.DownloadTaskKey
import com.app.market.domain.model.download.taskKey
import com.app.market.domain.model.install.DeltaFallback
import com.app.market.domain.model.install.InstallArtifact
import com.app.market.domain.model.install.InstallEvent
import com.app.market.domain.model.install.InstallFailureCode
import com.app.market.domain.model.install.InstallCleanupPolicy
import com.app.market.domain.model.install.InstallRequest
import com.app.market.domain.model.install.InstallSource
import com.app.market.domain.model.install.InstallUserAction
import com.app.market.domain.model.installer.InstallerMode
import com.app.market.domain.repository.InstallRepository
import com.app.market.domain.repository.InstallerPreferencesRepository
import com.app.market.domain.repository.PackageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal class AndroidDownloadDataSource(
    private val tasks: InstallTaskStore,
    private val installer: InstallRepository,
    private val downloader: RemotePackageDownloader,
    private val packageStore: MediaStorePackageStore,
    private val staging: ArtifactStagingStore,
    private val preferences: InstallerPreferencesRepository,
    private val packages: PackageRepository,
    private val launcher: InstallTaskLauncher,
    private val packageChanges: PackageChangeHandler,
    private val deltaFallbackBus: DeltaFallbackBus,
    private val scope: CoroutineScope,
) : PlatformDownloadDataSource, InstallTaskProcessor, InstallResultHandler, SavedPackageInstallLauncher {
    private val mutableStates = MutableStateFlow<Map<String, DownloadState>>(emptyMap())
    override val states: StateFlow<Map<String, DownloadState>> = mutableStates.asStateFlow()
    private val mutableTaskStates = MutableStateFlow<Map<DownloadTaskKey, DownloadState>>(emptyMap())
    override val taskStates: StateFlow<Map<DownloadTaskKey, DownloadState>> = mutableTaskStates.asStateFlow()

    private val mutableInstalledPackages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val installedPackages: SharedFlow<String> = mutableInstalledPackages.asSharedFlow()
    override val deltaFallbacks: SharedFlow<DeltaFallback> = deltaFallbackBus.events

    private val mutablePendingUserAction = MutableStateFlow<InstallUserAction?>(null)
    override val pendingUserAction: StateFlow<InstallUserAction?> = mutablePendingUserAction.asStateFlow()

    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val lifecycleMutex = Mutex()
    private val restored = CompletableDeferred<Unit>()
    private val downloadSlots = Semaphore(MAX_CONCURRENT_DOWNLOADS)

    init {
        scope.launch {
            try {
                restoreTasks()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // 恢复失败只降级为空任务列表，绝不能阻断后续下载/安装能力
                debugLog("InstallTask") { "Task restore failed: ${error.stackTraceToString()}" }
            } finally {
                restored.complete(Unit)
            }
        }
    }

    override fun start(meta: DownloadMeta, installAfterDownload: Boolean) {
        scope.launch {
            restored.await()
            val saveToDownloads = !installAfterDownload ||
                    runCatching { preferences.saveToDownloads() }.getOrDefault(false)
            val deleteAfterUpdate = updateCleanupEnabled(meta.packageName, meta.versionCode, installAfterDownload)
            val deltaUpdateEnabled = runCatching {
                preferences.deltaUpdateEnabled()
            }.getOrDefault(false)
            val record = lifecycleMutex.withLock {
                val taskKey = meta.taskKey
                val existing = tasks.all().filter { it.taskKey == taskKey }
                if (existing.any { it.phase in ActivePhases }) return@withLock null
                // 暂停/失败的同版本任务保留原 id，复用暂存数据实现断点续传；其余记录清掉
                val resumable = existing.lastOrNull {
                    it.phase in ResumablePhases
                }
                existing.forEach { stale ->
                    if (stale.id == resumable?.id) return@forEach
                    tasks.remove(stale.id)
                    staging.clear(stale.id)
                    removePublishedState(stale)
                }

                val id = resumable?.id ?: UUID.randomUUID().toString()
                InstallTaskRecord(
                    id = id,
                    appId = meta.appId,
                    request = meta.toInstallRequest(id, saveToDownloads, deltaUpdateEnabled, deleteAfterUpdate),
                    installAfterDownload = installAfterDownload,
                    progress = resumable?.progress,
                ).also {
                    tasks.put(it)
                    publishState(it)
                }
            } ?: return@launch
            launchTask(record)
        }
    }

    override fun install(packageName: String) {
        scope.launch {
            restored.await()
            val current = mutableStates.value[packageName] ?: return@launch
            val savedId = current.savedPackageId ?: return@launch
            runCatching { enqueueSavedPackage(savedId, current.appId, propagateLaunchFailure = false) }
                .onFailure { error ->
                    val message = error.message ?: "Saved package is unavailable"
                    publishFailureAction(InstallFailureCode.STORAGE, message)
                    mutableStates.update { states ->
                        states + (packageName to current.copy(
                            phase = DownloadPhase.FAILED,
                            savedPackageId = null,
                            errorMessage = message,
                        ))
                    }
                    runCatching {
                        tasks.all()
                            .filter { it.request.packageName == packageName }
                            .forEach { record ->
                                tasks.update(record.id) {
                                    it.copy(
                                        phase = DownloadPhase.FAILED,
                                        errorMessage = message,
                                    )
                                }
                            }
                    }.onFailure { persistenceError ->
                        debugLog("InstallTask") { "Unable to persist saved package failure: $persistenceError" }
                    }
                }
        }
    }

    override suspend fun installSavedPackage(savedPackageId: String) {
        restored.await()
        try {
            enqueueSavedPackage(savedPackageId, appId = null, propagateLaunchFailure = true)
        } catch (error: Throwable) {
            publishFailureAction(
                InstallFailureCode.STORAGE,
                error.message ?: "Saved package is unavailable",
            )
            throw error
        }
    }

    override fun cancel(packageName: String) {
        scope.launch {
            restored.await()
            val record = tasks.all().lastOrNull {
                it.request.packageName == packageName && it.phase !in setOf(DownloadPhase.DOWNLOADED, DownloadPhase.FAILED)
            } ?: return@launch
            pauseTask(record.id)
        }
    }

    override fun cancel(packageName: String, versionCode: Long) {
        scope.launch {
            restored.await()
            val record = tasks.all().lastOrNull {
                it.request.packageName == packageName &&
                        it.request.versionCode == versionCode &&
                        it.phase !in setOf(DownloadPhase.DOWNLOADED, DownloadPhase.FAILED)
            } ?: return@launch
            pauseTask(record.id)
        }
    }

    override fun clear(packageName: String) {
        scope.launch {
            restored.await()
            val records = tasks.all().filter { it.request.packageName == packageName }
            records.forEach { record ->
                activeJobs[record.id]?.cancel()
                installer.cancel(record.id)
                tasks.remove(record.id)
                staging.clear(record.id)
            }
            val savedIds = buildSet {
                mutableStates.value[packageName]?.savedPackageId?.let(::add)
                records.mapNotNullTo(this) { it.savedPackageId }
            }
            savedIds.forEach { packageStore.delete(it) }
            mutableStates.update { it - packageName }
            mutableTaskStates.update { states -> states.filterKeys { it.packageName != packageName } }
        }
    }

    override fun consumePendingUserAction() {
        mutablePendingUserAction.value = null
    }

    override suspend fun process(taskId: String) {
        restored.await()
        val job = currentCoroutineContext().job
        // 暂停后立刻恢复时旧任务可能还在收尾：等它完全退出再接管，避免状态互相覆盖
        while (true) {
            val previous = activeJobs.putIfAbsent(taskId, job) ?: break
            if (previous === job) break
            previous.cancel()
            previous.join()
            activeJobs.remove(taskId, previous)
        }
        val record = tasks.get(taskId) ?: run {
            activeJobs.remove(taskId)
            return
        }
        try {
            // 「全部更新」会一次拉起几十个任务，不限并发会切碎带宽并塞满暂存区
            val needsSlot = record.request.artifacts.any { it.source !is InstallSource.Local }
            if (needsSlot) {
                updateRecord(taskId) { it.copy(phase = DownloadPhase.QUEUED, errorMessage = "") }
                downloadSlots.acquire()
            }
            try {
                updateRecord(taskId) {
                    it.copy(
                        phase = DownloadPhase.DOWNLOADING,
                        errorMessage = "",
                    )
                }
                val events = if (record.installAfterDownload) {
                    installer.install(record.request)
                } else {
                    downloader.download(record.request)
                }
                events.collect { event ->
                    handleEvent(taskId, record, event)
                }
            } finally {
                if (needsSlot) downloadSlots.release()
            }
            confirmInstall(taskId)
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                updateRecord(taskId) { it.copy(phase = DownloadPhase.PAUSED) }
            }
            throw error
        } catch (error: Throwable) {
            val message = error.message ?: "Task failed"
            recordFailure(taskId, InstallFailureCode.UNKNOWN, message) {
                it.copy(
                    phase = DownloadPhase.FAILED,
                    errorMessage = message,
                )
            }
        } finally {
            activeJobs.remove(taskId, job)
        }
    }

    override suspend fun onUnhandledFailure(taskId: String, error: Throwable) {
        if (error is CancellationException) return
        val message = error.message ?: "Installation failed"
        recordFailure(taskId, InstallFailureCode.UNKNOWN, message) {
            it.copy(
                phase = DownloadPhase.FAILED,
                errorMessage = message,
            )
        }
    }

    override fun cancelTask(taskId: String) {
        scope.launch {
            restored.await()
            pauseTask(taskId)
        }
    }

    override suspend fun onPendingUserAction(taskId: String) {
        restored.await()
        updateRecord(taskId) { it.copy(phase = DownloadPhase.AWAITING_USER_ACTION) }
    }

    override suspend fun onResult(taskId: String, status: Int, message: String?) {
        restored.await()
        val record = tasks.get(taskId) ?: return
        if (status == PackageInstaller.STATUS_SUCCESS) {
            // Normally PACKAGE_ADDED/REPLACED wins. This is a safety net for ROMs that suppress
            // package broadcasts: preserve the same cache-first ordering before completing.
            if (packageChanges.onPackageChanged(record.request.packageName, installed = true)) {
                onPackageChanged(record.request.packageName)
            }
            return
        }

        val hasSavedPackage = record.savedPackageId != null
        val failureMessage = message ?: "Installation failed ($status)"
        val shouldShowFailure = status != PackageInstaller.STATUS_FAILURE_ABORTED
        recordFailure(
            taskId = taskId,
            code = if (shouldShowFailure) InstallFailureCode.SESSION else InstallFailureCode.CANCELLED,
            message = failureMessage,
        ) {
            it.copy(
                phase = if (hasSavedPackage) DownloadPhase.DOWNLOADED else DownloadPhase.FAILED,
                errorMessage = failureMessage,
            )
        }
    }

    override suspend fun onPackageChanged(packageName: String) {
        restored.await()
        val installedVersion = runCatching { packages.freshInstalledVersionCode(packageName) }.getOrNull()
        val completed = tasks.all().filter { record ->
            record.request.packageName == packageName &&
                    record.phase in setOf(DownloadPhase.INSTALLING, DownloadPhase.AWAITING_USER_ACTION) &&
                    InstallCleanupPolicy.installationConfirmed(record.request.versionCode, installedVersion)
        }
        completed.forEach { record ->
            deleteCompletedPackage(record)
            tasks.remove(record.id)
            staging.clear(record.id)
            removePublishedState(record)
        }
        // This broadcast is the authoritative signal that PackageManager now sees the package.
        // Emit it even when the install-result callback already removed our task record.
        mutableInstalledPackages.emit(packageName)
    }

    /** commit 后主动确认：STATUS 回调与 PACKAGE_ADDED 广播都可能被 ROM 压制，丢一次就永远卡 INSTALLING。 */
    private suspend fun confirmInstall(taskId: String) {
        val record = tasks.get(taskId) ?: return
        if (record.phase !in PendingResultPhases) return
        val packageName = record.request.packageName
        val expected = record.request.versionCode
        // 特权模式无需用户交互，很快就该有结果；标准模式要留出系统确认弹窗的时间
        val timeout = if (preferences.mode() in PrivilegedModes) {
            PRIVILEGED_CONFIRM_TIMEOUT_MS
        } else {
            STANDARD_CONFIRM_TIMEOUT_MS
        }
        var elapsed = 0L
        while (elapsed < timeout) {
            // 转入 AWAITING_USER_ACTION 后进度由用户掌握，不能按超时判失败
            val current = tasks.get(taskId) ?: return
            if (current.phase != DownloadPhase.INSTALLING) return
            val installed = runCatching { packages.freshInstalledVersionCode(packageName) }.getOrNull()
            if (installed != null && (expected <= 0L || installed >= expected)) {
                onPackageChanged(packageName)
                return
            }
            delay(INSTALL_CONFIRM_INTERVAL_MS)
            elapsed += INSTALL_CONFIRM_INTERVAL_MS
        }
        timeOutInstall(taskId, "安装结果未回传，请重试")
    }

    /** 无回执的 INSTALLING / AWAITING_USER_ACTION 收敛回可操作相位：有本地包退回 DOWNLOADED，否则判失败。 */
    private suspend fun timeOutInstall(taskId: String, message: String) {
        val record = tasks.get(taskId) ?: return
        if (record.phase !in PendingResultPhases) return
        debugLog("InstallTask") { "No install result for ${record.request.packageName}, releasing $taskId" }
        updateRecord(taskId) {
            it.copy(
                phase = if (it.savedPackageId != null) DownloadPhase.DOWNLOADED else DownloadPhase.FAILED,
                externalInstallerPackage = null,
                errorMessage = message,
            )
        }
    }

    private suspend fun handleEvent(taskId: String, original: InstallTaskRecord, event: InstallEvent) {
        when (event) {
            InstallEvent.Preparing -> updateRecord(taskId) { it.copy(phase = DownloadPhase.QUEUED) }
            is InstallEvent.Progress -> {
                val percent = if (event.total > 0L) {
                    ((event.completed * 100L) / event.total).coerceIn(0L, 100L).toInt()
                } else {
                    null
                }
                val current = mutableTaskStates.value[original.taskKey] ?: original.toDownloadState()
                if (current.progress != percent || current.phase != DownloadPhase.DOWNLOADING) {
                    publishTransientState(
                        current.copy(
                            progress = percent,
                            phase = DownloadPhase.DOWNLOADING,
                        )
                    )
                }
            }

            is InstallEvent.Downloaded -> updateRecord(taskId) {
                it.copy(
                    phase = DownloadPhase.DOWNLOADED,
                    progress = 100,
                    savedPackageId = event.savedPackageId,
                )
            }

            is InstallEvent.SessionReady -> updateRecord(taskId) {
                it.copy(
                    phase = DownloadPhase.INSTALLING,
                    progress = 100,
                    sessionId = event.sessionId,
                    savedPackageId = event.savedPackageId ?: it.savedPackageId,
                )
            }

            is InstallEvent.Committed -> Unit
            is InstallEvent.ExternalInstallerPrepared -> updateRecord(taskId) {
                it.copy(
                    phase = DownloadPhase.INSTALLING,
                    progress = 100,
                    savedPackageId = event.savedPackageId,
                    externalInstallerPackage = event.installerPackageName,
                )
            }

            is InstallEvent.ExternalInstallerLaunched -> updateRecord(taskId) {
                it.copy(
                    phase = DownloadPhase.AWAITING_USER_ACTION,
                    progress = 100,
                    savedPackageId = event.savedPackageId,
                    externalInstallerPackage = event.installerPackageName,
                )
            }

            is InstallEvent.Failed -> recordFailure(taskId, event.code, event.message) {
                it.copy(
                    phase = if (it.savedPackageId != null) DownloadPhase.DOWNLOADED else DownloadPhase.FAILED,
                    externalInstallerPackage = null,
                    errorMessage = event.message,
                )
            }
        }
    }

    private suspend fun updateRecord(
        taskId: String,
        transform: (InstallTaskRecord) -> InstallTaskRecord,
    ): InstallTaskRecord? {
        val updated = tasks.update(taskId, transform) ?: return null
        publishState(updated)
        return updated
    }

    private fun publishState(record: InstallTaskRecord) {
        publishTransientState(record.toDownloadState())
    }

    private fun publishTransientState(state: DownloadState) {
        mutableTaskStates.update { states -> states + (state.taskKey to state) }
        mutableStates.update { states ->
            states + (state.packageName to state)
        }
    }

    private fun removePublishedState(record: InstallTaskRecord) {
        val key = record.taskKey
        mutableTaskStates.update { it - key }
        val replacement = mutableTaskStates.value.values.lastOrNull { it.packageName == key.packageName }
        mutableStates.update { states ->
            if (replacement == null) states - key.packageName
            else states + (key.packageName to replacement)
        }
    }

    private suspend fun launchTask(record: InstallTaskRecord, propagateFailure: Boolean = false) {
        try {
            launcher.start(record.id)
        } catch (error: Throwable) {
            val message = error.message ?: "Unable to start installer service"
            recordFailure(record.id, InstallFailureCode.UNKNOWN, message) {
                it.copy(
                    phase = DownloadPhase.FAILED,
                    errorMessage = message,
                )
            }
            if (propagateFailure) throw error
        }
    }

    private suspend fun enqueueSavedPackage(
        savedPackageId: String,
        appId: Long?,
        propagateLaunchFailure: Boolean,
    ) {
        val record = lifecycleMutex.withLock {
            val saved = packageStore.get(savedPackageId) ?: error("Saved package is unavailable")
            val existing = tasks.all().filter { it.request.packageName == saved.packageName }
            check(existing.none { it.phase in ActivePhases }) {
                "An installation for ${saved.displayName} is already active"
            }
            existing.forEach {
                tasks.remove(it.id)
                staging.clear(it.id)
                removePublishedState(it)
            }

            val id = UUID.randomUUID().toString()
            val request = InstallRequest(
                id = id,
                packageName = saved.packageName,
                displayName = saved.displayName,
                versionName = saved.versionName,
                versionCode = saved.versionCode,
                icon = saved.icon,
                artifacts = saved.artifacts.mapIndexed { index, artifact ->
                    InstallArtifact(
                        name = sessionName(index, artifact.fileName, "split"),
                        source = InstallSource.Local(artifact.uri),
                        size = artifact.size,
                    )
                },
                saveToDownloads = if (preferences.mode() == InstallerMode.THIRD_PARTY) {
                    preferences.saveToDownloads()
                } else {
                    false
                },
                sourceSavedPackageId = savedPackageId,
                deleteAfterUpdate = updateCleanupEnabled(saved.packageName, saved.versionCode, installAfterDownload = true),
            )
            InstallTaskRecord(
                id = id,
                appId = appId ?: mutableStates.value[saved.packageName]?.appId ?: 0L,
                request = request,
                installAfterDownload = true,
                savedPackageId = savedPackageId,
            ).also {
                tasks.put(it)
                publishState(it)
            }
        }
        launchTask(record, propagateLaunchFailure)
    }

    private suspend fun pauseTask(taskId: String) {
        activeJobs[taskId]?.cancel()
        installer.cancel(taskId)
        updateRecord(taskId) { record ->
            if (record.phase == DownloadPhase.AWAITING_USER_ACTION && record.savedPackageId != null) {
                record.copy(
                    phase = DownloadPhase.DOWNLOADED,
                    externalInstallerPackage = null,
                    errorMessage = "",
                )
            } else {
                record.copy(phase = DownloadPhase.PAUSED)
            }
        }
    }

    private suspend fun updateCleanupEnabled(packageName: String, targetVersion: Long, installAfterDownload: Boolean): Boolean {
        if (!installAfterDownload) return false
        val enabled = runCatching { preferences.deleteAfterUpdate() }.getOrDefault(false)
        if (!enabled) return false
        val installedVersion = runCatching { packages.freshInstalledVersionCode(packageName) }.getOrNull()
        return InstallCleanupPolicy.deleteAfterUpdate(enabled, installAfterDownload, installedVersion, targetVersion)
    }

    /** Called only after the installed version has confirmed success, including recovery after process death. */
    private suspend fun deleteCompletedPackage(record: InstallTaskRecord) {
        if (!record.request.saveToDownloads || record.request.deleteAfterUpdate) {
            record.savedPackageId?.let { savedId ->
                runCatching { packageStore.delete(savedId) }
                    .onFailure { error -> debugLog("InstallTask") { "Saved package cleanup failed for $savedId: $error" } }
            }
        }
    }

    private suspend fun restoreTasks() {
        val records = tasks.all()
        if (records.isEmpty()) {
            staging.retainOnly(emptyList())
            reclaimSessions(emptyList())
            return
        }
        val installed = runCatching {
            packages.installedVersionCodes(records.map { it.request.packageName })
        }.getOrDefault(emptyMap())
        records.forEach { original ->
            val installedVersion = installed[original.request.packageName]
            if (original.phase in setOf(DownloadPhase.INSTALLING, DownloadPhase.AWAITING_USER_ACTION) &&
                original.request.versionCode > 0L &&
                installedVersion != null && installedVersion >= original.request.versionCode
            ) {
                deleteCompletedPackage(original)
                tasks.remove(original.id)
                return@forEach
            }
            var record = if (original.phase in setOf(DownloadPhase.QUEUED, DownloadPhase.DOWNLOADING)) {
                tasks.update(original.id) { it.copy(phase = DownloadPhase.PAUSED) } ?: original
            } else {
                original
            }
            // 长期无进展说明回执已丢；留着会拦下用户的所有后续操作
            if (record.phase in PendingResultPhases &&
                System.currentTimeMillis() - record.modifiedAt > STALE_INSTALL_AGE_MS
            ) {
                timeOutInstall(record.id, "安装结果未回传，请重试")
                record = tasks.get(record.id) ?: record
            }
            if (record.phase == DownloadPhase.DOWNLOADED &&
                (record.savedPackageId == null || packageStore.get(record.savedPackageId) == null)
            ) {
                record = tasks.update(record.id) {
                    it.copy(
                        phase = DownloadPhase.FAILED,
                        savedPackageId = null,
                        errorMessage = "Saved package is unavailable",
                    )
                } ?: record
            }
            publishState(record)
        }
        val surviving = tasks.all()
        staging.retainOnly(surviving.map { it.id })
        reclaimSessions(surviving)
        runCatching { packageStore.sweepOrphans() }
            .onFailure { error -> debugLog("InstallTask") { "Saved package sweep failed: $error" } }
    }

    private suspend fun reclaimSessions(records: List<InstallTaskRecord>) {
        val retained = records.asSequence()
            .filter { it.phase in PendingResultPhases }
            .mapNotNull { it.sessionId }
            .toSet()
        runCatching { installer.reclaimSessions(retained) }
            .onFailure { error -> debugLog("InstallTask") { "Session reclaim failed: $error" } }
    }

    private fun publishFailureAction(code: InstallFailureCode, message: String) {
        if (code == InstallFailureCode.CANCELLED) return
        val action = userAction(code, message)
        mutablePendingUserAction.update { current ->
            when {
                action == InstallUserAction.GrantUnknownSourcesPermission -> action
                current == InstallUserAction.GrantUnknownSourcesPermission -> current
                else -> action
            }
        }
    }

    private suspend fun recordFailure(
        taskId: String,
        code: InstallFailureCode,
        message: String,
        transform: (InstallTaskRecord) -> InstallTaskRecord,
    ) {
        publishFailureAction(code, message)
        runCatching { updateRecord(taskId, transform) }
            .onFailure { error -> debugLog("InstallTask") { "Unable to persist failure for $taskId: $error" } }
    }

    private fun userAction(code: InstallFailureCode, message: String): InstallUserAction =
        if (
            code == InstallFailureCode.UNKNOWN_SOURCES_PERMISSION ||
            message.contains("install unknown apps", ignoreCase = true)
        ) {
            InstallUserAction.GrantUnknownSourcesPermission
        } else {
            InstallUserAction.InstallationFailed(message.ifBlank { "Installation failed" })
        }

    private fun InstallTaskRecord.toDownloadState(): DownloadState = DownloadState(
        appId = appId,
        packageName = request.packageName,
        displayName = request.displayName,
        progress = progress,
        phase = phase,
        savedPackageId = savedPackageId,
        errorMessage = errorMessage,
        icon = request.icon,
        versionName = request.versionName,
        versionCode = request.versionCode,
        source = request.marketSource,
    )

    private fun DownloadMeta.toInstallRequest(
        id: String,
        saveToDownloads: Boolean,
        deltaUpdateEnabled: Boolean,
        deleteAfterUpdate: Boolean,
    ): InstallRequest {
        val resolvedParts = parts.takeIf { it.isNotEmpty() }
            ?: listOf(DownloadPart("base", "base", url, size))
        val baseApk = File(installedBaseApkPath)
        val canUseDelta = deltaUpdateEnabled &&
                resolvedParts.size == 1 &&
                baseApk.isFile &&
                baseApk.canRead()
        return InstallRequest(
            id = id,
            packageName = packageName,
            displayName = displayName,
            versionName = versionName,
            versionCode = versionCode,
            icon = icon,
            marketSource = source,
            artifacts = resolvedParts.mapIndexed { index, part ->
                val fullSource = InstallSource.Remote(part.url, requestHeaders)
                val patch = part.patch
                InstallArtifact(
                    name = sessionName(index, part.name, part.type),
                    source = if (canUseDelta && patch != null) {
                        InstallSource.Delta(
                            full = fullSource,
                            patch = InstallSource.Remote(
                                patch.url,
                                patch.requestHeaders.ifEmpty { requestHeaders },
                            ),
                            patchSize = patch.size,
                            patchChecksum = patch.hash,
                            patchVersion = patch.version,
                            baseApkPath = installedBaseApkPath,
                            patchProtocol = patch.protocol,
                        )
                    } else {
                        fullSource
                    },
                    size = part.size,
                    checksum = part.hash,
                )
            },
            saveToDownloads = saveToDownloads,
            deleteAfterUpdate = deleteAfterUpdate,
        )
    }

    private fun sessionName(index: Int, name: String, type: String): String {
        if (index == 0 && (name.equals("base", true) || type.equals("base", true))) return "base.apk"
        val safe = listOf(name, type)
            .filter { it.isNotBlank() && !it.equals("base", true) }
            .joinToString("_")
            .ifBlank { "split_$index" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "${index}_$safe".let { if (it.endsWith(".apk", true)) it else "$it.apk" }
    }

    private val InstallTaskRecord.taskKey: DownloadTaskKey
        get() = DownloadTaskKey(request.packageName, request.versionCode)

    private companion object {
        val ActivePhases = setOf(
            DownloadPhase.QUEUED,
            DownloadPhase.DOWNLOADING,
            DownloadPhase.INSTALLING,
            DownloadPhase.AWAITING_USER_ACTION,
        )
        val ResumablePhases = setOf(DownloadPhase.PAUSED, DownloadPhase.FAILED)
        val PendingResultPhases = setOf(DownloadPhase.INSTALLING, DownloadPhase.AWAITING_USER_ACTION)
        val PrivilegedModes = setOf(InstallerMode.ROOT, InstallerMode.SHIZUKU)
        const val PRIVILEGED_CONFIRM_TIMEOUT_MS = 30_000L
        const val STANDARD_CONFIRM_TIMEOUT_MS = 180_000L
        const val INSTALL_CONFIRM_INTERVAL_MS = 400L
        const val MAX_CONCURRENT_DOWNLOADS = 3
        const val STALE_INSTALL_AGE_MS = 30 * 60 * 1000L
    }
}
