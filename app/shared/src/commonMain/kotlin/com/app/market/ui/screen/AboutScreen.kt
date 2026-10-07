package com.app.market.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.app.market.resources.Res
import com.app.market.resources.about_author_name
import com.app.market.resources.about_github
import com.app.market.resources.about_open_source_licenses
import com.app.market.resources.about_project
import com.app.market.resources.about_title
import com.app.market.resources.app_name
import com.app.market.resources.back
import com.app.market.resources.ic_launcher
import com.app.market.ui.component.SectionTitle
import com.app.market.ui.component.blur.BlurredBar
import com.app.market.ui.component.blur.ColorBlendToken
import com.app.market.ui.component.blur.rememberBlurBackdrop
import com.app.market.ui.component.blur.rememberBlurEnabled
import com.app.market.ui.component.effect.BgEffectBackground
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
fun AboutScreen(
    onBack: () -> Unit = {},
    onOpenUrl: (String) -> Unit = {},
) {
    val scrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()
    // 视差进度逐帧变化：只以 State 传递，读取下沉到顶栏槽位与 graphicsLayer
    val scrollProgress = remember {
        derivedStateOf {
            when {
                lazyListState.firstVisibleItemIndex > 0 -> 1f
                else -> {
                    val spacer = lazyListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "logoSpacer" }
                    if (spacer != null && spacer.size > 0) {
                        (lazyListState.firstVisibleItemScrollOffset.toFloat() / spacer.size).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                }
            }
        }
    }
    val collapsed by remember { derivedStateOf { scrollProgress.value == 1f } }

    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null && collapsed

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                SmallTopAppBar(
                    title = stringResource(Res.string.about_title),
                    scrollBehavior = scrollBehavior,
                    color = if (blurActive || !collapsed) Color.Transparent else colorScheme.surface,
                    titleColor = colorScheme.onSurface.copy(
                        alpha = ((scrollProgress.value - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    ),
                    navigationIcon = {
                        val layoutDirection = LocalLayoutDirection.current
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(Res.string.back),
                                tint = colorScheme.onSurface,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                },
                            )
                        }
                    },
                )
            }
        },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box(modifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier) {
            AboutContent(
                innerPadding = innerPadding,
                scrollBehavior = scrollBehavior,
                lazyListState = lazyListState,
                scrollProgress = scrollProgress,
                onOpenUrl = onOpenUrl,
            )
        }
    }
}

