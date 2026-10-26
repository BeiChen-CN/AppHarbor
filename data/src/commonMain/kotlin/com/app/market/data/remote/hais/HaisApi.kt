package com.app.market.data.remote.hais

import com.app.market.data.platform.md5
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPart
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Clock

/**
 * HAIS（apk.hais.pw）APK 归档站接口配置。
 *
 * 该站没有登录态，匿名调用只需带上固定的小程序来源头与按时间戳现算的签名。
 */
internal data class HaisApiConfig(
    val baseUrl: String = "https://api.apk.hais.pw",
    val appId: String = "wx1e82ed4cb8ab63a1",
    /** 匿名令牌，服务器接受空串；保留可配位以便后续换正式令牌。 */
    val appToken: String = "",
)

/** 主条目列表页固定 10 条，APK 变体列表固定 20 条（与服务器实际返回一致）。 */
internal const val HaisSearchPageSize = 10
internal const val HaisHistoryPageSize = 20

/** 历史版本换取直链的并发上限，避免整页同时打满接口。 */
private const val HistoryFetchConcurrency = 3

private const val HaisUserAgent = "Trove (HAIS APK archive source)"

private val HaisZone = TimeZone.of("Asia/Shanghai")

private val HaisJson = Json { ignoreUnknownKeys = true }

/**
 * HAIS APK 归档站客户端。
 *
 * 站点语义：主条目以**包名**作为 id（`/apkMain/list` 的 `id` 字段就是包名），
 * APK 变体（历史版本、不同渠道包）用短 ID。因此本层所有主条目接口都以包名为键，
 * [MarketAppInfo.appId] 恒为 0，由上层路由按包名索引。
 */
