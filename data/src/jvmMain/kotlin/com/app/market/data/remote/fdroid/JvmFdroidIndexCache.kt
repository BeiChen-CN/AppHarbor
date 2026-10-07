package com.app.market.data.remote.fdroid

import com.app.market.domain.exception.MarketException
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.client.plugins.timeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.security.DigestInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.PushbackInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** entry.json 顶层元数据：判定本地索引缓存是否仍与远端一致。 */
internal data class FdroidEntryJson(
    val timestamp: Long,
    val maxAge: Int,
    val index: FdroidIndexAsset,
    val numPackages: Int,
)

/**
 * [FdroidIndexCache] 的 JVM 实现（Android 与桌面共用，位于 jvmMain）。
 *
 * 同步流程：entry.json 判定新鲜度 -> 下载 index-v2（gzip 传输约 20MB）->
 * 流式解压 + SHA-256 校验 + 逐包重建摘要与切片 -> 原子替换缓存文件。
 * 任一环节失败时保留旧缓存；完全无缓存时抛出 [MarketException]。
 *
 * 差异说明：F-Droid 官方提供基于 JSON Patch 的 index diff，但对 60MB 文档做
 * 增量补丁需要整档 DOM 或同等的文档重写器，复杂度与收益不成比例，首期不做，
 * 每次索引变化（约每日 1 次）都全量重建；下载走 gzip，流量成本约 20MB。
 */
