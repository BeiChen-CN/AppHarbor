package com.app.market.domain.model.install

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallCleanupPolicyTest {
    @Test
    fun cleanupIsOptInAndOnlyAppliesToAnUpgradeScheduledForInstallation() {
        assertTrue(InstallCleanupPolicy.deleteAfterUpdate(true, true, 10, 11))
        assertFalse(InstallCleanupPolicy.deleteAfterUpdate(false, true, 10, 11))
        assertFalse(InstallCleanupPolicy.deleteAfterUpdate(true, false, 10, 11))
        for (installed in listOf(null, 0L, -1L, 11L, 12L)) {
            assertFalse(InstallCleanupPolicy.deleteAfterUpdate(true, true, installed, 11), "Installed: $installed")
        }
        assertFalse(InstallCleanupPolicy.deleteAfterUpdate(true, true, 10, 0))
    }

    @Test
    fun unrelatedBroadcastsAndMissingPackagesCannotConfirmSuccess() {
        assertFalse(InstallCleanupPolicy.installationConfirmed(11, null))
        assertFalse(InstallCleanupPolicy.installationConfirmed(11, 0))
        assertFalse(InstallCleanupPolicy.installationConfirmed(11, 10))
        assertTrue(InstallCleanupPolicy.installationConfirmed(11, 11))
        assertTrue(InstallCleanupPolicy.installationConfirmed(11, 12))
        // Legacy stores without version codes can still confirm the package exists.
        assertTrue(InstallCleanupPolicy.installationConfirmed(0, 11))
    }

    @Test
    fun oldRequestsDoNotOptInToDeletionWhenCopiedOrRestored() {
        val request = InstallRequest("task", "example.app", "Example", "1", 1, emptyList())
        assertFalse(request.deleteAfterUpdate)
        assertFalse(request.copy(id = "retry").deleteAfterUpdate)
        assertTrue(request.copy(deleteAfterUpdate = true).copy(id = "restored").deleteAfterUpdate)
    }
}
