package com.app.market.data.repository

import com.app.market.data.local.PreferenceChanges
import com.app.market.data.local.PreferencesDataSource
import com.app.market.data.remote.xiaomi.LenientJson
import com.app.market.data.remote.xiaomi.XiaomiApi
import com.app.market.data.remote.xiaomi.XiaomiClient
import com.app.market.data.remote.xiaomi.XiaomiProtocol
import com.app.market.data.remote.xiaomi.epochMillis
import com.app.market.data.remote.xiaomi.obj
import com.app.market.data.remote.xiaomi.platform.DeviceDefaults
import com.app.market.data.remote.xiaomi.platform.DeviceDefaultsDataSource
import com.app.market.data.remote.xiaomi.preferences.ProfilePreferenceKeys
import com.app.market.data.remote.xiaomi.preferences.XiaomiIdentityPreferenceKeys
import com.app.market.data.remote.xiaomi.str
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.profile.MarketProfile
import com.app.market.domain.model.profile.MarketProfileFields
import com.app.market.domain.model.profile.OppoRequestContext
import com.app.market.domain.model.profile.OppoStoreRegion
import com.app.market.domain.model.profile.ProfileSource
import com.app.market.domain.model.profile.ProfileTemplate
import com.app.market.domain.model.profile.SamsungRequestContext
import com.app.market.domain.model.profile.SamsungStoreRegion
import com.app.market.domain.model.profile.oppoRequestContext
import com.app.market.domain.model.profile.oppoStoreRegion
import com.app.market.domain.model.profile.requestContext
import com.app.market.domain.model.profile.samsungStoreRegion
import com.app.market.domain.repository.ProfileRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