internal class JvmFdroidIndexCache(
    private val rootDir: File,
    private val client: HttpClient,
    private val config: FdroidApiConfig = FdroidApiConfig(),
) : FdroidIndexCache {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val mutex = Mutex()

    /** 进程内摘要缓存；null 表示尚未加载/构建。 */
    @Volatile
    private var cachedSummary: FdroidIndexSummary? = null

    /** 上次 entry.json 复查时间；0 表示进程启动后尚未联网判定。 */
    @Volatile
    private var lastEntryCheckMs = 0L

    /** 全量重建后仍未收录的包名，避免同一缺失包反复触发 20MB 级重建。 */
    @Volatile
    private var rebuildMisses: MutableSet<String> = mutableSetOf()

    override suspend fun summary(): FdroidIndexSummary = mutex.withLock {
        val now = System.currentTimeMillis()
        val current = cachedSummary ?: loadSummaryFromDisk()
        cachedSummary = current
        // 节流：进程内半小时内不重复联网判定新鲜度
        if (current != null && now - lastEntryCheckMs < ENTRY_CHECK_INTERVAL_MS) return@withLock current
        try {
            refreshLocked().also { cachedSummary = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 离线等场景：有旧缓存就降级使用，完全无数据才向调用方报错
            if (current != null) {
                lastEntryCheckMs = now
                current
            } else {
                throw e
            }
        }
    }

    override suspend fun fragment(packageName: String): FdroidAppFragment? = mutex.withLock {
        val file = fragmentFile(packageName) ?: return@withLock null
        if (cachedSummary == null) {
            cachedSummary = try {
                refreshLocked()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
        if (packageName in rebuildMisses) return@withLock null
        readFragment(file)?.let { return@withLock it }
        // 切片缺失（远端新增应用 / 切片目录被清）：全量重建一次后重读；
        // 重建后仍缺失视为未收录，进程内记录避免重复触发大下载
        cachedSummary = try {
            refreshLocked(forceRebuild = true)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock null
        }
        readFragment(file) ?: run {
            rebuildMisses.add(packageName)
            null
        }
    }

    /**
     * 与远端对齐索引：entry.json 的哈希与本地 meta 一致即视为新鲜。
     * [forceRebuild] 跳过新鲜判定直接重建（切片缺失场景）。
     */
    private suspend fun refreshLocked(forceRebuild: Boolean = false): FdroidIndexSummary {
        val entry = fetchEntry()
        lastEntryCheckMs = System.currentTimeMillis()
        if (!forceRebuild) {
            val meta = readMeta()
            if (meta != null && meta.indexSha256 == entry.index.sha256) {
                loadSummaryFromDisk()?.let { return it }
            }
        }
        return rebuild(entry)
    }

    /** 下载索引并全量重建摘要与切片；失败时清理临时文件并保留旧缓存。 */
    private suspend fun rebuild(entry: FdroidEntryJson): FdroidIndexSummary = withContext(Dispatchers.IO) {
        rootDir.mkdirs()
        val download = File(rootDir, INDEX_TMP_NAME)
        try {
            downloadIndexTo(download)
            val summary = parseAndBuild(download, entry)
            writeSummaryAndMeta(summary, entry)
            summary
        } finally {
            download.delete()
        }
    }

    private suspend fun downloadIndexTo(target: File) {
        val url = "${config.repoBase.trimEnd('/')}/index-v2.json"
        client.prepareGet(url) {
            header(HttpHeaders.UserAgent, FdroidApiUserAgent)
            // 显式声明 gzip：OkHttp 引擎在调用方自带 Accept-Encoding 时不做透明解压，
            // 与 CIO 引擎行为对齐，落到磁盘的都是服务端原始字节（是否压缩由魔数嗅探决定）
            header(HttpHeaders.AcceptEncoding, "gzip")
            // 索引体积大且弱网常见，独立于共享客户端 60s 的专属超时
            timeout { requestTimeoutMillis = INDEX_TIMEOUT_MS }
        }.execute { response ->
            if (!response.status.isSuccess()) {
                throw MarketException("F-Droid 索引下载失败：HTTP ${response.status.value}")
            }
            // 索引 gzip 传输约 20MB，整读后落盘；逐块泵送在 ktor 3 缺少稳定的 ByteArray API
            val bytes = response.bodyAsBytes()
            FileOutputStream(target).use { out ->
                out.write(bytes)
                out.flush()
            }
        }
    }

    /**
     * 流式解析下载文件并构建摘要 + 切片：gzip 魔数嗅探 -> SHA-256 校验 ->
     * 逐包解码写入 fragments.tmp -> 校验通过后原子替换 fragments 目录。
     * 校验哈希是 entry.json 给出的未压缩内容哈希。
     */
    private fun parseAndBuild(download: File, entry: FdroidEntryJson): FdroidIndexSummary {
        val digest = MessageDigest.getInstance("SHA-256")
        val apps = HashMap<String, FdroidAppSummary>(entry.numPackages.coerceAtLeast(0) * 2)
        val tmpFragments = File(rootDir, FRAGMENTS_TMP_DIR)
        tmpFragments.deleteRecursively()
        tmpFragments.mkdirs()
        try {
            FileInputStream(download).use { raw ->
                val pushback = PushbackInputStream(BufferedInputStream(raw, STREAM_BUFFER_BYTES), 2)
                val stream = if (isGzip(pushback)) GZIPInputStream(pushback, STREAM_BUFFER_BYTES) else pushback
                DigestInputStream(stream, digest).use { hashed ->
                    FdroidIndexScanner(BufferedInputStream(hashed, STREAM_BUFFER_BYTES)).use { scanner ->
                        scanner.forEachPackage { packageName, packageJson ->
                            val app = json.decodeFromString<FdroidIndexPackage>(packageJson)
                            apps[packageName] = app.toSummary(packageName)
                            File(tmpFragments, "$packageName.json")
                                .writeText(json.encodeToString(FdroidAppFragment.serializer(), app.toFragment(packageName)))
                        }
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(entry.index.sha256, ignoreCase = true)) {
                throw MarketException("F-Droid 索引内容校验失败（sha256 不匹配）")
            }
        } catch (e: Exception) {
            tmpFragments.deleteRecursively()
            throw e
        }
        // 校验通过后才替换正式切片目录
        File(rootDir, FRAGMENTS_DIR).deleteRecursively()
        if (!tmpFragments.renameTo(File(rootDir, FRAGMENTS_DIR))) {
            // renameTo 可能因目标被占用而失败（Windows 常见）：回退为复制
            tmpFragments.copyRecursively(File(rootDir, FRAGMENTS_DIR))
            tmpFragments.deleteRecursively()
        }
        rebuildMisses = mutableSetOf()
        return FdroidIndexSummary(
            indexSha256 = entry.index.sha256.lowercase(),
            builtAtMs = System.currentTimeMillis(),
            indexTimestamp = entry.timestamp,
            apps = apps,
        )
    }

    /** 摘要与 meta 落盘；先写临时文件再替换，任意时点崩溃都不会破坏旧缓存。 */
    private fun writeSummaryAndMeta(summary: FdroidIndexSummary, entry: FdroidEntryJson) {
        val summaryJson = json.encodeToString(FdroidIndexSummary.serializer(), summary)
        replaceFile(File(rootDir, SUMMARY_TMP_NAME), File(rootDir, SUMMARY_NAME)) { it.writeText(summaryJson) }
        val meta = FdroidIndexMeta(
            indexSha256 = summary.indexSha256,
            indexTimestamp = entry.timestamp,
            numPackages = entry.numPackages,
            fetchedAtMs = System.currentTimeMillis(),
        )
        val metaJson = json.encodeToString(FdroidIndexMeta.serializer(), meta)
        replaceFile(File(rootDir, META_TMP_NAME), File(rootDir, META_NAME)) { it.writeText(metaJson) }
    }

    private inline fun replaceFile(tmp: File, target: File, write: (File) -> Unit) {
        write(tmp)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private suspend fun fetchEntry(): FdroidEntryJson {
        val url = "${config.repoBase.trimEnd('/')}/entry.json"
        val text = try {
            client.get(url) {
                header(HttpHeaders.UserAgent, FdroidApiUserAgent)
            }.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw MarketException("无法获取 F-Droid 仓库元数据：${e.message}", e)
        }
        val root = parseFdroidObject(text)
        val index = root.fdjObject("index") ?: throw MarketException("F-Droid 仓库元数据格式异常")
        return FdroidEntryJson(
            timestamp = root.fdjLong("timestamp"),
            maxAge = root.fdjLong("maxAge").toInt(),
            index = FdroidIndexAsset(
                name = index.fdjString("name"),
                sha256 = index.fdjString("sha256"),
                size = index.fdjLong("size"),
            ),
            numPackages = index.fdjLong("numPackages").toInt(),
        )
    }

    private fun readMeta(): FdroidIndexMeta? {
        val file = File(rootDir, META_NAME)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<FdroidIndexMeta>(file.readText()) }.getOrNull()
    }

    private fun loadSummaryFromDisk(): FdroidIndexSummary? {
        val file = File(rootDir, SUMMARY_NAME)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<FdroidIndexSummary>(file.readText()) }.getOrNull()
    }

    private fun readFragment(file: File): FdroidAppFragment? {
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<FdroidAppFragment>(file.readText()) }.getOrNull()
    }

    /** 包名仅允许字母/数字/点/下划线/连字符，杜绝路径穿越。 */
    private fun fragmentFile(packageName: String): File? {
        if (packageName.isEmpty() || packageName.length > MAX_PACKAGE_NAME_LENGTH) return null
        if (packageName.any { !it.isLetterOrDigit() && it != '.' && it != '_' && it != '-' }) return null
        return File(File(rootDir, FRAGMENTS_DIR), "$packageName.json")
    }

    /** gzip 魔数嗅探：读取两字节判定后回退，兼容各引擎不同的压缩透传行为。 */
    private fun isGzip(stream: PushbackInputStream): Boolean {
        val head = ByteArray(2)
        var read = 0
        while (read < head.size) {
            val n = stream.read(head, read, head.size - read)
            if (n == -1) break
            read += n
        }
        val gzip = read == 2 && head[0] == 0x1F.toByte() && head[1] == 0x8B.toByte()
        if (read > 0) stream.unread(head, 0, read)
        return gzip
    }

    private fun FdroidIndexPackage.toSummary(packageName: String): FdroidAppSummary {
        val versions = versions.values.mapNotNull { it.toSummaryVersion() }.sortedByDescending(FdroidSummaryVersion::versionCode)
        // F-Droid 客户端同款策略：优先推无 antiFeatures 的最高版本
        val latest = versions.firstOrNull { it.antiFeatures.isEmpty() } ?: versions.firstOrNull()
        return FdroidAppSummary(
            displayName = metadata.name.fdroidLocalized().ifBlank { packageName },
            summary = metadata.summary.fdroidLocalized(),
            icon = metadata.icon.fdroidAssetPath(),
            categories = metadata.categories,
            license = metadata.license,
            preferredSigner = metadata.preferredSigner.trim().lowercase(),
            lastUpdated = metadata.lastUpdated,
            latest = latest,
        )
    }

    private fun FdroidIndexVersion.toSummaryVersion(): FdroidSummaryVersion? {
        val manifest = manifest ?: return null
        val file = file ?: return null
        if (manifest.versionCode <= 0L || file.name.isBlank()) return null
        return FdroidSummaryVersion(
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            apkName = file.name,
            apkSha256 = file.sha256,
            apkSize = file.size,
            added = added,
            minSdkVersion = manifest.usesSdk?.minSdkVersion ?: 0,
            signers = manifest.signer?.sha256.orEmpty().map(String::trim),
            antiFeatures = antiFeatures.keys.toList(),
            whatsNew = whatsNew.fdroidLocalized(),
        )
    }

    private fun FdroidIndexPackage.toFragment(packageName: String): FdroidAppFragment = FdroidAppFragment(
        packageName = packageName,
        name = metadata.name,
        summary = metadata.summary,
        description = metadata.description,
        icon = metadata.icon,
        screenshots = metadata.screenshots,
        categories = metadata.categories,
        license = metadata.license,
        authorName = metadata.authorName,
        authorEmail = metadata.authorEmail,
        sourceCode = metadata.sourceCode,
        webSite = metadata.webSite,
        changelog = metadata.changelog,
        preferredSigner = metadata.preferredSigner.trim().lowercase(),
        added = metadata.added,
        lastUpdated = metadata.lastUpdated,
        versions = versions.values.mapNotNull { it.toFragmentVersion() }.sortedByDescending(FdroidFragmentVersion::versionCode),
    )

    private fun FdroidIndexVersion.toFragmentVersion(): FdroidFragmentVersion? {
        val manifest = manifest ?: return null
        val file = file ?: return null
        if (manifest.versionCode <= 0L || file.name.isBlank()) return null
        return FdroidFragmentVersion(
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            added = added,
            apkName = file.name,
            apkSha256 = file.sha256,
            apkSize = file.size,
            minSdkVersion = manifest.usesSdk?.minSdkVersion ?: 0,
            targetSdkVersion = manifest.usesSdk?.targetSdkVersion ?: 0,
            signers = manifest.signer?.sha256.orEmpty().map(String::trim),
            whatsNew = whatsNew,
            antiFeatures = antiFeatures.keys.toList(),
        )
    }

    private companion object {
        const val META_NAME = "meta.json"
        const val SUMMARY_NAME = "summary.json"
        const val META_TMP_NAME = "meta.json.tmp"
        const val SUMMARY_TMP_NAME = "summary.json.tmp"
        const val INDEX_TMP_NAME = "index-v2.json.tmp"
        const val FRAGMENTS_DIR = "fragments"
        const val FRAGMENTS_TMP_DIR = "fragments.tmp"
        const val MAX_PACKAGE_NAME_LENGTH = 255

        /** 索引体积大且弱网常见：独立于共享客户端 60s 的专属超时。 */
        const val INDEX_TIMEOUT_MS = 15 * 60 * 1000L

        /** 进程内 entry.json 新鲜度复查间隔。 */
        const val ENTRY_CHECK_INTERVAL_MS = 30 * 60 * 1000L

        const val STREAM_BUFFER_BYTES = 1 shl 20
        const val DOWNLOAD_BUFFER_BYTES = 256 * 1024
    }
}
