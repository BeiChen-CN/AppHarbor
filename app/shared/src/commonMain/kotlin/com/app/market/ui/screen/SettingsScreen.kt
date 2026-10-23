package com.app.market.ui.screen

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.preference.HomePage
import com.app.market.resources.*
import com.app.market.domain.repository.CommentSources
import com.app.market.domain.repository.FeaturePreferences
import com.app.market.domain.repository.RecommendationSources
import com.app.market.domain.model.update.UpdateSources
import com.app.market.ui.component.*
import com.app.market.ui.util.appSourceLabel
import com.app.market.viewmodel.InstallerSettingsViewModel
import com.app.market.viewmodel.UpdatesUiState
import com.app.market.viewmodel.UpdatesViewModel
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.*
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun SettingsTab(
    updatesViewModel: UpdatesViewModel,
    installerSettingsViewModel: InstallerSettingsViewModel,
    bottomPadding: Dp,
    appManagementSupported: Boolean = true,
    onNavigateDeviceProfile: () -> Unit,
    onNavigateInstaller: () -> Unit,
    onNavigateIgnored: () -> Unit,
    onNavigateManualUpdate: () -> Unit,
    onNavigateUpdateHistory: () -> Unit,
    onNavigateSavedPackages: () -> Unit,
    onNavigateDownloadingApps: () -> Unit,
    onNavigateAbout: () -> Unit,
    onNavigateTheme: () -> Unit,
) {
    val updatesState by updatesViewModel.uiState.collectAsStateWithLifecycle()
    val installerState by installerSettingsViewModel.uiState.collectAsStateWithLifecycle()
    val homePage by updatesViewModel.homePage.collectAsStateWithLifecycle()
    val searchSources by updatesViewModel.searchSources.collectAsStateWithLifecycle()
    val showAppComments by updatesViewModel.showAppComments.collectAsStateWithLifecycle()
    val showSameDeveloper by updatesViewModel.showSameDeveloper.collectAsStateWithLifecycle()
    val showPromotions by updatesViewModel.showPromotions.collectAsStateWithLifecycle()
    val detectClipboardLinks by updatesViewModel.detectClipboardLinks.collectAsStateWithLifecycle()
    val stripAppNameSubtitle by updatesViewModel.stripAppNameSubtitle.collectAsStateWithLifecycle()
    val selectedSource = AppSource.entries.firstOrNull { it in searchSources } ?: AppSource.Default.first()
    val selectedUpdateSource by updatesViewModel.updateSource.collectAsStateWithLifecycle()
    val features by updatesViewModel.featureSettings.collectAsStateWithLifecycle()
    val searchCapabilities = selectedSource.capabilities
    var sourcePicker by remember { mutableStateOf(false) }
    var pinsPicker by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val searchEntries = settingsSearchEntries(
        features = features,
        updatesState = updatesState,
        showAppComments = showAppComments,
        stripAppNameSubtitle = stripAppNameSubtitle,
        detectClipboardLinks = detectClipboardLinks,
        updatesViewModel = updatesViewModel,
        onNavigateTheme = onNavigateTheme,
        onNavigateDeviceProfile = onNavigateDeviceProfile,
        onNavigateInstaller = onNavigateInstaller,
        onNavigateDownloadingApps = onNavigateDownloadingApps,
        onNavigateSavedPackages = onNavigateSavedPackages,
        onNavigateUpdateHistory = onNavigateUpdateHistory,
        onNavigateIgnored = onNavigateIgnored,
        onNavigateManualUpdate = onNavigateManualUpdate,
        onNavigateAbout = onNavigateAbout,
    )

    MainTabScaffold(title = stringResource(Res.string.nav_settings)) { topPadding, backdropModifier, scrollBehavior ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().then(backdropModifier).scrollEndHaptic().overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = topPadding + PageVerticalPadding,
                bottom = bottomPadding + PageVerticalPadding,
            ),
        ) {
            item(key = "settings-search") {
                InputField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    onSearch = {},
                    expanded = searchQuery.isNotBlank(),
                    onExpandedChange = {},
                    label = stringResource(Res.string.settings_search),
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
            if (searchQuery.isBlank()) {
            settingsGroup("sources", Res.string.settings_sources_content, topPadding = 0.dp, rows = buildList {
                add(SettingsRow("recommendations") {
                    WindowDropdownPreference(title = stringResource(Res.string.recommendation_source),
                        summary = stringResource(Res.string.recommendation_source_summary),
                        items = RecommendationSources.map { appSourceLabel(it) },
                        selectedIndex = RecommendationSources.indexOf(features.recommendationSource),
                        onSelectedIndexChange = { updatesViewModel.setRecommendationSource(RecommendationSources[it]) })
                })
                add(SettingsRow("recommendation-updates") {
                    SwitchPreference(title = stringResource(Res.string.recommendation_updates),
                        summary = stringResource(Res.string.recommendation_updates_summary),
                        checked = features.showRecommendationUpdates,
                        onCheckedChange = updatesViewModel::setShowRecommendationUpdates)
                })
                add(SettingsRow("clipboard-links") {
                    SwitchPreference(title = stringResource(Res.string.clipboard_link_detect),
                        summary = stringResource(Res.string.clipboard_link_detect_summary),
                        checked = detectClipboardLinks,
                        onCheckedChange = updatesViewModel::setDetectClipboardLinks)
                })
                add(SettingsRow("search") {
                    WindowDropdownPreference(title = stringResource(Res.string.search_sources),
                        summary = stringResource(Res.string.search_sources_summary), items = AppSource.entries.map { appSourceLabel(it) },
                        selectedIndex = selectedSource.ordinal,
                        onSelectedIndexChange = { updatesViewModel.setSearchSource(AppSource.entries[it]) })
                })
                add(SettingsRow("comments-source") {
                    val options = listOf(null) + CommentSources
                    WindowDropdownPreference(title = stringResource(Res.string.comment_source),
                        summary = stringResource(Res.string.comment_source_summary),
                        items = options.map { it?.let { source -> appSourceLabel(source) } ?: stringResource(Res.string.follow_app_source) },
                        selectedIndex = options.indexOf(features.commentSource),
                        onSelectedIndexChange = { updatesViewModel.setCommentSource(options[it]) })
                })
                add(SettingsRow("comments") {
                    SwitchPreference(title = stringResource(Res.string.show_app_comments),
                        summary = stringResource(Res.string.show_app_comments_summary), checked = showAppComments,
                        onCheckedChange = updatesViewModel::setShowAppComments)
                })
                if (searchCapabilities.supportsSameDeveloperApps) add(SettingsRow("same-developer") {
                    SwitchPreference(title = stringResource(Res.string.show_same_developer),
                        summary = stringResource(Res.string.show_same_developer_summary), checked = showSameDeveloper,
                        onCheckedChange = updatesViewModel::setShowSameDeveloper)
                })
                if (searchCapabilities.supportsPromotions) add(SettingsRow("promotions") {
                    SwitchPreference(title = stringResource(Res.string.show_promotions),
                        summary = stringResource(Res.string.show_promotions_summary), checked = showPromotions,
                        onCheckedChange = updatesViewModel::setShowPromotions)
                })
                if (searchCapabilities.supportsSearchAdsFilter) add(SettingsRow("ads") {
                    SwitchPreference(title = stringResource(Res.string.remove_search_ads),
                        summary = stringResource(Res.string.remove_search_ads_summary), checked = updatesState.removeSearchAds,
                        onCheckedChange = updatesViewModel::setRemoveSearchAds)
                })
                if (searchCapabilities.supportsQuickAppFilter) add(SettingsRow("quick-games") {
                    SwitchPreference(title = stringResource(Res.string.filter_quick_games),
                        summary = stringResource(Res.string.filter_quick_games_summary), checked = updatesState.filterQuickGames,
                        onCheckedChange = updatesViewModel::setFilterQuickGames)
                })
                if (searchCapabilities.supportsReservationFilter) add(SettingsRow("reservations") {
                    SwitchPreference(title = stringResource(Res.string.filter_reservation_apps),
                        summary = stringResource(Res.string.filter_reservation_apps_summary), checked = updatesState.filterReservationApps,
                        onCheckedChange = updatesViewModel::setFilterReservationApps)
                })
                add(SettingsRow("device") {
                    ArrowPreference(title = stringResource(Res.string.device_profile), summary = stringResource(Res.string.device_profile_summary),
                        onClick = onNavigateDeviceProfile)
                })
            })
            settingsGroup("updates-install", Res.string.settings_updates_install, rows = buildList {
                if (appManagementSupported) {
                    add(SettingsRow("network-policy") {
                        SwitchPreference(title = stringResource(Res.string.only_unmetered_downloads),
                            summary = stringResource(Res.string.only_unmetered_downloads_summary), checked = features.onlyUnmeteredDownloads,
                            onCheckedChange = updatesViewModel::setOnlyUnmeteredDownloads)
                    })
                    add(SettingsRow("aggregate") {
                        SwitchPreference(title = stringResource(Res.string.aggregate_updates),
                            summary = stringResource(Res.string.aggregate_updates_summary), checked = features.aggregateUpdates,
                            onCheckedChange = updatesViewModel::setAggregateUpdates)
                    })
                    if (features.aggregateUpdates) {
                        add(SettingsRow("aggregate-sources") {
                            ArrowPreference(title = stringResource(Res.string.aggregate_sources),
                                summary = stringResource(Res.string.aggregate_sources_summary, features.aggregateSources.size), onClick = { sourcePicker = true })
                        })
                        add(SettingsRow("prereleases") {
                            SwitchPreference(title = stringResource(Res.string.include_prerelease_updates),
                                summary = stringResource(Res.string.include_prerelease_updates_summary), checked = features.includePrereleaseUpdates,
                                onCheckedChange = updatesViewModel::setIncludePrereleaseUpdates)
                        })
                        add(SettingsRow("pins") {
                            ArrowPreference(title = stringResource(Res.string.pinned_sources),
                                summary = stringResource(Res.string.pinned_sources_summary, features.preferredUpdateSources.size), onClick = { pinsPicker = true })
                        })
                    } else add(SettingsRow("update-source") {
                        WindowDropdownPreference(title = stringResource(Res.string.update_source),
                            summary = stringResource(Res.string.update_source_summary), items = UpdateSources.map { appSourceLabel(it) },
                            selectedIndex = UpdateSources.indexOf(selectedUpdateSource).coerceAtLeast(0),
                            onSelectedIndexChange = { updatesViewModel.setUpdateSource(UpdateSources[it]) })
                    })
                    add(SettingsRow("system-apps") {
                        SwitchPreference(title = stringResource(Res.string.show_system_apps),
                            summary = stringResource(Res.string.show_system_apps_summary), checked = updatesState.showSystemUpdates,
                            onCheckedChange = updatesViewModel::setShowSystemUpdates)
                    })
                    if (installerState.deltaUpdateSupported && (features.aggregateUpdates || selectedUpdateSource.capabilities.supportsDeltaUpdates)) {
                        add(SettingsRow("delta") {
                            SwitchPreference(title = stringResource(Res.string.installer_delta_update),
                                summary = stringResource(Res.string.installer_delta_update_summary), checked = installerState.deltaUpdateEnabled,
                                onCheckedChange = installerSettingsViewModel::setDeltaUpdateEnabled)
                        })
                        if (installerState.deltaUpdateEnabled) add(SettingsRow("delta-fallback") {
                            SwitchPreference(title = stringResource(Res.string.installer_delta_fallback_notice),
                                summary = stringResource(Res.string.installer_delta_fallback_notice_summary), checked = installerState.deltaFallbackNoticeEnabled,
                                onCheckedChange = installerSettingsViewModel::setDeltaFallbackNoticeEnabled)
                        })
                    }
                    add(SettingsRow("ignored") {
                        ArrowPreference(title = stringResource(Res.string.ignored_apps), summary = stringResource(Res.string.ignored_apps_summary), onClick = onNavigateIgnored)
                    })
                    add(SettingsRow("manual") {
                        ArrowPreference(title = stringResource(Res.string.manual_update), summary = stringResource(Res.string.manual_update_summary), onClick = onNavigateManualUpdate)
                    })
                    add(SettingsRow("history") {
                        ArrowPreference(title = stringResource(Res.string.update_history), summary = stringResource(Res.string.update_history_summary), onClick = onNavigateUpdateHistory)
                    })
                }
                add(SettingsRow("downloading") {
                    ArrowPreference(title = stringResource(Res.string.downloading_apps), summary = stringResource(Res.string.downloading_apps_summary), onClick = onNavigateDownloadingApps)
                })
                add(SettingsRow("saved") {
                    ArrowPreference(title = stringResource(Res.string.saved_packages), summary = stringResource(Res.string.saved_packages_summary), onClick = onNavigateSavedPackages)
                })
                if (appManagementSupported) {
                    add(SettingsRow("installer") {
                        ArrowPreference(title = stringResource(Res.string.installer_section), summary = stringResource(Res.string.installer_section_summary), onClick = onNavigateInstaller)
                    })
                    add(SettingsRow("delete-apk") {
                        SwitchPreference(title = stringResource(Res.string.installer_delete_after_update),
                            summary = stringResource(Res.string.installer_delete_after_update_summary), checked = installerState.deleteAfterUpdate,
                            onCheckedChange = installerSettingsViewModel::setDeleteAfterUpdate)
                    })
                    if (installerState.focusNotificationSupported) add(SettingsRow("focus-notification") {
                        SwitchPreference(title = stringResource(if (installerState.xiaomiIslandSupported) Res.string.xiaomi_island_optimization else Res.string.focus_notification_optimization),
                            summary = stringResource(Res.string.xiaomi_island_optimization_summary), checked = installerState.xiaomiIslandOptimizationEnabled,
                            onCheckedChange = installerSettingsViewModel::setXiaomiIslandOptimizationEnabled)
                    })
                }
            })
            settingsGroup("appearance", Res.string.settings_appearance_interaction, rows = listOf(
                SettingsRow("home") {
                    val options = if (appManagementSupported) listOf(HomePage.RECOMMENDATIONS, HomePage.UPDATES, HomePage.SEARCH)
                        else listOf(HomePage.RECOMMENDATIONS, HomePage.SEARCH)
                    WindowDropdownPreference(title = stringResource(Res.string.home_page), summary = stringResource(Res.string.home_page_summary),
                        items = options.map { stringResource(it.labelRes) }, selectedIndex = options.indexOf(homePage).coerceAtLeast(0),
                        onSelectedIndexChange = { updatesViewModel.setHomePage(options[it]) })
                },
                SettingsRow("name") {
                    SwitchPreference(title = stringResource(Res.string.strip_name_subtitle), summary = stringResource(Res.string.strip_name_subtitle_summary),
                        checked = stripAppNameSubtitle, onCheckedChange = updatesViewModel::setStripAppNameSubtitle)
                },
                SettingsRow("theme") {
                    ArrowPreference(title = stringResource(Res.string.theme), summary = stringResource(Res.string.theme_summary), onClick = onNavigateTheme)
                },
                SettingsRow("about") {
                    ArrowPreference(title = stringResource(Res.string.about), summary = stringResource(Res.string.about_summary), onClick = onNavigateAbout)
                },
            ))
            } else {
                val query = searchQuery.trim()
                val entries = searchEntries.filter { entry ->
                    entry.title.contains(query, ignoreCase = true) ||
                        entry.summary?.contains(query, ignoreCase = true) == true
                }
                if (entries.isEmpty()) {
                    item(key = "settings-search-empty") {
                        Text(
                            text = stringResource(Res.string.settings_search_no_results),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 32.dp),
                            color = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    itemsIndexed(entries, key = { index, entry -> "search-$index-${entry.title}" }) { index, entry ->
                        CardSegmentContainer(isFirst = index == 0, isLast = index == entries.lastIndex) {
                            SettingsSearchRow(entry)
                        }
                    }
                }
            }
        }
    }
    if (sourcePicker) AggregateSourcesDialog(features.aggregateSources, updatesViewModel::setAggregateSources, { sourcePicker = false })
    if (pinsPicker) PinnedSourcesDialog(features.preferredUpdateSources, updatesViewModel::setPreferredUpdateSource, { pinsPicker = false })
}

