package com.app.market.ui.util

import com.app.market.domain.repository.MainTabId
import com.app.market.resources.Res
import com.app.market.resources.nav_categories
import com.app.market.resources.nav_recommendations
import com.app.market.resources.nav_search
import com.app.market.resources.nav_settings
import com.app.market.resources.nav_updates
import org.jetbrains.compose.resources.StringResource

/** 底栏类别在设置里的显示名，与导航栏标签共用同一份资源。 */
val MainTabId.labelRes: StringResource
    get() = when (this) {
        MainTabId.RECOMMENDATIONS -> Res.string.nav_recommendations
        MainTabId.CATEGORY -> Res.string.nav_categories
        MainTabId.UPDATES -> Res.string.nav_updates
        MainTabId.SEARCH -> Res.string.nav_search
        MainTabId.SETTINGS -> Res.string.nav_settings
    }
