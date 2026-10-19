package com.app.market.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.market.domain.model.market.AppSource
import com.app.market.resources.*
import com.app.market.ui.util.appSourceLabel
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun PinnedSourcesDialog(
    pinned: Map<String, AppSource>,
    onSelect: (String, AppSource?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    WindowDialog(show = true, title = stringResource(Res.string.pinned_sources), onDismissRequest = onDismiss) {
        Column(modifier) {
            Text(stringResource(if (pinned.isEmpty()) Res.string.pinned_sources_empty else Res.string.pinned_sources_remove_hint), Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).scrollEndHaptic().overScrollVertical()) {
                items(pinned.toSortedMap().entries.toList(), key = { it.key }) { (packageName, source) ->
                    ArrowPreference(title = packageName, summary = appSourceLabel(source), onClick = { onSelect(packageName, null) })
                }
            }
            AppTextButton(stringResource(Res.string.confirm), onDismiss, Modifier.fillMaxWidth())
        }
    }
}
