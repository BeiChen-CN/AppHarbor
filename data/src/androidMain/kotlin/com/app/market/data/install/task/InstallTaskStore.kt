package com.app.market.data.install.task

import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.local.StringPreferenceKey
import com.app.market.domain.model.download.DownloadPhase
import com.app.market.domain.model.install.InstallArtifact
import com.app.market.domain.model.install.InstallRequest
import com.app.market.domain.model.install.InstallSource
import com.app.market.domain.model.market.AppSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal data class InstallTaskRecord(
    val id: String,
    val appId: Long,
    val request: InstallRequest,
    val installAfterDownload: Boolean,
    val phase: DownloadPhase = DownloadPhase.QUEUED,
    val progress: Int? = null,
    val savedPackageId: String? = null,
    val sessionId: Int? = null,
    val externalInstallerPackage: String? = null,
    val errorMessage: String = "",
    val modifiedAt: Long = System.currentTimeMillis(),
)

/** 安装任务记录。读走内存，写合并落盘——持久化只为跨进程存活。 */
internal class InstallTaskStore(
    private val preferences: PreferencesDataSource,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var records: MutableList<InstallTaskRecord>? = null
    private var flushScheduled = false

    suspend fun all(): List<InstallTaskRecord> = mutex.withLock { loaded().toList() }

    suspend fun get(id: String): InstallTaskRecord? = mutex.withLock {
        loaded().firstOrNull { it.id == id }
    }

    suspend fun put(record: InstallTaskRecord) = mutex.withLock {
        val current = loaded()
        current.removeAll { it.id == record.id }
        current += record.copy(modifiedAt = System.currentTimeMillis())
        scheduleFlush()
    }

    suspend fun update(id: String, transform: (InstallTaskRecord) -> InstallTaskRecord): InstallTaskRecord? = mutex.withLock {
        val current = loaded()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return@withLock null
        val updated = transform(current[index]).copy(modifiedAt = System.currentTimeMillis())
        current[index] = updated
        scheduleFlush()
        updated
    }

    suspend fun remove(id: String) = mutex.withLock {
        loaded().removeAll { it.id == id }
        scheduleFlush()
    }

    private suspend fun loaded(): MutableList<InstallTaskRecord> = records ?: readFromDisk().also { records = it }

    private suspend fun readFromDisk(): MutableList<InstallTaskRecord> {
        val raw = preferences.read(RecordsKey)?.takeIf { it.isNotBlank() } ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { index -> array.optJSONObject(index)?.toRecord() }
                .filterNotNull()
                .toMutableList()
        }.getOrDefault(mutableListOf())
    }

    /** 合并同一批状态流转的写入；调用方已持有 [mutex]。标志在取快照时清零，否则落盘期间的变更会被吞掉。 */
    private fun scheduleFlush() {
        if (flushScheduled) return
        flushScheduled = true
        scope.launch {
            delay(FLUSH_DELAY_MS)
            val snapshot = mutex.withLock {
                flushScheduled = false
                records?.toList()
            } ?: return@launch
            val array = JSONArray()
            snapshot.forEach { array.put(it.toJson()) }
            runCatching { preferences.put(RecordsKey, array.toString()) }
        }
    }

    private fun InstallTaskRecord.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("appId", appId)
        put("request", request.toJson())
        put("installAfterDownload", installAfterDownload)
        put("phase", phase.name)
        progress?.let { put("progress", it) }
        savedPackageId?.let { put("savedPackageId", it) }
        sessionId?.let { put("sessionId", it) }
        externalInstallerPackage?.let { put("externalInstallerPackage", it) }
        put("modifiedAt", modifiedAt)
    }

    private fun JSONObject.toRecord(): InstallTaskRecord? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val request = optJSONObject("request")?.toRequest() ?: return null
        return InstallTaskRecord(
            id = id,
            appId = optLong("appId"),
            request = request,
            installAfterDownload = optBoolean("installAfterDownload", true),
            phase = runCatching { DownloadPhase.valueOf(optString("phase")) }.getOrDefault(DownloadPhase.QUEUED),
            progress = if (has("progress")) optInt("progress") else null,
            savedPackageId = optString("savedPackageId").takeIf { it.isNotBlank() },
            sessionId = if (has("sessionId")) optInt("sessionId") else null,
            externalInstallerPackage = optString("externalInstallerPackage").takeIf { it.isNotBlank() },
            modifiedAt = optLong("modifiedAt"),
        )
    }

    private fun InstallRequest.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("packageName", packageName)
        put("displayName", displayName)
        put("versionName", versionName)
        put("versionCode", versionCode)
        put("icon", icon)
        put("saveToDownloads", saveToDownloads)
        put("deleteAfterUpdate", deleteAfterUpdate)
        sourceSavedPackageId?.let { put("sourceSavedPackageId", it) }
        marketSource?.let { put("marketSource", it.token) }
        put("artifacts", JSONArray().also { array -> artifacts.forEach { array.put(it.toJson()) } })
    }

    private fun JSONObject.toRequest(): InstallRequest? {
        val id = optString("id").takeIf { it.isNotBlank() } ?: return null
        val packageName = optString("packageName").takeIf { it.isNotBlank() } ?: return null
        val artifactArray = optJSONArray("artifacts") ?: return null
        val artifacts = buildList {
            for (index in 0 until artifactArray.length()) {
                artifactArray.optJSONObject(index)?.toArtifact()?.let(::add)
            }
        }
        if (artifacts.isEmpty()) return null
        return InstallRequest(
            id = id,
            packageName = packageName,
            displayName = optString("displayName", packageName),
            versionName = optString("versionName"),
            versionCode = optLong("versionCode"),
            icon = optString("icon"),
            artifacts = artifacts,
            saveToDownloads = optBoolean("saveToDownloads", true),
            deleteAfterUpdate = optBoolean("deleteAfterUpdate", false),
            sourceSavedPackageId = optString("sourceSavedPackageId").takeIf { it.isNotBlank() },
            marketSource = AppSource.fromToken(optString("marketSource")),
        )
    }

    private fun InstallArtifact.toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("size", size)
        put("checksum", checksum)
        when (val value = source) {
            is InstallSource.Remote -> {
                put("sourceType", "remote")
                put("url", value.url)
                put("headers", JSONObject(value.headers))
            }

            is InstallSource.Delta -> {
                put("sourceType", "delta")
                put("fullUrl", value.full.url)
                put("fullHeaders", JSONObject(value.full.headers))
                put("patchUrl", value.patch.url)
                put("patchHeaders", JSONObject(value.patch.headers))
                put("patchSize", value.patchSize)
                put("patchChecksum", value.patchChecksum)
                put("patchVersion", value.patchVersion)
                put("patchProtocol", value.patchProtocol)
                put("baseApkPath", value.baseApkPath)
            }

            is InstallSource.Local -> {
                put("sourceType", "local")
                put("uri", value.uri)
            }
        }
    }

    private fun JSONObject.toArtifact(): InstallArtifact? {
        val name = optString("name").takeIf { it.isNotBlank() } ?: return null
        val source = when (optString("sourceType")) {
            "remote" -> {
                val url = optString("url").takeIf { it.isNotBlank() } ?: return null
                InstallSource.Remote(url, stringMap("headers"))
            }

            "delta" -> {
                val fullUrl = optString("fullUrl").takeIf { it.isNotBlank() } ?: return null
                val patchUrl = optString("patchUrl").takeIf { it.isNotBlank() } ?: return null
                val baseApkPath = optString("baseApkPath").takeIf { it.isNotBlank() } ?: return null
                InstallSource.Delta(
                    full = InstallSource.Remote(fullUrl, stringMap("fullHeaders")),
                    patch = InstallSource.Remote(patchUrl, stringMap("patchHeaders")),
                    patchSize = optLong("patchSize", -1L),
                    patchChecksum = optString("patchChecksum"),
                    patchVersion = optInt("patchVersion"),
                    baseApkPath = baseApkPath,
                    patchProtocol = optString("patchProtocol", "file"),
                )
            }

            "local" -> InstallSource.Local(optString("uri").takeIf { it.isNotBlank() } ?: return null)
            else -> return null
        }
        return InstallArtifact(
            name = name,
            source = source,
            size = optLong("size", -1L),
            checksum = optString("checksum"),
        )
    }

    private fun JSONObject.stringMap(name: String): Map<String, String> {
        val values = optJSONObject(name) ?: return emptyMap()
        return buildMap {
            values.keys().forEach { key -> put(key, values.optString(key)) }
        }
    }

    private companion object {
        const val FLUSH_DELAY_MS = 150L
        val RecordsKey = StringPreferenceKey("installer_tasks", "records")
    }
}
