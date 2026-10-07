package com.app.market.data.remote.fdroid

/**
 * F-Droid 仓库索引缓存。P2 引入：让 F-Droid 具备完整详情（长描述/截图/分类）与
 * 更新检查能力，数据源为 f-droid.org 的 index-v2（60MB+，gzip 传输约 20MB）。
 *
 * 缓存布局（各平台提供根目录）：
 * ```
 * <root>/meta.json        索引哈希等元数据，判断新鲜度
 * <root>/summary.json     全量摘要（每应用展示信息 + 最新版本），更新检查高频读取
 * <root>/fragments/<pkg>.json  单应用切片（全部版本 + 富文本），详情/历史版本按需读取
 * ```
 *
 * 新鲜度判定：entry.json 中 index.sha256 与 meta.json 比对，不同即全量重建
 * （F-Droid 的 diff 增量补丁需要对 60MB 文档做 JSON Patch，首期不做，见工作日志）。
 */
internal interface FdroidIndexCache {
    /**
     * 仓库摘要。缓存缺失或过期时自动同步（下载索引 -> 流式重建摘要与切片）；
     * 同步失败但本地存在旧缓存时返回旧缓存，完全无数据时抛 [com.app.market.domain.exception.MarketException]。
     */
    suspend fun summary(): FdroidIndexSummary

    /** 单应用切片；索引未收录或切片缺失返回 null（调用方降级到轻量 API 路径）。 */
    suspend fun fragment(packageName: String): FdroidAppFragment?
}
