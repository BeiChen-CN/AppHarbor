package com.app.market.data.remote.xiaomi

import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.platform.debugLog
import com.app.market.data.remote.xiaomi.platform.XiaomiDeviceIdentityDataSource
import com.app.market.data.remote.xiaomi.preferences.XiaomiIdentityPreferenceKeys
import com.app.market.domain.exception.AppNotListedException
import com.app.market.domain.exception.MarketException
import com.app.market.domain.model.download.DownloadMeta
import com.app.market.domain.model.download.DownloadPart
import com.app.market.domain.model.download.DownloadPatch
import com.app.market.domain.model.installed.InstalledPackage
import com.app.market.domain.model.market.AppComment
import com.app.market.domain.model.market.AppComments
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppPromotion
import com.app.market.domain.model.market.AppScreenshot
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.AppVideo
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.ScreenshotOrientation
import com.app.market.domain.model.market.SearchPage
import com.app.market.domain.model.market.hasInstalledSplits
import com.app.market.domain.model.profile.MarketProfile
import com.app.market.domain.model.today.TodayArticle
import com.app.market.domain.model.today.TodayArticleBlock
import com.app.market.domain.model.today.TodayFeaturedItem
import com.app.market.domain.model.today.TodayFeedPage
import com.app.market.domain.model.update.ManualUpdateRequest
import com.app.market.domain.model.update.ManualUpdateResult
import com.app.market.domain.model.update.ManualUpdateStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

private val todayImageTag = Regex(
    """<img\b[^>]*\bsrc\s*=\s*["']([^"']+)["'][^>]*>""",
    RegexOption.IGNORE_CASE,
)

