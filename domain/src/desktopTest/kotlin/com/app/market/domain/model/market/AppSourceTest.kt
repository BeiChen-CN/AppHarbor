package com.app.market.domain.model.market

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppSourceTest {
    @Test
    fun tencentSupportsIndependentUpdates() {
        assertEquals(setOf(AppSource.TENCENT), AppSource.parse("tencent"))
        assertEquals("tencent", AppSource.serialize(setOf(AppSource.TENCENT)))
        assertTrue(AppSource.TENCENT.capabilities.supportsUpdates)
        assertFalse(AppSource.TENCENT.capabilities.supportsTodayFeed)
        assertFalse(AppSource.TENCENT.capabilities.supportsHistoricalVersions)
        assertFalse(AppSource.TENCENT.capabilities.supportsDeltaUpdates)
        assertFalse(AppSource.TENCENT.capabilities.supportsComments)
    }

    @Test
    fun coolapkSupportsSearchAndFullUpdatesWithoutUnsupportedFeatures() {
        assertEquals(setOf(AppSource.COOLAPK), AppSource.parse("coolapk"))
        assertEquals("coolapk", AppSource.serialize(setOf(AppSource.COOLAPK)))
        assertTrue(AppSource.COOLAPK.capabilities.supportsUpdates)
        assertFalse(AppSource.COOLAPK.capabilities.supportsTodayFeed)
        assertFalse(AppSource.COOLAPK.capabilities.supportsHistoricalVersions)
        assertFalse(AppSource.COOLAPK.capabilities.supportsDeltaUpdates)
        assertFalse(AppSource.COOLAPK.capabilities.supportsComments)
    }
    @Test
    fun legacyMultiSelectionMigratesToSingleSource() {
        assertEquals(setOf(AppSource.XIAOMI), AppSource.parse("xiaomi,vivo"))
        assertEquals(setOf(AppSource.WANDOUJIA), AppSource.parse("wandoujia"))
    }

    @Test
    fun capabilitiesMatchRoutingContract() {
        // 评论 / 同开发者应用目前只有小米与 OPPO 支持
        assertTrue(AppSource.XIAOMI.capabilities.supportsComments)
        assertTrue(AppSource.XIAOMI.capabilities.supportsSameDeveloperApps)
        assertTrue(AppSource.OPPO.capabilities.supportsComments)
        assertTrue(AppSource.OPPO.capabilities.supportsSameDeveloperApps)
        assertFalse(AppSource.VIVO.capabilities.supportsComments)
        assertFalse(AppSource.WANDOUJIA.capabilities.supportsComments)
        assertFalse(AppSource.SAMSUNG.capabilities.supportsComments)
        assertFalse(AppSource.HUAWEI.capabilities.supportsComments)

        // 小米 / vivo / 豌豆荚优先走 openLink 深链，其余优先打开已安装应用
        assertTrue(AppSource.XIAOMI.capabilities.prefersOpenLinkLaunch)
        assertTrue(AppSource.VIVO.capabilities.prefersOpenLinkLaunch)
        assertTrue(AppSource.WANDOUJIA.capabilities.prefersOpenLinkLaunch)
        assertFalse(AppSource.OPPO.capabilities.prefersOpenLinkLaunch)
        assertFalse(AppSource.SAMSUNG.capabilities.prefersOpenLinkLaunch)
        assertFalse(AppSource.HUAWEI.capabilities.prefersOpenLinkLaunch)

        // 只有三星不报告可信 delta 大小
        assertFalse(AppSource.SAMSUNG.capabilities.reportsDeltaSize)
        assertTrue(AppSource.XIAOMI.capabilities.reportsDeltaSize)
        assertTrue(AppSource.HUAWEI.capabilities.reportsDeltaSize)

        // 豌豆荚 / 三星 / 华为 / 荣耀没有独立今日页内容
        assertFalse(AppSource.WANDOUJIA.capabilities.supportsTodayFeed)
        assertFalse(AppSource.SAMSUNG.capabilities.supportsTodayFeed)
        assertFalse(AppSource.HUAWEI.capabilities.supportsTodayFeed)
        assertFalse(AppSource.HONOR.capabilities.supportsTodayFeed)
        assertTrue(AppSource.XIAOMI.capabilities.supportsTodayFeed)
        assertTrue(AppSource.VIVO.capabilities.supportsTodayFeed)
        assertTrue(AppSource.OPPO.capabilities.supportsTodayFeed)
        assertTrue(AppSource.DefaultTodaySource.capabilities.supportsTodayFeed)

        // 搜索过滤 / 详情优惠目前只有对应源会声明
        assertTrue(AppSource.XIAOMI.capabilities.supportsSearchAdsFilter)
        assertTrue(AppSource.XIAOMI.capabilities.supportsQuickAppFilter)
        assertTrue(AppSource.XIAOMI.capabilities.supportsReservationFilter)
        assertTrue(AppSource.XIAOMI.capabilities.supportsPromotions)
        assertTrue(AppSource.VIVO.capabilities.supportsSearchAdsFilter)
        assertTrue(AppSource.OPPO.capabilities.supportsSearchAdsFilter)
        assertTrue(AppSource.HONOR.capabilities.supportsSearchAdsFilter)
        assertTrue(AppSource.HUAWEI.capabilities.supportsSearchAdsFilter)
        assertFalse(AppSource.WANDOUJIA.capabilities.supportsSearchAdsFilter)
        assertFalse(AppSource.WANDOUJIA.capabilities.supportsQuickAppFilter)
        assertFalse(AppSource.WANDOUJIA.capabilities.supportsReservationFilter)
        assertFalse(AppSource.WANDOUJIA.capabilities.supportsPromotions)
        assertFalse(AppSource.SAMSUNG.capabilities.supportsSearchAdsFilter)
        assertFalse(AppSource.SAMSUNG.capabilities.supportsPromotions)
    }
}
