package com.app.market.domain.model.update

import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo

data class UpdateSelectionPolicy(
    val includePrereleases: Boolean = false,
    val preferredSources: Map<String, AppSource> = emptyMap(),
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

fun selectLatestUpdates(apps: List<MarketAppInfo>, policy: UpdateSelectionPolicy = UpdateSelectionPolicy()): List<MarketAppInfo> =
    eligibleUpdates(apps, policy).groupBy { it.packageName }.values.map { candidates ->
        val preferred = policy.preferredSources[candidates.first().packageName]
        // 固定来源只从该来源更新；暂时失败时不偷偷切换到其他渠道。
        candidates.filter { preferred == null || it.source == preferred }.maxWithOrNull { a, b ->
            val x = PublicVersion.parse(a.versionName)
            val y = PublicVersion.parse(b.versionName)
            (if (x != null && y != null) x.compareTo(y) else 0).takeIf { it != 0 }
                ?: a.versionCode.compareTo(b.versionCode).takeIf { it != 0 }
                ?: b.source.ordinal.compareTo(a.source.ordinal)
        }
    }.filterNotNull().sortedBy { it.displayName.lowercase() }

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
