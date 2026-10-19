package com.app.market.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirst
import androidx.compose.ui.util.fastRoundToInt
import com.app.market.resources.Res
import com.app.market.resources.back
import com.app.market.ui.component.blur.BlurredBar
import com.app.market.ui.component.blur.progressiveBarBlur
import com.app.market.ui.component.blur.progressiveBottomBarBlur
import com.app.market.ui.component.blur.rememberBlurBackdrop
import com.app.market.ui.util.rememberIsWideScreen
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import top.yukonga.miuix.kmp.anim.folmeSpring
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBarDefaults
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Large collapsible top bar on phones; pinned [SmallTopAppBar] on wide screens where the side
 * navigation rail replaces the bottom bar and vertical space is scarce.
 */
@Composable
fun AdaptiveTopAppBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    color: Color = MiuixTheme.colorScheme.surface,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable () -> Unit = {},
) {
    if (rememberIsWideScreen()) {
        SmallTopAppBar(
            title = title,
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
        )
    } else {
        TopAppBar(
            title = title,
            color = color,
            scrollBehavior = scrollBehavior,
            navigationIcon = navigationIcon,
            actions = actions,
            bottomContent = bottomContent,
        )
    }
}

/**
 * MIUIX top app bar variant whose expanded title area accepts arbitrary content. The measurement
 * and placement rules mirror MIUIX TopAppBarLayout so the compact title stays centered while the
 * expanded content drives the collapse range.
 */
