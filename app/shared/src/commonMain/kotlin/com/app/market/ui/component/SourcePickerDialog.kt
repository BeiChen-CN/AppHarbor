package com.app.market.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.market.domain.model.market.AppSource
import com.app.market.resources.Res
import com.app.market.resources.cancel
import com.app.market.resources.follow_app_source
import com.app.market.ui.util.appSourceLabel
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun SourcePickerDialog(
    show: Boolean,
    title: String,
    options: List<AppSource?>,
    selected: AppSource?,
    onSelect: (AppSource?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    nullLabel: String = stringResource(Res.string.follow_app_source),
) {
    WindowDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Column(modifier) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).scrollEndHaptic().overScrollVertical()) {
                items(options, key = { it?.token ?: "default" }) { source ->
                    RadioButtonPreference(
                        title = source?.let { appSourceLabel(it) } ?: nullLabel,
                        selected = selected == source,
                        radioButtonLocation = RadioButtonLocation.End,
                        onClick = { onSelect(source); onDismiss() },
                    )
                }
            }
            AppTextButton(text = stringResource(Res.string.cancel), onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}
