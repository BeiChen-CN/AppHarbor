package com.app.market.ui.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.update.UpdateSources
import com.app.market.resources.*
import com.app.market.ui.util.appSourceLabel
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun AggregateSourcesDialog(
    selected: Set<AppSource>,
    onSave: (Set<AppSource>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf(selected) }
    WindowDialog(show = true, title = stringResource(Res.string.aggregate_sources), onDismissRequest = onDismiss) {
        Column(modifier) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).scrollEndHaptic().overScrollVertical()) {
                items(UpdateSources, key = { it.token }) { source ->
                    SwitchPreference(title = appSourceLabel(source), checked = source in draft,
                        onCheckedChange = { checked -> draft = if (checked) draft + source else draft - source })
                }
            }
            if (draft.isEmpty()) Text(stringResource(Res.string.choose_at_least_one_source), Modifier.padding(16.dp))
            Row(Modifier.fillMaxWidth()) {
                AppTextButton(stringResource(Res.string.cancel), onDismiss, Modifier.weight(1f))
                AppTextButton(stringResource(Res.string.confirm), { onSave(draft); onDismiss() },
                    Modifier.weight(1f), enabled = draft.isNotEmpty())
            }
        }
    }
}