internal class XiaomiApi(
    private val preferences: PreferencesDataSource,
    private val updateInfoCache: UpdateInfoCache,
    private val identityProvider: XiaomiDeviceIdentityDataSource,
    private val http: XiaomiHttpClient,
    private val xiaomiClient: XiaomiClient,
) {
    private val MARKET = "https://app.market.xiaomi.com/apm"
    private val WEB_CATEGORY = "https://app.mi.com/categotyAllListApi"
    private val EXP_ID = "$MARKET/expId"
    private val UPDATE = "https://updateinfo.market.xiaomi.com/apm/updateinfo/v2?lo=CN"
    private val DIFF_SIZE = "https://updateinfo.market.xiaomi.com/apm/updateinfo/diffsize?lo=CN"
    private val THUMB = "https://sf0.market.xiaomi.com/thumbnail/"
    private val DEFAULT_XMSF_VERSION = "70005022"

    /** 服务端最新版本号，空串表示未取到。 */
    data class ServerVersions(val webResVersion: String, val pageConfigVersion: String)

    /** 拉取 /apm/config 取最新 webResVersion/pageConfigVersion（发 0 强制返最新，只取版本号不下载）。 */
    suspend fun syncServerVersions(profile: MarketProfile): ServerVersions? {
        // Official Market obtains the encrypted dctx from /expId before using it as a base parameter.
        refreshDctx(profile, "")
        val params = commonParams(profile).toMutableMap()
        params.putAll(
            mapOf(
                "resourceVersionCode" to "0", "tabVersionCode" to "0", "clientConfigVersionCode" to "0",
                "productName" to "mimarket", "subscribeGameTab" to "1", "lastShowRankTime" to "0",
                "resourcePageMd5" to "",
            )
        )
        val url = XiaomiSigner.signedUrl("$MARKET/config?${query(params)}")
        val json = runCatching { parseJsonObject(http.get(url, "")) }.getOrNull() ?: return null
        val webRes = json.obj("webResourceInfo").int("versionCode", 0).takeIf { it > 0 }?.toString().orEmpty()
        val tabValue = json.obj("tabInfo").str("value")
        val pageConfig = if (tabValue.isNotBlank()) {
            runCatching { parseJsonObject(tabValue) }.getOrNull().long("versionCode").takeIf { it > 0 }?.toString().orEmpty()
        } else {
            ""
        }
        if (webRes.isBlank() && pageConfig.isBlank()) return null
        return ServerVersions(webRes, pageConfig)
    }

    suspend fun search(
        keyword: String,
        page: Int,
        profile: MarketProfile,
        cookie: String,
        removeSearchAds: Boolean,
    ): SearchPage {
        val params = commonParams(profile).toMutableMap()
        params.putAll(
            mapOf(
                "bottomTab" to "true", "flag" to "2", "feReload" to "false", "isNewUI" to "true",
                "ref" to "input", "responseType" to "1", "searchFrom" to "input", "minacompatible" to "1",
                "native" to "1", "keyword" to keyword, "renderType" to "1", "pageRef" to "com.miui.home",
                "showGoogleAppsType" to "2", "sourcePackage" to "com.miui.home",
                "supportSlide" to "1", "previousFromRef" to "searchSuggest", "voiceAssistVersion" to "507513001",
                "suggestV" to "1", "isSupportCreative" to "true", "refs" to "input-searchResult",
                "search_session" to "${profile.instanceId}${epochMillis()}", "page" to page.toString()
            )
        )
        val url = XiaomiSigner.signedUrl("$MARKET/search?${query(params)}")
        return parseSearch(parseJsonObject(http.get(url, cookie)), removeSearchAds)
    }

    /** Public web listing; unsigned and device-independent, so no profile/cookie is needed. */
    suspend fun categoryApps(categoryId: Int, page: Int): SearchPage {
        val url = "$WEB_CATEGORY?${query(mapOf("page" to page.toString(), "categoryId" to categoryId.toString(), "pageSize" to "30"))}"
        return parseCategoryApps(parseJsonObject(http.get(url, "")))
    }

    suspend fun downloadMeta(app: MarketAppInfo, keyword: String, profile: MarketProfile, cookie: String): DownloadMeta {
        val targetVersionCode = app.versionCode.takeIf { it > 0L }
        val installedVersionCode = app.installedVersionCode.takeIf { it > 0L }
        val retryVersionCode = previousVersionCode(installedVersionCode)
        var requestVersionCode = initialDownloadRequestVersionCode(installedVersionCode, targetVersionCode)
        var parseTargetVersionCode = targetVersionCode
            .takeUnless { requestVersionCode != null && requestVersionCode == retryVersionCode }
        logReducedDownloadRequest("detail", app, targetVersionCode, installedVersionCode, requestVersionCode)
        var json = downloadMetaJson(
            app = app,
            keyword = keyword,
            profile = profile,
            cookie = cookie,
            targetVersionCode = targetVersionCode,
            requestVersionCode = requestVersionCode,
            scene = DownloadScene.DETAIL,
        )
        if (shouldRetryWithPreviousInstalledVersion(json, targetVersionCode, requestVersionCode) &&
            requestVersionCode != retryVersionCode
        ) {
            debugLog("XiaomiApi") {
                "retry detail download meta package=${app.packageName} target=$targetVersionCode " +
                        "response=${json.long("versionCode", 0L)} versionCode=$requestVersionCode retryVersionCode=$retryVersionCode"
            }
            requestVersionCode = retryVersionCode
            parseTargetVersionCode = null
            json = downloadMetaJson(
                app = app,
                keyword = keyword,
                profile = profile,
                cookie = cookie,
                targetVersionCode = targetVersionCode,
                requestVersionCode = requestVersionCode,
                scene = DownloadScene.DETAIL,
            )
        }
        if (isAlreadyAtDownloadTarget(json) && requestVersionCode != null) {
            requestVersionCode = retryVersionCode ?: requestVersionCode
            parseTargetVersionCode = null
            json = downloadMetaJson(
                app = app,
                keyword = keyword,
                profile = profile,
                cookie = cookie,
                targetVersionCode = null,
                requestVersionCode = requestVersionCode,
                scene = DownloadScene.DETAIL,
            )
        }
        if (shouldRetryWithPreviousInstalledVersion(json, targetVersionCode, requestVersionCode) &&
            requestVersionCode != retryVersionCode
        ) {
            debugLog("XiaomiApi") {
                "retry fallback detail download meta package=${app.packageName} target=$targetVersionCode " +
                        "response=${json.long("versionCode", 0L)} versionCode=$requestVersionCode retryVersionCode=$retryVersionCode"
            }
            requestVersionCode = retryVersionCode
            parseTargetVersionCode = null
            json = downloadMetaJson(
                app = app,
                keyword = keyword,
                profile = profile,
                cookie = cookie,
                targetVersionCode = null,
                requestVersionCode = requestVersionCode,
                scene = DownloadScene.DETAIL,
            )
        }
        return parseDownloadMeta(app, json, parseTargetVersionCode, downloadRef = "detail")
    }

    suspend fun downloadUpdateMeta(app: MarketAppInfo, profile: MarketProfile, cookie: String): DownloadMeta {
        val requestedTargetVersionCode = app.versionCode.takeIf { it > 0 }
        var parseTargetVersionCode = requestedTargetVersionCode
        val installedVersionCode = app.installedVersionCode.takeIf { it > 0L }
        val retryVersionCode = previousVersionCode(installedVersionCode)
        var requestVersionCode = initialDownloadRequestVersionCode(installedVersionCode, requestedTargetVersionCode)
        if (requestVersionCode != null && requestVersionCode == retryVersionCode) {
            parseTargetVersionCode = null
        }
        logReducedDownloadRequest("update", app, requestedTargetVersionCode, installedVersionCode, requestVersionCode)
        var json = downloadMetaJson(
            app = app,
            keyword = app.displayName,
            profile = profile,
            cookie = cookie,
            targetVersionCode = requestedTargetVersionCode,
            requestVersionCode = requestVersionCode,
            scene = DownloadScene.UPDATE,
        )
        if (shouldRetryWithPreviousInstalledVersion(json, requestedTargetVersionCode, requestVersionCode) &&
            requestVersionCode != retryVersionCode
        ) {
            debugLog("XiaomiApi") {
                "retry update download meta package=${app.packageName} target=$requestedTargetVersionCode " +
                        "response=${json.long("versionCode", 0L)} versionCode=$requestVersionCode retryVersionCode=$retryVersionCode"
            }
            requestVersionCode = retryVersionCode
            parseTargetVersionCode = null
            json = downloadMetaJson(
                app = app,
                keyword = app.displayName,
                profile = profile,
                cookie = cookie,
                targetVersionCode = requestedTargetVersionCode,
                requestVersionCode = requestVersionCode,
                scene = DownloadScene.UPDATE,
            )
        }
        if (isAlreadyAtDownloadTarget(json) && app.isSystemApp && app.installedVersionCode > 0L) {
            requestVersionCode = retryVersionCode ?: requestVersionCode
            parseTargetVersionCode = null
            json = downloadMetaJson(
                app = app,
                keyword = app.displayName,
                profile = profile,
                cookie = cookie,
                targetVersionCode = null,
                requestVersionCode = requestVersionCode,
                scene = DownloadScene.DETAIL,
            )
        }
        if (shouldRetryWithPreviousInstalledVersion(json, requestedTargetVersionCode, requestVersionCode) &&
            requestVersionCode != retryVersionCode
        ) {
            debugLog("XiaomiApi") {
                "retry fallback update download meta package=${app.packageName} target=$requestedTargetVersionCode " +
                        "response=${json.long("versionCode", 0L)} versionCode=$requestVersionCode retryVersionCode=$retryVersionCode"
            }
            requestVersionCode = retryVersionCode
            parseTargetVersionCode = null
            json = downloadMetaJson(
                app = app,
                keyword = app.displayName,
                profile = profile,
                cookie = cookie,
                targetVersionCode = null,
                requestVersionCode = requestVersionCode,
                scene = DownloadScene.DETAIL,
            )
        }
        debugLog("XiaomiApi") {
            "download update meta package=${app.packageName} system=${app.isSystemApp} " +
                    "installed=${app.installedVersionCode} target=${app.versionCode} apks=${json.arr("apks").len} " +
                    "unfitness=${json.int("unfitnessType", -1)} desc=${json.str("unfitnessDesc").take(80)}"
        }
        return parseDownloadMeta(app, json, parseTargetVersionCode, downloadRef = "upgrade")
    }

    private fun shouldRetryWithPreviousInstalledVersion(
        json: JsonObject,
        targetVersionCode: Long?,
        requestVersionCode: Long?,
    ): Boolean {
        val target = targetVersionCode ?: return false
        val requestVersion = requestVersionCode ?: return false
        if (requestVersion <= 1L) return false
        if (json.arr("apks").len == 0) return false
        val responseVersionCode = json.long("versionCode", 0L)
        return responseVersionCode in 1 until target
    }

    private fun previousVersionCode(versionCode: Long?): Long? =
        versionCode?.minus(1)?.takeIf { it > 0L }

    private fun initialDownloadRequestVersionCode(installedVersionCode: Long?, targetVersionCode: Long?): Long? =
        if (installedVersionCode != null && targetVersionCode != null && installedVersionCode >= targetVersionCode) {
            previousVersionCode(installedVersionCode) ?: installedVersionCode
        } else {
            installedVersionCode
        }

    private fun logReducedDownloadRequest(
        scene: String,
        app: MarketAppInfo,
        targetVersionCode: Long?,
        installedVersionCode: Long?,
        requestVersionCode: Long?,
    ) {
        if (installedVersionCode == null || requestVersionCode == null || installedVersionCode == requestVersionCode) return
        debugLog("XiaomiApi") {
            "reduced $scene download meta package=${app.packageName} target=$targetVersionCode " +
                    "installed=$installedVersionCode requestVersionCode=$requestVersionCode"
        }
    }

    private fun parseDownloadMeta(
        app: MarketAppInfo,
        json: JsonObject,
        targetVersionCode: Long? = null,
        downloadRef: String = "detail",
    ): DownloadMeta {
        val expectedVersionCode = targetVersionCode?.takeIf { it > 0L } ?: app.versionCode
        val responseVersionCode = json.long("versionCode", expectedVersionCode)
        val responseVersionName = json.str("versionName", app.versionName)
        if (targetVersionCode != null && expectedVersionCode > 0 && responseVersionCode > 0 && responseVersionCode != expectedVersionCode) {
            throw MarketException("服务器未开放该版本 APK: 目标 $expectedVersionCode, 返回 $responseVersionName($responseVersionCode)")
        }
        val hosts = json.arr("hosts")
        val apks = json.arr("apks") ?: JsonArray(emptyList())
        if (apks.len == 0) {
            val reason = json.str("unfitnessDesc")
                .ifBlank { json.str("errorDesc") }
                .ifBlank { json.str("message") }
            throw MarketException(
                if (reason.isBlank()) "下载失败: 无 APK 包体" else "下载失败: $reason"
            )
        }
        val host = hosts.strAt(0).ifBlank { json.str("host", "https://fgb0.market.xiaomi.com/download/") }
        val patchVersion = json.int("bspatchVersion")
        // Dynamic-feature records can precede the canonical base entry. Keep base/base first so
        // the installer always maps the actual base APK to base.apk.
        val parts = canonicalizeXiaomiParts(
            downloadParts(apks, host, patchVersion, app.installedOldApkHash)
        )
        val primary = primaryDownloadPart(parts)
        val fullDownloadSize = parts.sumOf { it.size }.takeIf { it > 0L }
            ?: json.long("apkSizeV2", json.long("apkSize", primary.size))
        return DownloadMeta(
            appId = app.appId,
            packageName = json.str("packageName", app.packageName),
            displayName = app.displayName,
            versionName = responseVersionName.ifBlank { app.versionName },
            versionCode = responseVersionCode.takeIf { it > 0 } ?: expectedVersionCode,
            url = primary.url,
            size = fullDownloadSize,
            parts = parts,
            installedBaseApkPath = app.installedBaseApkPath,
            icon = app.icon,
            changeLog = app.changeLog,
            requestHeaders = xiaomiClient.downloadHeaders(downloadRef),
            source = AppSource.XIAOMI,
        )
    }

    private suspend fun downloadMetaJson(
        app: MarketAppInfo,
        keyword: String,
        profile: MarketProfile,
        cookie: String,
        targetVersionCode: Long?,
        requestVersionCode: Long?,
        scene: DownloadScene,
    ): JsonObject {
        val params = commonParams(profile).toMutableMap()
        val sceneParams = when (scene) {
            DownloadScene.DETAIL -> mapOf(
                "ad" to "0", "appId" to app.appId.toString(), "bundleType" to "main",
                "callerPackage" to "com.xiaomi.market", "callerSignature" to "88daa889de21a80bca64464243c9ede6",
                "downloadGrantType" to "0", "downloadImmediately" to "false", "launchWhenInstalled" to "false",
                "entrance" to "detail", "pageTag" to "detail", "keyword" to keyword,
                "pName" to app.packageName,
                "packageName" to app.packageName, "pageRef" to "com.miui.home", "originalPageRef" to "com.miui.home",
                "pos" to "detailInstallBtn", "ref" to "detail", "refPosition" to "0",
                "refs" to "input-searchResult-detail/${app.appId}",
                "releaseType" to "0", "sid" to "${profile.instanceId}default",
                "senderPackageName" to "com.xiaomi.market", "sourcePackage" to "com.xiaomi.market",
                "supportCompressType" to "1", "supportModifyUrl" to "true", "supportSdm" to "true",
                "supportSpeedInstall" to "true", "supportCloudProfile" to "true", "supportCloudVerify" to "true",
                "isLightToFull" to "false", "useCache" to "false", "previousFromRef" to "detailInstallBtn"
            )

            DownloadScene.UPDATE -> mapOf(
                "ad" to "0", "appId" to app.appId.toString(), "autoUpdateEnabled" to "false",
                "bundleType" to "main", "downloadGrantType" to "0", "kcgsdk" to "false",
                "lastUseTime" to "0", "oldApkHash" to app.installedOldApkHash,
                "pName" to app.packageName, "pageRef" to "com.miui.home",
                "ref" to "upgrade", "refPosition" to "0", "sourcePackage" to "com.miui.home",
                "supportCloudProfile" to "true", "supportCloudVerify" to "true",
                "supportCompressType" to "1", "supportModifyUrl" to "true", "supportSdm" to "true",
                "supportSpeedInstall" to "true", "taskStartTime" to epochMillis().toString(),
                "useCache" to "false", "versionCode" to app.installedVersionCode.toString(),
                "versionName" to app.installedVersionName,
                "card_position" to "0", "item_position" to "0",
                "pre_rerank_card_position" to "0", "pre_rerank_item_position" to "0",
                "session_id" to "${profile.instanceId}${epochMillis()}"
            )
        }
        params.putAll(sceneParams)
        if (targetVersionCode != null) params["targetVersionCode"] = targetVersionCode.toString()
        if (requestVersionCode != null) params["versionCode"] = requestVersionCode.toString()
        return parseJsonObject(http.get(XiaomiSigner.signedUrl("$MARKET/download/${app.appId}?${query(params)}"), cookie))
    }

    private enum class DownloadScene {
        DETAIL,
        UPDATE,
    }

    private fun isAlreadyAtDownloadTarget(json: JsonObject): Boolean =
        json.arr("apks").len == 0 &&
                json.int("unfitnessType", 0) == 1 &&
                json.str("unfitnessDesc").contains("下载版本")

    suspend fun appDetail(
        appId: Long,
        packageName: String,
        installedVersionCode: Long = 0L,
        profile: MarketProfile,
        cookie: String,
        externalQuery: String? = null,
    ): AppDetail {
        val detailJson = appDetailJson(appId, packageName, installedVersionCode, profile, cookie, externalQuery)
        val appInfo = requireListedAppInfo(detailJson)
        val app = parseApp(appInfo, installedVersionCode)
        val briefShow = appInfoTabData(detailJson).obj("detailTabBriefShow")
        val detailMedia = parseDetailMedia(
            json = detailJson,
            resolveUrl = { imageUrl(it, "l720q80") },
            resolveExpandedUrl = { imageUrl(it, "q90") },
        )
        return AppDetail(
            app = app,
            brief = appInfo.str("briefShow", briefShow.str("briefShow")),
            introduction = briefShow.str("introduction"),
            changeLog = briefShow.str("changeLog", app.changeLog).ifBlank { app.changeLog },
            category = listOf(appInfo.str("level1CategoryName"), appInfo.str("level2CategoryName"))
                .filter { it.isNotBlank() }
                .joinToString(" / "),
            ageClassification = parseAgeClassification(appInfo),
            downloadCount = appInfo.long("downloadCount"),
            registrationNum = appInfo.str("registrationNum", briefShow.str("registrationNum"))
                .takeUnless { it.contains("暂未查询") }
                .orEmpty(),
            updateTime = epochMillis(appInfo.long("updateTime", briefShow.long("updateTime"))),
            privacyUrl = appInfo.obj("extraData").str("privacyUrl").ifBlank { briefShow.str("privacyUrl") },
            screenshots = detailMedia.screenshots,
            videos = detailMedia.videos,
            commentCount = parseDetailRatingCount(appInfo),
            comments = emptyList(),
            sameDeveloperApps = emptyList(),
            promotions = detailMedia.promotions,
        )
    }

    suspend fun appDetailJson(
        appId: Long,
        packageName: String,
        installedVersionCode: Long = 0L,
        profile: MarketProfile,
        cookie: String,
        externalQuery: String? = null,
    ): JsonObject {
        val detailParams = commonParams(profile).toMutableMap()
        detailParams.putAll(
            mapOf(
                "bottomTab" to "true", "feReload" to "false", "isNewUI" to "true",
                "ref" to "detail", "minacompatible" to "1", "native" to "1",
                "entrance" to "detail", "pageTag" to "detail", "bundleType" to "main",
                "pageRef" to "com.miui.home", "originalPageRef" to "com.miui.home",
                "sourcePackage" to "com.xiaomi.market", "senderPackageName" to "com.xiaomi.market",
                "callerPackage" to "com.xiaomi.market", "callerSignature" to "88daa889de21a80bca64464243c9ede6",
                "previousFromRef" to "searchResult", "suggestV" to "1", "supportSlide" to "1",
                "pos" to "detailInstallBtn", "sid" to "${profile.instanceId}default",
                "packageName" to packageName,
                "oldVersionCode" to installedVersionCode.coerceAtLeast(0L).toString(),
                "releaseType" to "0",
                "refs" to "input-searchResult-detail/$appId",
                "supportH5" to "2", "supportBetaApp" to "true", "supportGameLottery" to "true",
                "supportCorpInternal" to "true", "supportSmallApk" to "true",
                "safeModeCheck" to "false", "safeModeType" to "0",
                "gameCenterVersionCode" to "135100100", "minaPlatformVersion" to "13170003",
                "voiceAssistVersion" to "507513001",
            )
        )
        if (externalQuery == null) {
            detailParams["personalAssistantVersion"] = "253081"
        } else {
            detailParams.putAll(externalDetailRequestParams(externalQuery))
        }
        return parseJsonObject(
            http.get(XiaomiSigner.signedUrl("$MARKET/app/tabs/basicInfo/$appId?${query(detailParams)}"), cookie)
        )
    }

    fun checkUpdatesFlow(
        installed: List<InstalledPackage>,
        profile: MarketProfile,
        cookie: String,
        security: String,
        resolveOldApkHashes: suspend (List<MarketAppInfo>) -> List<MarketAppInfo> = { it },
        deltaUpdatesEnabled: Boolean = true,
    ): Flow<List<MarketAppInfo>> = flow {
        val eligible = installed.filter { it.packageName != "com.app.market" }
        if (eligible.isEmpty()) {
            emit(emptyList())
            return@flow
        }
        val localByPackage = eligible.associateBy(InstalledPackage::packageName)
        val result = requestUpdateInfo(
            packages = withMiuiUpdateAnchor(eligible.sortedBy(InstalledPackage::packageName)),
            localByPackage = localByPackage,
            profile = profile,
            cookie = cookie,
            security = security,
        )
        val merged = mergeUpdateCandidates(result.updates)
        emit(merged)
        val hashReady = if (deltaUpdatesEnabled) resolveOldApkHashes(merged) else merged.map {
            it.copy(installedOldApkHash = "0", deltaSize = 0L)
        }
        if (hashReady != merged) emit(hashReady)
        if (!deltaUpdatesEnabled) return@flow
        val withDeltaSizes = withDeltaSizes(hashReady, profile, cookie, security)
        if (withDeltaSizes != hashReady) emit(withDeltaSizes)
    }

    suspend fun checkManualUpdate(
        request: ManualUpdateRequest,
        profile: MarketProfile,
        cookie: String,
        security: String,
    ): ManualUpdateResult {
        val target = InstalledPackage(
            packageName = request.packageName,
            versionCode = request.versionCode,
            versionName = request.versionName,
            isSystemApp = request.isSystemApp,
            installedBy = request.installedBy.ifBlank { "0" },
            splits = request.splits.ifBlank { "0" },
            oldApkHash = request.oldApkHash.ifBlank { "0" },
            apkSource = request.apkSource.ifBlank { "0" },
        )
        val packages = withMiuiUpdateAnchor(listOf(target))
        val local = mapOf(target.packageName to target)
        val result = requestUpdateInfo(packages, local, profile, cookie, security)
        val targetUpdate = result.updates
            .filter { it.packageName == target.packageName }
            .maxByOrNull(MarketAppInfo::versionCode)
        val recognized = target.packageName in result.recognizedPackages
        return when {
            targetUpdate != null -> ManualUpdateResult(ManualUpdateStatus.UPDATE_AVAILABLE, targetUpdate)
            recognized -> ManualUpdateResult(ManualUpdateStatus.RECOGNIZED_NO_UPDATE)
            else -> ManualUpdateResult(ManualUpdateStatus.NOT_FOUND)
        }
    }

    private suspend fun requestUpdateInfo(
        packages: List<InstalledPackage>,
        localByPackage: Map<String, InstalledPackage>,
        profile: MarketProfile,
        cookie: String,
        security: String,
    ): UpdateInfoResult {
        val fields = updateInfoRequestFields(
            common = commonParams(profile),
            androidVersion = profile.androidVersion,
            instanceId = profile.instanceId,
            packages = packages,
            invalidSystemPackageHash = updateInfoCache.invalidSystemPackageHash(),
            timestamp = epochMillis(),
        )
        XiaomiSigner.signFormForUrl(UPDATE, fields)
        val postUrl = XiaomiSigner.signedPostUrl(UPDATE, fields, security)
        val response = parseJsonObject(http.postForm(postUrl, query(fields), cookie))
        logUpdateInfoSummary(packages, fields, response, postUrl.contains("signature="))
        updateInfoCache.updateInvalidSystemPackageHash(response)
        val serverApps = buildList {
            addAll(parseApps(response.arr("listApp") ?: JsonArray(emptyList()), isSystemApp = false))
            addAll(parseApps(response.arr("miuiApp") ?: JsonArray(emptyList()), isSystemApp = true))
        }
        val recognized = buildSet {
            addAll(serverApps.map(MarketAppInfo::packageName))
            response.arr("support64Pkgs")?.let { values ->
                for (index in 0 until values.len) values.strAt(index).takeIf(String::isNotBlank)?.let(::add)
            }
        }
        val updates = serverApps.filter { server ->
            val local = localByPackage[server.packageName]
            local != null && local.versionCode < server.versionCode
        }.map { server ->
            val local = localByPackage[server.packageName] ?: return@map server
            server.copy(
                installedVersionName = local.versionName,
                installedVersionCode = local.versionCode,
                installedOldApkHash = local.oldApkHash,
                installedBaseApkPath = local.baseApkPath,
                installedSplits = local.splits,
                isSystemApp = local.isSystemApp,
            )
        }
        return UpdateInfoResult(updates, recognized)
    }

    private fun mergeUpdateCandidates(updates: List<MarketAppInfo>): List<MarketAppInfo> =
        updates.groupBy(MarketAppInfo::packageName).values.map { candidates ->
            candidates.maxWith(compareBy<MarketAppInfo>(MarketAppInfo::versionCode).thenBy(MarketAppInfo::isSystemApp))
        }

    private suspend fun withDeltaSizes(
        updates: List<MarketAppInfo>,
        profile: MarketProfile,
        cookie: String,
        security: String,
    ): List<MarketAppInfo> {
        val candidates = updates.filter {
            it.installedVersionCode > 0L && it.installedOldApkHash.isNotBlank() &&
                    it.installedOldApkHash != "0" && !it.hasInstalledSplits()
        }
        if (candidates.isEmpty()) return updates
        val firstAttempt = requestDeltaSizes(candidates, profile, cookie, security)
        val sizes = firstAttempt ?: run {
            debugLog("XiaomiApi") { "diffsize response unavailable; refreshing device context and retrying" }
            runCatching { refreshDctx(profile, cookie) }
                .onFailure { error -> debugLog("XiaomiApi") { "diffsize context refresh failed: ${error.message}" } }
            requestDeltaSizes(candidates, profile, cookie, security)
        }
        if (sizes.isNullOrEmpty()) return updates
        return updates.map { app -> sizes[app.packageName]?.let { app.copy(deltaSize = it) } ?: app }
    }

    private suspend fun requestDeltaSizes(
        candidates: List<MarketAppInfo>,
        profile: MarketProfile,
        cookie: String,
        security: String,
    ): Map<String, Long>? {
        val fields = deltaSizeRequestFields(
            common = commonParams(profile),
            instanceId = profile.instanceId,
            candidates = candidates,
            timestamp = epochMillis(),
        )
        XiaomiSigner.signFormForUrl(DIFF_SIZE, fields)
        val postUrl = XiaomiSigner.signedPostUrl(DIFF_SIZE, fields, security)
        val response = runCatching {
            parseJsonObject(http.postForm(postUrl, query(fields), cookie))
        }.onFailure { error ->
            debugLog("XiaomiApi") { "diffsize failed: ${error.message}" }
        }.getOrNull() ?: return null
        return parseDeltaSizeResponse(response)
    }

    private suspend fun refreshDctx(profile: MarketProfile, cookie: String) {
        val identity = identityProvider.identity()
        val params = commonParams(profile).toMutableMap()
        params["xmsfVersion"] = identity.xmsfVersion.ifBlank { DEFAULT_XMSF_VERSION }
        val response = runCatching {
            parseJsonObject(http.get(XiaomiSigner.signedUrl("$EXP_ID?${query(params)}"), cookie))
        }.getOrNull() ?: return
        response.str("dctx").takeIf(String::isNotBlank)?.let {
            preferences.put(XiaomiIdentityPreferenceKeys.ServerDeviceContext, it)
        }
        response.str("exp_id").ifBlank { response.str("expId") }.takeIf(String::isNotBlank)?.let {
            preferences.put(XiaomiIdentityPreferenceKeys.ExperimentId, it)
        }
    }

    private data class UpdateInfoResult(
        val updates: List<MarketAppInfo> = emptyList(),
        val recognizedPackages: Set<String> = emptySet(),
    )

    private fun logUpdateInfoSummary(
        packages: List<InstalledPackage>,
        fields: Map<String, String>,
        json: JsonObject,
        hasUrlSignature: Boolean,
    ) {
        val miui = json.arr("miuiApp")
        val list = json.arr("listApp")
        val names = buildList {
            for (i in 0 until (miui?.len ?: 0)) {
                val app = miui?.objAt(i) ?: continue
                add(app.str("packageName").ifBlank { app.str("displayName") })
            }
        }.take(12)
        debugLog("XiaomiApi") {
            "updateinfo result pkgs=${packages.size} sys=${packages.count { it.isSystemApp }} " +
                    "hasMiui=${miui != null} miui=${miui?.len ?: 0} list=${list?.len ?: 0} " +
                    "dctx=${fields.containsKey("dctx")} tz=${fields.containsKey("tzNonce") && fields.containsKey("tzSign")} " +
                    "useExp=${fields["useExpId"]?.take(48)} urlSignature=$hasUrlSignature miuiPackages=$names"
        }
    }

    suspend fun appComments(
        appId: Long,
        versionCode: Long,
        profile: MarketProfile,
        cookie: String,
    ): AppComments {
        val params = commonParams(profile).toMutableMap()
        params.putAll(
            mapOf(
                "bottomTab" to "true", "feReload" to "false", "isNewUI" to "true",
                "sourcePackage" to "com.app.market", "previousFromRef" to "detail/$appId",
                "count" to "20", "itemId" to appId.toString(), "versionCode" to versionCode.toString(),
                "oldVersionCode" to versionCode.toString(), "native" to "1", "maxCommentId" to "0",
                "page" to "0", "commentLevel" to "0", "pageRef" to "com.app.market",
                "supportSlide" to "1", "suggestV" to "1", "minacompatible" to "1"
            )
        )
        val json = parseJsonObject(http.get(XiaomiSigner.signedUrl("$MARKET/usercomment/app/listV2?${query(params)}"), cookie))
        val comments = parseComments(json.arr("list") ?: JsonArray(emptyList()))
        val totalCount = listOf(
            json.long("totalCount"),
            json.long("commentCount"),
            json.long("allCount"),
            json.long("total"),
        ).firstOrNull { it > 0L } ?: comments.size.toLong()
        return AppComments(
            items = comments,
            totalCount = maxOf(totalCount, comments.size.toLong()),
        )
    }

    suspend fun sameDeveloperApps(
        appId: Long,
        profile: MarketProfile,
        cookie: String,
    ): List<MarketAppInfo> {
        val params = commonParams(profile).toMutableMap()
        params.putAll(
            mapOf(
                "appId" to appId.toString(), "bottomTab" to "true", "combine" to "1",
                "h5" to "1", "page" to "0", "pageSize" to "15", "ref" to "samedev",
                "pageRef" to "com.app.market", "sourcePackage" to "com.app.market",
                "fromExternal" to "false", "minacompatible" to "1", "netStatus" to "1",
                "networkStatus" to "1", "networkType" to "5G", "stamp" to "0"
            )
        )
        val json = parseJsonObject(http.get(XiaomiSigner.signedUrl("$MARKET/samedev?${query(params)}"), cookie))
        return parseApps(json.arr("listApp") ?: JsonArray(emptyList()))
    }

    /**
     * Golden Mi Award feed used by the bundled `goldmi.html` page.
     *
     * Xiaomi Market 4.121.s.00 sends `pageSize=9` and `page` to `zone/goldMiV2`.
     * The response is a component list whose award cards carry their app in `data.listApp`.
     */
    suspend fun goldMiFeed(
        page: Int,
        pageSize: Int,
        profile: MarketProfile,
        cookie: String,
    ): TodayFeedPage {
        require(page >= 0) { "page must be non-negative" }
        require(pageSize > 0) { "pageSize must be positive" }
        val params = commonParams(profile).toMutableMap()
        params.putAll(todayCommonParams())
        params.putAll(
            mapOf(
                "page" to page.toString(),
                "pageSize" to pageSize.toString(),
                "ref" to "goldmi",
                "previousFromRef" to "goldmi",
            )
        )
        val url = XiaomiSigner.signedUrl("$MARKET/zone/goldMiV2?${query(params)}")
        return parseGoldMiFeed(parseJsonObject(http.get(url, cookie)))
    }

    /**
     * A single Today article (`@GET topic/detail`). The body arrives as `list[0].data.topicItemList`
     * (banner + rich-text + app blocks); [parseTodayArticle] flattens it into [TodayArticle].
     */
    suspend fun todayArticle(rId: String, profile: MarketProfile, cookie: String): TodayArticle {
        val params = commonParams(profile).toMutableMap()
        params.putAll(todayCommonParams())
        params["rId"] = rId
        val json = parseJsonObject(http.get(XiaomiSigner.signedUrl("$MARKET/topic/detail?${query(params)}"), cookie))
        return parseTodayArticle(rId, json)
    }

    /** Shared H5-to-Native params the Today tab pages send (mirrors the client's base params). */
    private fun todayCommonParams(): Map<String, String> = mapOf(
        "bottomTab" to "true", "feReload" to "false", "isNewUI" to "true",
        "native" to "1", "suggestV" to "1", "supportSlide" to "1", "minacompatible" to "1",
        "pageRef" to "com.xiaomi.market", "sourcePackage" to "com.xiaomi.market",
        "previousFromRef" to "today",
    )

    internal fun parseGoldMiFeed(json: JsonObject): TodayFeedPage {
        val components = json.arr("list") ?: return TodayFeedPage(emptyList(), json.bool("hasMore"))
        val items = mutableListOf<TodayFeaturedItem>()
        for (componentIndex in 0 until components.len) {
            val data = components.objAt(componentIndex)?.obj("data") ?: continue
            val apps = data.arr("listApp") ?: continue
            for (appIndex in 0 until apps.len) {
                val item = apps.objAt(appIndex) ?: continue
                val clickUrl = item.str("clickUrl").ifBlank { appOpenLink(item) }
                val app = parseAppOrNull(item)?.let { parsed ->
                    if (parsed.openLink.isNotBlank() || clickUrl.isBlank()) parsed else parsed.copy(openLink = clickUrl)
                } ?: continue
                items += TodayFeaturedItem(
                    rId = queryParam(clickUrl, "rId").ifBlank { item.str("rId") },
                    title = data.str("title").ifBlank { data.str("linkTitle") }
                        .ifBlank { item.str("card_title") }
                        .ifBlank { app.displayName },
                    summary = item.str("description"),
                    coverImage = normalizeTodayMediaUrl(item.str("imgUrl"), "l720q90"),
                    app = app,
                    articleLink = clickUrl,
                )
            }
        }
        val distinct = items.distinctBy { it.app?.packageName ?: it.rId.ifBlank { it.articleLink } }
        return TodayFeedPage(distinct, json.bool("hasMore", distinct.isNotEmpty()))
    }

    internal fun parseTodayArticle(rId: String, json: JsonObject): TodayArticle {
        val data = json.arr("list").objAt(0).obj("data")
        val items = data.arr("topicItemList") ?: JsonArray(emptyList())
        val blocks = mutableListOf<TodayArticleBlock>()
        for (i in 0 until items.len) {
            val item = items.objAt(i) ?: continue
            when (item.str("topicItemType")) {
                "topicBanner" -> parseTodayBanner(item)?.let(blocks::add)
                "topicRichText" -> parseTodayRichText(item)?.let(blocks::add)
                "topicApp" -> parseAppOrNull(item)?.let { blocks += TodayArticleBlock.App(it) }
                "topicImage" -> parseTodayImage(item)?.let(blocks::add)
                else -> {
                    when {
                        item.str("packageName").isNotBlank() ->
                            parseAppOrNull(item)?.let { blocks += TodayArticleBlock.App(it) }

                        item.obj("richTextInfo").str("desc").isNotBlank() ||
                                item.str("desc").isNotBlank() || item.str("content").isNotBlank() ->
                            parseTodayRichText(item)?.let(blocks::add)

                        else -> parseTodayImage(item)?.let(blocks::add)
                    }
                }
            }
        }

        if (blocks.none { it is TodayArticleBlock.Banner }) {
            val fallbackBanner = data.obj("bannerInfo").str("banner")
                .ifBlank { data.str("banner") }
                .ifBlank { data.str("thumbnail") }
                .ifBlank { data.str("mticon") }
                .ifBlank { data.str("webViewPic") }
            normalizeTodayMediaUrl(fallbackBanner, "q90")
                .takeIf(String::isNotBlank)
                ?.let {
                    blocks.add(
                        0,
                        TodayArticleBlock.Banner(
                            imageUrl = it,
                            // 与 feed 封面同规格，头图占位可直接命中缓存
                            previewImageUrl = normalizeTodayMediaUrl(fallbackBanner, "l720q90"),
                        ),
                    )
                }
        }

        if (blocks.none { it is TodayArticleBlock.App }) {
            data.obj("appInfo")?.let(::parseAppOrNull)?.let { blocks += TodayArticleBlock.App(it) }
            listOf(data.arr("listApp"), data.arr("appList")).forEach { appList ->
                for (i in 0 until appList.len) {
                    appList.objAt(i)?.let(::parseAppOrNull)?.let { blocks += TodayArticleBlock.App(it) }
                }
            }
        }

        val headerBanner = blocks.filterIsInstance<TodayArticleBlock.Banner>().firstOrNull()
        val richText = blocks.filterIsInstance<TodayArticleBlock.RichText>()
            .joinToString("\n\n", transform = TodayArticleBlock.RichText::html)
        val apps = blocks.filterIsInstance<TodayArticleBlock.App>()
            .map(TodayArticleBlock.App::value)
            .distinctBy(MarketAppInfo::packageName)
        return TodayArticle(
            rId = rId,
            title = data.str("title")
                .ifBlank { data.str("detailTitle") }
                .ifBlank { data.str("outerTitle") }
                .ifBlank { data.str("webViewTitle") },
            headerImage = headerBanner?.imageUrl.orEmpty(),
            headerImagePreview = headerBanner?.previewImageUrl.orEmpty(),
            richTextHtml = richText,
            app = apps.firstOrNull(),
            apps = apps,
            blocks = blocks,
        )
    }

    private fun parseTodayBanner(item: JsonObject): TodayArticleBlock.Banner? {
        val info = item.obj("bannerInfo")
        val image = info.str("banner")
            .ifBlank { item.str("banner") }
            .ifBlank { item.str("mticon") }
            .ifBlank { item.str("webViewPic") }
            .ifBlank { item.str("imgUrl") }
        val url = normalizeTodayMediaUrl(image, "q90")
        if (url.isBlank()) return null
        return TodayArticleBlock.Banner(
            imageUrl = url,
            width = info.int("bannerWidthForDisplay", item.int("width")),
            height = info.int("bannerHeightForDisplay", item.int("height")),
            // 与 feed 封面同规格，头图占位可直接命中缓存
            previewImageUrl = normalizeTodayMediaUrl(image, "l720q90"),
        )
    }

    private fun parseTodayRichText(item: JsonObject): TodayArticleBlock.RichText? {
        val html = item.obj("richTextInfo").str("desc")
            .ifBlank { item.str("desc") }
            .ifBlank { item.str("content") }
        if (html.isBlank()) return null

        val imageUrls = mutableListOf<String>()
        val normalizedHtml = todayImageTag.replace(html) { match ->
            val source = match.groups[1] ?: return@replace match.value
            val normalizedUrl = normalizeTodayMediaUrl(source.value, "w1000q80")
            imageUrls += normalizedUrl
            val start = source.range.first - match.range.first
            val end = source.range.last - match.range.first + 1
            match.value.replaceRange(start, end, normalizedUrl)
        }
        return TodayArticleBlock.RichText(normalizedHtml, imageUrls)
    }

    private fun parseTodayImage(item: JsonObject): TodayArticleBlock.Image? {
        val info = item.obj("imageInfo")
        val image = info.str("image")
            .ifBlank { info.str("url") }
            .ifBlank { item.str("image") }
            .ifBlank { item.str("imgUrl") }
            .ifBlank { item.str("mticon") }
            .ifBlank { item.str("webViewPic") }
        val url = normalizeTodayMediaUrl(image, "q90")
        if (url.isBlank()) return null
        return TodayArticleBlock.Image(
            imageUrl = url,
            width = info.int("width", item.int("width")),
            height = info.int("height", item.int("height")),
        )
    }

    private fun normalizeTodayMediaUrl(path: String, spec: String): String {
        val url = when {
            path.startsWith("//") -> "https:$path"
            else -> imageUrl(path, spec)
        }
        if (!url.startsWith("http://")) return url
        val host = url.substringAfter("://").substringBefore('/')
        return if (host == "market.xiaomi.com" || host.endsWith(".market.xiaomi.com")) {
            "https://${url.substringAfter("://")}"
        } else {
            url
        }
    }

    /** Reads a single query-string value from a raw URL (returns "" when absent). */
    private fun queryParam(url: String, key: String): String {
        val queryPart = url.substringAfter('?', "").takeIf { it.isNotBlank() } ?: return ""
        return queryPart.split('&').firstNotNullOfOrNull { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0 && pair.substring(0, idx) == key) pair.substring(idx + 1) else null
        }?.let(::urlDecode) ?: ""
    }

    fun iconUrl(icon: String): String = imageUrl(icon, "l144q80")

    fun imageUrl(path: String, spec: String): String {
        if (path.isBlank()) return ""
        if (path.startsWith("http")) return path
        if (path.startsWith("webp/") || path.startsWith("png/")) return THUMB + path
        return "${THUMB}webp/$spec/$path"
    }

    private fun parseSearch(json: JsonObject, removeSearchAds: Boolean): SearchPage {
        val out = mutableListOf<MarketAppInfo>()
        val list = json.arr("list") ?: return SearchPage(emptyList(), hasMore = false)
        for (i in 0 until list.len) {
            val node = list.objAt(i) ?: continue
            val cardType = node.str("type")
            val data = node.obj("data") ?: continue
            val apps = data.arr("listApp") ?: data.arr("appList") ?: continue
            for (j in 0 until apps.len) {
                val o = apps.objAt(j) ?: continue
                if (removeSearchAds && o.bool("isSearchTopAd")) continue
                parseAppOrNull(o, type = cardType)?.let(out::add)
            }
        }
        val items = out.distinctBy { it.packageName }
        // Trust the server's hasMore when present; otherwise keep paging until a page comes back empty.
        return SearchPage(items, hasMore = json.bool("hasMore", items.isNotEmpty()))
    }

    private fun parseCategoryApps(json: JsonObject): SearchPage {
        val list = json.arr("data") ?: return SearchPage(emptyList(), hasMore = false)
        val items = (0 until list.len).mapNotNull { i ->
            val o = list.objAt(i) ?: return@mapNotNull null
            val pkg = o.str("packageName")
            if (pkg.isBlank()) return@mapNotNull null
            MarketAppInfo(
                appId = o.long("appId"),
                packageName = pkg,
                displayName = o.str("displayName", pkg),
                publisherName = o.str("publisherName"),
                versionName = "",
                versionCode = 0L,
                icon = o.str("icon").replaceFirst("http://", "https://"),
                apkSize = o.long("apkSize") * 1024L,
                ratingScore = o.double("ratingScore") / 2,
                type = o.str("level1CategoryName"),
                category = o.str("level2CategoryName", o.str("level1CategoryName")),
            )
        }.distinctBy { it.packageName }
        return SearchPage(items, hasMore = json.bool("hasNext", items.isNotEmpty()))
    }

    private fun parseApps(arr: JsonArray, isSystemApp: Boolean = false): List<MarketAppInfo> {
        val out = mutableListOf<MarketAppInfo>()
        for (i in 0 until arr.len) {
            val o = arr.objAt(i) ?: continue
            parseAppOrNull(o, isSystemApp)?.let(out::add)
        }
        return out
    }

    private fun parseApp(o: JsonObject, installedVersionCode: Long = 0L): MarketAppInfo =
        parseAppOrNull(o, installedVersionCode = installedVersionCode) ?: MarketAppInfo(
            appId = o.long("appId", o.long("id")),
            packageName = o.str("packageName"),
            displayName = o.str("displayName", o.str("packageName")),
            publisherName = o.str("publisherName"),
            versionName = o.str("versionName"),
            versionCode = o.long("versionCode"),
            icon = iconUrl(o.str("icon")),
            apkSize = o.long("apkSizeV2", o.long("apkSize")),
            ratingScore = o.double("ratingScore"),
            changeLog = o.str("changeLog"),
            openLink = appOpenLink(o),
            type = o.str("type"),
            subscribeState = o.int("subscribeState"),
        )

    private fun parseAppOrNull(
        o: JsonObject,
        isSystemApp: Boolean = false,
        type: String = o.str("type"),
        installedVersionCode: Long = 0L,
    ): MarketAppInfo? {
        val pkg = o.str("packageName")
        if (pkg.isBlank()) return null
        val downloadable = downloadableVariant(o, installedVersionCode)
        return MarketAppInfo(
            appId = o.long("appId", o.long("id")),
            packageName = pkg,
            displayName = downloadable.str("displayName").takeIf { it.isNotBlank() } ?: o.str("displayName", pkg),
            publisherName = o.str("publisherName"),
            versionName = downloadable.str("versionName").takeIf { it.isNotBlank() } ?: o.str("versionName"),
            versionCode = downloadable?.long("versionCode")?.takeIf { it > 0L } ?: o.long("versionCode"),
            icon = imageUrl(downloadable.str("icon").takeIf { it.isNotBlank() } ?: o.str("icon"), "l144q80"),
            apkSize = downloadable?.long("apkSize")?.takeIf { it > 0L }
                ?: o.long("apkSizeV2", o.long("apkSize")),
            ratingScore = o.double("commentScore", o.double("ratingScore")),
            changeLog = o.str("changeLog"),
            isSystemApp = isSystemApp,
            openLink = appOpenLink(o),
            type = type,
            isAd = o.bool("isSearchTopAd"),
            subscribeState = o.int("subscribeState"),
            downloadBlockReason = xiaomiDownloadBlockReason(o)
                .ifBlank { downloadable?.let(::xiaomiDownloadBlockReason).orEmpty() },
        )
    }

    /** 服务端 updateTime 秒 / 毫秒混用，统一归一为 epoch 毫秒；非法值返回 0。 */
    private fun epochMillis(value: Long): Long = when {
        value <= 0L -> 0L
        value < 1_000_000_000_000L -> value * 1000
        else -> value
    }

    private fun appOpenLink(o: JsonObject): String =
        o.str("deeplink")
            .ifBlank { o.str("deepLink") }
            .ifBlank { o.str("ext_deeplink") }
            .ifBlank { o.str("inner_deeplink") }
            .ifBlank { o.str("deeplinkUrl") }
            .ifBlank { o.str("deeplinkAfterInstall") }
            .ifBlank { o.str("linkUrlWhenClick") }
            .ifBlank { o.str("linkUrl") }
            .ifBlank { o.str("link") }
            .ifBlank { o.str("actionUrl") }
            .ifBlank { o.str("marketLink") }
            .ifBlank { o.obj("extraData").str("deeplink") }
            .ifBlank { o.obj("extraData").str("deepLink") }
            .ifBlank { o.obj("extraData").str("ext_deeplink") }
            .ifBlank { o.obj("extraData").str("inner_deeplink") }
            .ifBlank { o.obj("extraData").str("deeplinkUrl") }
            .ifBlank { o.obj("extraData").str("deeplinkAfterInstall") }
            .ifBlank { o.obj("extraData").str("linkUrlWhenClick") }
            .ifBlank { o.obj("extraData").str("linkUrl") }
            .ifBlank { o.obj("extraData").str("link") }

    private fun downloadableVariant(o: JsonObject, installedVersionCode: Long): JsonObject? {
        val variants = o.obj("fitnessApks").arr("apks") ?: return null
        val candidates = mutableListOf<JsonObject>()
        for (i in 0 until variants.len) {
            val variant = variants.objAt(i) ?: continue
            val versionCode = variant.long("versionCode", 0L)
            if (variant.bool("downloadDisable", false) || versionCode <= 0L) continue
            candidates += variant
        }
        if (installedVersionCode > 0L) {
            candidates
                .filter { it.supportsInstalledVersion(installedVersionCode) }
                .maxByOrNull { it.long("versionCode", 0L) }
                ?.let { return it }
        }
        return candidates.defaultDownloadableVariant()
    }

    private fun JsonObject.supportsInstalledVersion(installedVersionCode: Long): Boolean {
        val min = long("minAppVersion", Long.MIN_VALUE).let {
            if (it > 0L) it else Long.MIN_VALUE
        }
        val max = long("maxAppVersion", Long.MAX_VALUE).let {
            if (it > 0L) it else Long.MAX_VALUE
        }
        return installedVersionCode in min..max
    }

    private fun List<JsonObject>.defaultDownloadableVariant(): JsonObject? =
        filter { variant ->
            val min = variant.long("minAppVersion", Long.MIN_VALUE)
            val max = variant.long("maxAppVersion", Long.MAX_VALUE)
            min <= 0L && max >= Int.MAX_VALUE.toLong()
        }.maxByOrNull { it.long("versionCode", 0L) }
            ?: maxByOrNull { it.long("versionCode", 0L) }

    private fun parseComments(arr: JsonArray): List<AppComment> {
        val out = mutableListOf<AppComment>()
        for (i in 0 until arr.len) {
            val node = arr.objAt(i) ?: continue
            val data = node.obj("data") ?: continue
            val candidates = listOf(data.obj("main"), data.obj("comment"), data)
            for (item in candidates) {
                if (item == null) continue
                val content = item.str("content")
                if (content.isBlank()) continue
                out += AppComment(
                    userName = item.str("userName", item.str("nickname", "-")),
                    content = content,
                    score = item.double("score", item.double("star", 0.0))
                )
                break
            }
        }
        return out.distinctBy { it.content }
    }

    private fun downloadParts(
        apks: JsonArray,
        host: String,
        patchVersion: Int,
        oldApkHash: String,
    ): List<DownloadPart> {
        val parts = mutableListOf<DownloadPart>()
        for (i in 0 until apks.len) {
            val apk = apks.objAt(i) ?: continue
            val path = apk.str("url")
            if (path.isBlank()) continue
            val diffPath = apk.str("diffUrl")
            val diffSize = apk.long("diffSize")
            val patch = if (diffPath.isNotBlank() && diffSize > 0L) {
                DownloadPatch(
                    url = resolvedUrl(host, diffPath),
                    size = diffSize,
                    hash = apk.str("diffHash"),
                    version = patchVersion,
                    oldApkHash = oldApkHash,
                )
            } else {
                null
            }
            parts += DownloadPart(
                name = apk.str("name").ifBlank { "base" },
                type = apk.str("type").ifBlank { "base" },
                url = resolvedUrl(host, path),
                size = apk.long("size"),
                hash = apk.str("hash"),
                patch = patch,
            )
        }
        return parts
    }

    private fun primaryDownloadPart(parts: List<DownloadPart>): DownloadPart =
        parts.firstOrNull { it.name == "base" && it.type == "base" }
            ?: parts.firstOrNull { it.type == "base" }
            ?: matchingApkPart(parts)

    private fun matchingApkPart(parts: List<DownloadPart>): DownloadPart =
        parts.firstOrNull() ?: DownloadPart("", "base", "", 0L)

    private suspend fun commonParams(profile: MarketProfile): Map<String, String> =
        deviceFingerprintParams(profile) + deviceCapabilityParams()

    /** 设备指纹：机型、系统、屏幕、版本号等会被服务端做真实性校验的字段。 */
    private suspend fun deviceFingerprintParams(profile: MarketProfile): Map<String, String> {
        val identity = identityProvider.identity()
        return buildMap {
            put("activedTimeInterval", identity.activeTimeInterval.ifBlank { "1" })
            put("co", profile.co)
            put("cpuArchitecture", profile.cpuArchitecture)
            put("device", profile.device)
            put("deviceType", "0")
            put("installDay", identity.installDay.ifBlank { "1" })
            put("instance_id", profile.instanceId)
            put("la", profile.la)
            put("launchDay", identity.launchDay.ifBlank { "1" })
            put("lo", profile.lo)
            put("marketVersion", profile.marketVersion)
            put("miuiBigVersionCode", profile.miuiBigVersionCode)
            put("miuiBigVersionName", profile.miuiBigVersionName)
            put("model", profile.model)
            put("network", "unknown")
            put("newUser", "false")
            put("os", profile.os)
            put("osV2", profile.osV2)
            put("osBigVersionCode", profile.osBigVersionCode)
            put("osBigVersionName", profile.osBigVersionName)
            put("androidVersion", profile.androidVersion)
            put("pageConfigVersion", profile.pageConfigVersion)
            put("resolution", profile.resolution)
            put("densityDpi", profile.densityDpi)
            put("densityScaleFactor", profile.densityScaleFactor)
            put("hybridFrameworkVersion", profile.hybridFrameworkVersion)
            put("supportedIslandVersion", profile.supportedIslandVersion)
            put("hasGMSCore", profile.hasGMSCore)
            put("ro", "unknown")
            put("sdk", profile.sdk)
            put("webResVersion", profile.webResVersion)
            put("oaId", identity.oaId.ifBlank { "6f24320b1e9596bf" })
            if (identity.dctx.isNotBlank()) put("dctx", identity.dctx)
            if (identity.tzNonce.isNotBlank() && identity.tzSign.isNotBlank()) {
                put("tzNonce", identity.tzNonce)
                put("tzSign", identity.tzSign)
            }
        }
    }

    /** 设备能力开关：客户端支持的特性，决定服务端下发哪种包体/能力位。 */
    private suspend fun deviceCapabilityParams(): Map<String, String> {
        val experimentId = currentUseExpId(DEFAULT_USE_EXP_ID)
        return buildMap {
            put("ARCoreApkVersion", "-1")
            put("childMode", "0")
            put("clientConfigVersion", "447")
            put("clientFlag", "2")
            put("debugMode", "false")
            put("downloadRestriction", "1")
            put("downloadRestrictionMode", "0")
            put("isMiuiLite", "false")
            put("isSupportIsland", "true")
            put("isSupportMessageBox", "true")
            put("isSupportQuickGameInstall", "true")
            put("isSupportUninstall", "true")
            put("isTangoEnabled", "true")
            put("minorsMode", "false")
            put("needBlockWelfare", "true")
            put("privacyCompliance", "true")
            put("rankTypeV2", "true")
            put("rustRuntimeVersion", "1.6.0")
            put("supportAgent", "true")
            put("supportBundle", "1")
            put("supportDownloaderUpdate", "1")
            put("supportOperateIcon", "true")
            put("supportPatchVer", "0,1,2,3")
            put("supportSmallApk", "true")
            put("useExpId", experimentId)
        }
    }

    private suspend fun currentUseExpId(fallback: String): String =
        preferences.read(XiaomiIdentityPreferenceKeys.ExperimentId)
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?: fallback

    private companion object {
        const val DEFAULT_USE_EXP_ID =
            "2023001,2311551,2398789,2398846,2346056,2075312,2362289,2378691,2403435,2263575,2404589,2333160,2286434,1411227,1978999,2368587,2362073,2059056"

    }
}

