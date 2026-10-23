package com.app.market.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import org.jetbrains.compose.resources.stringResource
import com.app.market.resources.Res
import com.app.market.resources.cancel
import com.app.market.resources.confirm
import com.app.market.resources.theme_accent_default
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

private val PRESET_COLORS = listOf(
    0xFF5B7CFF, 0xFF32ADE6, 0xFF00C8BE, 0xFF34C759, 0xFF7ED321, 0xFFF7B500,
    0xFFFF9500, 0xFFF65E5E, 0xFFFF2D55, 0xFFAF52C2, 0xFF5856D6, 0xFF8E8E93,
)

private val HUE_RAMP = listOf(
    Color(0xFFFF0000), Color(0xFFFFFF00), Color(0xFF00FF00),
    Color(0xFF00FFFF), Color(0xFF0000FF), Color(0xFFFF00FF), Color(0xFFFF0000),
)

@Composable
fun ColorPickerDialog(
    show: Boolean,
    initialColor: Color,
    title: String,
    onDismissRequest: () -> Unit,
    onConfirm: (Color) -> Unit,
    modifier: Modifier = Modifier,
    onResetRequest: (() -> Unit)? = null,
) {
    val initialHsv = remember(show) { colorToHsv(initialColor) }
    var hue by remember(show) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(show) { mutableFloatStateOf(initialHsv[1]) }
    var value by remember(show) { mutableFloatStateOf(initialHsv[2]) }
    val currentColor = hsvToColor(hue, saturation, value)

    WindowDialog(
        show = show,
        title = title,
        onDismissRequest = onDismissRequest,
        insideMargin = DpSize(0.dp, 24.dp),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(36.dp)
                        .squircleBackground(color = currentColor, cornerRadius = 100.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "#%06X".format(currentColor.toArgb() and 0xFFFFFF),
                    color = MiuixTheme.colorScheme.onSurface,
                )
            }
            SaturationValuePanel(
                hue = hue,
                saturation = saturation,
                value = value,
                onChange = { s, v ->
                    saturation = s
                    value = v
                },
            )
            HueBar(
                hue = hue,
                onHueChange = { hue = it },
            )
            PresetGrid(
                currentColor = currentColor,
                onPicked = { picked ->
                    val pickedHsv = colorToHsv(picked)
                    hue = pickedHsv[0]
                    saturation = pickedHsv[1]
                    value = pickedHsv[2]
                },
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            if (onResetRequest != null) {
                AppTextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(Res.string.theme_accent_default),
                    onClick = onResetRequest,
                )
                Spacer(Modifier.width(16.dp))
            }
            AppTextButton(
                modifier = Modifier.weight(1f),
                text = stringResource(Res.string.cancel),
                onClick = onDismissRequest,
            )
            Spacer(Modifier.width(16.dp))
            AppTextButton(
                modifier = Modifier.weight(1f),
                text = stringResource(Res.string.confirm),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = { onConfirm(currentColor) },
            )
        }
    }
}

@Composable
private fun SaturationValuePanel(
    hue: Float,
    saturation: Float,
    value: Float,
    onChange: (Float, Float) -> Unit,
) {
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    val hueColor = hsvToColor(hue, 1f, 1f)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .squircleClip(12.dp)
            .onSizeChanged { panelSize = it }
            .pointerInput(Unit) {
                detectTapGestures { updateSaturationValue(it, panelSize, onChange) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    updateSaturationValue(change.position, panelSize, onChange)
                }
            },
    ) {
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.horizontalGradient(listOf(Color.White, hueColor))),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))),
        )
        if (panelSize != IntSize.Zero) {
            val center = Offset(
                saturation * panelSize.width,
                (1f - value) * panelSize.height,
            )
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .offset { IntOffset(center.x.roundToInt() - 9, center.y.roundToInt() - 9) }
                    .size(18.dp)
                    .border(2.dp, Color.White, CircleShape),
            )
        }
    }
}

private fun updateSaturationValue(
    offset: Offset,
    panelSize: IntSize,
    onChange: (Float, Float) -> Unit,
) {
    if (panelSize.width <= 0 || panelSize.height <= 0) return
    val s = (offset.x / panelSize.width).coerceIn(0f, 1f)
    val v = 1f - (offset.y / panelSize.height).coerceIn(0f, 1f)
    onChange(s, v)
}

@Composable
private fun HueBar(
    hue: Float,
    onHueChange: (Float) -> Unit,
) {
    var barSize by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .squircleClip(100.dp)
            .onSizeChanged { barSize = it }
            .background(Brush.horizontalGradient(HUE_RAMP))
            .pointerInput(Unit) {
                detectTapGestures { updateHue(it, barSize, onHueChange) }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    updateHue(change.position, barSize, onHueChange)
                }
            },
    ) {
        if (barSize != IntSize.Zero) {
            val thumbSizePx = with(density) { 6.dp.toPx() }
            val thumbOffset = IntOffset(
                (hue / 360f * barSize.width).roundToInt() - (thumbSizePx / 2f).roundToInt(),
                0,
            )
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .offset { thumbOffset }
                    .size(width = 6.dp, height = 32.dp)
                    .background(Color.White, CircleShape),
            )
        }
    }
}

private fun updateHue(
    offset: Offset,
    barSize: IntSize,
    onHueChange: (Float) -> Unit,
) {
    if (barSize.width <= 0) return
    onHueChange((offset.x / barSize.width).coerceIn(0f, 1f) * 360f)
}

@Composable
private fun PresetGrid(
    currentColor: Color,
    onPicked: (Color) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PRESET_COLORS.chunked(6).forEach { rowColors ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                rowColors.forEach { argb ->
                    val color = Color(argb)
                    val selected = color == currentColor
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .then(
                                if (selected) {
                                    Modifier.squircleBorder(
                                        width = 2.dp,
                                        color = MiuixTheme.colorScheme.primary,
                                        cornerRadius = 100.dp,
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .padding(4.dp)
                            .squircleBackground(color = color, cornerRadius = 100.dp)
                            .pointerInput(color) { detectTapGestures { onPicked(color) } },
                    )
                }
            }
        }
    }
}

private fun colorToHsv(color: Color): FloatArray {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    val hue = when {
        delta <= 0f -> 0f
        max == r -> 60f * (((g - b) / delta) % 6f)
        max == g -> 60f * (((b - r) / delta) + 2f)
        else -> 60f * (((r - g) / delta) + 4f)
    }.let { if (it < 0f) it + 360f else it }
    val saturation = if (max <= 0f) 0f else delta / max
    return floatArrayOf(hue, saturation, max)
}

private fun hsvToColor(hue: Float, saturation: Float, value: Float): Color {
    val chroma = value * saturation
    val x = chroma * (1f - abs((hue / 60f) % 2f - 1f))
    val m = value - chroma
    val (r, g, b) = when {
        hue < 60f -> Triple(chroma, x, 0f)
        hue < 120f -> Triple(x, chroma, 0f)
        hue < 180f -> Triple(0f, chroma, x)
        hue < 240f -> Triple(0f, x, chroma)
        hue < 300f -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    return Color(r + m, g + m, b + m)
}
