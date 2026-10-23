package com.app.market.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import com.app.market.domain.model.market.MarketLink
import com.app.market.domain.repository.MarketLinkResolver
import com.app.market.domain.repository.UpdatePreferencesRepository
import com.app.market.platform.UiPlatform
import com.app.market.resources.Res
import com.app.market.resources.cancel
import com.app.market.resources.clipboard_link_generic_source
import com.app.market.resources.clipboard_link_message
import com.app.market.resources.clipboard_link_title
import com.app.market.resources.open
import com.app.market.ui.component.AppTextButton
import com.app.market.ui.util.appSourceLabel
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 窗口获得焦点时读取剪贴板（Android 10 起只有获得焦点的窗口才能读），
 * 识别到商店链接且之前没问过就弹窗询问是否打开。
 */
@Composable
internal fun ClipboardLinkPrompt(onOpen: (MarketLink) -> Unit) {
    val uiPlatform = koinInject<UiPlatform>()
    val prefs = koinInject<UpdatePreferencesRepository>()
    val resolver = koinInject<MarketLinkResolver>()
    val detectEnabled by prefs.detectClipboardLinks.collectAsStateWithLifecycle()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var pending by remember { mutableStateOf<MarketLink?>(null) }

    LaunchedEffect(windowFocused, detectEnabled) {
        if (!windowFocused || !detectEnabled) return@LaunchedEffect
        val text = withContext(Dispatchers.Default) { uiPlatform.readNewClipboardText() }
            ?: return@LaunchedEffect
        val link = resolver.resolve(text) ?: return@LaunchedEffect
        val key = link.toUnifiedUri()
        if (key == prefs.lastClipboardLink()) return@LaunchedEffect
        prefs.setLastClipboardLink(key)
        pending = link
    }

    val link = pending
    WindowDialog(
        show = link != null,
        title = stringResource(Res.string.clipboard_link_title),
        onDismissRequest = { pending = null },
    ) {
        if (link == null) return@WindowDialog
        val sourceName = link.source?.let { appSourceLabel(it) }
            ?: stringResource(Res.string.clipboard_link_generic_source)
        val target = link.packageName.ifBlank { "#${link.storeAppId}" }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                text = stringResource(Res.string.clipboard_link_message, sourceName, target),
                color = MiuixTheme.colorScheme.onSurface,
            )
            Row(modifier = Modifier.fillMaxWidth()) {
                AppTextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(Res.string.cancel),
                    onClick = { pending = null },
                )
                Spacer(Modifier.width(16.dp))
                AppTextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(Res.string.open),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        pending = null
                        onOpen(link)
                    },
                )
            }
        }
    }
}
