package com.app.market.data.repository

import com.app.market.data.remote.box7723.Box7723Api
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.repository.Box7723Repository
import com.app.market.domain.repository.InstalledPackagesRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class Box7723RepositoryImpl(
    private val api: Box7723Api,
    private val installedPackages: InstalledPackagesRepository,
) : Box7723Repository {

    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        api.search(keyword, page)
    }

    override suspend fun appDetail(appId: Long): AppDetail = withContext(Dispatchers.Default) {
        val detail = api.appDetail(appId)
        val installed = runCatching {
            installedPackages.installedPackage(detail.app.packageName)
        }.getOrNull()

        if (installed == null) {
            detail
        } else {
            detail.copy(app = detail.app.withInstalled(installed))
        }
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        api.downloadMeta(app)
    }
}

private fun MarketAppInfo.withInstalled(installed: com.app.market.domain.model.installed.InstalledPackage): MarketAppInfo = copy(
    isSystemApp = installed.isSystemApp,
    installedVersionName = installed.versionName,
    installedVersionCode = installed.versionCode,
    installedOldApkHash = installed.oldApkHash,
    installedBaseApkPath = installed.baseApkPath,
    installedSplits = installed.splits,
)