internal class ProfileRepositoryImpl(
    private val preferences: PreferencesDataSource,
    private val api: XiaomiApi,
    private val deviceDefaults: DeviceDefaultsDataSource,
    private val xiaomiClient: XiaomiClient,
) : ProfileRepository {

    private val mutex = Mutex()
    private val cached = mutableMapOf<AppSource, MarketProfile>()

    override fun canUseDevice(appSource: AppSource): Boolean =
        canUseCurrentDevice(appSource, deviceDefaults.current())

    override suspend fun load(appSource: AppSource): MarketProfile = mutex.withLock { loadLocked(appSource) }

    override suspend fun currentSource(appSource: AppSource): ProfileSource =
        mutex.withLock { currentSourceLocked(appSource) }

    override suspend fun setSource(source: ProfileSource, appSource: AppSource) = mutex.withLock {
        preferences.put(ProfilePreferenceKeys.source(appSource), source.token())
        clearOverridesLocked(appSource)
        setCurrentTemplateNameLocked(null, appSource)
        refreshCachedProfileLocked(appSource)
    }

    override suspend fun currentOppoStoreRegion(): OppoStoreRegion = mutex.withLock {
        when (preferences.read(ProfilePreferenceKeys.OppoStoreRegion)) {
            "china" -> OppoStoreRegion.CHINA
            "global" -> OppoStoreRegion.GLOBAL
            else -> loadLocked(AppSource.OPPO).oppoStoreRegion()
        }
    }

    override suspend fun setOppoStoreRegion(region: OppoStoreRegion) = mutex.withLock {
        preferences.put(
            ProfilePreferenceKeys.OppoStoreRegion,
            if (region == OppoStoreRegion.CHINA) "china" else "global",
        )
    }

    override suspend fun currentSamsungStoreRegion(): SamsungStoreRegion = mutex.withLock {
        when (preferences.read(ProfilePreferenceKeys.SamsungStoreRegion)) {
            "china" -> SamsungStoreRegion.CHINA
            "global" -> SamsungStoreRegion.GLOBAL
            else -> loadLocked(AppSource.SAMSUNG).samsungStoreRegion()
        }
    }

    override suspend fun setSamsungStoreRegion(region: SamsungStoreRegion) = mutex.withLock {
        preferences.put(
            ProfilePreferenceKeys.SamsungStoreRegion,
            if (region == SamsungStoreRegion.CHINA) "china" else "global",
        )
    }

    override suspend fun oppoRequestContext(region: OppoStoreRegion): OppoRequestContext = mutex.withLock {
        oppoRequestContextLocked(region)
    }

    override suspend fun saveOppoRequestContext(
        region: OppoStoreRegion,
        context: OppoRequestContext,
    ) = mutex.withLock {
        val userRegion = ProfilePreferenceKeys.oppoUserRegion(region)
        preferences.update(
            userRegion.namespace,
            PreferenceChanges(
                strings = mapOf(
                    userRegion to context.userRegion,
                    ProfilePreferenceKeys.oppoSystemLocale(region) to context.systemLocale,
                    ProfilePreferenceKeys.oppoSupportedLocales(region) to context.supportedLocales,
                    ProfilePreferenceKeys.oppoLocale(region) to context.locale,
                )
            ),
        )
    }

    override suspend fun samsungRequestContext(region: SamsungStoreRegion): SamsungRequestContext = mutex.withLock {
        samsungRequestContextLocked(region)
    }

    override suspend fun saveSamsungRequestContext(
        region: SamsungStoreRegion,
        context: SamsungRequestContext,
    ) = mutex.withLock {
        val countryCode = ProfilePreferenceKeys.samsungCountryCode(region)
        preferences.update(
            countryCode.namespace,
            PreferenceChanges(
                strings = mapOf(
                    countryCode to context.countryCode,
                    ProfilePreferenceKeys.samsungLanguage(region) to context.language,
                    ProfilePreferenceKeys.samsungMcc(region) to context.mcc,
                    ProfilePreferenceKeys.samsungMnc(region) to context.mnc,
                    ProfilePreferenceKeys.samsungCsc(region) to context.csc,
                )
            ),
        )
    }

    override suspend fun currentTemplateName(appSource: AppSource): String? =
        mutex.withLock { currentTemplateNameLocked(appSource) }

    override suspend fun setCurrentTemplateName(name: String?, appSource: AppSource) = mutex.withLock {
        setCurrentTemplateNameLocked(name, appSource)
    }

    override suspend fun templates(): List<ProfileTemplate> = mutex.withLock { templatesLocked() }

    override suspend fun saveTemplate(name: String, profile: MarketProfile, appSource: AppSource) = mutex.withLock {
        val cleanName = name.trim().ifBlank { nextTemplateNameLocked() }
        val next = (templatesLocked().filterNot { it.name == cleanName } + ProfileTemplate(cleanName, profile))
            .sortedBy { it.name }
        writeTemplatesLocked(next)
        applyTemplateLocked(appSource, cleanName)
        Unit
    }

    override suspend fun applyTemplate(name: String, appSource: AppSource): Boolean = mutex.withLock {
        applyTemplateLocked(appSource, name)
    }

    override suspend fun deleteTemplate(name: String): Boolean = mutex.withLock {
        val current = templatesLocked()
        if (current.none { it.name == name }) return@withLock false
        val selectedSources = AppSource.entries.filter { currentTemplateNameLocked(it) == name }
        writeTemplatesLocked(current.filterNot { it.name == name })
        selectedSources.forEach { setCurrentTemplateNameLocked(null, it) }
        if (selectedSources.isNotEmpty()) cached.clear()
        true
    }

    override suspend fun isOverridden(name: String, appSource: AppSource): Boolean = mutex.withLock {
        name in readOverrideKeysLocked(appSource)
    }

    override suspend fun save(profile: MarketProfile) = mutex.withLock {
        saveOverridesLocked(profile, MarketProfileFields.ALL.toSet(), AppSource.XIAOMI)
    }

    override suspend fun save(profile: MarketProfile, overridden: Set<String>, appSource: AppSource) = mutex.withLock {
        saveOverridesLocked(profile, overridden, appSource)
    }

    override suspend fun resetField(name: String, appSource: AppSource) = mutex.withLock {
        val next = readOverrideKeysLocked(appSource).toMutableSet().also { it.remove(name) }
        preferences.remove(ProfilePreferenceKeys.field(appSource, name))
        writeOverrideKeysLocked(appSource, next)
        setCurrentTemplateNameLocked(null, appSource)
        refreshCachedProfileLocked(appSource)
    }

    override suspend fun resetAll(appSource: AppSource) = mutex.withLock {
        clearOverridesLocked(appSource)
        setCurrentTemplateNameLocked(null, appSource)
        refreshCachedProfileLocked(appSource)
    }

    override suspend fun syncFromServerIfDue() = withContext(Dispatchers.Default) {
        val profile = mutex.withLock {
            val now = epochMillis()
            val last = preferences.read(ProfilePreferenceKeys.LastServerSync)?.toLongOrNull() ?: 0L
            val hasServerDctx = !preferences.read(XiaomiIdentityPreferenceKeys.ServerDeviceContext).isNullOrBlank()
            // Old builds stored a raw FID as dctx. Force one migration sync until /expId supplies
            // the encrypted server context, even when config versions were synced recently.
            if (now - last < SYNC_INTERVAL_MS && hasServerDctx) return@withLock null
            loadLocked()
        } ?: return@withContext

        val result = runCatching { api.syncServerVersions(profile) }.getOrNull() ?: return@withContext
        mutex.withLock {
            preferences.put(ProfilePreferenceKeys.LastServerSync, epochMillis().toString())
            var changed = false
            if (result.webResVersion.isNotBlank()) {
                preferences.put(ProfilePreferenceKeys.SyncedWebResource, result.webResVersion)
                changed = true
            }
            if (result.pageConfigVersion.isNotBlank()) {
                preferences.put(ProfilePreferenceKeys.SyncedPageConfig, result.pageConfigVersion)
                changed = true
            }
            if (changed) refreshCachedProfileLocked(AppSource.XIAOMI)
        }
    }

    private suspend fun loadLocked(appSource: AppSource = AppSource.XIAOMI): MarketProfile {
        cached[appSource]?.let { return it }
        val defaults = deviceDefaults.current()
        val preset = presetProfile(defaults, stableIdLocked(appSource), appSource)
        val device = deviceProfile(preset, defaults)
        val useDevice = currentSourceLocked(appSource) == ProfileSource.DEVICE &&
                canUseCurrentDevice(appSource, defaults)
        val syncedVersions = mapOf(
            "webResVersion" to preferences.read(ProfilePreferenceKeys.SyncedWebResource),
            "pageConfigVersion" to preferences.read(ProfilePreferenceKeys.SyncedPageConfig),
        )
        return resolve(preset, device, useDevice, overridesLocked(appSource), syncedVersions)
            .also {
                cached[appSource] = it
                if (appSource == AppSource.XIAOMI) xiaomiClient.updateUserAgent(it)
            }
    }

    private suspend fun oppoRequestContextLocked(region: OppoStoreRegion): OppoRequestContext {
        val defaults = region.oppoRequestContext()
        return OppoRequestContext(
            userRegion = preferences.read(ProfilePreferenceKeys.oppoUserRegion(region))
                ?: defaults.userRegion,
            systemLocale = preferences.read(ProfilePreferenceKeys.oppoSystemLocale(region))
                ?: defaults.systemLocale,
            supportedLocales = preferences.read(ProfilePreferenceKeys.oppoSupportedLocales(region))
                ?: defaults.supportedLocales,
            locale = preferences.read(ProfilePreferenceKeys.oppoLocale(region))
                ?: defaults.locale,
        )
    }

    private suspend fun samsungRequestContextLocked(region: SamsungStoreRegion): SamsungRequestContext {
        val defaults = region.requestContext()
        return SamsungRequestContext(
            countryCode = preferences.read(ProfilePreferenceKeys.samsungCountryCode(region))
                ?: defaults.countryCode,
            language = preferences.read(ProfilePreferenceKeys.samsungLanguage(region))
                ?: defaults.language,
            mcc = preferences.read(ProfilePreferenceKeys.samsungMcc(region)) ?: defaults.mcc,
            mnc = preferences.read(ProfilePreferenceKeys.samsungMnc(region)) ?: defaults.mnc,
            csc = preferences.read(ProfilePreferenceKeys.samsungCsc(region)) ?: defaults.csc,
        )
    }

    private suspend fun refreshCachedProfileLocked(appSource: AppSource) {
        cached.remove(appSource)
        loadLocked(appSource)
    }

    private suspend fun currentSourceLocked(appSource: AppSource = AppSource.XIAOMI): ProfileSource =
        when (preferences.read(ProfilePreferenceKeys.source(appSource))) {
            "device" -> ProfileSource.DEVICE
            "preset" -> ProfileSource.PRESET
            else -> defaultProfileSource(appSource, canUseDevice(appSource))
        }

    private suspend fun currentTemplateNameLocked(appSource: AppSource): String? {
        val name = preferences.read(ProfilePreferenceKeys.currentTemplate(appSource))
            ?.takeIf { it.isNotBlank() } ?: return null
        return name.takeIf { selected -> templatesLocked().any { it.name == selected } }
    }

    private suspend fun setCurrentTemplateNameLocked(name: String?, appSource: AppSource) {
        if (name.isNullOrBlank()) {
            preferences.remove(ProfilePreferenceKeys.currentTemplate(appSource))
        } else {
            preferences.put(ProfilePreferenceKeys.currentTemplate(appSource), name)
        }
    }

    private suspend fun templatesLocked(): List<ProfileTemplate> {
        val raw = preferences.read(ProfilePreferenceKeys.Templates)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val array = runCatching { LenientJson.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
        return buildList {
            array.forEach { element ->
                val value = element as? JsonObject ?: return@forEach
                val name = value.str("name").trim()
                val profileObject = value.obj("profile") ?: return@forEach
                if (name.isNotBlank()) add(ProfileTemplate(name, profileObject.toMarketProfileLocked()))
            }
        }
    }

    private suspend fun applyTemplateLocked(appSource: AppSource, name: String): Boolean {
        val template = templatesLocked().firstOrNull { it.name == name } ?: return false
        preferences.put(ProfilePreferenceKeys.source(appSource), "preset")
        saveOverridesLocked(template.profile, MarketProfileFields.ALL.toSet(), appSource, name)
        return true
    }

    private suspend fun saveOverridesLocked(
        profile: MarketProfile,
        overridden: Set<String>,
        appSource: AppSource,
        selectedTemplateName: String? = null,
    ) {
        val previous = readOverrideKeysLocked(appSource)
        (previous - overridden).forEach { preferences.remove(ProfilePreferenceKeys.field(appSource, it)) }
        overridden.forEach { name ->
            preferences.put(ProfilePreferenceKeys.field(appSource, name), MarketProfileFields.valueOf(profile, name))
        }
        writeOverrideKeysLocked(appSource, overridden)
        setCurrentTemplateNameLocked(selectedTemplateName, appSource)
        refreshCachedProfileLocked(appSource)
    }

    private suspend fun overridesLocked(appSource: AppSource): Map<String, String> = buildMap {
        readOverrideKeysLocked(appSource).forEach { name ->
            preferences.read(ProfilePreferenceKeys.field(appSource, name))
                ?.takeIf { it.isNotBlank() }
                ?.let { put(name, it) }
        }
    }

    private suspend fun clearOverridesLocked(appSource: AppSource) {
        readOverrideKeysLocked(appSource).forEach { preferences.remove(ProfilePreferenceKeys.field(appSource, it)) }
        preferences.remove(ProfilePreferenceKeys.overrides(appSource))
    }

    private suspend fun readOverrideKeysLocked(appSource: AppSource): Set<String> {
        val raw = preferences.read(ProfilePreferenceKeys.overrides(appSource))
            ?.takeIf { it.isNotBlank() } ?: return emptySet()
        val array = runCatching { LenientJson.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptySet()
        return array.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .filter { it in MarketProfileFields.ALL }
            .toSet()
    }

    private suspend fun writeOverrideKeysLocked(appSource: AppSource, keys: Set<String>) {
        val array = buildJsonArray { keys.sorted().forEach { add(it) } }
        preferences.put(ProfilePreferenceKeys.overrides(appSource), array.toString())
    }

    private suspend fun writeTemplatesLocked(items: List<ProfileTemplate>) {
        val array = buildJsonArray {
            items.forEach { item ->
                add(buildJsonObject {
                    put("name", item.name)
                    put("profile", item.profile.toJson())
                })
            }
        }
        preferences.put(ProfilePreferenceKeys.Templates, array.toString())
    }

    private suspend fun nextTemplateNameLocked(): String {
        val used = templatesLocked().mapTo(mutableSetOf()) { it.name }
        var index = 1
        while ("模板$index" in used) index++
        return "模板$index"
    }

    private suspend fun stableIdLocked(appSource: AppSource): String {
        preferences.read(ProfilePreferenceKeys.field(appSource, "instanceId"))
            ?.takeIf { it.length == 36 && it.count { ch -> ch == '-' } == 4 }
            ?.let { return it }
        val value = randomUuid()
        preferences.put(ProfilePreferenceKeys.field(appSource, "instanceId"), value)
        return value
    }

    private fun presetProfile(
        defaults: DeviceDefaults,
        instanceId: String,
        appSource: AppSource = AppSource.XIAOMI,
    ): MarketProfile = when (appSource) {
        AppSource.OPPO -> oppoPresetProfile(defaults, instanceId)
        AppSource.VIVO -> vivoPresetProfile(defaults, instanceId)
        AppSource.SAMSUNG -> samsungPresetProfile(defaults, instanceId)
        AppSource.HONOR -> honorPresetProfile(defaults, instanceId)
        AppSource.HUAWEI -> huaweiPresetProfile(defaults, instanceId)
        AppSource.XIAOMI, AppSource.WANDOUJIA, AppSource.TAPTAP, AppSource.KUAIBAO, AppSource.FDROID, AppSource.COOLAPK, AppSource.TENCENT -> xiaomiPresetProfile(defaults, instanceId)
    }

    private fun xiaomiPresetProfile(defaults: DeviceDefaults, instanceId: String): MarketProfile = MarketProfile(
        co = "CN",
        la = defaults.language.ifBlank { "zh" },
        lo = "CN",
        cpuArchitecture = "arm64-v8a",
        device = "popsicle",
        model = "2509FPN0BC",
        os = "OS3.0.315.0.WPBCNXM",
        osV2 = "OS3.0.315.0.WPBCNXM",
        androidVersion = "16",
        sdk = "36",
        resolution = "1200*2608",
        densityDpi = "480",
        densityScaleFactor = "3.0",
        miuiBigVersionCode = "816",
        miuiBigVersionName = "V816",
        osBigVersionCode = "3",
        osBigVersionName = "OS3.0",
        marketVersion = XiaomiProtocol.VERSION_CODE,
        pageConfigVersion = "18432101",
        webResVersion = "3193",
        hybridFrameworkVersion = "13170201",
        buildId = "BP2A.250605.031.A3",
        instanceId = instanceId,
        hasGMSCore = "true",
        supportedIslandVersion = "3",
    )

    /** Anonymous realme/HeyTap profile used when the current device is not in the OPPO family. */
    private fun oppoPresetProfile(defaults: DeviceDefaults, instanceId: String): MarketProfile = MarketProfile(
        co = "CN",
        la = defaults.language.ifBlank { "zh" },
        lo = "CN",
        cpuArchitecture = "arm64-v8a",
        device = "RMX5062",
        model = "RMX5062",
        // OPPO's OCS header carries this firmware component, while User-Agent uses V16.1.0.
        os = "16.0.8.301",
        osV2 = "V16.1.0",
        androidVersion = "16",
        sdk = "36",
        resolution = "1272*2800",
        densityDpi = "568",
        densityScaleFactor = "3.55",
        miuiBigVersionCode = "",
        miuiBigVersionName = "",
        osBigVersionCode = "16",
        osBigVersionName = "V16.1.0",
        marketVersion = "122280",
        pageConfigVersion = "18432101",
        webResVersion = "3193",
        hybridFrameworkVersion = "",
        buildId = "BP2A.250605.015",
        instanceId = instanceId,
        hasGMSCore = "true",
        supportedIslandVersion = "1",
    )

    /** Anonymous Galaxy S24 Ultra profile used when the host is not a Samsung device. */
    private fun samsungPresetProfile(defaults: DeviceDefaults, instanceId: String): MarketProfile = MarketProfile(
        co = "CN",
        la = defaults.language.ifBlank { "zh" },
        lo = "CN",
        cpuArchitecture = "arm64-v8a",
        device = "e3q",
        model = "SM-S9280",
        os = "16",
        osV2 = "One UI 8",
        androidVersion = "16",
        sdk = "36",
        resolution = "1440*3120",
        densityDpi = "560",
        densityScaleFactor = "3.5",
        miuiBigVersionCode = "",
        miuiBigVersionName = "",
        osBigVersionCode = "8",
        osBigVersionName = "One UI 8",
        marketVersion = "460811300",
        pageConfigVersion = "",
        webResVersion = "",
        hybridFrameworkVersion = "",
        buildId = "BP2A.250605.031.A3",
        instanceId = instanceId,
        hasGMSCore = "true",
        supportedIslandVersion = "",
    )

    /** Captured Honor Magic7 preset used when current-device mode is unavailable. */
    private fun honorPresetProfile(defaults: DeviceDefaults, instanceId: String): MarketProfile = MarketProfile(
        co = "CN",
        la = "zh",
        lo = "CN",
        cpuArchitecture = "arm64-v8a",
        device = "PTP-AN00",
        model = "HONOR Magic7",
        os = "16",
        osV2 = "MagicOS_10.0.0",
        androidVersion = "16",
        sdk = "36",
        resolution = "1264*2800",
        densityDpi = "560",
        densityScaleFactor = "3.5",
        miuiBigVersionCode = "",
        miuiBigVersionName = "",
        osBigVersionCode = "10",
        osBigVersionName = "MagicOS 10.0.0",
        marketVersion = "160107301",
        pageConfigVersion = "",
        webResVersion = "",
        hybridFrameworkVersion = "",
        buildId = "HONORPTP-AN00",
        instanceId = instanceId,
        hasGMSCore = "true",
        supportedIslandVersion = "",
        hman = "HONOR",
        htype = "PTP-AN00",
        spreadModelName = "HONOR Magic7",
        deliveryCountry = "CN",
        roamingCountry = "CN",
        osVer = "16",
        magicVersion = "MagicOS_10.0.0",
        androidApiVersion = "36",
        apkVer = "160107301",
        apkVerName = "16.1.7.301",
        language = "zh_CN",
        dpi = "560",
        cpu = "arm64-v8a",
        supportGms = "0",
        terminalType = "1",
        // Official retail devices expose ro.logsystem.usertype=1. 0 matches no package strategy.
        honorUserType = "1",
        honorDeviceMode = "1",
        honorIsParallelSpace = "1",
    )

    private fun deviceProfile(preset: MarketProfile, defaults: DeviceDefaults): MarketProfile = MarketProfile(
        co = defaults.co,
        la = defaults.language.ifBlank { "zh" },
        lo = defaults.lo,
        cpuArchitecture = defaults.cpuArchitecture,
        device = defaults.device,
        model = defaults.model,
        os = defaults.os,
        osV2 = defaults.osV2,
        androidVersion = defaults.androidVersion,
        sdk = defaults.sdk,
        resolution = defaults.resolution,
        densityDpi = defaults.densityDpi,
        densityScaleFactor = defaults.densityScaleFactor,
        miuiBigVersionCode = defaults.miuiBigVersionCode,
        miuiBigVersionName = defaults.miuiBigVersionName,
        osBigVersionCode = defaults.osBigVersionCode,
        osBigVersionName = defaults.osBigVersionName,
        marketVersion = preset.marketVersion,
        pageConfigVersion = preset.pageConfigVersion,
        webResVersion = preset.webResVersion,
        hybridFrameworkVersion = defaults.hybridFrameworkVersion,
        buildId = defaults.buildId,
        instanceId = preset.instanceId,
        hasGMSCore = defaults.hasGMSCore,
        supportedIslandVersion = defaults.supportedIslandVersion,
        hman = defaults.manufacturer.ifBlank { preset.hman },
        htype = defaults.model.ifBlank { preset.htype },
        spreadModelName = defaults.honorMarketingName,
        deliveryCountry = defaults.co.ifBlank { preset.deliveryCountry },
        roamingCountry = defaults.lo.ifBlank { preset.roamingCountry },
        osVer = defaults.androidVersion.ifBlank { preset.osVer },
        magicVersion = defaults.magicVersion.ifBlank { preset.magicVersion },
        androidApiVersion = defaults.sdk.ifBlank { preset.androidApiVersion },
        apkVer = preset.apkVer,
        apkVerName = preset.apkVerName,
        language = defaults.language.replace('-', '_').let { value ->
            if ('_' in value) value else "${value}_${defaults.co.ifBlank { "CN" }}"
        },
        dpi = defaults.densityDpi.ifBlank { preset.dpi },
        cpu = defaults.cpuArchitecture.substringBefore(',').ifBlank { preset.cpu },
        supportGms = if (defaults.co.equals("CN", ignoreCase = true)) {
            "0"
        } else if (defaults.hasGMSCore.toBooleanStrictOrNull() == true) {
            "1"
        } else {
            "0"
        },
        terminalType = defaults.honorTerminalType,
        honorAndroidId = defaults.honorAndroidId,
        honorUdid = defaults.honorUdid,
        honorOaid = defaults.honorOaid,
        honorMagicSysVersion = defaults.honorMagicSysVersion,
        honorUserType = defaults.honorUserType,
        honorDeviceMode = defaults.honorDeviceMode,
        honorIsParallelSpace = defaults.honorIsParallelSpace,
    )

    private fun resolve(
        preset: MarketProfile,
        device: MarketProfile,
        useDevice: Boolean,
        overrides: Map<String, String>,
        syncedVersions: Map<String, String?>,
    ): MarketProfile {
        fun pick(name: String, presetValue: String, deviceValue: String): String {
            if (name in SYNC_MANAGED) syncedVersions[name]?.takeIf { it.isNotBlank() }?.let { return it }
            overrides[name]?.let { return it }
            return selectProfileField(
                presetValue = presetValue,
                deviceValue = deviceValue,
                useDevice = useDevice,
                appLevel = name in APP_LEVEL,
                deviceBacked = name in DEVICE_BACKED,
            )
        }
        return MarketProfile(
            co = pick("co", preset.co, device.co),
            la = pick("la", preset.la, device.la),
            lo = pick("lo", preset.lo, device.lo),
            cpuArchitecture = pick("cpuArchitecture", preset.cpuArchitecture, device.cpuArchitecture),
            device = pick("device", preset.device, device.device),
            model = pick("model", preset.model, device.model),
            os = pick("os", preset.os, device.os),
            osV2 = pick("osV2", preset.osV2, device.osV2),
            androidVersion = pick("androidVersion", preset.androidVersion, device.androidVersion),
            sdk = pick("sdk", preset.sdk, device.sdk),
            resolution = pick("resolution", preset.resolution, device.resolution),
            densityDpi = pick("densityDpi", preset.densityDpi, device.densityDpi),
            densityScaleFactor = pick("densityScaleFactor", preset.densityScaleFactor, device.densityScaleFactor),
            miuiBigVersionCode = pick("miuiBigVersionCode", preset.miuiBigVersionCode, device.miuiBigVersionCode),
            miuiBigVersionName = pick("miuiBigVersionName", preset.miuiBigVersionName, device.miuiBigVersionName),
            osBigVersionCode = pick("osBigVersionCode", preset.osBigVersionCode, device.osBigVersionCode),
            osBigVersionName = pick("osBigVersionName", preset.osBigVersionName, device.osBigVersionName),
            marketVersion = pick("marketVersion", preset.marketVersion, device.marketVersion),
            pageConfigVersion = pick("pageConfigVersion", preset.pageConfigVersion, device.pageConfigVersion),
            webResVersion = pick("webResVersion", preset.webResVersion, device.webResVersion),
            hybridFrameworkVersion = pick("hybridFrameworkVersion", preset.hybridFrameworkVersion, device.hybridFrameworkVersion),
            buildId = pick("buildId", preset.buildId, device.buildId),
            instanceId = pick("instanceId", preset.instanceId, device.instanceId),
            hasGMSCore = pick("hasGMSCore", preset.hasGMSCore, device.hasGMSCore),
            supportedIslandVersion = pick("supportedIslandVersion", preset.supportedIslandVersion, device.supportedIslandVersion),
            hman = pick("hman", preset.hman, device.hman),
            htype = pick("htype", preset.htype, device.htype),
            spreadModelName = pick("spreadModelName", preset.spreadModelName, device.spreadModelName),
            deliveryCountry = pick("deliveryCountry", preset.deliveryCountry, device.deliveryCountry),
            roamingCountry = pick("roamingCountry", preset.roamingCountry, device.roamingCountry),
            osVer = pick("osVer", preset.osVer, device.osVer),
            magicVersion = pick("magicVersion", preset.magicVersion, device.magicVersion),
            androidApiVersion = pick("androidApiVersion", preset.androidApiVersion, device.androidApiVersion),
            apkVer = pick("apkVer", preset.apkVer, device.apkVer),
            apkVerName = pick("apkVerName", preset.apkVerName, device.apkVerName),
            language = pick("language", preset.language, device.language),
            dpi = pick("dpi", preset.dpi, device.dpi),
            cpu = pick("cpu", preset.cpu, device.cpu),
            supportGms = pick("supportGms", preset.supportGms, device.supportGms),
            terminalType = pick("terminalType", preset.terminalType, device.terminalType),
            honorAndroidId = if (useDevice) device.honorAndroidId else preset.honorAndroidId,
            honorUdid = if (useDevice) device.honorUdid else preset.honorUdid,
            honorOaid = if (useDevice) device.honorOaid else preset.honorOaid,
            honorMagicSysVersion = if (useDevice) {
                device.honorMagicSysVersion
            } else {
                preset.honorMagicSysVersion
            },
            honorUserType = if (useDevice) device.honorUserType else preset.honorUserType,
            honorDeviceMode = if (useDevice) device.honorDeviceMode else preset.honorDeviceMode,
            honorIsParallelSpace = if (useDevice) {
                device.honorIsParallelSpace
            } else {
                preset.honorIsParallelSpace
            },
        )
    }

    private fun MarketProfile.toJson(): JsonObject = buildJsonObject {
        MarketProfileFields.ALL.forEach { name -> put(name, MarketProfileFields.valueOf(this@toJson, name)) }
    }

    private suspend fun JsonObject.toMarketProfileLocked(): MarketProfile {
        val defaults = deviceDefaults.current()
        var profile = presetProfile(defaults, stableIdLocked(AppSource.XIAOMI))
        MarketProfileFields.ALL.forEach { name ->
            profile = MarketProfileFields.set(profile, name, str(name, MarketProfileFields.valueOf(profile, name)))
        }
        return profile
    }

    private fun ProfileSource.token(): String = if (this == ProfileSource.DEVICE) "device" else "preset"

    private fun randomUuid(): String {
        fun part(length: Int): String = (1..length).joinToString("") { "0123456789abcdef".random().toString() }
        return "${part(8)}-${part(4)}-${part(4)}-${part(4)}-${part(12)}"
    }

    private companion object {
        const val SYNC_INTERVAL_MS = 86_400_000L
        val DEVICE_BACKED = setOf(
            "cpuArchitecture", "resolution", "densityDpi", "densityScaleFactor", "sdk", "androidVersion",
            "hybridFrameworkVersion",
        )
        val APP_LEVEL = setOf(
            "marketVersion", "pageConfigVersion", "webResVersion", "instanceId",
            "apkVer", "apkVerName",
        )
        val SYNC_MANAGED = setOf("webResVersion", "pageConfigVersion")
    }
}

/** Stable anonymous Huawei profile used by the preset source. */
internal fun huaweiPresetProfile(
    @Suppress("UNUSED_PARAMETER") defaults: DeviceDefaults,
    instanceId: String,
): MarketProfile = MarketProfile(
    co = "CN",
    la = "zh",
    lo = "CN",
    cpuArchitecture = "arm64-v8a",
    device = "NOH-AN00",
    model = "NOH-AN00",
    os = "12",
    osV2 = "HarmonyOS",
    androidVersion = "12",
    sdk = "31",
    resolution = "1344*2772",
    densityDpi = "480",
    densityScaleFactor = "3.0",
    miuiBigVersionCode = "",
    miuiBigVersionName = "",
    osBigVersionCode = "",
    osBigVersionName = "",
    marketVersion = "160601300",
    pageConfigVersion = "",
    webResVersion = "",
    hybridFrameworkVersion = "",
    buildId = "HUAWEINOH-AN00",
    instanceId = instanceId,
    hasGMSCore = "false",
    supportedIslandVersion = "",
)

/** Default vivo phone profile; non-vivo protocol fields stay empty. */
internal fun vivoPresetProfile(
    @Suppress("UNUSED_PARAMETER") defaults: DeviceDefaults,
    instanceId: String,
): MarketProfile = MarketProfile(
    co = "CN",
    la = "zh",
    lo = "CN",
    cpuArchitecture = "arm64-v8a",
    device = "PD2408",
    model = "V2408A",
    os = "compiler260710223223",
    osV2 = "compiler260710223223",
    androidVersion = "16",
    sdk = "36",
    resolution = "1440*2560",
    densityDpi = "640",
    densityScaleFactor = "4.0",
    miuiBigVersionCode = "",
    miuiBigVersionName = "",
    osBigVersionCode = "",
    osBigVersionName = "",
    marketVersion = "61510",
    pageConfigVersion = "18411801",
    webResVersion = "3211",
    hybridFrameworkVersion = "",
    buildId = "V417IR",
    instanceId = instanceId,
    hasGMSCore = "true",
    supportedIslandVersion = "",
)

internal fun defaultProfileSource(appSource: AppSource, deviceAvailable: Boolean): ProfileSource = when {
    appSource == AppSource.OPPO -> ProfileSource.PRESET
    deviceAvailable -> ProfileSource.DEVICE
    else -> ProfileSource.PRESET
}

internal fun selectProfileField(
    presetValue: String,
    deviceValue: String,
    useDevice: Boolean,
    appLevel: Boolean,
    deviceBacked: Boolean,
): String = when {
    appLevel -> presetValue
    deviceBacked && useDevice -> deviceValue.ifBlank { presetValue }
    deviceBacked -> presetValue
    useDevice -> deviceValue
    else -> presetValue
}

internal fun canUseCurrentDevice(appSource: AppSource, defaults: DeviceDefaults): Boolean = when (appSource) {
    AppSource.XIAOMI -> defaults.isXiaomi && defaults.isComplete
    AppSource.OPPO -> defaults.isOppoFamily && defaults.isOppoComplete
    AppSource.VIVO -> defaults.isVivoFamily && defaults.isVivoComplete
    AppSource.SAMSUNG -> defaults.isSamsungFamily && defaults.isSamsungComplete
    AppSource.HONOR -> defaults.isHonorFamily && defaults.isHonorComplete &&
            defaults.honorAndroidId.isNotBlank()

    AppSource.HUAWEI -> defaults.isHuaweiFamily && defaults.isHuaweiComplete
    AppSource.WANDOUJIA, AppSource.TAPTAP, AppSource.KUAIBAO, AppSource.FDROID, AppSource.COOLAPK, AppSource.TENCENT -> false
}
