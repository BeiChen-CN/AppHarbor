package com.app.market.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.app.market.domain.repository.MainTabId
import com.app.market.resources.*
import com.app.market.ui.util.labelRes
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 底栏类别可见性选择器。
 *
 * 只允许隐藏至少留下一个页签，否则底栏会变成空条；因此最后一个可见项不可关闭。
 */
@Composable
fun HiddenMainTabsDialog(
    hidden: Set<MainTabId>,
    appManagementSupported: Boolean,
    onSave: (Set<MainTabId>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 桌面端没有「更新」页签，不展示也不允许配置。
    val configurable = remember(appManagementSupported) {
        MainTabId.Configurable.filter { appManagementSupported || it != MainTabId.UPDATES }
    }
    var draft by remember(hidden) { mutableStateOf(hidden) }
    val visibleCount = configurable.count { it !in draft }

    WindowDialog(show = true, title = stringResource(Res.string.hidden_main_tabs), onDismissRequest = onDismiss) {
        Column(modifier) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp).scrollEndHaptic().overScrollVertical()) {
                items(configurable, key = { it.token }) { tab ->
                    val visible = tab !in draft
                    SwitchPreference(
                        title = stringResource(tab.labelRes),
                        checked = visible,
                        // 只剩一个可见页签时禁止再关闭，保证底栏不为空。
                        enabled = visibleCount > 1 || !visible,
                        onCheckedChange = { checked ->
                            draft = if (checked) draft - tab else draft + tab
                        },
                    )
                }
            }
            if (visibleCount <= 1) {
                Text(stringResource(Res.string.choose_at_least_one_tab), Modifier.padding(16.dp))
            }
            Row(Modifier.fillMaxWidth()) {
                AppTextButton(stringResource(Res.string.cancel), onDismiss, Modifier.weight(1f))
                AppTextButton(
                    stringResource(Res.string.confirm),
                    {
                        onSave(draft)
                        onDismiss()
                    },
                    Modifier.weight(1f),
                    enabled = visibleCount >= 1,
                )
            }
        }
    }
}