internal class HaisApi(
    private val client: HttpClient,
    private val config: HaisApiConfig = HaisApiConfig(),
) {
    /**
     * 搜索/列举应用。空关键词即按站内默认顺序列举全部（实测约 155 页）。
     *
     * @param page 0-based 页号，映射为接口的 1-based `pageNum`。
     */
    suspend fun search(keyword: String, page: Int = 0): SearchPage {
        val targetPage = (page + 1).coerceAtLeast(1)
        val data = get(
            "/apkMain/list?packages=&source=&keyword=${keyword.trim().encodeQuery()}" +
                "&pageNum=$targetPage&pageSize=$HaisSearchPageSize&order=",
        ).decodeHais<HaisMainPage>() ?: throw MarketException("HAIS 搜索结果解析失败")
        return SearchPage(
            items = data.records.map { it.toAppInfo() },
            hasMore = data.current < data.pages,
        )
    }

    /**
     * 应用详情：`/apkMain/get?id=<包名>`。
     */
    suspend fun appDetail(packageName: String): AppDetail {
        if (packageName.isBlank()) throw MarketException("HAIS 缺少包名")
        val record = get("/apkMain/get?id=${packageName.encodeQuery()}").decodeHais<HaisMainRecord>()
            ?: throw MarketException("HAIS 未收录该应用")
        return record.toAppDetail()
    }

    /**
     * 最新版本下载元数据：先在 `/apk/list` 里按 versionCode 找对应 APK 变体，
     * 找不到同版本就退回该包名的第一条（服务器倒序返回，首条即最新）。
     */
    suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta {
        val records = apkRecords(app.packageName)
        val target = records.firstOrNull { it.versionCode == app.versionCode }
            ?: records.firstOrNull()
            ?: throw MarketException("HAIS 未提供该应用的安装包")
        val info = downInfo(target.id) ?: throw MarketException("HAIS 未提供该应用的下载直链")
        return info.toDownloadMeta(
            appId = app.appId,
            packageName = app.packageName.ifBlank { target.packages },
            displayName = app.displayName,
            fallbackIcon = app.icon,
            fallbackVersionName = app.versionName,
            fallbackVersionCode = app.versionCode,
        )
    }

    /**
     * 历史版本分页。
     *
     * 取舍说明：`/apk/list` 返回的 `extend` 恒为空 `{}`，不含 downUrls，直链只能靠
     * `/apk/getDownInfo` 换取；而 [HistoricalVersion.versionId] 是 Long，装不下 base62 短 ID
     * （解码回 Long 太脆弱）。因此在本阶段就并发换取直链写进 [HistoricalVersion.downloadUrl]，
     * [historicalDownloadMeta] 便零请求直接构造下载元数据。
     * 并发用 [Semaphore] 限到 [HistoryFetchConcurrency]；单条换链失败只跳过该条，不影响整页。
     */
    suspend fun historicalVersions(packageName: String, offset: Int): HistoricalVersionPage {
        if (packageName.isBlank()) throw MarketException("HAIS 缺少包名，无法加载历史版本")
        val safeOffset = offset.coerceAtLeast(0)
        val pageNum = safeOffset / HaisHistoryPageSize + 1
        val page = get(
            "/apk/list?packages=${packageName.encodeQuery()}&source=&keyword=" +
                "&pageNum=$pageNum&pageSize=$HaisHistoryPageSize&order=",
        ).decodeHais<HaisApkPage>() ?: return HistoricalVersionPage(emptyList(), null)

        // 服务器会把同厂商的其他包（查 com.tencent.mm 会混入 com.tencent.wechatkids）带进来，按包名严格过滤
        val records = page.records.filter { it.packages == packageName }
        val slots = Semaphore(HistoryFetchConcurrency)
        val items = coroutineScope {
            records.map { record ->
                async {
                    slots.withPermit {
                        val info = runCatching { downInfo(record.id) }.getOrNull()
                        val url = info?.downUrls()?.firstOrNull().orEmpty()
                        if (url.isBlank()) return@withPermit null
                        HistoricalVersion(
                            appId = 0L,
                            packageName = packageName,
                            displayName = record.name.ifBlank { info?.name.orEmpty() },
                            icon = record.icon.ifBlank { info?.icon.orEmpty() },
                            // 短 ID 无法无损转 Long：下载能力全部走 downloadUrl，故 versionId 置 0
                            versionId = 0L,
                            versionName = record.versionName.ifBlank { info?.versionName.orEmpty() },
                            versionCode = record.versionCode.takeIf { it > 0L } ?: (info?.versionCode ?: 0L),
                            minSdkVersion = record.targetSdkVersion ?: 0,
                            downloadUrl = url,
                            size = record.size ?: info?.size ?: 0L,
                            createdAt = haisTimeMillis(record.createTime),
                            updatedAt = haisTimeMillis(record.addTime.ifBlank { record.createTime }),
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }

        // offset 按服务器原始条数累加，避免被过滤掉的条目让分页原地踏步
        val next = safeOffset + page.records.size
        return HistoricalVersionPage(
            items = items,
            nextOffset = next.takeIf { page.current < page.pages },
        )
    }

    /**
     * 历史版本下载元数据：直链已在 [historicalVersions] 阶段写入
     * [HistoricalVersion.downloadUrl]，这里不再请求网络。
     */
    fun historicalDownloadMeta(version: HistoricalVersion): DownloadMeta {
        val url = version.downloadUrl
        if (url.isBlank()) throw MarketException("HAIS 未提供该历史版本的下载地址")
        return DownloadMeta(
            appId = version.appId,
            packageName = version.packageName,
            displayName = version.displayName.ifBlank { version.packageName },
            versionName = version.versionName,
            versionCode = version.versionCode,
            url = url,
            size = version.size,
            parts = listOf(DownloadPart(name = "", type = "base", url = url, size = version.size)),
            icon = version.icon,
        )
    }

    /** 该包名下的 APK 变体（第一页，最新 20 条），已按包名过滤。 */
    private suspend fun apkRecords(packageName: String): List<HaisApkRecord> {
        if (packageName.isBlank()) return emptyList()
        val page = get(
            "/apk/list?packages=${packageName.encodeQuery()}&source=&keyword=" +
                "&pageNum=1&pageSize=$HaisHistoryPageSize&order=",
        ).decodeHais<HaisApkPage>() ?: return emptyList()
        return page.records.filter { it.packages == packageName }
    }

    /** 换取短 ID 对应的下载直链；失败返回 null，由调用方决定跳过还是兜底。 */
    private suspend fun downInfo(shortId: String): HaisDownInfo? {
        if (shortId.isBlank()) return null
        return get("/apk/getDownInfo?id=${shortId.encodeQuery()}&wx=").decodeHais<HaisDownInfo>()
    }

    /** 统一 GET：带上签名头，校验 HTTP 状态与业务 `code`，返回 `data` 节点。 */
    private suspend fun get(path: String): JsonObject {
        val response = client.get("${config.baseUrl}$path") {
            signHeaders().forEach { (name, value) -> header(name, value) }
        }
        if (!response.status.isSuccess()) {
            throw MarketException("HAIS 请求失败：HTTP ${response.status.value}")
        }
        val text = response.bodyAsText()
        val envelope = runCatching { HaisJson.decodeFromString<HaisRawEnvelope>(text) }.getOrNull()
            ?: throw MarketException("HAIS 响应解析失败")
        if (envelope.code != 200 || envelope.data == null) {
            throw MarketException("HAIS 接口返回异常：${envelope.message.ifBlank { "code=${envelope.code}" }}")
        }
        return envelope.data
    }

    /**
     * 小程序来源鉴权头：`X-Sign = md5(appId + 当前毫秒时间戳)` 小写 hex。
     * 时间戳必须每次请求现算，签名不可缓存。
     */
    private fun signHeaders(): Map<String, String> {
        val timestamp = Clock.System.now().toEpochMilliseconds().toString()
        return mapOf(
            "X-Platform" to "ApkBox",
            "X-AppId" to config.appId,
            "X-AppToken" to config.appToken,
            "X-Sign" to (config.appId + timestamp).md5Hex(),
            "User-Agent" to HaisUserAgent,
        )
    }
}

/** 业务信封：`{ code, data, message }`；`data` 因接口而异，先留成原始 JSON 再二次解码。 */
@Serializable
private data class HaisRawEnvelope(
    val code: Int = -1,
    val data: JsonObject? = null,
    val message: String = "",
)

@Serializable
internal data class HaisMainRecord(
    val id: String = "",
    val name: String = "",
    val icon: String = "",
    val content: String? = null,
    val createTime: String = "",
    val updateTime: String = "",
    val downNum: Long = 0L,
    val versionCode: Long = 0L,
    val versionName: String = "",
    val libArm32Num: Int = 0,
    val libArm64Num: Int = 0,
    val libNoNum: Int = 0,
    val sourceCustNum: Int = 0,
    val sourceGoogleNum: Int = 0,
    val sourceOfficalNum: Int = 0,
    val status: String = "",
    val statusStr: String = "",
    val targetSdkVersion: Int? = null,
    val viewsNum: Long = 0L,
    val extend: JsonObject? = null,
)

@Serializable
internal data class HaisMainPage(
    val current: Int = 1,
    val pages: Int = 1,
    val records: List<HaisMainRecord> = emptyList(),
    val size: Int = 0,
    val total: Long = 0L,
)

@Serializable
internal data class HaisApkRecord(
    val id: String = "",
    val mainId: String = "",
    val packages: String = "",
    val name: String = "",
    val icon: String = "",
    val md5: String = "",
    val size: Long? = null,
    val source: String = "",
    val custName: String = "",
    val structure: String = "",
    val targetSdkVersion: Int? = null,
    val versionCode: Long = 0L,
    val versionName: String = "",
    val addTime: String = "",
    val createTime: String = "",
    val downTime: Long = 0L,
    val downNum: Long = 0L,
    val extend: JsonObject? = null,
)

@Serializable
internal data class HaisApkPage(
    val current: Int = 1,
    val pages: Int = 1,
    val records: List<HaisApkRecord> = emptyList(),
    val size: Int = 0,
    val total: Long = 0L,
)

/** `/apk/getDownInfo` 的返回体：与列表同形，额外带 `extend.downUrls`。 */
@Serializable
internal data class HaisDownInfo(
    val id: String = "",
    val mainId: String = "",
    val packages: String = "",
    val name: String = "",
    val icon: String = "",
    val md5: String = "",
    val size: Long? = null,
    val source: String = "",
    val custName: String = "",
    val structure: String = "",
    val targetSdkVersion: Int? = null,
    val versionCode: Long = 0L,
    val versionName: String = "",
    val addTime: String = "",
    val createTime: String = "",
    val downTime: Long = 0L,
    val downNum: Long = 0L,
    val extend: HaisDownExtend? = null,
) {
    /** 下载直链列表；取第一个即可作为直链。 */
    fun downUrls(): List<String> = extend?.downUrls.orEmpty()
}

@Serializable
internal data class HaisDownExtend(
    val downUrls: List<String> = emptyList(),
)

/** 主条目 → 搜索结果项。appId 恒 0（HAIS 以包名为主键），source 由上层路由补齐。 */
internal fun HaisMainRecord.toAppInfo(): MarketAppInfo = MarketAppInfo(
    appId = 0L,
    packageName = id,
    displayName = name.ifBlank { id },
    publisherName = "",
    versionName = versionName,
    versionCode = versionCode,
    icon = icon,
    // 主条目接口不返回安装包体积，列表页先置 0，下载时以 APK 变体记录为准
    apkSize = 0L,
    ratingScore = 0.0,
    changeLog = content.orEmpty().trim(),
    downloadCount = downNum,
    category = "",
)

/** 主条目 → 详情页。该站无截图、评论接口，三者返回空集合。 */
internal fun HaisMainRecord.toAppDetail(): AppDetail = AppDetail(
    app = toAppInfo(),
    brief = content.orEmpty().trim(),
    introduction = content.orEmpty().trim(),
    changeLog = content.orEmpty().trim(),
    category = "",
    ageClassification = "",
    downloadCount = downNum,
    registrationNum = "",
    updateTime = haisTimeMillis(updateTime),
    privacyUrl = "",
    screenshots = emptyList(),
    comments = emptyList(),
    sameDeveloperApps = emptyList(),
)

/** 直链信息 → 下载元数据；体积与 md5 以接口返回为准。 */
internal fun HaisDownInfo.toDownloadMeta(
    appId: Long,
    packageName: String,
    displayName: String,
    fallbackIcon: String = "",
    fallbackVersionName: String = "",
    fallbackVersionCode: Long = 0L,
): DownloadMeta {
    val url = downUrls().firstOrNull().orEmpty()
    if (url.isBlank()) throw MarketException("HAIS 未提供该应用的下载直链")
    val apkSize = size ?: 0L
    return DownloadMeta(
        appId = appId,
        packageName = packageName.ifBlank { packages },
        displayName = displayName.ifBlank { name.ifBlank { packages } },
        versionName = versionName.ifBlank { fallbackVersionName },
        versionCode = versionCode.takeIf { it > 0L } ?: fallbackVersionCode,
        url = url,
        size = apkSize,
        parts = listOf(DownloadPart(name = "", type = "base", url = url, size = apkSize, hash = md5)),
        icon = icon.ifBlank { fallbackIcon },
    )
}

/** `"2026-08-30 09:50:13"`（东八区墙钟时间）→ 毫秒时间戳；解析失败按 0 处理。 */
internal fun haisTimeMillis(text: String): Long {
    if (text.isBlank()) return 0L
    return runCatching {
        LocalDateTime.parse(text.trim().replace('/', '-').replace(' ', 'T'))
            .toInstant(HaisZone)
            .toEpochMilliseconds()
    }.getOrDefault(0L)
}

private inline fun <reified T> JsonObject.decodeHais(): T? = runCatching {
    HaisJson.decodeFromString<T>(toString())
}.getOrNull()

private fun String.encodeQuery(): String = buildString {
    for (char in this@encodeQuery) {
        when {
            char.code < 0x80 && (char.isLetterOrDigit() || char in "-._~") -> append(char)
            char == ' ' -> append("%20")
            else -> {
                // 关键词多为中文：按 UTF-8 逐字节百分号编码，不依赖平台 URL 编码器
                char.toString().encodeToByteArray().forEach { byte ->
                    append('%')
                    append(HexDigits[(byte.toInt() shr 4) and 0x0f])
                    append(HexDigits[byte.toInt() and 0x0f])
                }
            }
        }
    }
}

private const val HexDigits = "0123456789ABCDEF"

private fun String.md5Hex(): String = md5(encodeToByteArray()).joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