internal fun canonicalizeXiaomiParts(parts: List<DownloadPart>): List<DownloadPart> {
    val baseIndex = parts.indexOfFirst {
        it.name.equals("base", ignoreCase = true) && it.type.equals("base", ignoreCase = true)
    }.takeIf { it >= 0 } ?: parts.indexOfFirst {
        it.name.equals("base", ignoreCase = true)
    }.takeIf { it >= 0 } ?: parts.indexOfFirst {
        it.type.equals("base", ignoreCase = true)
    }
    if (baseIndex <= 0) return parts
    return buildList(parts.size) {
        add(parts[baseIndex])
        parts.forEachIndexed { index, part -> if (index != baseIndex) add(part) }
    }
}

internal fun externalDetailRequestParams(encodedQuery: String): Map<String, String> = buildMap {
    put("needInnovateDmConfig", "true")
    put("checkPermission", "true")
    if (encodedQuery.isNotBlank()) {
        put("deeplinkParams", encodedQuery)
        put("needUrlDecode", "true")
    }
    put("supportFloatCard", "2")
    put("supportExpType", "2")
}

internal fun requireListedAppInfo(detailJson: JsonObject): JsonObject {
    val appInfo = detailJson.obj("appInfo")
    if (appInfo == null || appInfo.long("appId") <= 0L) {
        throw AppNotListedException(detailJson.str("errorMessage"))
    }
    return appInfo
}

