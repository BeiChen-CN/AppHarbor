package com.app.market.domain.model.install

/** A positive version code is required to distinguish an upgrade from a fresh install or a reinstall. */
object InstallCleanupPolicy {
    fun deleteAfterUpdate(
        enabled: Boolean,
        installAfterDownload: Boolean,
        installedVersionCode: Long?,
        targetVersionCode: Long,
    ): Boolean = enabled && installAfterDownload && installedVersionCode != null &&
        installedVersionCode > 0L && targetVersionCode > installedVersionCode

    /** Package broadcasts alone do not prove that this task's target version was installed. */
    fun installationConfirmed(targetVersionCode: Long, installedVersionCode: Long?): Boolean =
        installedVersionCode != null && installedVersionCode > 0L &&
            (targetVersionCode <= 0L || installedVersionCode >= targetVersionCode)
}
