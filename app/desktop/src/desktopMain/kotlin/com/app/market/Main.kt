package com.app.market

import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.app.market.data.platform.DebugLogging
import com.app.market.di.dataModules
import com.app.market.di.uiPlatformModule
import com.app.market.di.viewModelModule
import com.app.market.platform.setupImageLoader
import com.app.market.resources.Res
import com.app.market.resources.app_name
import com.app.market.resources.ic_launcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.core.context.startKoin

fun main() {
    DebugLogging.enabled = System.getProperty("appmarket.debug")?.toBooleanStrictOrNull() ?: false
    val koinApplication = startKoin {
        modules(*dataModules.toTypedArray(), uiPlatformModule, viewModelModule)
    }
    setupImageLoader(koinApplication.koin.get())
    application {
        val state = rememberWindowState(
            size = DpSize(420.dp, 860.dp),
            position = WindowPosition.Aligned(Alignment.Center),
        )
        val baseIcon = painterResource(Res.drawable.ic_launcher)
        val windowIcon = remember(baseIcon) { RoundedCornerPainter(baseIcon, cornerRadiusFraction = 0.23f) }
        Window(
            state = state,
            onCloseRequest = {
                // 尽力让在途 IO 收尾；桌面端任务状态不持久化，不等它落完
                runCatching { koinApplication.koin.get<CoroutineScope>().cancel() }
                exitApplication()
            },
            title = stringResource(Res.string.app_name),
            icon = windowIcon,
        ) {
            App()
        }
    }
}

private class RoundedCornerPainter(
    private val painter: Painter,
    private val cornerRadiusFraction: Float,
) : Painter() {
    override val intrinsicSize: Size get() = painter.intrinsicSize

    override fun DrawScope.onDraw() {
        val radius = size.minDimension * cornerRadiusFraction
        val path = Path().apply {
            addRoundRect(RoundRect(Rect(Offset.Zero, size), CornerRadius(radius, radius)))
        }
        clipPath(path) {
            with(painter) { draw(size) }
        }
    }
}