@Composable
fun CollapsibleContentTopAppBar(
    title: String,
    scrollBehavior: ScrollBehavior,
    modifier: Modifier = Modifier,
    color: Color = MiuixTheme.colorScheme.surface,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    persistentActions: @Composable RowScope.() -> Unit = {},
    expandedContentBehindToolbar: Boolean = false,
    applyWindowInsets: Boolean = true,
    expandedContent: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val toolbarHeightPx = with(density) { TopAppBarDefaults.CollapsedHeight.roundToPx() }
    val topInsetPx = if (expandedContentBehindToolbar && applyWindowInsets) {
        WindowInsets.systemBars.getTop(density)
    } else {
        0
    }
    val collapsedHeightPx = toolbarHeightPx + topInsetPx
    val heightOffset = remember(scrollBehavior) { { scrollBehavior.state.heightOffset } }
    val collapsedFraction = remember(scrollBehavior) { { scrollBehavior.state.collapsedFraction } }
    val expandedAlpha = remember(scrollBehavior) {
        { 1f - (collapsedFraction() * 3f).coerceIn(0f, 1f) }
    }
    val compactContentVisible by remember(scrollBehavior) {
        derivedStateOf { scrollBehavior.state.collapsedFraction * 3f >= 1f }
    }
    val compactTitleAlpha = remember { Animatable(if (compactContentVisible) 1f else 0f) }
    val compactTitleTranslationY = remember {
        Animatable(if (compactContentVisible) 0f else 20f)
    }

    LaunchedEffect(compactContentVisible) {
        if (compactContentVisible) {
            val showSpec = folmeSpring<Float>(damping = 1f, response = 0.3f)
            launch { compactTitleAlpha.animateTo(1f, showSpec) }
            launch { compactTitleTranslationY.animateTo(0f, showSpec) }
        } else {
            val hideSpec = folmeSpring<Float>(damping = 1f, response = 0.15f)
            launch { compactTitleAlpha.animateTo(0f, hideSpec) }
            launch { compactTitleTranslationY.animateTo(20f, hideSpec) }
        }
    }

    Layout(
        content = {
            Box(
                Modifier
                    .layoutId("navigationIcon")
                    .padding(start = TopAppBarDefaults.NavigationIconPadding),
            ) {
                navigationIcon()
            }
            Box(
                Modifier
                    .layoutId("title")
                    .padding(horizontal = TopAppBarDefaults.TitlePadding)
                    .graphicsLayer {
                        alpha = compactTitleAlpha.value
                        translationY = compactTitleTranslationY.value
                    },
            ) {
                Text(
                    text = title,
                    color = MiuixTheme.colorScheme.onSurface,
                    fontSize = MiuixTheme.textStyles.title3.fontSize,
                    fontWeight = FontWeight.Medium,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                )
            }
            Box(
                Modifier
                    .layoutId("actions")
                    .padding(end = TopAppBarDefaults.ActionIconPadding),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    persistentActions()
                    if (compactContentVisible) actions()
                }
            }
            Box(
                Modifier
                    .layoutId("expandedContent")
                    .then(
                        if (expandedContentBehindToolbar) {
                            Modifier
                        } else {
                            Modifier.padding(top = TopAppBarDefaults.CollapsedHeight)
                        },
                    )
                    .graphicsLayer { alpha = expandedAlpha() },
            ) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, heightOffset().fastRoundToInt()) }
                        .onSizeChanged { size ->
                            val expansionHeight = if (expandedContentBehindToolbar) {
                                (size.height - collapsedHeightPx).coerceAtLeast(0)
                            } else {
                                size.height
                            }
                            val limit = -expansionHeight.toFloat()
                            if (scrollBehavior.state.heightOffsetLimit != limit) {
                                scrollBehavior.state.heightOffsetLimit = limit
                            }
                        },
                ) {
                    expandedContent()
                }
            }
        },
        modifier = modifier
            .background(color)
            .then(
                if (!applyWindowInsets) {
                    Modifier
                } else {
                    Modifier
                        .windowInsetsPadding(
                            WindowInsets.displayCutout.only(WindowInsetsSides.Horizontal),
                        )
                        .windowInsetsPadding(
                            WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal),
                        )
                        .then(
                            if (expandedContentBehindToolbar) {
                                Modifier
                            } else {
                                Modifier.windowInsetsPadding(
                                    WindowInsets.systemBars.only(WindowInsetsSides.Top),
                                )
                            },
                        )
                },
            )
            .clipToBounds()
            .pointerInput(Unit) { detectTapGestures { } },
    ) { measurables, constraints ->
        val navigation = measurables.fastFirst { it.layoutId == "navigationIcon" }
            .measure(constraints.copy(minWidth = 0, minHeight = 0))
        val actionIcons = measurables.fastFirst { it.layoutId == "actions" }
            .measure(constraints.copy(minWidth = 0, minHeight = 0))
        val availableTitleWidth = if (constraints.maxWidth == Constraints.Infinity) {
            constraints.maxWidth
        } else {
            (constraints.maxWidth - navigation.width - actionIcons.width).coerceAtLeast(0)
        }
        val title = measurables.fastFirst { it.layoutId == "title" }
            .measure(
                constraints.copy(
                    minWidth = 0,
                    maxWidth = if (availableTitleWidth == Constraints.Infinity) {
                        availableTitleWidth
                    } else {
                        (availableTitleWidth * 0.7f).fastRoundToInt()
                    },
                    minHeight = 0,
                ),
            )
        val expanded = measurables.fastFirst { it.layoutId == "expandedContent" }
            .measure(
                constraints.copy(
                    minWidth = 0,
                    minHeight = 0,
                    maxHeight = Constraints.Infinity,
                ),
            )
        val expansion = (expanded.height - collapsedHeightPx).coerceAtLeast(0)
        val barHeight = (collapsedHeightPx + expansion + heightOffset().fastRoundToInt())
            .coerceIn(collapsedHeightPx, collapsedHeightPx + expansion)
        val verticalCenter = topInsetPx + toolbarHeightPx / 2

        layout(constraints.maxWidth, barHeight) {
            expanded.placeRelative(x = 0, y = 0)
            navigation.placeRelative(
                x = 0,
                y = verticalCenter - navigation.height / 2,
            )
            var titleX = (constraints.maxWidth - title.width) / 2
            if (titleX < navigation.width) {
                titleX = navigation.width
            } else if (titleX + title.width > constraints.maxWidth - actionIcons.width) {
                titleX = constraints.maxWidth - actionIcons.width - title.width
            }
            title.placeRelative(
                x = titleX,
                y = verticalCenter - title.height / 2,
            )
            actionIcons.placeRelative(
                x = constraints.maxWidth - actionIcons.width,
                y = verticalCenter - actionIcons.height / 2,
            )
        }
    }
}

