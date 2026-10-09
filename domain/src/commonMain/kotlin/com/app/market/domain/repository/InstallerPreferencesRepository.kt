package com.app.market.domain.repository

import com.app.market.domain.model.installer.InstallerMode

/** Persisted installer settings and availability of platform-dependent installer options. */
interface InstallerPreferencesRepository {
    suspend fun mode(): InstallerMode
    suspend fun setMode(mode: InstallerMode)
    suspend fun thirdPartyInstallerPackage(): String
    suspend fun setThirdPartyInstallerPackage(packageName: String)
    suspend fun saveToDownloads(): Boolean
    suspend fun setSaveToDownloads(enabled: Boolean)
    suspend fun deleteAfterUpdate(): Boolean
    suspend fun setDeleteAfterUpdate(enabled: Boolean)
    fun userActionNotRequiredConfigurable(): Boolean
    suspend fun userActionNotRequiredEnabled(): Boolean
    suspend fun setUserActionNotRequiredEnabled(enabled: Boolean)
    fun deltaUpdateSupported(): Boolean
    suspend fun deltaUpdateEnabled(): Boolean
    suspend fun setDeltaUpdateEnabled(enabled: Boolean)

    /** 增量回退全量时弹提示，用于暴露尚未实现的补丁格式分支。 */
    suspend fun deltaFallbackNoticeEnabled(): Boolean
    suspend fun setDeltaFallbackNoticeEnabled(enabled: Boolean)

    /** 设备支持焦点通知（OS2）或超级岛（OS3），控制开关显隐。 */
    fun focusNotificationSupported(): Boolean

    /** 设备为超级岛（OS3 带岛胶囊）模式，控制开关的标题措辞。 */
    fun xiaomiIslandSupported(): Boolean
    suspend fun xiaomiIslandOptimizationEnabled(): Boolean
    suspend fun setXiaomiIslandOptimizationEnabled(enabled: Boolean)
}
