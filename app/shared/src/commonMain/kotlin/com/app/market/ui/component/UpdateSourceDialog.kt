package com.app.market.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.resources.*
import com.app.market.ui.util.appSourceLabel
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun UpdateSourceDialog(
    appName: String,
    packageName: String,
    candidates: List<MarketAppInfo>,
    pinned: AppSource?,
    enabledSources: Set<AppSource>,
    onSelect: (AppSource?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = (enabledSources + listOfNotNull(pinned)).sortedBy { it.ordinal }
    WindowDialog(show = true, title = appName, onDismissRequest = onDismiss) {
        Column(modifier) {
            Text(packageName, modifier = Modifier.padding(horizontal = 16.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).scrollEndHaptic().overScrollVertical()) {
                item("auto") {
                    RadioButtonPreference(title = stringResource(Res.string.auto_latest_source), selected = pinned == null,
                        radioButtonLocation = RadioButtonLocation.End, onClick = { onSelect(null); onDismiss() })
                }
                items(options, key = { it.token }) { source ->
                    val app = candidates.firstOrNull { it.source == source }
                    RadioButtonPreference(title = appSourceLabel(source),
                        summary = app?.let { stringResource(Res.string.source_version_build, it.versionName, it.versionCode) }
                            ?: stringResource(if (source in enabledSources) Res.string.source_no_candidate else Res.string.source_disabled),
                        selected = pinned == source, radioButtonLocation = RadioButtonLocation.End,
                        onClick = { onSelect(source); onDismiss() })
                }
            }
            Text(stringResource(Res.string.pinned_source_hint), Modifier.padding(16.dp))
            AppTextButton(stringResource(Res.string.cancel), onDismiss, Modifier.fillMaxWidth())
        }
    }
}