internal fun parseScreenshots(
    json: JsonObject,
    resolveUrl: (String) -> String,
): List<AppScreenshot> = parseDetailMedia(json, resolveUrl).screenshots

internal data class AppDetailMedia(
    val screenshots: List<AppScreenshot>,
    val promotions: List<AppPromotion>,
    val videos: List<AppVideo> = emptyList(),
)

// 游戏详情的 detailTabList 可能以社区 tab 开头，应用数据在 type=detailTabAppInfo 的 tab 里。
internal fun appInfoTabData(json: JsonObject): JsonObject? {
    val tabs = json.arr("detailTabList")
    for (index in 0 until tabs.len) {
        val tab = tabs.objAt(index) ?: continue
        if (tab.str("type") == "detailTabAppInfo") return tab.obj("data")
    }
    return tabs.objAt(0).obj("data")
}

internal fun parseDetailMedia(
    json: JsonObject,
    resolveUrl: (String) -> String,
    resolveExpandedUrl: (String) -> String = resolveUrl,
): AppDetailMedia {
    val appInfo = json.obj("appInfo")
    val fallbackOrientation = parseScreenshotOrientation(appInfo.str("screenshotType"))
    val screenshots = mutableListOf<AppScreenshot>()
    val promotions = mutableListOf<AppPromotion>()
    val videos = mutableListOf<AppVideo>()
    val detailList = appInfoTabData(json).arr("detailVideoAndScreenshotList")

    if (detailList != null) {
        for (index in 0 until detailList.len) {
            val item = detailList.objAt(index) ?: continue
            val videoInfo = item.obj("appVideoInfoWithCover")
            if (videoInfo != null) {
                val videoUrl = videoInfo.str("videoUrl")
                if (videoUrl.isNotBlank() && videoInfo.bool("showVideo", default = true)) {
                    videos += AppVideo(
                        url = videoUrl,
                        coverUrl = resolveUrl(videoInfo.str("coverUrl")),
                        orientation = item.str("orientation")
                            .takeIf { it.isNotBlank() }
                            ?.let(::parseScreenshotOrientation)
                            ?: ScreenshotOrientation.LANDSCAPE,
                        title = item.str("displayName"),
                    )
                }
                continue
            }
            val path = item.str("screenshot")
            if (path.isBlank()) continue
            if (item.int("type") == DETAIL_MEDIA_TYPE_ACTIVITY) {
                val activity = item.obj("appActivityConfig") ?: continue
                val expandedPath = activity.str("expandScreenshot").ifBlank { path }
                promotions += AppPromotion(
                    previewImageUrl = resolveUrl(path),
                    expandedImageUrl = resolveExpandedUrl(expandedPath),
                    title = activity.str("mainTitle").ifBlank { activity.str("subTitle") },
                    description = activity.str("mainText"),
                    category = activity.str("activityShowType"),
                    activityTag = activity.str("activityTag"),
                    jumpUrl = activity.str("jumpUrl"),
                )
                continue
            }
            val orientation = item.str("orientation")
                .takeIf { it.isNotBlank() }
                ?.let(::parseScreenshotOrientation)
                ?: fallbackOrientation
            screenshots += AppScreenshot(resolveUrl(path), orientation, resolveExpandedUrl(path))
        }
    }

    if (screenshots.isEmpty()) {
        appInfo.str("screenshot")
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)
            .forEach { path ->
                screenshots += AppScreenshot(resolveUrl(path), fallbackOrientation, resolveExpandedUrl(path))
            }
    }
    return AppDetailMedia(
        screenshots = screenshots,
        promotions = promotions,
        videos = videos,
    )
}

