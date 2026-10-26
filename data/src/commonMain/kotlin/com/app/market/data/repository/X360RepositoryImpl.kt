package com.app.market.data.repository

import com.app.market.data.remote.x360.X360Api
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.repository.InstalledPackagesRepository
import com.app.market.domain.repository.X360Repository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class X360RepositoryImpl(
    private val api: X360Api,
    private val installedPackages: InstalledPackagesRepository,
) : X360Repository {

    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        api.search(keyword, page)
    }

    override suspend fun appDetail(appId: Long, packageName: String): AppDetail = withContext(Dispatchers.Default) {
        val detail = api.appDetail(appId, packageName)
        val installed = runCatching {
            installedPackages.installedPackage(detail.app.packageName)
        }.getOrNull()

        if (installed == null) {
            detail
        } else {
            detail.copy(app = detail.app.withX360Installed(installed))
        }
    }

    override suspend fun appComments(app: MarketAppInfo): AppComments = withContext(Dispatchers.Default) {
        api.appComments(app)
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        api.downloadMeta(app)
    }
}

private fun MarketAppInfo.withX360Installed(
    installed: com.app.market.domain.model.installed.InstalledPackage,
): MarketAppInfo = copy(
    isSystemApp = installed.isSystemApp,
    installedVersionName = installed.versionName,
    installedVersionCode = installed.versionCode,
    installedOldApkHash = installed.oldApkHash,
    installedBaseApkPath = installed.baseApkPath,
    installedSplits = installed.splits,
)