private data class SettingsRow(val key: String, val content: @Composable () -> Unit)

private sealed interface SettingsSearchEntry {
    val title: String
    val summary: String?

    data class Switch(
        override val title: String,
        override val summary: String?,
        val checked: Boolean,
        val onChecked: (Boolean) -> Unit,
    ) : SettingsSearchEntry

    data class Dropdown(
        override val title: String,
        override val summary: String?,
        val items: List<String>,
        val selectedIndex: Int,
        val onSelect: (Int) -> Unit,
    ) : SettingsSearchEntry

    data class Link(
        override val title: String,
        override val summary: String?,
        val onClick: () -> Unit,
    ) : SettingsSearchEntry
}

@Composable
private fun SettingsSearchRow(entry: SettingsSearchEntry) {
    when (entry) {
        is SettingsSearchEntry.Switch -> SwitchPreference(
            title = entry.title,
            summary = entry.summary,
            checked = entry.checked,
            onCheckedChange = entry.onChecked,
        )

        is SettingsSearchEntry.Dropdown -> OverlayDropdownPreference(
            items = entry.items,
            selectedIndex = entry.selectedIndex.coerceAtLeast(0),
            title = entry.title,
            summary = entry.summary,
            onSelectedIndexChange = entry.onSelect,
        )

        is SettingsSearchEntry.Link -> ArrowPreference(
            title = entry.title,
            summary = entry.summary,
            onClick = entry.onClick,
        )
    }
}

