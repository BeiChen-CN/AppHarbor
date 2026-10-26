package com.app.market.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.app.market.domain.repository.ReleaseTokenRepository
import com.app.market.resources.Res
import com.app.market.resources.cancel
import com.app.market.resources.confirm
import com.app.market.resources.github_login
import com.app.market.resources.github_login_signed_in
import com.app.market.resources.github_logout
import com.app.market.resources.github_token_hint
import com.app.market.resources.github_token_placeholder
import com.app.market.resources.github_token_remaining
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * GitHub 令牌登录对话框。
 *
 * 令牌只用于 api.github.com（匿名 60 次/小时 → 登录后 5000 次/小时），
 * 不参与 APK 下载请求。保存前先向 GitHub 换取登录名做校验，无效令牌不落盘。
 */
@Composable
fun GitHubLoginDialog(
    show: Boolean,
    repository: ReleaseTokenRepository,
    onDismissRequest: () -> Unit,
) {
    val state by repository.state.collectAsStateWithLifecycle()
    OverlayDialog(
        show = show,
        title = stringResource(Res.string.github_login),
        summary = stringResource(Res.string.github_token_hint),
        onDismissRequest = onDismissRequest,
    ) {
        var text by remember(show) { mutableStateOf("") }
        var busy by remember(show) { mutableStateOf(false) }
        var error by remember(show) { mutableStateOf("") }
        var quota by remember(show) { mutableStateOf<Pair<Int, Int>?>(null) }
        val scope = rememberCoroutineScope()

        // 已登录时顺带拉一次额度，让用户知道令牌到底有没有生效。
        LaunchedEffect(show, state.loggedIn) {
            quota = if (show) repository.quota() else null
        }

        TextField(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            value = text,
            label = stringResource(Res.string.github_token_placeholder),
            singleLine = true,
            onValueChange = { newValue -> text = newValue.trim() },
        )
        if (state.loggedIn) {
            Text(
                text = stringResource(Res.string.github_login_signed_in, state.login.ifBlank { "-" }),
                modifier = Modifier.padding(bottom = 8.dp),
                color = MiuixTheme.colorScheme.primary,
                style = MiuixTheme.textStyles.footnote1,
            )
        }
        quota?.let { (remaining, limit) ->
            Text(
                text = stringResource(Res.string.github_token_remaining, remaining, limit),
                modifier = Modifier.padding(bottom = 8.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.footnote1,
            )
        }
        if (error.isNotBlank()) {
            Text(
                text = error,
                modifier = Modifier.padding(bottom = 8.dp),
                color = MiuixTheme.colorScheme.error,
                style = MiuixTheme.textStyles.footnote1,
            )
        }
        Row(horizontalArrangement = Arrangement.SpaceBetween) {
            AppTextButton(
                modifier = Modifier.weight(1f),
                text = stringResource(Res.string.cancel),
                onClick = onDismissRequest,
            )
            Spacer(Modifier.width(12.dp))
            if (state.loggedIn) {
                AppTextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(Res.string.github_logout),
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            repository.logout()
                            busy = false
                            onDismissRequest()
                        }
                    },
                )
                Spacer(Modifier.width(12.dp))
            }
            AppTextButton(
                modifier = Modifier.weight(1f),
                text = stringResource(Res.string.confirm),
                enabled = !busy && text.isNotBlank(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    busy = true
                    error = ""
                    scope.launch {
                        runCatching { repository.login(text) }
                            .onSuccess { onDismissRequest() }
                            .onFailure { error = it.message ?: it.toString() }
                        busy = false
                    }
                },
            )
        }
    }
}
