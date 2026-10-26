package com.app.market.ui.component

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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

private val RowHeight = 56.dp

/**
 * 聚合来源选择器。
 *
 * 已勾选的来源按列表顺序形成优先级：排在上面者优先，只有它不是最新版本时才顺移到下一个。
 * 长按已勾选的行可上下拖动调整优先次序；末段未勾选的来源不参与聚合。
 */
@Composable
fun AggregateSourcesDialog(
    selected: List<AppSource>,
    onSave: (List<AppSource>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val picked = remember(selected) {
        selected.distinct().filter { it in UpdateSources }.ifEmpty { UpdateSources.take(1) }
    }
    var checked by remember(selected) { mutableStateOf(picked.toSet()) }
    // 全量顺序表：勾选的在前（按优先级），未勾选的按枚举顺序垫后。
    var order by remember(selected) {
        mutableStateOf(picked + UpdateSources.filterNot { it in checked })
    }

    val density = LocalDensity.current
    val rowHeightPx = with(density) { RowHeight.toPx() }
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragOffset by remember { mutableFloatStateOf(0f) }

    fun moveWithinChecked(from: Int, to: Int) {
        val checkedCount = order.count { it in checked }
        val target = to.coerceIn(0, (checkedCount - 1).coerceAtLeast(0))
        if (from == target) return
        val moved = order.toMutableList()
        moved.add(target, moved.removeAt(from))
        order = moved
    }

    WindowDialog(show = true, title = stringResource(Res.string.aggregate_sources), onDismissRequest = onDismiss) {
        Column(modifier) {
            Text(
                stringResource(Res.string.aggregate_sources_order_hint),
                Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.footnote1,
            )
            LazyColumn(
                Modifier.fillMaxWidth().heightIn(max = 360.dp).scrollEndHaptic().overScrollVertical(),
                // 拖动期间禁用列表自身滚动，避免同一次手势被两套机制消费。
                userScrollEnabled = draggingIndex < 0,
            ) {
                itemsIndexed(order, key = { _, source -> source.token }) { index, source ->
                    val isDragging = index == draggingIndex
                    val rank = order.indexOfFirst { it in checked }.let { first ->
                        if (first >= 0 && source in checked) {
                            order.take(index + 1).count { it in checked }
                        } else {
                            0
                        }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(RowHeight)
                            .zIndex(if (isDragging) 1f else 0f)
                            .offset { IntOffset(0, if (isDragging) dragOffset.toInt() else 0) }
                            .pointerInput(source, order.size, checked.size) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        // 只有已勾选的来源有优先级意义，未勾选行不进入拖动状态。
                                        if (source in checked) {
                                            draggingIndex = index
                                            dragOffset = 0f
                                        }
                                    },
                                    onDragEnd = { draggingIndex = -1; dragOffset = 0f },
                                    onDragCancel = { draggingIndex = -1; dragOffset = 0f },
                                    onDrag = { change, amount ->
                                        if (draggingIndex < 0 || rowHeightPx <= 0f) return@detectDragGesturesAfterLongPress
                                        change.consume()
                                        dragOffset += amount.y
                                        val steps = (dragOffset / rowHeightPx).toInt()
                                        if (steps != 0) {
                                            moveWithinChecked(draggingIndex, draggingIndex + steps)
                                            draggingIndex += steps
                                            dragOffset -= steps * rowHeightPx
                                        }
                                    },
                                )
                            },
                    ) {
                        SwitchPreference(
                            title = appSourceLabel(source),
                            summary = if (source in checked && rank > 0) {
                                stringResource(Res.string.aggregate_source_priority, rank)
                            } else {
                                null
                            },
                            checked = source in checked,
                            onCheckedChange = { isOn ->
                                checked = if (isOn) checked + source else checked - source
                                // 每次切换后重排：勾选的聚到前面，保持各自原有相对次序。
                                order = order.filter { it in checked } + order.filterNot { it in checked }
                            },
                        )
                    }
                }
            }
            if (checked.isEmpty()) {
                Text(stringResource(Res.string.choose_at_least_one_source), Modifier.padding(16.dp))
            }
            Row(Modifier.fillMaxWidth()) {
                AppTextButton(stringResource(Res.string.cancel), onDismiss, Modifier.weight(1f))
                AppTextButton(
                    stringResource(Res.string.confirm),
                    {
                        val result = order.filter { it in checked }
                        if (result.isNotEmpty()) {
                            onSave(result)
                            onDismiss()
                        }
                    },
                    Modifier.weight(1f),
                    enabled = checked.isNotEmpty(),
                )
            }
        }
    }
}
