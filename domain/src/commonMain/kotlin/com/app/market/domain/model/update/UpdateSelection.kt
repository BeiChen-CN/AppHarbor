package com.app.market.domain.model.update

import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo

data class UpdateSelectionPolicy(
    val includePrereleases: Boolean = false,
    val preferredSources: Map<String, AppSource> = emptyMap(),
    /** 聚合来源优先级顺序（靠前优先）。为 null 时按枚举顺序回退。 */
    val sourceOrder: List<AppSource>? = null,
)

/** 候选必须能升级本机；商店的版本码只作安装门槛，跨源排序先比较公开版本。 */
fun eligibleUpdates(apps: List<MarketAppInfo>, policy: UpdateSelectionPolicy = UpdateSelectionPolicy()): List<MarketAppInfo> =
    apps.filter { app ->
        val version = PublicVersion.parse(app.versionName)
        val installed = PublicVersion.parse(app.installedVersionName.orEmpty())
        app.packageName.isNotBlank() && app.versionCode > app.installedVersionCode &&
            (version == null || installed == null || version >= installed) &&
            (policy.includePrereleases || version?.prerelease == null || installed?.prerelease != null)
    }.distinctBy { Triple(it.packageName, it.source, it.versionCode) }

/**
 * 每个包只保留一条更新行。
 *
 * 来源选择顺序：
 * 1. 固定来源（[UpdateSelectionPolicy.preferredSources]）只从该来源更新，失败也不偷偷切换；
 * 2. 否则先取所有候选里的最高版本，再在达到该版本的来源中按用户拖动排定的优先级
 *    （[UpdateSelectionPolicy.sourceOrder]）取最靠前的一个——即「列表最上面的来源若版本最新就用它，
 *    不是最新就依次顺移到下一个来源」；
 * 3. 没有任何来源达到最高版本时（只可能发生在版本名无法解析的混合场景），退回按版本码 + 枚举顺序择一。
 */
fun selectLatestUpdates(apps: List<MarketAppInfo>, policy: UpdateSelectionPolicy = UpdateSelectionPolicy()): List<MarketAppInfo> =
    eligibleUpdates(apps, policy).groupBy { it.packageName }.values.map { candidates ->
        val preferred = policy.preferredSources[candidates.first().packageName]
        // 固定来源只从该来源更新；暂时失败时不偷偷切换到其他渠道。
        val pool = candidates.filter { preferred == null || it.source == preferred }
        pickBySourceOrder(pool, policy.sourceOrder)
    }.filterNotNull().sortedBy { it.displayName.lowercase() }

/** 在候选里按「最高版本优先，同级按来源优先级」挑一条。 */
private fun pickBySourceOrder(
    candidates: List<MarketAppInfo>,
    sourceOrder: List<AppSource>?,
): MarketAppInfo? {
    if (candidates.isEmpty()) return null
    val newest = candidates.reduce { a, b -> if (compareVersion(a, b) >= 0) a else b }
    val tied = candidates.filter { compareVersion(it, newest) == 0 }
    if (tied.size == 1) return tied.single()
    if (sourceOrder.isNullOrEmpty()) {
        // 无自定义顺序时保持旧行为：枚举顺序靠前者胜出。
        return tied.maxByOrNull { -it.source.ordinal } ?: tied.first()
    }
    val rank = sourceOrder.withIndex().associate { (index, source) -> source to index }
    return tied.minByOrNull { rank[it.source] ?: Int.MAX_VALUE } ?: tied.first()
}

private fun compareVersion(a: MarketAppInfo, b: MarketAppInfo): Int {
    val x = PublicVersion.parse(a.versionName)
    val y = PublicVersion.parse(b.versionName)
    val byName = if (x != null && y != null) x.compareTo(y) else 0
    if (byName != 0) return byName
    return a.versionCode.compareTo(b.versionCode)
}

/** 兼容常见 Android 版本名；构建元数据不参与比较，测试后缀及数字段参与比较。 */
internal data class PublicVersion(val core: List<String>, val prerelease: List<String>?) : Comparable<PublicVersion> {
    override fun compareTo(other: PublicVersion): Int {
        for (i in 0 until maxOf(core.size, other.core.size)) {
            compareNumber(core.getOrElse(i) { "0" }, other.core.getOrElse(i) { "0" }).takeIf { it != 0 }?.let { return it }
        }
        if (prerelease == null) return if (other.prerelease == null) 0 else 1
        if (other.prerelease == null) return -1
        for (i in 0 until maxOf(prerelease.size, other.prerelease.size)) {
            val a = prerelease.getOrNull(i) ?: return -1
            val b = other.prerelease.getOrNull(i) ?: return 1
            val aNumeric = a.all(Char::isDigit)
            val bNumeric = b.all(Char::isDigit)
            val order = when {
                aNumeric && bNumeric -> compareNumber(a, b)
                aNumeric -> -1
                bNumeric -> 1
                else -> a.compareTo(b)
            }
            if (order != 0) return order
        }
        return 0
    }

    companion object {
        private val pattern = Regex("""^[vV]?(\d+(?:\.\d+)*)(?:[- ]?([a-zA-Z][0-9a-zA-Z.-]*)|-([0-9][0-9a-zA-Z.-]*))?(?:\+[0-9a-zA-Z.-]+)?$""")
        fun parse(raw: String): PublicVersion? {
            val match = pattern.matchEntire(raw.trim()) ?: return null
            val suffix = match.groupValues[2].ifBlank { match.groupValues[3] }
            val identifiers = suffix.takeIf { it.isNotBlank() }?.let {
                Regex("[a-zA-Z]+|[0-9]+").findAll(it).map { token -> token.value.lowercase() }.toList()
            }
            return PublicVersion(match.groupValues[1].split('.'), identifiers)
        }

        private fun compareNumber(left: String, right: String): Int {
            val a = left.trimStart('0').ifEmpty { "0" }
            val b = right.trimStart('0').ifEmpty { "0" }
            return a.length.compareTo(b.length).takeIf { it != 0 } ?: a.compareTo(b)
        }
    }
}
