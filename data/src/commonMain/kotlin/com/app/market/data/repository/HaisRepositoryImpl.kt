package com.app.market.data.repository

import com.app.market.data.remote.hais.HaisApi
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.repository.HaisRepository
import com.app.market.domain.repository.InstalledPackagesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * HAIS APK 归档站仓储：薄封装 [HaisApi]，并在详情路径合并本地安装态。
 *
 * HAIS 以包名为主键、[MarketAppInfo.appId] 恒为 0，`source` 字段不在此处赋值，
 * 统一由上层路由按来源覆盖。
 */
internal class HaisRepositoryImpl(
    private val api: HaisApi,
    private val installedPackages: InstalledPackagesRepository,
) : HaisRepository {

    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        api.search(keyword, page)
    }

    override suspend fun appDetail(packageName: String): AppDetail = withContext(Dispatchers.Default) {
        val detail = api.appDetail(packageName)
        val installed = runCatching {
            installedPackages.installedPackage(detail.app.packageName)
        }.getOrNull()

        if (installed == null) {
            detail
        } else {
            detail.copy(app = detail.app.withHaisInstalled(installed))
        }
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        api.downloadMeta(app)
    }

    override suspend fun historicalVersions(packageName: String, offset: Int): HistoricalVersionPage =
        withContext(Dispatchers.Default) {
            api.historicalVersions(packageName, offset)
        }

    override suspend fun historicalDownloadMeta(version: HistoricalVersion): DownloadMeta =
        withContext(Dispatchers.Default) {
            api.historicalDownloadMeta(version)
        }
}

/** 合并本地安装态；本文件独立命名以避免与同包其他仓储的私有扩展重名。 */
private fun MarketAppInfo.withHaisInstalled(installed: InstalledPackage): MarketAppInfo = copy(
    isSystemApp = installed.isSystemApp,
    installedVersionName = installed.versionName,
    installedVersionCode = installed.versionCode,
    installedOldApkHash = installed.oldApkHash,
    installedBaseApkPath = installed.baseApkPath,
    installedSplits = installed.splits,
)