@Composable
private fun AboutContent(
    innerPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    lazyListState: LazyListState,
    scrollProgress: State<Float>,
    onOpenUrl: (String) -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val density = LocalDensity.current

    // 内容区至少铺满一屏（LazyColumn 视口高），内容更高时按内容增长——横屏矮视口下用固定高会把超出部分裁掉
    val viewportHeight by remember {
        derivedStateOf { lazyListState.layoutInfo.viewportSize.height }
    }

    val backdrop = rememberBlurBackdrop()

    val isDark = isSystemInDarkTheme()
    val blurEnabled by rememberBlurEnabled()
    val effectBackground = remember { isRuntimeShaderSupported() }

    val cardBlendColors = remember(isDark) {
        if (isDark) ColorBlendToken.Overlay_Extra_Thin_Dark else ColorBlendToken.Pured_Regular_Light
    }
    val logoBlend = remember(isDark) {
        if (isDark) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1.toInt()), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500.toInt()), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a.toInt()), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f.toInt()), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200.toInt()), BlurBlendMode.Lab),
            )
        }
    }

    var logoHeightDp by remember { mutableStateOf(300.dp) }

    fun versionCodeProgress() = ((scrollProgress.value - 0.05f) / 0.15f).coerceIn(0f, 1f)
    fun projectNameProgress() = ((scrollProgress.value - 0.20f) / 0.15f).coerceIn(0f, 1f)
    fun iconProgress() = ((scrollProgress.value - 0.35f) / 0.15f).coerceIn(0f, 1f)

    val scrollPadding = PaddingValues(
        top = innerPadding.calculateTopPadding(),
        start = innerPadding.calculateStartPadding(layoutDirection),
        end = innerPadding.calculateEndPadding(layoutDirection),
    )
    val logoPadding = PaddingValues(
        top = innerPadding.calculateTopPadding() + 40.dp,
        start = innerPadding.calculateStartPadding(layoutDirection),
        end = innerPadding.calculateEndPadding(layoutDirection),
    )

    BgEffectBackground(
        dynamicBackground = effectBackground,
        modifier = Modifier.fillMaxSize(),
        bgModifier = backdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier,
        effectBackground = effectBackground,
        alpha = { 1f - scrollProgress.value },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = logoPadding.calculateTopPadding() + 52.dp,
                    start = logoPadding.calculateStartPadding(layoutDirection),
                    end = logoPadding.calculateEndPadding(layoutDirection),
                )
                .onSizeChanged { size -> with(density) { logoHeightDp = size.height.toDp() } },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(88.dp)
                    .graphicsLayer {
                        alpha = 1 - iconProgress()
                        scaleX = 1 - iconProgress() * 0.05f
                        scaleY = 1 - iconProgress() * 0.05f
                    },
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .squircleClip(24.dp)
                        .background(Color.White),
                ) {
                    Image(
                        modifier = Modifier.size(88.dp),
                        painter = painterResource(Res.drawable.ic_launcher),
                        contentDescription = "icon",
                    )
                }
            }
            Text(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 5.dp)
                    .graphicsLayer {
                        alpha = 1 - projectNameProgress()
                        scaleX = 1 - projectNameProgress() * 0.05f
                        scaleY = 1 - projectNameProgress() * 0.05f
                    }
                    .then(
                        if (backdrop != null) {
                            Modifier.textureBlur(
                                backdrop = backdrop,
                                shape = RoundedCornerShape(16.dp),
                                blurRadius = 150f,
                                noiseCoefficient = BlurDefaults.NoiseCoefficient,
                                colors = BlurColors(blendColors = logoBlend),
                                contentBlendMode = BlendMode.DstIn,
                                enabled = true,
                            )
                        } else Modifier
                    ),
                text = stringResource(Res.string.app_name),
                color = colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
            )
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        alpha = 1 - versionCodeProgress()
                        scaleX = 1 - versionCodeProgress() * 0.05f
                        scaleY = 1 - versionCodeProgress() * 0.05f
                    },
                color = colorScheme.onSurfaceVariantSummary,
                text = "v${misc.VersionInfo.VERSION_NAME} (${misc.VersionInfo.VERSION_CODE})",
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp)
                    .graphicsLayer {
                        alpha = 1 - versionCodeProgress()
                        scaleX = 1 - versionCodeProgress() * 0.05f
                        scaleY = 1 - versionCodeProgress() * 0.05f
                    },
                color = colorScheme.onSurfaceVariantSummary,
                text = stringResource(Res.string.about_author_name, "姜北尘"),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = scrollPadding.calculateTopPadding(),
                start = scrollPadding.calculateStartPadding(layoutDirection),
                end = scrollPadding.calculateEndPadding(layoutDirection),
            ),
        ) {
            item(key = "logoSpacer") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(logoHeightDp + 52.dp + logoPadding.calculateTopPadding() - scrollPadding.calculateTopPadding() + 126.dp),
                    contentAlignment = Alignment.TopCenter,
                    content = { },
                )
            }

            item(key = "about") {
                Column(modifier = Modifier.heightIn(min = with(density) { viewportHeight.toDp() }).padding(bottom = 12.dp)) {
                    SectionTitle(text = stringResource(Res.string.about_project))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .then(
                                if (backdrop != null) {
                                    Modifier.textureBlur(
                                        backdrop = backdrop,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = 60f,
                                        noiseCoefficient = BlurDefaults.NoiseCoefficient,
                                        colors = BlurColors(blendColors = cardBlendColors),
                                        enabled = true,
                                    )
                                } else Modifier
                        ),
                        colors = CardDefaults.defaultColors(
                            if (backdrop != null && blurEnabled) Color.Transparent else colorScheme.surfaceContainer,
                            Color.Transparent,
                        ),
                    ) {
                        ArrowPreference(
                            title = stringResource(Res.string.about_github),
                            summary = "github.com/BeiChen-CN/AppMarket",
                            onClick = { onOpenUrl("https://github.com/BeiChen-CN/AppMarket") },
                        )
                    }

                    SectionTitle(text = stringResource(Res.string.about_open_source_licenses))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp)
                            .then(
                                if (backdrop != null) {
                                    Modifier.textureBlur(
                                        backdrop = backdrop,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = 60f,
                                        noiseCoefficient = BlurDefaults.NoiseCoefficient,
                                        colors = BlurColors(blendColors = cardBlendColors),
                                        enabled = true,
                                    )
                                } else Modifier
                        ),
                        colors = CardDefaults.defaultColors(
                            if (backdrop != null && blurEnabled) Color.Transparent else colorScheme.surfaceContainer,
                            Color.Transparent,
                        ),
                    ) {
                        ArrowPreference(
                            title = "AndroidX Activity",
                            summary = "developer.android.com/jetpack/androidx/releases/activity",
                            onClick = { onOpenUrl("https://developer.android.com/jetpack/androidx/releases/activity") },
                        )
                        ArrowPreference(
                            title = "AndroidX Core",
                            summary = "developer.android.com/jetpack/androidx/releases/core",
                            onClick = { onOpenUrl("https://developer.android.com/jetpack/androidx/releases/core") },
                        )
                        ArrowPreference(
                            title = "AndroidX DataStore",
                            summary = "developer.android.com/jetpack/androidx/releases/datastore",
                            onClick = { onOpenUrl("https://developer.android.com/jetpack/androidx/releases/datastore") },
                        )
                        ArrowPreference(
                            title = "AndroidX Lifecycle",
                            summary = "developer.android.com/jetpack/androidx/releases/lifecycle",
                            onClick = { onOpenUrl("https://developer.android.com/jetpack/androidx/releases/lifecycle") },
                        )
                        ArrowPreference(
                            title = "AndroidX Navigation Event",
                            summary = "developer.android.com/jetpack/androidx/releases/navigationevent",
                            onClick = { onOpenUrl("https://developer.android.com/jetpack/androidx/releases/navigationevent") },
                        )
                        ArrowPreference(
                            title = "AndroidX SplashScreen",
                            summary = "developer.android.com/jetpack/androidx/releases/core",
                            onClick = { onOpenUrl("https://developer.android.com/jetpack/androidx/releases/core") },
                        )
                        ArrowPreference(
                            title = "coil",
                            summary = "github.com/coil-kt/coil",
                            onClick = { onOpenUrl("https://github.com/coil-kt/coil") },
                        )
                        ArrowPreference(
                            title = "Compose Media Player",
                            summary = "github.com/kdroidFilter/ComposeMediaPlayer",
                            onClick = { onOpenUrl("https://github.com/kdroidFilter/ComposeMediaPlayer") },
                        )
                        ArrowPreference(
                            title = "Compose Multiplatform",
                            summary = "github.com/JetBrains/compose-multiplatform",
                            onClick = { onOpenUrl("https://github.com/JetBrains/compose-multiplatform") },
                        )
                        ArrowPreference(
                            title = "HDiffPatch",
                            summary = "github.com/sisong/HDiffPatch",
                            onClick = { onOpenUrl("https://github.com/sisong/HDiffPatch") },
                        )
                        ArrowPreference(
                            title = "HiddenApiBypass",
                            summary = "github.com/LSPosed/AndroidHiddenApiBypass",
                            onClick = { onOpenUrl("https://github.com/LSPosed/AndroidHiddenApiBypass") },
                        )
                        ArrowPreference(
                            title = "HyperNotification",
                            summary = "github.com/xzakota/HyperNotification",
                            onClick = { onOpenUrl("https://github.com/xzakota/HyperNotification") },
                        )
                        ArrowPreference(
                            title = "Koin",
                            summary = "github.com/InsertKoinIO/koin",
                            onClick = { onOpenUrl("https://github.com/InsertKoinIO/koin") },
                        )
                        ArrowPreference(
                            title = "kotlinx-datetime",
                            summary = "github.com/Kotlin/kotlinx-datetime",
                            onClick = { onOpenUrl("https://github.com/Kotlin/kotlinx-datetime") },
                        )
                        ArrowPreference(
                            title = "kotlinx.collections.immutable",
                            summary = "github.com/Kotlin/kotlinx.collections.immutable",
                            onClick = { onOpenUrl("https://github.com/Kotlin/kotlinx.collections.immutable") },
                        )
                        ArrowPreference(
                            title = "kotlinx.coroutines",
                            summary = "github.com/Kotlin/kotlinx.coroutines",
                            onClick = { onOpenUrl("https://github.com/Kotlin/kotlinx.coroutines") },
                        )
                        ArrowPreference(
                            title = "kotlinx.serialization",
                            summary = "github.com/Kotlin/kotlinx.serialization",
                            onClick = { onOpenUrl("https://github.com/Kotlin/kotlinx.serialization") },
                        )
                        ArrowPreference(
                            title = "ktor",
                            summary = "github.com/ktorio/ktor",
                            onClick = { onOpenUrl("https://github.com/ktorio/ktor") },
                        )
                        ArrowPreference(
                            title = "miuix",
                            summary = "github.com/compose-miuix-ui/miuix",
                            onClick = { onOpenUrl("https://github.com/compose-miuix-ui/miuix") },
                        )
                        ArrowPreference(
                            title = "Shizuku",
                            summary = "github.com/RikkaApps/Shizuku",
                            onClick = { onOpenUrl("https://github.com/RikkaApps/Shizuku") },
                        )
                    }

                    Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.systemBars))
                }
            }
        }
    }
}