@Composable
fun CollapsibleMarketScaffold(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
    persistentActions: @Composable RowScope.() -> Unit = {},
    expandedContentBehindToolbar: Boolean = false,
    expandedNavigationIconTint: Color = MiuixTheme.colorScheme.onSurface,
    showNavigationIcon: Boolean = true,
    applyWindowInsets: Boolean = true,
    expandedContent: @Composable () -> Unit,
    content: @Composable (
        innerPadding: PaddingValues,
        backdropModifier: Modifier,
        scrollBehavior: ScrollBehavior,
    ) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface
    val navigationIconTint = if (expandedContentBehindToolbar) {
        lerp(
            expandedNavigationIconTint,
            MiuixTheme.colorScheme.onSurface,
            (scrollBehavior.state.collapsedFraction * 3f).coerceIn(0f, 1f),
        )
    } else {
        MiuixTheme.colorScheme.onSurface
    }

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                CollapsibleContentTopAppBar(
                    title = title,
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        if (showNavigationIcon) {
                            val layoutDirection = LocalLayoutDirection.current
                            IconButton(onClick = onBack) {
                                Icon(
                                    imageVector = MiuixIcons.Back,
                                    contentDescription = stringResource(Res.string.back),
                                    tint = navigationIconTint,
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                    },
                                )
                            }
                        }
                    },
                    actions = actions,
                    persistentActions = persistentActions,
                    expandedContentBehindToolbar = expandedContentBehindToolbar,
                    applyWindowInsets = applyWindowInsets,
                    expandedContent = expandedContent,
                )
            }
        },
        contentWindowInsets = if (applyWindowInsets) {
            WindowInsets.systemBars.union(WindowInsets.displayCutout)
        } else {
            WindowInsets(0)
        },
    ) { innerPadding ->
        val backdropModifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier
        content(innerPadding, backdropModifier, scrollBehavior)
    }
}

/**
 * Blurred, collapsible-app-bar scaffold used by the pushed sub-screens.
 * The [content] receives the inner padding, a backdrop [Modifier] to apply to its scrollable
 * container (so the bars frost over it) and the [ScrollBehavior] to wire into nested scroll.
 */
@Composable
fun MarketScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
    bottomContent: @Composable (ScrollBehavior) -> Unit = {},
    content: @Composable (innerPadding: PaddingValues, backdropModifier: Modifier, scrollBehavior: ScrollBehavior) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        modifier = modifier,
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = title,
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        val layoutDirection = LocalLayoutDirection.current
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = stringResource(Res.string.back),
                                tint = MiuixTheme.colorScheme.onSurface,
                                modifier = Modifier.graphicsLayer {
                                    scaleX = if (layoutDirection == LayoutDirection.Rtl) -1f else 1f
                                },
                            )
                        }
                    },
                    actions = actions,
                    bottomContent = { bottomContent(scrollBehavior) },
                )
            }
        },
    ) { innerPadding ->
        val backdropModifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier
        content(innerPadding, backdropModifier, scrollBehavior)
    }
}

/**
 * Blurred, collapsible-app-bar scaffold used by each bottom-navigation tab.
 * Unlike [MarketScaffold] there is no back button; the parent owns the bottom navigation bar so
 * the tab only supplies its own large [TopAppBar]. The [content] receives the top inset to feed
 * into its list `contentPadding`, a backdrop [Modifier] for its scrollable container and the
 * [ScrollBehavior] to wire into nested scroll.
 */
@Composable
fun MainTabScaffold(
    title: String,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (topPadding: Dp, backdropModifier: Modifier, scrollBehavior: ScrollBehavior) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val backdrop = rememberBlurBackdrop()
    val blurActive = backdrop != null
    val barColor = if (blurActive) Color.Transparent else MiuixTheme.colorScheme.surface

    Scaffold(
        topBar = {
            BlurredBar(backdrop = backdrop, blurActive = blurActive) {
                AdaptiveTopAppBar(
                    title = title,
                    color = barColor,
                    scrollBehavior = scrollBehavior,
                    actions = actions,
                )
            }
        },
    ) { innerPadding ->
        val backdropModifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier
        content(innerPadding.calculateTopPadding(), backdropModifier, scrollBehavior)
    }
}