@Composable
private fun settingsSearchEntries(
    features: FeaturePreferences,
    updatesState: UpdatesUiState,
    showAppComments: Boolean,
    stripAppNameSubtitle: Boolean,
    detectClipboardLinks: Boolean,
    updatesViewModel: UpdatesViewModel,
    onNavigateTheme: () -> Unit,
    onNavigateDeviceProfile: () -> Unit,
    onNavigateInstaller: () -> Unit,
    onNavigateDownloadingApps: () -> Unit,
    onNavigateSavedPackages: () -> Unit,
    onNavigateUpdateHistory: () -> Unit,
    onNavigateIgnored: () -> Unit,
    onNavigateManualUpdate: () -> Unit,
    onNavigateAbout: () -> Unit,
): List<SettingsSearchEntry> = listOf(
    SettingsSearchEntry.Link(stringResource(Res.string.theme), stringResource(Res.string.theme_summary), onNavigateTheme),
    SettingsSearchEntry.Link(stringResource(Res.string.device_profile), stringResource(Res.string.device_profile_summary), onNavigateDeviceProfile),
    SettingsSearchEntry.Link(stringResource(Res.string.installer_section), stringResource(Res.string.installer_section_summary), onNavigateInstaller),
    SettingsSearchEntry.Link(stringResource(Res.string.downloading_apps), stringResource(Res.string.downloading_apps_summary), onNavigateDownloadingApps),
    SettingsSearchEntry.Link(stringResource(Res.string.saved_packages), stringResource(Res.string.saved_packages_summary), onNavigateSavedPackages),
    SettingsSearchEntry.Link(stringResource(Res.string.update_history), stringResource(Res.string.update_history_summary), onNavigateUpdateHistory),
    SettingsSearchEntry.Link(stringResource(Res.string.ignored_apps), stringResource(Res.string.ignored_apps_summary), onNavigateIgnored),
    SettingsSearchEntry.Link(stringResource(Res.string.manual_update), stringResource(Res.string.manual_update_summary), onNavigateManualUpdate),
    SettingsSearchEntry.Link(stringResource(Res.string.about), stringResource(Res.string.about_summary), onNavigateAbout),
    SettingsSearchEntry.Switch(
        stringResource(Res.string.recommendation_updates),
        stringResource(Res.string.recommendation_updates_summary),
        features.showRecommendationUpdates,
        updatesViewModel::setShowRecommendationUpdates,
    ),
    SettingsSearchEntry.Switch(
        stringResource(Res.string.clipboard_link_detect),
        stringResource(Res.string.clipboard_link_detect_summary),
        detectClipboardLinks,
        updatesViewModel::setDetectClipboardLinks,
    ),
    SettingsSearchEntry.Switch(
        stringResource(Res.string.show_app_comments),
        stringResource(Res.string.show_app_comments_summary),
        showAppComments,
        updatesViewModel::setShowAppComments,
    ),
    SettingsSearchEntry.Switch(
        stringResource(Res.string.only_unmetered_downloads),
        stringResource(Res.string.only_unmetered_downloads_summary),
        features.onlyUnmeteredDownloads,
        updatesViewModel::setOnlyUnmeteredDownloads,
    ),
    SettingsSearchEntry.Switch(
        stringResource(Res.string.aggregate_updates),
        stringResource(Res.string.aggregate_updates_summary),
        features.aggregateUpdates,
        updatesViewModel::setAggregateUpdates,
    ),
    SettingsSearchEntry.Switch(
        stringResource(Res.string.show_system_apps),
        stringResource(Res.string.show_system_apps_summary),
        updatesState.showSystemUpdates,
        updatesViewModel::setShowSystemUpdates,
    ),
    SettingsSearchEntry.Switch(
        stringResource(Res.string.strip_name_subtitle),
        stringResource(Res.string.strip_name_subtitle_summary),
        stripAppNameSubtitle,
        updatesViewModel::setStripAppNameSubtitle,
    ),
    SettingsSearchEntry.Dropdown(
        stringResource(Res.string.recommendation_source),
        stringResource(Res.string.recommendation_source_summary),
        RecommendationSources.map { appSourceLabel(it) },
        RecommendationSources.indexOf(features.recommendationSource),
        { updatesViewModel.setRecommendationSource(RecommendationSources[it]) },
    ),
)

private fun LazyListScope.settingsGroup(key: String, title: StringResource, topPadding: Dp = 20.dp, rows: List<SettingsRow>) {
    item("$key-title") { SectionTitle(stringResource(title), topPadding = topPadding) }
    itemsIndexed(rows, key = { _, row -> "$key-${row.key}" }) { index, row ->
        CardSegmentContainer(isFirst = index == 0, isLast = index == rows.lastIndex) { row.content() }
    }
}

private val HomePage.labelRes
    get() = when (this) {
        HomePage.UPDATES -> Res.string.nav_updates
        HomePage.SEARCH -> Res.string.nav_search
        HomePage.RECOMMENDATIONS -> Res.string.nav_recommendations
    }
