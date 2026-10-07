package com.app.market.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.app.market.domain.model.profile.requestContext
import com.app.market.domain.repository.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DeviceProfileViewModel(
    private val store: ProfileRepository,
) : ViewModel() {

    private val _profiles = MutableStateFlow<Map<AppSource, MarketProfile>>(emptyMap())
    val profiles: StateFlow<Map<AppSource, MarketProfile>> = _profiles.asStateFlow()

    private val _sources = MutableStateFlow<Map<AppSource, ProfileSource>>(emptyMap())
    val sources: StateFlow<Map<AppSource, ProfileSource>> = _sources.asStateFlow()

    private val _templateNames = MutableStateFlow<Map<AppSource, String?>>(emptyMap())
    val templateNames: StateFlow<Map<AppSource, String?>> = _templateNames.asStateFlow()

    private val _overriddenFields = MutableStateFlow<Map<AppSource, Set<String>>>(emptyMap())
    val overriddenFields: StateFlow<Map<AppSource, Set<String>>> = _overriddenFields.asStateFlow()

    private val _canUseDevice = MutableStateFlow<Map<AppSource, Boolean>>(emptyMap())
    val canUseDevice: StateFlow<Map<AppSource, Boolean>> = _canUseDevice.asStateFlow()

    private val _oppoStoreRegion = MutableStateFlow(OppoStoreRegion.CHINA)
    val oppoStoreRegion: StateFlow<OppoStoreRegion> = _oppoStoreRegion.asStateFlow()

    private val _oppoRequestContext = MutableStateFlow(OppoStoreRegion.CHINA.oppoRequestContext())
    val oppoRequestContext: StateFlow<OppoRequestContext> = _oppoRequestContext.asStateFlow()

    private val _samsungStoreRegion = MutableStateFlow(SamsungStoreRegion.CHINA)
    val samsungStoreRegion: StateFlow<SamsungStoreRegion> = _samsungStoreRegion.asStateFlow()

    private val _samsungRequestContext = MutableStateFlow(SamsungStoreRegion.CHINA.requestContext())
    val samsungRequestContext: StateFlow<SamsungRequestContext> = _samsungRequestContext.asStateFlow()

    private val _templates = MutableStateFlow<List<ProfileTemplate>>(emptyList())
    val templates: StateFlow<List<ProfileTemplate>> = _templates.asStateFlow()

    private val _showSaveTemplateDialog = MutableStateFlow<AppSource?>(null)
    val showSaveTemplateDialog: StateFlow<AppSource?> = _showSaveTemplateDialog.asStateFlow()

    private val edited = mutableMapOf<AppSource, MutableSet<String>>()
    private val editedOppoRequestContexts = mutableMapOf<OppoStoreRegion, OppoRequestContext>()
    private val editedSamsungRequestContexts = mutableMapOf<SamsungStoreRegion, SamsungRequestContext>()

    init {
        viewModelScope.launch {
            refreshAll()
        }
    }

    fun setSource(source: ProfileSource, appSource: AppSource) = mutate {
        store.setSource(source, appSource)
        if (appSource == AppSource.SAMSUNG) resetSamsungRequestContexts()
        edited.remove(appSource)
        refreshAll()
    }

    fun setOppoStoreRegion(region: OppoStoreRegion) = mutate {
        store.setOppoStoreRegion(region)
        _oppoStoreRegion.value = region
        _oppoRequestContext.value = editedOppoRequestContexts[region] ?: store.oppoRequestContext(region)
    }

    fun setSamsungStoreRegion(region: SamsungStoreRegion) = mutate {
        store.setSamsungStoreRegion(region)
        _samsungStoreRegion.value = region
        _samsungRequestContext.value =
            editedSamsungRequestContexts[region] ?: store.samsungRequestContext(region)
    }

    fun updateOppoRequestContext(name: String, value: String) {
        val current = _oppoRequestContext.value
        val next = when (name) {
            "user-region" -> current.copy(userRegion = value)
            "system-locale" -> current.copy(systemLocale = value)
            "supported-locales" -> current.copy(supportedLocales = value)
            "locale" -> current.copy(locale = value)
            else -> return
        }
        editedOppoRequestContexts[_oppoStoreRegion.value] = next
        _oppoRequestContext.value = next
    }

    fun updateSamsungRequestContext(name: String, value: String) {
        val current = _samsungRequestContext.value
        val next = when (name) {
            "countryCode" -> current.copy(countryCode = value)
            "lang" -> current.copy(language = value)
            "mcc" -> current.copy(mcc = value)
            "mnc" -> current.copy(mnc = value)
            "csc" -> current.copy(csc = value)
            else -> return
        }
        editedSamsungRequestContexts[_samsungStoreRegion.value] = next
        _templateNames.update { it + (AppSource.SAMSUNG to null) }
        _samsungRequestContext.value = next
    }

    fun applyTemplate(name: String, appSource: AppSource) = mutate {
        if (store.applyTemplate(name, appSource)) {
            if (appSource == AppSource.SAMSUNG) resetSamsungRequestContexts()
            edited.remove(appSource)
            refreshAll()
        }
    }

    fun useCustom(appSource: AppSource) = mutate {
        val profile = _profiles.value[appSource] ?: return@mutate
        store.save(profile, fieldsFor(appSource).toSet(), appSource)
        store.setCurrentTemplateName(null, appSource)
        edited.remove(appSource)
        refreshAll()
    }

    fun update(appSource: AppSource, name: String, value: String) {
        val profile = _profiles.value[appSource] ?: return
        edited.getOrPut(appSource) { mutableSetOf() }.add(name)
        _templateNames.update { it + (appSource to null) }
        _profiles.update { it + (appSource to MarketProfileFields.set(profile, name, value)) }
        _overriddenFields.update { overridden ->
            overridden + (appSource to (overridden[appSource].orEmpty() + name))
        }
    }

    fun save(appSource: AppSource) {
        val oppoContextEdits = if (appSource == AppSource.OPPO) editedOppoRequestContexts.toMap() else emptyMap()
        val samsungContextEdits =
            if (appSource == AppSource.SAMSUNG) editedSamsungRequestContexts.toMap() else emptyMap()
        mutate {
            val profile = _profiles.value[appSource] ?: return@mutate
            store.save(
                profile,
                (edited[appSource].orEmpty() + _overriddenFields.value[appSource].orEmpty()).toSet(),
                appSource,
            )
            oppoContextEdits.forEach { (region, context) ->
                store.saveOppoRequestContext(region, context)
                if (editedOppoRequestContexts[region] == context) {
                    editedOppoRequestContexts.remove(region)
                }
            }
            samsungContextEdits.forEach { (region, context) ->
                store.saveSamsungRequestContext(region, context)
                if (editedSamsungRequestContexts[region] == context) {
                    editedSamsungRequestContexts.remove(region)
                }
            }
            edited.remove(appSource)
            refreshAll()
        }
    }

    fun setShowSaveTemplateDialog(appSource: AppSource?) {
        _showSaveTemplateDialog.value = appSource
    }

    fun defaultTemplateName(): String {
        val used = _templates.value.mapTo(mutableSetOf()) { it.name }
        var index = 1
        while ("模板$index" in used) index++
        return "模板$index"
    }

    fun saveTemplate(name: String, appSource: AppSource) = mutate {
        val profile = _profiles.value[appSource] ?: return@mutate
        store.saveTemplate(name.ifBlank { defaultTemplateName() }, profile, appSource)
        edited.remove(appSource)
        refreshAll()
        _showSaveTemplateDialog.value = null
    }

    fun deleteTemplate(name: String) = mutate {
        if (store.deleteTemplate(name)) {
            edited.clear()
            refreshAll()
        }
    }

    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private suspend fun resetSamsungRequestContexts() {
        editedSamsungRequestContexts.clear()
        SamsungStoreRegion.entries.forEach { region ->
            store.saveSamsungRequestContext(region, region.requestContext())
        }
    }

    private suspend fun refreshAll() {
        _templates.value = store.templates()
        EDITABLE_SOURCES.forEach { refreshSource(it) }
        val region = store.currentOppoStoreRegion()
        _oppoStoreRegion.value = region
        _oppoRequestContext.value = editedOppoRequestContexts[region] ?: store.oppoRequestContext(region)
        val samsungRegion = store.currentSamsungStoreRegion()
        _samsungStoreRegion.value = samsungRegion
        _samsungRequestContext.value =
            editedSamsungRequestContexts[samsungRegion] ?: store.samsungRequestContext(samsungRegion)
    }

    private suspend fun refreshSource(appSource: AppSource) {
        _sources.update { it + (appSource to store.currentSource(appSource)) }
        _templateNames.update { it + (appSource to store.currentTemplateName(appSource)) }
        _profiles.update { it + (appSource to store.load(appSource)) }
        _overriddenFields.update {
            it + (appSource to MarketProfileFields.ALL.filterTo(mutableSetOf()) { field ->
                store.isOverridden(field, appSource)
            })
        }
        _canUseDevice.update { it + (appSource to store.canUseDevice(appSource)) }
    }

    companion object {
        /** 有独立协议/指纹消费方的来源；豌豆荚请求不携带设备信息，故不在编辑页展示。 */
        val EDITABLE_SOURCES = listOf(
            AppSource.XIAOMI,
            AppSource.VIVO,
            AppSource.OPPO,
            AppSource.SAMSUNG,
            AppSource.HONOR,
            AppSource.HUAWEI,
        )

        private val HONOR_ONLY_FIELDS = setOf(
            "hman", "htype", "spreadModelName", "deliveryCountry", "roamingCountry",
            "osVer", "magicVersion", "androidApiVersion", "apkVer", "apkVerName",
            "language", "dpi", "cpu", "supportGms", "terminalType",
        )
        val FIELDS = MarketProfileFields.ALL.filterNot { it in HONOR_ONLY_FIELDS }

        // Only values consumed by OppoSigner/openId are editable in OPPO mode. Region/locale
        // request values are shown separately by DeviceProfileScreen.
        val OPPO_FIELDS = listOf(
            "co",
            "device",
            "model",
            "os",
            "osV2",
            "androidVersion",
            "sdk",
            "resolution",
            "osBigVersionName",
            "buildId",
            "instanceId",
        )

        // vivo's update protocol consumes these device/request values. Xiaomi/HyperOS-only
        // version, page-resource and island fields are intentionally hidden in vivo mode.
        val VIVO_FIELDS = listOf(
            "co",
            "la",
            "lo",
            "cpuArchitecture",
            "device",
            "model",
            "os",
            "osV2",
            "androidVersion",
            "sdk",
            "resolution",
            "densityDpi",
            "densityScaleFactor",
            "marketVersion",
            "buildId",
            "instanceId",
            "hasGMSCore",
        )

        val SAMSUNG_FIELDS = listOf(
            "cpuArchitecture",
            "model",
            "sdk",
            "instanceId",
        )

        val HUAWEI_FIELDS = listOf(
            "co",
            "la",
            "lo",
            "cpuArchitecture",
            "model",
            "androidVersion",
            "buildId",
            "instanceId",
        )

        val HONOR_FIELDS = listOf(
            "hman",
            "htype",
            "spreadModelName",
            "deliveryCountry",
            "roamingCountry",
            "osVer",
            "magicVersion",
            "androidApiVersion",
            "apkVer",
            "apkVerName",
            "language",
            "dpi",
            "resolution",
            "cpu",
            "supportGms",
            "terminalType",
            "instanceId",
        )

        fun fieldsFor(source: AppSource): List<String> = when (source) {
            AppSource.OPPO -> OPPO_FIELDS
            AppSource.VIVO -> VIVO_FIELDS
            AppSource.SAMSUNG -> SAMSUNG_FIELDS
            AppSource.HONOR -> HONOR_FIELDS
            AppSource.HUAWEI -> HUAWEI_FIELDS
            AppSource.XIAOMI, AppSource.WANDOUJIA, AppSource.TAPTAP, AppSource.KUAIBAO, AppSource.FDROID -> FIELDS
        }

        fun hasCustomSamsungRequestContext(
            region: SamsungStoreRegion,
            context: SamsungRequestContext,
        ): Boolean = context != region.requestContext()

        fun valueOf(profile: MarketProfile, name: String): String = MarketProfileFields.valueOf(profile, name)
    }
}