// Xiaomi detail responses use type 8 for app activity cards, not gallery screenshots.
private const val DETAIL_MEDIA_TYPE_ACTIVITY = 8

internal fun parseScreenshotOrientation(raw: String): ScreenshotOrientation =
    when (raw.trim().lowercase()) {
        "1", "horizontal", "horizon", "landscape" -> ScreenshotOrientation.LANDSCAPE
        else -> ScreenshotOrientation.PORTRAIT
    }

/** Converts Xiaomi's search/detail compatibility flags into the shared disabled-download state. */
internal fun xiaomiDownloadBlockReason(value: JsonObject): String {
    val incompatible = value.int("fitness") != 0 || value.int("unfitnessType") > 0
    val disabled = value.bool("downloadDisable")
    if (!incompatible && !disabled) return ""

    val rawReason = value.str("unfitnessDesc")
        .ifBlank { value.str("downloadDisableDesc") }
        .ifBlank { value.str("errorDesc") }
    if (rawReason.isNotBlank()) {
        val translated = runCatching { parseJsonObject(rawReason) }.getOrNull()
        return translated?.str("zh_CN")
            ?.ifBlank { translated.str("zh") }
            ?.ifBlank { translated.str("en_US") }
            ?.ifBlank { translated.str("en") }
            ?.ifBlank { rawReason }
            ?: rawReason
    }

    val tags = value.arr("compatibilityTagList")
    for (index in 0 until tags.len) {
        val description = tags.objAt(index).str("desc")
        if (description.isNotBlank()) return description
    }
    return if (incompatible) "当前设备不兼容" else "当前应用暂不可下载"
}

