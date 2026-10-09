package com.app.market.data.local.preferences

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstallerPreferenceKeysTest {
    @Test
    fun deleteAfterUpdateDefaultsToDisabled() {
        assertFalse(InstallerPreferenceKeys.DeleteAfterUpdate.default)
        kotlin.test.assertNotEquals(InstallerPreferenceKeys.SaveToDownloads, InstallerPreferenceKeys.DeleteAfterUpdate)
        kotlin.test.assertNotEquals(InstallerPreferenceKeys.LegacyDeleteAfterInstall, InstallerPreferenceKeys.DeleteAfterUpdate)
    }

    @Test
    fun deltaUpdatesDefaultToEnabled() {
        assertTrue(InstallerPreferenceKeys.DeltaUpdate.default)
    }

    @Test
    fun userActionNotRequiredDefaultsToDisabled() {
        assertFalse(InstallerPreferenceKeys.UserActionNotRequired.default)
    }
}
