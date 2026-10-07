package com.app.market.data.remote.fdroid

import kotlinx.serialization.Serializable

/**
 * F-Droid 仓库 index-v2 的部分模型。索引原始 JSON 高达 60MB+，仅声明实际用到的字段，
 * 其余（权限清单、nativecode、featureGraphic 等）由 ignoreUnknownKeys 在流式扫描阶段丢弃。
 *
 * 字段命名对齐官方 index-v2 规范（fdroidserver 生成），类型以实测为准：
 * - 本地化字段是 `locale -> 文本` 映射（如 zh-CN / en-US）
 * - 图标/截图是 `locale -> {name, sha256, size}`，name 为仓库根的相对路径（以 / 开头）
 * - versions 的键是版本指纹哈希而非 versionCode，真实版本号在 manifest 里
 * - antiFeatures 是 `特性名 -> locale 描述` 映射
 */
@Serializable
internal data class FdroidIndexPackage(
    val metadata: FdroidIndexMetadata = FdroidIndexMetadata(),
    val versions: Map<String, FdroidIndexVersion> = emptyMap(),
)

@Serializable
internal data class FdroidIndexMetadata(
    val name: Map<String, String> = emptyMap(),
    val summary: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val icon: Map<String, FdroidIndexAsset> = emptyMap(),
    val screenshots: FdroidIndexScreenshots? = null,
    val categories: List<String> = emptyList(),
    val license: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    val sourceCode: String = "",
    val webSite: String = "",
    val changelog: String = "",
    val issueTracker: String = "",
    val preferredSigner: String = "",
    val added: Long = 0L,
    val lastUpdated: Long = 0L,
)

@Serializable
internal data class FdroidIndexAsset(
    /** 仓库根相对路径（以 / 开头），拼接仓库地址即为直链。 */
    val name: String = "",
    val sha256: String = "",
    val size: Long = 0L,
)

@Serializable
internal data class FdroidIndexScreenshots(
    val phone: Map<String, List<FdroidIndexAsset>> = emptyMap(),
)

@Serializable
internal data class FdroidIndexVersion(
    val added: Long = 0L,
    val file: FdroidIndexAsset? = null,
    val manifest: FdroidIndexManifest? = null,
    val whatsNew: Map<String, String> = emptyMap(),
    val antiFeatures: Map<String, Map<String, String>> = emptyMap(),
)

@Serializable
internal data class FdroidIndexManifest(
    val versionName: String = "",
    val versionCode: Long = 0L,
    val usesSdk: FdroidIndexSdk? = null,
    val signer: FdroidIndexSigner? = null,
)

@Serializable
internal data class FdroidIndexSdk(
    val minSdkVersion: Int = 0,
    val targetSdkVersion: Int = 0,
)

@Serializable
internal data class FdroidIndexSigner(
    val sha256: List<String> = emptyList(),
)

/**
 * 由索引重建的仓库摘要（summary.json），供更新检查等高频路径使用：
 * 每应用仅保留展示信息与最新版本，全量约 1~2MB，可整体驻留内存。
 * [FdroidAppSummary.latest] 已按 antiFeatures 过滤取最高 versionCode。
 */
@Serializable
internal data class FdroidIndexSummary(
    /** 构建摘要时校验通过的索引内容哈希（entry.json 给出的期望值）。 */
    val indexSha256: String = "",
    /** 摘要构建时间（本地时钟），仅用于诊断。 */
    val builtAtMs: Long = 0L,
    /** entry.json 的仓库时间戳，反映索引对应的发布批次。 */
    val indexTimestamp: Long = 0L,
    val apps: Map<String, FdroidAppSummary> = emptyMap(),
)

@Serializable
internal data class FdroidAppSummary(
    val displayName: String = "",
    val summary: String = "",
    /** 图标仓库相对路径；空串表示索引未提供图标。 */
    val icon: String = "",
    val categories: List<String> = emptyList(),
    val license: String = "",
    /** 包级声明的签名证书指纹（SHA-256 小写十六进制）；空串表示未声明。 */
    val preferredSigner: String = "",
    val lastUpdated: Long = 0L,
    val latest: FdroidSummaryVersion? = null,
)

@Serializable
internal data class FdroidSummaryVersion(
    val versionName: String = "",
    val versionCode: Long = 0L,
    val apkName: String = "",
    val apkSha256: String = "",
    val apkSize: Long = 0L,
    val added: Long = 0L,
    val minSdkVersion: Int = 0,
    val signers: List<String> = emptyList(),
    val antiFeatures: List<String> = emptyList(),
    /** 最新版本的版本说明（zh-CN 优先），作为更新列表的更新日志展示。 */
    val whatsNew: String = "",
)

/**
 * 单应用切片（fragments/<pkg>.json），详情页与历史版本页按需读取：
 * 保留该应用的全部版本与富文本元数据，单个文件通常 5~50KB。
 */
@Serializable
internal data class FdroidAppFragment(
    val packageName: String = "",
    val name: Map<String, String> = emptyMap(),
    val summary: Map<String, String> = emptyMap(),
    val description: Map<String, String> = emptyMap(),
    val icon: Map<String, FdroidIndexAsset> = emptyMap(),
    val screenshots: FdroidIndexScreenshots? = null,
    val categories: List<String> = emptyList(),
    val license: String = "",
    val authorName: String = "",
    val authorEmail: String = "",
    val sourceCode: String = "",
    val webSite: String = "",
    val changelog: String = "",
    val preferredSigner: String = "",
    val added: Long = 0L,
    val lastUpdated: Long = 0L,
    /** 按 versionCode 降序排列的全部版本。 */
    val versions: List<FdroidFragmentVersion> = emptyList(),
)

@Serializable
internal data class FdroidFragmentVersion(
    val versionName: String = "",
    val versionCode: Long = 0L,
    val added: Long = 0L,
    val apkName: String = "",
    val apkSha256: String = "",
    val apkSize: Long = 0L,
    val minSdkVersion: Int = 0,
    val targetSdkVersion: Int = 0,
    val signers: List<String> = emptyList(),
    val whatsNew: Map<String, String> = emptyMap(),
    val antiFeatures: List<String> = emptyList(),
)

/** 缓存元数据（meta.json）：以 entry.json 的索引哈希判断缓存是否仍然新鲜。 */
@Serializable
internal data class FdroidIndexMeta(
    val indexSha256: String = "",
    val indexTimestamp: Long = 0L,
    val numPackages: Int = 0,
    val fetchedAtMs: Long = 0L,
)

/**
 * 中文简体优先的本地化文本挑选：zh-CN -> zh-Hans -> zh-TW -> en-US -> en -> 任意一条。
 * F-Droid 索引的 locale 键实测为 zh-CN / zh-TW / en-US 等 BCP-47 标签。
 */
internal fun Map<String, String>.fdroidLocalized(): String =
    this["zh-CN"]
        ?: this["zh-Hans"]
        ?: this["zh-TW"]
        ?: this["en-US"]
        ?: this["en"]
        ?: values.firstOrNull()
        .orEmpty()

/** 图标等资源的本地化挑选：zh-CN -> en-US -> 任一有效条目，返回仓库相对路径。 */
internal fun Map<String, FdroidIndexAsset>.fdroidAssetPath(): String =
    (this["zh-CN"] ?: this["en-US"] ?: values.firstOrNull { it.name.isNotBlank() })?.name.orEmpty()
