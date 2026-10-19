package com.app.market.domain.model.update

import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import kotlin.test.*

class UpdateSelectionTest {
    private fun app(version: String, code: Long, source: AppSource = AppSource.XIAOMI) =
        MarketAppInfo(1, "com.example.app", "Example", "", version, code, "", 0, ratingScore = 0.0,
            installedVersionCode = 1, installedVersionName = "1.0", source = source)

    @Test fun releaseBeatsRcWithLargerCodeAndBuildMetadataDoesNotChangeOrder() {
        val release = app("2.0+build.1", 20)
        val beta = app("2.0-rc.10", 200, AppSource.TENCENT)
        assertEquals(listOf(release), selectLatestUpdates(listOf(beta, release), UpdateSelectionPolicy(true)))
        assertEquals(0, PublicVersion.parse("v2.0+42")!!.compareTo(PublicVersion.parse("2.0.0+99")!!))
    }

    @Test fun prereleasesAreOptInButInstalledTestChannelCanContinue() {
        val beta = app("2.0-beta1", 20)
        assertTrue(selectLatestUpdates(listOf(beta)).isEmpty())
        assertEquals(listOf(beta), selectLatestUpdates(listOf(beta), UpdateSelectionPolicy(true)))
        assertEquals(1, selectLatestUpdates(listOf(beta.copy(installedVersionName = "2.0-alpha2"))).size)
        assertTrue(selectLatestUpdates(listOf(beta.copy(installedVersionName = "2.0")), UpdateSelectionPolicy(true)).isEmpty())
    }

    @Test fun numericPrereleaseIdentifiersAndVeryLargeVersionSegmentsAreOrdered() {
        assertTrue(PublicVersion.parse("2.0-rc.10")!! > PublicVersion.parse("2.0-rc.2")!!)
        assertTrue(PublicVersion.parse("999999999999999999999.0")!! > PublicVersion.parse("99999999999999999999.0")!!)
        assertTrue(PublicVersion.parse("2.0-beta2")!! > PublicVersion.parse("2.0-beta1")!!)
    }

    @Test fun pinNeverFallsBackToOtherChannelAndCodeStillPreventsDowngrade() {
        val pinned = app("1.5", 15)
        val other = app("2.0", 20, AppSource.TENCENT)
        val policy = UpdateSelectionPolicy(preferredSources = mapOf(pinned.packageName to pinned.source))
        assertEquals(listOf(pinned), selectLatestUpdates(listOf(pinned, other), policy))
        assertTrue(selectLatestUpdates(listOf(other), policy).isEmpty())
        assertTrue(selectLatestUpdates(listOf(other.copy(installedVersionCode = 30))).isEmpty())
    }
}
