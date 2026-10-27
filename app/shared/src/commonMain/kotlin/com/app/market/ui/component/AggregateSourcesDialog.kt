package com.app.market.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
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

/**
 * 聚合来源选择器。
 *
 * 已勾选的来源按列表顺序形成优先级：排在上面者优先，只有它不是最新版本时才顺移到下一个。
 * 长按已勾选的行可上下拖动调整优先次序；末段未勾选的来源不参与聚合。
 *
 * 拖动期间不实时重排列表：实时重排会让 LazyColumn 的锚定项不断变化，整块内容跟着跳动，
 * 手指下的行还会同时叠加「列表已换位」和「手动偏移」两份位移。这里改成拖动时只移动被拖的行，
 * 松手时才把新次序写回列表，位移完全由手势决定。
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
        mutableStateOf(picked + UpdateSources.filterNot { it in picked })
    }

    val listState = rememberLazyListState()
    // 行高以实际测量为准：SwitchPreference 的高度由 miuix 决定，写死常量会让步进错位。
    var rowHeightPx by remember { mutableFloatStateOf(0f) }
    var draggingSource by remember { mutableStateOf<AppSource?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }

    fun checkedCount() = order.count { it in checked }

    /**
     * 非拖动行为了让出落点需要平移的距离。
     *
     * 被拖的行跨过某一行时，那一行朝相反方向让一格，形成「空位跟着手指走」的效果；
     * 拖回原位（dragTargetIndex == from）时全部归零，避免残留位移。
     */
    fun shiftFor(index: Int): Float {
        val from = draggingSource?.let(order::indexOf) ?: return 0f
        if (index == from || dragTargetIndex < 0 || dragTargetIndex == from || rowHeightPx <= 0f) return 0f
        return if (dragTargetIndex > from) {
            if (index in (from + 1)..dragTargetIndex) -rowHeightPx else 0f
        } else {
            if (index in dragTargetIndex..(from - 1)) rowHeightPx else 0f
        }
    }

    WindowDialog(show = true, title = stringResource(Res.string.aggregate_sources), onDismissRequest = onDismiss) {
        Column(modifier) {
            Text(
                stringResource(Res.string.aggregate_sources_order_hint),
                Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                style = top.yukonga.miuix.kmp.theme.MiuixTheme.textStyles.footnote1,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).scrollEndHaptic().overScrollVertical(),
                // 拖动期间禁用列表自身滚动，避免同一次手势被两套机制消费。
                userScrollEnabled = draggingSource == null,
            ) {
                itemsIndexed(order, key = { _, source -> source.token }) { index, source ->
                    val isDragging = source == draggingSource
                    val rank = if (source in checked) order.take(index + 1).count { it in checked } else 0
                    // 让位用动画过渡，被拖行本身则必须是 0 延迟的即时位移。
                    val shift by animateFloatAsState(
                        targetValue = shiftFor(index),
                        animationSpec = tween(durationMillis = 120, easing = LinearEasing),
                        label = "sourceRowShift",
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .zIndex(if (isDragging) 1f else 0f)
                            .onSizeChanged { if (it.height > 0) rowHeightPx = it.height.toFloat() }
                            .offset { IntOffset(0, if (isDragging) dragOffset.toInt() else shift.toInt()) }
                            .alpha(if (isDragging) 0.85f else 1f)
                            .pointerInput(source, index, order.size, checked.size) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = {
                                        // 只有已勾选的来源有优先级意义，未勾选行不进入拖动状态。
                                        if (source in checked) {
                                            draggingSource = source
                                            dragTargetIndex = index
                                            dragOffset = 0f
                                        }
                                    },
                                    onDragEnd = {
                                        val from = draggingSource?.let(order::indexOf)
                                        if (from != null && from >= 0 && dragTargetIndex >= 0 && dragTargetIndex != from) {
                                            val moved = order.toMutableList()
                                            moved.add(dragTargetIndex, moved.removeAt(from))
                                            order = moved
                                        }
                                        draggingSource = null
                                        dragTargetIndex = -1
                                        dragOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggingSource = null
                                        dragTargetIndex = -1
                                        dragOffset = 0f
                                    },
                                    onDrag = { change, amount ->
                                        if (draggingSource == null || rowHeightPx <= 0f) {
                                            return@detectDragGesturesAfterLongPress
                                        }
                                        change.consume()
                                        val start = draggingSource?.let(order::indexOf) ?: return@detectDragGesturesAfterLongPress
                                        val lastCheckedIndex = (checkedCount() - 1).coerceAtLeast(0)
                                        // 位移夹在「第一个已勾选行 ~ 最后一个已勾选行」之间，
                                        // 否则被拖的行会飘出列表可视区，也拖不进未勾选区间。
                                        dragOffset = (dragOffset + amount.y)
                                            .coerceIn(-start * rowHeightPx, (lastCheckedIndex - start) * rowHeightPx)
                                        val steps = (dragOffset / rowHeightPx).toInt()
                                        dragTargetIndex = (start + steps).coerceIn(0, lastCheckedIndex)
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