/** Feed top bar: collapsing large title, no compact title. Collapsed overlay is a progressive blur when RuntimeShader is available, otherwise the status-bar dim gradient. */
@Composable
fun HidingMainTabScaffold(
    title: String,
    modifier: Modifier = Modifier,
    bottomTransitionHeight: Dp = 0.dp,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable (topPadding: Dp, backdropModifier: Modifier, scrollBehavior: ScrollBehavior) -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val density = LocalDensity.current
    val surfaceColor = MiuixTheme.colorScheme.surface
    // Desktop windows report no status-bar inset, so the top bar would have no
    // progressive blur/gradient at all there. Fall back to a default height.
    val statusBarGradientHeight = with(density) {
        (WindowInsets.statusBars.getTop(density).toDp() * 2).coerceAtLeast(56.dp)
    }
    val backdrop = rememberBlurBackdrop()
    val useProgressiveBlur = backdrop != null

    Scaffold(
        modifier = modifier,
        topBar = {
            Box {
                TopAppBar(
                    title = "",
                    largeTitle = title,
                    color = Color.Transparent,
                    scrollBehavior = scrollBehavior,
                    actions = actions,
                )
                if (useProgressiveBlur) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(statusBarGradientHeight)
                            .graphicsLayer { alpha = scrollBehavior.state.collapsedFraction }
                            .progressiveBarBlur(backdrop),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(statusBarGradientHeight)
                            .graphicsLayer { alpha = scrollBehavior.state.collapsedFraction }
                            .background(
                                Brush.verticalGradient(
                                    0f to surfaceColor.copy(alpha = 0.8f),
                                    1f to surfaceColor.copy(alpha = 0f),
                                )
                            ),
                    )
                }
            }
        },
    ) { innerPadding ->
        val backdropModifier = if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier
        Box(modifier = Modifier.fillMaxSize()) {
            content(innerPadding.calculateTopPadding(), backdropModifier, scrollBehavior)
            if (bottomTransitionHeight > 0.dp) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(bottomTransitionHeight)
                        .then(
                            if (useProgressiveBlur) {
                                Modifier.progressiveBottomBarBlur(backdrop)
                            } else {
                                Modifier.background(
                                    Brush.verticalGradient(
                                        0f to surfaceColor.copy(alpha = 0f),
                                        1f to surfaceColor.copy(alpha = 0.8f),
                                    ),
                                )
                            },
                        ),
                )
            }
        }
    }
}

/**
 * Like `padding(top = …)` but reads [top] in the layout phase, so a frame-rate value relayouts
 * instead of recomposing. Mirrors KernelSU's tab-bar spacing under collapsible top bars.
 */
fun Modifier.deferredTopPadding(top: () -> Dp): Modifier = layout { measurable, constraints ->
    val topPx = top().roundToPx().coerceAtLeast(0)
    val placeable = measurable.measure(
        Constraints(
            minWidth = constraints.minWidth,
            maxWidth = constraints.maxWidth,
            minHeight = (constraints.minHeight - topPx).coerceAtLeast(0),
            maxHeight = if (constraints.maxHeight == Constraints.Infinity) {
                Constraints.Infinity
            } else {
                (constraints.maxHeight - topPx).coerceAtLeast(0)
            },
        ),
    )
    val width = constraints.constrainWidth(placeable.width)
    val height = constraints.constrainHeight(placeable.height + topPx)
    layout(width, height) {
        placeable.place(0, topPx)
    }
}

/**
 * Uniform vertical gap used app-wide: between the app bars and scrollable page content, and
 * between stacked page elements. Screens add it to their content padding so every page opens
 * and closes with the same breathing room.
 */
val PageVerticalPadding = 12.dp

/**
 * Section header used across every screen. Wraps Miuix [SmallTitle] with a single, uniform
 * [insideMargin] so the top gap above every section title is identical app-wide regardless of what
 * precedes it — 20.dp, slightly larger than [PageVerticalPadding] to set groups apart. To keep
 * that gap uniform, the element directly above a [SectionTitle] must not add its own bottom
 * margin — the title alone owns the spacing (28.dp horizontal keeps the text aligned with
 * card-row content, which sits at 12.dp card margin + 16.dp inner inset). A title that sits
 * directly under the top bar should pass [topPadding] = 0.dp — there the page's
 * [PageVerticalPadding] content inset alone provides the gap.
 */
@Composable
fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
    topPadding: Dp = 20.dp,
) {
    SmallTitle(
        text = text,
        modifier = modifier,
        insideMargin = PaddingValues(start = 28.dp, top = topPadding, end = 28.dp, bottom = 8.dp),
    )
}
