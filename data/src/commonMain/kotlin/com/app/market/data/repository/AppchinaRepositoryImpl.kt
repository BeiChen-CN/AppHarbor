package com.app.market.data.repository

import com.app.market.data.remote.appchina.AppchinaApi
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.HistoricalVersion
import com.app.market.domain.model.market.HistoricalVersionPage
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.model.update.ManualUpdateStatus
import com.app.market.domain.repository.AppchinaRepository
import com.app.market.domain.repository.InstalledPackagesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

internal class AppchinaRepositoryImpl(
    private val api: AppchinaApi,
    private val installedPackages: InstalledPackagesRepository,
) : AppchinaRepository {
    override suspend fun search(keyword: String, page: Int): SearchPage = withContext(Dispatchers.Default) {
        api.search(keyword, page)
    }

    override suspend fun appDetail(appId: Long, packageName: String): AppDetail = withContext(Dispatchers.Default) {
        api.appDetail(appId, packageName)
    }

    override suspend fun appComments(app: MarketAppInfo): AppComments = withContext(Dispatchers.Default) {
        api.appComments(app)
    }

    override suspend fun downloadMeta(app: MarketAppInfo): DownloadMeta = withContext(Dispatchers.Default) {
        api.downloadMeta(app)
    }

    override suspend fun historicalVersions(appId: Long, packageName: String, offset: Int): HistoricalVersionPage =
        withContext(Dispatchers.Default) {
            api.historicalVersions(appId, packageName, offset)
        }

    override fun historicalDownloadMeta(version: HistoricalVersion): DownloadMeta =
        api.historicalDownloadMeta(version)

    override suspend fun checkUpdates(): List<MarketAppInfo> = withContext(Dispatchers.Default) {
        val installed = installedPackages.installed()
            .filter { it.packageName.isNotBlank() && it.versionCode > 0L }
        if (installed.isEmpty()) return@withContext emptyList()
        val results = parallel(installed) { local ->
            val found = latestFor(local.packageName) ?: return@parallel null
            if (found.versionCode > local.versionCode) {
                val latest = enrich(found)
                latest.takeIf { it.versionCode > local.versionCode }?.copy(
                    installedVersionCode = local.versionCode,
                    installedVersionName = local.versionName,
                    installedSplits = local.splits,
                    isSystemApp = local.isSystemApp,
                )
            } else {
                null
            }
        }
        if (results.isNotEmpty() && results.none { it.isSuccess }) results.first().getOrThrow()
        results.mapNotNull { it.getOrNull() }
    }

    override suspend fun checkManualUpdate(request: ManualUpdateRequest): ManualUpdateResult =
        withContext(Dispatchers.Default) {
            val found = latestFor(request.packageName)
                ?: return@withContext ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
            val latest = enrich(found)
            if (latest.versionCode <= request.versionCode) {
                return@withContext ManualUpdateResult(ManualUpdateStatus.RECOGNIZED_NO_UPDATE)
            }
            ManualUpdateResult(
                ManualUpdateStatus.UPDATE_AVAILABLE,
                latest.copy(
                    installedVersionCode = request.versionCode,
                    installedVersionName = request.versionName,
                    installedSplits = request.splits,
                    isSystemApp = request.isSystemApp,
                ),
            )
        }

    private suspend fun latestFor(packageName: String): MarketAppInfo? =
        api.search(packageName, 0).items.firstOrNull {
            it.packageName.equals(packageName, ignoreCase = true) && it.versionCode > 0L
        }

    /** 搜索结果没有版本名与更新日志，命中更新后再读取一次详情；失败时退回搜索结果。 */
    private suspend fun enrich(app: MarketAppInfo): MarketAppInfo =
        try {
            api.appDetail(app.appId, app.packageName).app
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            app
        }

    private suspend fun <T, R> parallel(items: List<T>, block: suspend (T) -> R): List<Result<R>> = coroutineScope {
        items.chunked(4).flatMap { batch ->
            batch.map { item ->
                async {
                    try {
                        Result.success(block(item))
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        Result.failure(error)
                    }
                }
            }.map { it.await() }
        }
    }
}
