package com.app.market

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.MarketLink
import com.app.market.domain.model.market.MarketLinkParser
import com.app.market.domain.repository.ThemePreferencesRepository
import com.app.market.domain.repository.UpdatePreferencesRepository
import com.app.market.platform.AndroidPermissionCoordinator
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class MainActivity : ComponentActivity(), KoinComponent {

    private val externalDetailRequest = MutableStateFlow<ExternalDetailRequest?>(null)
    private val externalSearchKeyword = MutableStateFlow<String?>(null)
    private val externalDownloadsRequest = MutableStateFlow(false)
    private val permissions: AndroidPermissionCoordinator by inject()
    private val updatePreferences: UpdatePreferencesRepository by inject()
    private val themePreferences: ThemePreferencesRepository by inject()
    private var contentReady = false

    private val installedAppsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            permissions.dispatchInstalledAppsResult(granted)
        }
    private val postNotificationsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            permissions.dispatchNotificationsResult()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        splashScreen.setKeepOnScreenCondition { !contentReady }
        consumeExternalIntent(intent)
        permissions.registerInstalledApps(installedAppsPermissionLauncher)
        permissions.registerNotifications(postNotificationsPermissionLauncher)
        setContent {
            val darkMode = isSystemInDarkTheme()
            DisposableEffect(darkMode) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                    navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkMode },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                }
                onDispose {}
            }

            val detailRequest by externalDetailRequest.collectAsState()
            val searchKeyword by externalSearchKeyword.collectAsState()
            val downloadsRequest by externalDownloadsRequest.collectAsState()
            val preferencesReady by updatePreferences.initialized.collectAsState()
            val themePreferencesReady by themePreferences.initialized.collectAsState()
            if (preferencesReady && themePreferencesReady) {
                App(
                    externalDetailLink = detailRequest?.link,
                    externalDetailQuery = detailRequest?.encodedQuery,
                    onExternalDetailConsumed = { consumed ->
                        if (externalDetailRequest.value?.link == consumed) {
                            externalDetailRequest.value = null
                        }
                    },
                    externalSearchKeyword = searchKeyword,
                    onExternalSearchConsumed = { consumed ->
                        if (externalSearchKeyword.value == consumed) {
                            externalSearchKeyword.value = null
                        }
                    },
                    externalOpenDownloads = downloadsRequest,
                    onExternalDownloadsConsumed = { externalDownloadsRequest.value = false },
                )
                SideEffect { contentReady = true }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeExternalIntent(intent)
    }

    private fun consumeExternalIntent(intent: Intent?) {
        if (intent.isDownloadsRequest()) {
            externalDownloadsRequest.value = true
            return
        }
        val keyword = intent.searchKeyword()
        if (keyword != null) {
            externalSearchKeyword.value = keyword
            return
        }
        externalDetailRequest.value = intent.detailRequest()
    }

    // 全局搜索的「去应用商店搜索」：market://search?q=xxx；命中但无关键词时返回空串，仅切到搜索页
    private fun Intent?.searchKeyword(): String? {
        if (this?.action != Intent.ACTION_VIEW) return null
        val uri = data ?: return null
        if (uri.scheme.orEmpty().lowercase() !in setOf("market", "mimarket")) return null
        if (uri.host.orEmpty().lowercase() !in setOf("search", "searchresult")) return null
        val keys = listOf("q", "keyword", "query", "key", "kw")
        return keys.firstNotNullOfOrNull { uri.getQueryParameter(it)?.asKeyword() }
            ?: keys.firstNotNullOfOrNull { getStringExtra(it)?.asKeyword() }
            ?: getStringExtra(Intent.EXTRA_TEXT)?.asKeyword()
            ?: ""
    }

    private fun String.asKeyword(): String? = trim().take(100).takeIf(String::isNotEmpty)

    // 多任务下载通知的落点：market://downloads 打开「下载中的应用」页
    private fun Intent?.isDownloadsRequest(): Boolean {
        if (this?.action != Intent.ACTION_VIEW) return false
        val uri = data ?: return false
        return uri.scheme.orEmpty().lowercase() in setOf("market", "mimarket") &&
                uri.host.orEmpty().lowercase() == "downloads"
    }

    override fun onResume() {
        super.onResume()
        permissions.checkInstalledAppsOnResume()
    }

    override fun onDestroy() {
        permissions.unregisterInstalledApps(installedAppsPermissionLauncher)
        permissions.unregisterNotifications(postNotificationsPermissionLauncher)
        super.onDestroy()
    }

    private fun Intent?.detailRequest(): ExternalDetailRequest? {
        if (this?.action != Intent.ACTION_VIEW) return null
        val uri = data
        val link = uri?.toString()?.let(MarketLinkParser::parse)
            ?: stringExtraPackageName()?.let { MarketLink(it, source = null) }
            ?: return null
        val encodedQuery = uri?.encodedQuery
            ?.takeIf { it.isNotBlank() && (link.source == null || link.source == AppSource.XIAOMI) }
        return ExternalDetailRequest(link, encodedQuery)
    }

    private fun Intent.stringExtraPackageName(): String? =
        listOf("id", "packageName", "pName", "pkg", "package", "android.intent.extra.PACKAGE_NAME")
            .firstNotNullOfOrNull { key -> getStringExtra(key)?.asPackageName() }


    private fun String.asPackageName(): String? =
        trim().takeIf { value ->
            value.length in 3..255 &&
                    value.any { it == '.' } &&
                    value.all { it.isLetterOrDigit() || it == '_' || it == '.' }
        }
}

private data class ExternalDetailRequest(
    val link: MarketLink,
    val encodedQuery: String?,
)