// 游戏无 ageClassification，适龄提示在 extraData.gameAgeRating：1/2/3 → 8+/12+/16+，0 无分级。
internal fun parseAgeClassification(appInfo: JsonObject): String =
    appInfo.str("ageClassification").ifBlank {
        when (appInfo.obj("extraData").int("gameAgeRating")) {
            1 -> "8+"
            2 -> "12+"
            3 -> "16+"
            else -> ""
        }
    }

internal fun parseDetailRatingCount(appInfo: JsonObject): Long {
    val directCount = listOf(
        appInfo.long("ratingTotalCount"),
        appInfo.long("commentCount"),
        appInfo.long("totalCommentCount"),
        appInfo.long("commentNum"),
    ).firstOrNull { it > 0L }
    if (directCount != null) return directCount

    val headerCards = appInfo.arr("headerCardInfos")
    for (index in 0 until headerCards.len) {
        val card = headerCards.objAt(index) ?: continue
        if (card.str("type") == "comment") {
            return card.long("bottomValue").coerceAtLeast(0L)
        }
    }
    return 0L
}

internal fun deltaSizeRequestFields(
    common: Map<String, String>,
    instanceId: String,
    candidates: List<MarketAppInfo>,
    timestamp: Long,
): MutableMap<String, String> = common.toMutableMap().apply {
    remove("tzNonce")
    remove("tzSign")
    put("session_id", instanceId + timestamp)
    put("packageName", candidates.joinToString(",", transform = MarketAppInfo::packageName))
    put("versionCode", candidates.joinToString(",") { it.installedVersionCode.toString() })
    put("oldApkHash", candidates.joinToString(",") { it.installedOldApkHash })
}

internal fun parseDeltaSizeResponse(response: JsonObject): Map<String, Long>? {
    val entries = response.arr("apkDiffInfoList") ?: return null
    return buildMap {
        for (index in 0 until entries.len) {
            val item = entries.objAt(index) ?: continue
            val packageName = item.str("packageName")
            val size = item.long("diffFileSize")
            if (packageName.isNotBlank() && size > 0L) put(packageName, size)
        }
    }
}
