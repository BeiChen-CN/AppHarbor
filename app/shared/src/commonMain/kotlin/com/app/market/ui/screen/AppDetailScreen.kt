package com.app.market.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import coil3.SingletonImageLoader
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.compose.rememberAsyncImagePainter
import coil3.request.ImageRequest
import com.app.market.domain.model.download.DownloadState
import com.app.market.domain.model.market.AppComment
import com.app.market.domain.model.market.AppDetail
import com.app.market.domain.model.market.AppPromotion
import com.app.market.domain.model.market.AppScreenshot
import com.app.market.domain.model.market.AppSource
import com.app.market.domain.model.market.AppVideo
import com.app.market.domain.model.market.MarketAppInfo
import com.app.market.domain.model.market.ScreenshotOrientation
import com.app.market.domain.model.market.isDownloadBlocked
import com.app.market.domain.model.market.isReservation
import com.app.market.platform.ImageSaveResult
import com.app.market.platform.UiPlatform
import com.app.market.resources.Res
import com.app.market.resources.age_rating
import com.app.market.resources.app_detail
import com.app.market.resources.app_info
import com.app.market.resources.app_size
import com.app.market.resources.app_update_time
import com.app.market.resources.app_version
import com.app.market.resources.cancel_download
import com.app.market.resources.comments_and_scores
import com.app.market.resources.date_full
import com.app.market.resources.download_count
import com.app.market.resources.featured_offers
import com.app.market.resources.historical_versions
import com.app.market.resources.historical_versions_summary
import com.app.market.resources.ic_star
import com.app.market.resources.ic_star_fill
import com.app.market.resources.image_save_failed
import com.app.market.resources.image_save_unsupported
import com.app.market.resources.image_saved
import com.app.market.resources.introduction
import com.app.market.resources.more_options
import com.app.market.resources.no_other_app_store
import com.app.market.resources.num_comments
import com.app.market.resources.open
import com.app.market.resources.open_in_other_app_store
import com.app.market.resources.open_link_failed
import com.app.market.resources.package_name
import com.app.market.resources.preview
import com.app.market.resources.privacy_policy
import com.app.market.resources.registration_num
import com.app.market.resources.reinstall
import com.app.market.resources.reserve
import com.app.market.resources.same_developer
import com.app.market.resources.save_image
import com.app.market.resources.tap_to_open
import com.app.market.resources.update
import com.app.market.resources.video_play
import com.app.market.resources.view_more
import com.app.market.resources.view_offer
import com.app.market.ui.component.AppActionButton
import com.app.market.ui.component.AppAsyncImage
import com.app.market.ui.component.AppButton
import com.app.market.ui.component.AppIcon
import com.app.market.ui.component.AppVideoViewer
import com.app.market.ui.component.CollapsibleMarketScaffold
import com.app.market.ui.component.LoadingBox
import com.app.market.ui.component.PageVerticalPadding
import com.app.market.ui.component.RelatedAppsCard
import com.app.market.ui.model.AppActionKind
import com.app.market.ui.model.actionKind
import com.app.market.ui.util.LocalStripAppNameSubtitle
import com.app.market.ui.util.allowColorSampling
import com.app.market.ui.util.animatedRect
import com.app.market.ui.util.appDisplayName
import com.app.market.ui.util.formatCount
import com.app.market.ui.util.formatSize
import com.app.market.ui.util.installActionText
import com.app.market.ui.util.localDateOf
import com.app.market.ui.util.splitAppDisplayName
import com.app.market.ui.util.topRegionLuminance
import com.app.market.viewmodel.AppDetailViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.number
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.DropdownImpl
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.ListPopupColumn
import top.yukonga.miuix.kmp.basic.PopupPositionProvider
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.overlay.OverlayBottomSheet
import top.yukonga.miuix.kmp.overlay.OverlayListPopup
import top.yukonga.miuix.kmp.squircle.squircleBackground
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

private val PREVIEW_ITEM_HEIGHT = 200.dp
private val PROMOTION_PREVIEW_WIDTH = 256.dp
private const val PORTRAIT_SCREENSHOT_RATIO = 5f / 9f
private const val SCREENSHOT_RATIO_TIMEOUT_MS = 5_000L
private const val SCREENSHOT_RATIO_SAMPLE_PX = 256
private const val SCREENSHOT_RATIO_CONCURRENCY = 3

private const val SCREENSHOT_VIEWER_ANIM_MS = 400
private const val SCREENSHOT_CORNER_DP = 16f
private const val LANDSCAPE_SCREENSHOT_RATIO = 16f / 9f

private const val PROMOTION_EXPANDED_RATIO = 9f / 16f
private val PROMOTION_SHEET_MAX_WIDTH = 420.dp

// miuix sheet 把手区 24dp + 空标题行 18dp
private val PROMOTION_SHEET_CHROME_HEIGHT = 42.dp

@Composable
fun AppDetailScreen(
    viewModel: AppDetailViewModel,
    appId: Long,
    packageName: String,
    externalQuery: String? = null,
    source: AppSource = AppSource.XIAOMI,
    onOpenDetail: (MarketAppInfo) -> Unit,
    onOpenHistory: (MarketAppInfo) -> Unit,
    onBack: () -> Unit,
) {
    val uiPlatform = koinInject<UiPlatform>()
    LaunchedEffect(appId, packageName, externalQuery, source) {
        viewModel.load(appId, packageName, externalQuery, source)
    }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val downloadStates = viewModel.downloadStates.collectAsStateWithLifecycle()
    val showComments by viewModel.showComments.collectAsStateWithLifecycle()
    val showSameDeveloper by viewModel.showSameDeveloper.collectAsStateWithLifecycle()
    val showPromotions by viewModel.showPromotions.collectAsStateWithLifecycle()

    val installText = installActionText()
    val updateText = stringResource(Res.string.update)
    val openText = stringResource(Res.string.open)
    val reserveText = stringResource(Res.string.reserve)
    val current = state.detail
    val currentActionKind = state.actionKind
    val currentPackageName = current?.app?.packageName
    // 进度每变 1% 就是新值：只传 State，顶层仅订阅离散布尔，读取下沉到按钮与菜单
    val currentDownloadState = remember(currentPackageName) {
        derivedStateOf { currentPackageName?.let { downloadStates.value[it] } }
    }
    val canRedownload = current != null && currentActionKind == AppActionKind.OPEN
    val canCancelDownload by remember(currentDownloadState) {
        derivedStateOf { currentDownloadState.value?.isComplete == false }
    }
    val actionKind = currentActionKind ?: current?.app?.actionKind()
    val actionText = when (actionKind) {
        AppActionKind.INSTALL -> installText
        AppActionKind.UPDATE -> updateText
        AppActionKind.OPEN -> openText
        AppActionKind.RESERVE -> reserveText
        null -> ""
    }
    val collapsedTitle = current?.app
        ?.let { appDisplayName(it.displayName, it.source) }
        ?: stringResource(Res.string.app_detail)

    val imageSaveScope = rememberCoroutineScope()
    val savedText = stringResource(Res.string.image_saved)
    val saveFailedText = stringResource(Res.string.image_save_failed)
    val saveUnsupportedText = stringResource(Res.string.image_save_unsupported)
    val noOtherAppStoreText = stringResource(Res.string.no_other_app_store)
    val canOpenOtherAppStore = uiPlatform.packageInstallationSupported
    val openInOtherAppStore: (String) -> Unit = { targetPackageName ->
        if (!uiPlatform.openInOtherAppStore(targetPackageName)) {
            uiPlatform.showToast(noOtherAppStoreText)
        }
    }
    val saveImage: (String, String) -> Unit = { url, fileName ->
        imageSaveScope.launch {
            val message = when (uiPlatform.saveImageToPictures(url, fileName)) {
                ImageSaveResult.Saved -> savedText
                ImageSaveResult.Failed -> saveFailedText
                ImageSaveResult.Unsupported -> saveUnsupportedText
            }
            uiPlatform.showToast(message)
        }
    }
    val phase = when {
        current != null -> DetailPhase.Content
        state.errorMessage.isNotEmpty() -> DetailPhase.Error
        else -> DetailPhase.Loading
    }
    // 预取截图真实宽高比；只要比例，故限解码尺寸与并发
    val platformContext = LocalPlatformContext.current
    val screenshotRatios = remember(current?.app?.appId) { mutableStateMapOf<String, Float>() }
    LaunchedEffect(current?.screenshots) {
        val screenshots = current?.screenshots ?: return@LaunchedEffect
        val imageLoader = SingletonImageLoader.get(platformContext)
        val slots = Semaphore(SCREENSHOT_RATIO_CONCURRENCY)
        for (shot in screenshots) {
            if (screenshotRatios.containsKey(shot.url)) continue
            launch {
                val image = slots.withPermit {
                    withTimeoutOrNull(SCREENSHOT_RATIO_TIMEOUT_MS.milliseconds) {
                        imageLoader.execute(
                            ImageRequest.Builder(platformContext)
                                .data(shot.url)
                                .size(SCREENSHOT_RATIO_SAMPLE_PX)
                                .build()
                        ).image
                    }
                }
                screenshotRatios[shot.url] = if (image != null && image.width > 0 && image.height > 0) {
                    image.width.toFloat() / image.height
                } else {
                    screenshotAspectRatio(shot.orientation)
                }
            }
        }
    }

    var expandedScreenshot by rememberSaveable(appId) { mutableIntStateOf(-1) }
    val screenshotCardBounds = remember { mutableStateMapOf<String, Rect>() }
    var playingVideo by remember(appId) { mutableStateOf<AppVideo?>(null) }
    val videoCardBounds = remember { mutableStateMapOf<String, Rect>() }

    SelectionContainer(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            CollapsibleMarketScaffold(
                title = collapsedTitle,
                onBack = onBack,
                actions = {
                    if (current != null && actionKind != null) {
                        DetailActionButton(
                            packageName = current.app.packageName,
                            actionText = actionText,
                            actionKind = actionKind,
                            downloadStateProvider = currentDownloadState,
                            enabled = actionKind == AppActionKind.OPEN || !current.app.isDownloadBlocked(),
                            onAction = {
                                when (actionKind) {
                                    AppActionKind.OPEN -> viewModel.openApp(current.app)
                                    AppActionKind.INSTALL, AppActionKind.UPDATE -> viewModel.install(current.app)
                                    AppActionKind.RESERVE -> Unit
                                }
                            },
                            onResumeDownload = {
                                when (actionKind) {
                                    AppActionKind.OPEN -> viewModel.redownload(current.app)
                                    AppActionKind.INSTALL, AppActionKind.UPDATE -> viewModel.install(current.app)
                                    AppActionKind.RESERVE -> Unit
                                }
                            },
                            onInstallDownloaded = viewModel::installDownloaded,
                            onCancel = viewModel::cancelDownload,
                            minHeight = 32.dp,
                        )
                    }
                    if (current != null && (canOpenOtherAppStore || canRedownload || canCancelDownload)) {
                        DetailMoreMenu(
                            canOpenOtherAppStore = canOpenOtherAppStore,
                            canRedownload = canRedownload,
                            canCancelDownload = canCancelDownload,
                            onOpenOtherAppStore = { openInOtherAppStore(current.app.packageName) },
                            onRedownload = { viewModel.redownload(current.app) },
                            onClearDownload = { viewModel.clearDownload(current.app.packageName) },
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                },
                expandedContent = {
                    if (current != null && actionKind != null) {
                        Box(Modifier.padding(bottom = 12.dp)) {
                            AppDetailHeading(
                                detail = current,
                                actionText = actionText,
                                actionKind = actionKind,
                                downloadStateProvider = currentDownloadState,
                                canOpenOtherAppStore = canOpenOtherAppStore,
                                canRedownload = canRedownload,
                                canCancelDownload = canCancelDownload,
                                onOpenOtherAppStore = { openInOtherAppStore(current.app.packageName) },
                                onRedownload = { viewModel.redownload(current.app) },
                                onClearDownload = { viewModel.clearDownload(current.app.packageName) },
                                onAction = {
                                    when (actionKind) {
                                        AppActionKind.OPEN -> viewModel.openApp(current.app)
                                        AppActionKind.INSTALL, AppActionKind.UPDATE -> viewModel.install(
                                            current.app
                                        )

                                        AppActionKind.RESERVE -> Unit
                                    }
                                },
                                onResumeDownload = {
                                    when (actionKind) {
                                        AppActionKind.OPEN -> viewModel.redownload(current.app)
                                        AppActionKind.INSTALL, AppActionKind.UPDATE -> viewModel.install(
                                            current.app
                                        )

                                        AppActionKind.RESERVE -> Unit
                                    }
                                },
                                onInstallDownloaded = viewModel::installDownloaded,
                                onCancel = viewModel::cancelDownload,
                                onSaveImage = saveImage,
                            )
                        }
                    }
                },
            ) { innerPadding, backdropModifier, scrollBehavior ->
                val layoutDirection = LocalLayoutDirection.current
                val contentPadding = PaddingValues(
                    start = innerPadding.calculateStartPadding(layoutDirection),
                    end = innerPadding.calculateEndPadding(layoutDirection),
                    top = innerPadding.calculateTopPadding() + PageVerticalPadding,
                    bottom = innerPadding.calculateBottomPadding() + PageVerticalPadding,
                )
                Crossfade(
                    targetState = phase,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MiuixTheme.colorScheme.surface)
                        .then(backdropModifier),
                    label = "detail",
                ) { target ->
                    when (target) {
                        DetailPhase.Loading -> LoadingBox(Modifier.fillMaxSize())
                        DetailPhase.Error -> Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = state.errorMessage,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                style = MiuixTheme.textStyles.main,
                                textAlign = TextAlign.Center,
                            )
                        }

                        DetailPhase.Content -> current?.let { detail ->
                            AppDetailContent(
                                current = detail,
                                downloadStates = downloadStates,
                                showComments = showComments,
                                showSameDeveloper = showSameDeveloper,
                                showPromotions = showPromotions,
                                contentPadding = contentPadding,
                                scrollBehavior = scrollBehavior,
                                viewModel = viewModel,
                                uiPlatform = uiPlatform,
                                saveImage = saveImage,
                                onOpenDetail = onOpenDetail,
                                onOpenHistory = onOpenHistory,
                                screenshotRatios = screenshotRatios,
                                hiddenScreenshot = expandedScreenshot,
                                onExpandScreenshot = { expandedScreenshot = it },
                                onScreenshotCardPositioned = { url, bounds ->
                                    if (bounds == null) screenshotCardBounds.remove(url) else screenshotCardBounds[url] = bounds
                                },
                                onPlayVideo = { playingVideo = it },
                                playingVideoUrl = playingVideo?.url,
                                onVideoCardPositioned = { url, bounds ->
                                    if (bounds == null) videoCardBounds.remove(url) else videoCardBounds[url] = bounds
                                },
                            )
                        }
                    }
                }
            }

            current?.takeIf { expandedScreenshot >= 0 && it.screenshots.isNotEmpty() }?.let { detail ->
                ScreenshotViewer(
                    screenshots = detail.screenshots,
                    screenshotRatios = screenshotRatios,
                    cardBounds = screenshotCardBounds,
                    initialPage = expandedScreenshot.coerceIn(0, detail.screenshots.lastIndex),
                    onPageChange = { expandedScreenshot = it },
                    onClosed = { expandedScreenshot = -1 },
                )
            }

            playingVideo?.let { video ->
                AppVideoViewer(
                    video = video,
                    cardBounds = videoCardBounds[video.url],
                    onClosed = { playingVideo = null },
                )
            }
        }
    }
}

private enum class DetailPhase { Loading, Error, Content }

@Composable
private fun AppDetailContent(
    current: AppDetail,
    downloadStates: State<Map<String, DownloadState>>,
    showComments: Boolean,
    showSameDeveloper: Boolean,
    showPromotions: Boolean,
    contentPadding: PaddingValues,
    scrollBehavior: ScrollBehavior,
    viewModel: AppDetailViewModel,
    uiPlatform: UiPlatform,
    saveImage: (String, String) -> Unit,
    onOpenDetail: (MarketAppInfo) -> Unit,
    onOpenHistory: (MarketAppInfo) -> Unit,
    screenshotRatios: Map<String, Float>,
    hiddenScreenshot: Int,
    onExpandScreenshot: (Int) -> Unit,
    onScreenshotCardPositioned: (String, Rect?) -> Unit,
    onPlayVideo: (AppVideo) -> Unit,
    playingVideoUrl: String?,
    onVideoCardPositioned: (String, Rect?) -> Unit,
) {
    val installText = installActionText()
    val reserveText = stringResource(Res.string.reserve)
    val previewTitle = stringResource(Res.string.preview)
    val promotionsTitle = stringResource(Res.string.featured_offers)
    val appInfoTitle = stringResource(Res.string.app_info)
    val historicalVersionsLabel = stringResource(Res.string.historical_versions)
    val historicalVersionsSummary = stringResource(Res.string.historical_versions_summary)

    val commentCount = maxOf(current.commentCount, current.comments.size.toLong())
    val commentsLabel = stringResource(
        Res.string.num_comments,
        formatCount(commentCount).ifBlank { "0" }
    )
    val commentsTitle = stringResource(Res.string.comments_and_scores)

    val sameDeveloperTitle = stringResource(Res.string.same_developer)
    val ageLabel = stringResource(Res.string.age_rating)
    val downloadCountLabel = stringResource(Res.string.download_count)
    val registrationLabel = stringResource(Res.string.registration_num)
    val privacyLabel = stringResource(Res.string.privacy_policy)
    val packageNameLabel = stringResource(Res.string.package_name)
    val tapToOpenText = stringResource(Res.string.tap_to_open)
    val viewMoreText = stringResource(Res.string.view_more)
    val appSizeLabel = stringResource(Res.string.app_size)
    val versionLabel = stringResource(Res.string.app_version)
    val updateTimeLabel = stringResource(Res.string.app_update_time)
    val introduction = current.introduction.ifBlank { current.brief }
    val stripNameSubtitle = LocalStripAppNameSubtitle.current
    val uriHandler = LocalUriHandler.current
    var showCommentsSheet by rememberSaveable(current.app.appId) { mutableStateOf(false) }
    var showPromotionSheet by rememberSaveable(current.app.appId) { mutableStateOf(false) }
    var promotionSheetIndex by rememberSaveable(current.app.appId) { mutableIntStateOf(0) }
    val sheetPromotion = current.promotions.getOrNull(promotionSheetIndex)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .scrollEndHaptic()
            .overScrollVertical()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item(key = "stats") {
            AppDetailStatsRow(
                rating = formatRating(current.app.ratingScore),
                reviews = commentsLabel,
                downloads = current.downloadCount
                    .takeUnless { current.app.source == AppSource.SAMSUNG }
                    ?.let { formatCount(it).ifBlank { "0" } },
                downloadsLabel = downloadCountLabel,
                appSize = formatSize(current.app.apkSize).ifBlank { "--" },
                appSizeLabel = appSizeLabel,
                ageClassification = current.ageClassification,
                ageLabel = ageLabel,
                onReviewsClick = if (current.comments.isNotEmpty()) {
                    { showCommentsSheet = true }
                } else {
                    null
                },
            )
        }

        val showPromotionsSection = showPromotions && current.promotions.isNotEmpty()
        if (showPromotionsSection || current.videos.isNotEmpty() || current.screenshots.isNotEmpty() || introduction.isNotBlank()) {
            item(key = "preview") {
                PreviewAndIntroductionSection(
                    current = current,
                    showPromotions = showPromotionsSection,
                    promotionsTitle = promotionsTitle,
                    screenshotsTitle = previewTitle,
                    screenshotRatios = screenshotRatios,
                    hiddenScreenshot = hiddenScreenshot,
                    onExpandScreenshot = onExpandScreenshot,
                    onScreenshotCardPositioned = onScreenshotCardPositioned,
                    onPlayVideo = onPlayVideo,
                    playingVideoUrl = playingVideoUrl,
                    onVideoCardPositioned = onVideoCardPositioned,
                    introduction = introduction,
                    onOpenPromotion = {
                        promotionSheetIndex = it
                        showPromotionSheet = true
                    },
                    onSaveImage = saveImage,
                )
            }
        }

        item(key = "appinfo") {
            DetailSection(
                title = appInfoTitle,
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    cornerRadius = 16.dp,
                    insideMargin = PaddingValues(16.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        InfoLine(packageNameLabel, current.app.packageName)
                        InfoLine(versionLabel, current.app.versionName)
                        if (current.updateTime > 0L) {
                            val date = localDateOf(current.updateTime)
                            InfoLine(
                                updateTimeLabel,
                                stringResource(
                                    Res.string.date_full,
                                    date.year,
                                    date.month.number,
                                    date.day
                                ),
                            )
                        }
                        if (current.registrationNum.isNotBlank()) {
                            InfoLine(registrationLabel, current.registrationNum)
                        }
                        if (current.privacyUrl.isNotBlank()) {
                            InfoLine(
                                label = privacyLabel,
                                value = tapToOpenText,
                                valueColor = MiuixTheme.colorScheme.primary,
                                onValueClick = { uriHandler.openUri(current.privacyUrl) },
                            )
                        }
                        if (current.app.source.capabilities.supportsHistoricalVersions) {
                            InfoLine(
                                label = historicalVersionsLabel,
                                value = historicalVersionsSummary,
                                valueColor = MiuixTheme.colorScheme.primary,
                                onValueClick = { onOpenHistory(current.app) },
                            )
                        }
                    }
                }
            }
        }

        if (showComments && current.comments.isNotEmpty()) {
            item(key = "comments") {
                val displayedComments = current.comments.take(2)
                DetailSection(
                    title = commentsTitle,
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        cornerRadius = 16.dp,
                        insideMargin = PaddingValues(16.dp),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Column {
                                Text(
                                    text = formatRating(current.app.ratingScore),
                                    color = MiuixTheme.colorScheme.onSurface,
                                    style = MiuixTheme.textStyles.title1.copy(lineHeight = 40.sp),
                                )
                                Text(
                                    text = commentsLabel,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
                                )
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                displayedComments.forEach { comment ->
                                    CommentContent(comment)
                                }
                            }
                            if (current.comments.size > 2) {
                                Text(
                                    text = viewMoreText,
                                    color = MiuixTheme.colorScheme.primary,
                                    style = MiuixTheme.textStyles.main.copy(lineHeight = 22.sp),
                                    modifier = Modifier.clickable { showCommentsSheet = true },
                                )
                            }
                        }
                    }
                }
            }
        }

        val sameDeveloper = current.sameDeveloperApps
            .filter {
                it.appId != current.app.appId &&
                        !it.packageName.equals(current.app.packageName, ignoreCase = true)
            }
            .take(3)
        if (showSameDeveloper && sameDeveloper.isNotEmpty()) {
            item(key = "samedev") {
                DetailSection(
                    title = sameDeveloperTitle,
                ) {
                    Box(Modifier.padding(horizontal = 12.dp)) {
                        RelatedAppsCard(
                            apps = sameDeveloper,
                            onOpenApp = onOpenDetail,
                            title = { app -> app.displayName.splitAppDisplayName(app.source).appName },
                            summary = { app ->
                                (if (stripNameSubtitle) "" else app.displayName.splitAppDisplayName(app.source).description)
                                    .ifBlank { app.publisherName }
                            },
                            colors = CardDefaults.defaultColors(),
                        ) { app ->
                            val reservation = app.isReservation()
                            val rowDownloadState = remember(app.packageName) {
                                derivedStateOf { downloadStates.value[app.packageName] }
                            }
                            DetailActionButton(
                                packageName = app.packageName,
                                actionText = if (reservation) reserveText else installText,
                                actionKind = if (reservation) AppActionKind.RESERVE else AppActionKind.INSTALL,
                                downloadStateProvider = rowDownloadState,
                                onAction = { viewModel.install(app) },
                                onResumeDownload = { viewModel.install(app) },
                                onInstallDownloaded = viewModel::installDownloaded,
                                onCancel = viewModel::cancelDownload,
                                minHeight = 34.dp,
                            )
                        }
                    }
                }
            }
        }
    }

    OverlayBottomSheet(
        show = showCommentsSheet,
        title = commentsTitle,
        onDismissRequest = { showCommentsSheet = false },
        enableNestedScroll = true,
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "comments-summary") {
                Column {
                    Text(
                        text = formatRating(current.app.ratingScore),
                        color = MiuixTheme.colorScheme.onSurface,
                        style = MiuixTheme.textStyles.title1.copy(lineHeight = 40.sp),
                    )
                    Text(
                        text = commentsLabel,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
                    )
                }
            }
            itemsIndexed(
                items = current.comments,
                key = { index, comment -> "${index}_${comment.userName}" },
            ) { _, comment ->
                CommentContent(comment)
            }
        }
    }

    var promotionHandleColor by remember(sheetPromotion?.expandedImageUrl) { mutableStateOf(Color.White) }
    OverlayBottomSheet(
        show = showPromotionSheet && sheetPromotion != null,
        dragHandleColor = promotionHandleColor,
        sheetMaxWidth = PROMOTION_SHEET_MAX_WIDTH,
        insideMargin = DpSize(0.dp, 0.dp),
        onDismissRequest = { showPromotionSheet = false },
        allowDismiss = true,
        enableNestedScroll = true,
    ) {
        val openLinkFailedText = stringResource(Res.string.open_link_failed)
        sheetPromotion?.let { promotion ->
            PromotionDetails(
                promotion = promotion,
                onOpenOffer = promotion.jumpUrl.takeIf(String::isNotBlank)?.let { jumpUrl ->
                    {
                        runCatching { uriHandler.openUri(jumpUrl) }
                            .onFailure { uiPlatform.showToast(openLinkFailedText) }
                    }
                },
                onHandleColor = { promotionHandleColor = it },
            )
        }
    }
}

@Composable
private fun AppDetailStatsRow(
    rating: String,
    reviews: String,
    downloads: String?,
    downloadsLabel: String,
    appSize: String,
    appSizeLabel: String,
    /** 空串表示没有分级信息，整格连同分隔线一起省掉。 */
    ageClassification: String,
    ageLabel: String,
    /** 无评论正文时传 null，否则点开只是空面板。 */
    onReviewsClick: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DetailStat(
            value = rating,
            label = reviews,
            showStar = true,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onReviewsClick != null) {
                        Modifier.clickable(
                            interactionSource = null,
                            indication = null,
                            onClick = onReviewsClick,
                        )
                    } else {
                        Modifier
                    }
                ),
        )
        if (downloads != null) {
            DetailStatDivider()
            DetailStat(
                value = downloads,
                label = downloadsLabel,
                modifier = Modifier.weight(1f),
            )
        }
        DetailStatDivider()
        DetailStat(
            value = appSize,
            label = appSizeLabel,
            modifier = Modifier.weight(1f),
        )
        if (ageClassification.isNotBlank()) {
            DetailStatDivider()
            DetailStat(
                value = ageClassification,
                label = ageLabel,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DetailStat(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    showStar: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = value,
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.main.copy(lineHeight = 20.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (showStar) {
                Icon(
                    painter = painterResource(Res.drawable.ic_star_fill),
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2.copy(fontSize = 12.sp, lineHeight = 16.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DetailStatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(28.dp)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
    )
}

@Composable
private fun PreviewAndIntroductionSection(
    current: AppDetail,
    showPromotions: Boolean,
    promotionsTitle: String,
    screenshotsTitle: String,
    screenshotRatios: Map<String, Float>,
    hiddenScreenshot: Int,
    onExpandScreenshot: (Int) -> Unit,
    onScreenshotCardPositioned: (String, Rect?) -> Unit,
    onPlayVideo: (AppVideo) -> Unit,
    playingVideoUrl: String?,
    onVideoCardPositioned: (String, Rect?) -> Unit,
    introduction: String,
    onOpenPromotion: (Int) -> Unit,
    onSaveImage: (String, String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (showPromotions) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallTitle(
                    text = promotionsTitle,
                    insideMargin = PaddingValues(horizontal = 28.dp, vertical = 0.dp),
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    itemsIndexed(
                        current.promotions,
                        key = { _, promotion -> promotion.previewImageUrl },
                    ) { index, promotion ->
                        PromotionPreview(
                            promotion = promotion,
                            fileName = "${current.app.packageName}_promotion_${index + 1}",
                            onClick = { onOpenPromotion(index) },
                            onSaveImage = onSaveImage,
                        )
                    }
                }
            }
        }

        if (current.videos.isNotEmpty() || current.screenshots.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallTitle(
                    text = screenshotsTitle,
                    insideMargin = PaddingValues(horizontal = 28.dp, vertical = 0.dp),
                )
                val readyPrefix = current.screenshots
                    .indexOfFirst { !screenshotRatios.containsKey(it.url) }
                    .let { if (it < 0) current.screenshots.size else it }
                LazyRow(
                    modifier = Modifier.height(PREVIEW_ITEM_HEIGHT),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                ) {
                    itemsIndexed(
                        current.videos,
                        key = { _, video -> video.url },
                    ) { _, video ->
                        VideoPreviewCard(
                            video = video,
                            hidden = video.url == playingVideoUrl,
                            onClick = { onPlayVideo(video) },
                            onPositioned = { bounds -> onVideoCardPositioned(video.url, bounds) },
                        )
                    }
                    itemsIndexed(
                        current.screenshots.take(readyPrefix),
                        key = { _, shot -> shot.url }) { index, shot ->
                        SaveableImageBox(
                            url = shot.expandedUrl,
                            fileName = "${current.app.packageName}_screenshot_${index + 1}",
                            onClick = { onExpandScreenshot(index) },
                            onSaveImage = onSaveImage,
                        ) {
                            // 滚出视口即移除记录，收回动画据此判断目标是否在场
                            DisposableEffect(shot.url) {
                                onDispose { onScreenshotCardPositioned(shot.url, null) }
                            }
                            Screenshot(
                                screenshot = shot,
                                ratio = screenshotRatios.getValue(shot.url),
                                modifier = Modifier
                                    // positionInRoot 不受父容器裁剪影响；boundsInRoot 会把半可见卡片裁成半张
                                    .onGloballyPositioned {
                                        onScreenshotCardPositioned(shot.url, Rect(it.positionInRoot(), it.size.toSize()))
                                    }
                                    .graphicsLayer { alpha = if (index == hiddenScreenshot) 0f else 1f }
                                    .squircleClip(16.dp),
                            )
                        }
                    }
                }
            }
        }

        if (introduction.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallTitle(
                    text = stringResource(Res.string.introduction),
                    insideMargin = PaddingValues(horizontal = 28.dp, vertical = 0.dp),
                )
                IntroductionSummary(
                    text = introduction,
                )
            }
        }
    }
}

@Composable
private fun VideoPreviewCard(
    video: AppVideo,
    hidden: Boolean,
    onClick: () -> Unit,
    onPositioned: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
) {
    DisposableEffect(video.url) {
        onDispose { onPositioned(null) }
    }
    Box(
        modifier = modifier
            .height(PREVIEW_ITEM_HEIGHT)
            .aspectRatio(screenshotAspectRatio(video.orientation))
            .onGloballyPositioned {
                onPositioned(Rect(it.positionInRoot(), it.size.toSize()))
            }
            .graphicsLayer { alpha = if (hidden) 0f else 1f }
            .squircleSurface(
                color = MiuixTheme.colorScheme.surfaceContainer,
                cornerRadius = 16.dp,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        AppAsyncImage(
            url = video.coverUrl,
            contentDescription = video.title.takeIf { it.isNotBlank() },
            contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            modifier = Modifier
                .size(44.dp)
                .squircleBackground(
                    color = Color.Black.copy(alpha = 0.35f),
                    cornerRadius = 22.dp,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                modifier = Modifier.padding(start = 2.dp),
                imageVector = MiuixIcons.Play,
                contentDescription = stringResource(Res.string.video_play),
                tint = Color.White,
            )
        }
    }
}

@Composable
private fun PromotionPreview(
    promotion: AppPromotion,
    fileName: String,
    onClick: () -> Unit,
    onSaveImage: (String, String) -> Unit,
) {
    Column(
        modifier = Modifier.width(PROMOTION_PREVIEW_WIDTH),
    ) {
        SaveableImageBox(
            url = promotion.previewImageUrl,
            fileName = fileName,
            onClick = onClick,
            onSaveImage = onSaveImage,
        ) {
            AppAsyncImage(
                url = promotion.previewImageUrl,
                contentDescription = promotion.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .width(PROMOTION_PREVIEW_WIDTH)
                    .height(PROMOTION_PREVIEW_WIDTH / LANDSCAPE_SCREENSHOT_RATIO)
                    .squircleClip(16.dp),
            )
        }
        if (promotion.title.isNotBlank()) {
            Text(
                text = promotion.title,
                modifier = Modifier.padding(horizontal = 4.dp),
                color = MiuixTheme.colorScheme.onSurface,
                style = MiuixTheme.textStyles.main,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (promotion.category.isNotBlank()) {
            Text(
                text = promotion.category,
                modifier = Modifier.padding(horizontal = 4.dp),
                color = MiuixTheme.colorScheme.primary,
                style = MiuixTheme.textStyles.body2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun PromotionDetails(
    promotion: AppPromotion,
    onOpenOffer: (() -> Unit)?,
    onHandleColor: (Color) -> Unit,
) {
    var imageRatio by remember(promotion.expandedImageUrl) {
        mutableFloatStateOf(
            PROMOTION_EXPANDED_RATIO
        )
    }
    val sheetScrollState = rememberScrollState()
    val platformContext = LocalPlatformContext.current
    val background = rememberAsyncImagePainter(
        model = remember(promotion.expandedImageUrl, platformContext) {
            ImageRequest.Builder(platformContext)
                .data(promotion.expandedImageUrl)
                .allowColorSampling()
                .build()
        },
        contentScale = ContentScale.Crop,
    )
    LaunchedEffect(background) {
        background.state.collect { state ->
            if (state is AsyncImagePainter.State.Success) {
                val image = state.result.image
                if (image.width > 0 && image.height > 0) {
                    imageRatio = image.width.toFloat() / image.height
                }
                // 亮度采样要做整图位图拷贝
                val luminance = withContext(Dispatchers.Default) { topRegionLuminance(image) }
                if (luminance != null) {
                    onHandleColor(if (luminance > 0.5f) Color.Black else Color.White)
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            // 背景画在内容内以随 sheet 位移；zIndex 压到把手之下，drawBehind 向上延伸盖住把手区
            .zIndex(-1f)
            .drawBehind { drawPromotionBackground(background) }
            .animateContentSize(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .verticalScroll(sheetScrollState),
        ) {
            // 按背景图比例撑起 sheet 高度
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(imageRatio),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.4f to Color.Black.copy(alpha = 0.55f),
                            1f to Color.Black.copy(alpha = 0.8f),
                        ),
                    )
                    .padding(horizontal = 24.dp)
                    .padding(top = 56.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 叠图文字固定白色系，不随主题（特例）
                if (promotion.title.isNotBlank()) {
                    Text(
                        text = promotion.title,
                        color = Color.White,
                        style = MiuixTheme.textStyles.title3,
                        fontWeight = FontWeight.Bold,
                    )
                }
                val labels =
                    listOf(promotion.category, promotion.activityTag).filter(String::isNotBlank)
                if (labels.isNotEmpty()) {
                    Text(
                        text = labels.joinToString(" | "),
                        color = Color.White.copy(alpha = 0.8f),
                        style = MiuixTheme.textStyles.body2,
                    )
                }
                if (promotion.description.isNotBlank()) {
                    SelectionContainer(
                        modifier = Modifier
                            .heightIn(max = 160.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = promotion.description,
                            color = Color.White.copy(alpha = 0.9f),
                            style = MiuixTheme.textStyles.paragraph.copy(lineHeight = 22.sp),
                        )
                    }
                }
                if (onOpenOffer != null) {
                    AppButton(
                        text = stringResource(Res.string.view_offer),
                        onClick = onOpenOffer,
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 12.dp),
                        colors = ButtonDefaults.buttonColorsPrimary(),
                        cornerRadius = 100.dp,
                    )
                }
            }
        }
    }
}

// 等比铺满内容区与上方把手区（Crop）；painter 未加载时无固有尺寸（NaN 比较为 false），走 else 分支不可见
private fun DrawScope.drawPromotionBackground(painter: Painter) {
    val chromePx = PROMOTION_SHEET_CHROME_HEIGHT.toPx()
    val target = Size(size.width, size.height + chromePx)
    val intrinsic = painter.intrinsicSize
    translate(top = -chromePx) {
        if (intrinsic.width > 0f && intrinsic.height > 0f) {
            val scale = maxOf(target.width / intrinsic.width, target.height / intrinsic.height)
            val drawSize = Size(intrinsic.width * scale, intrinsic.height * scale)
            translate(
                left = (target.width - drawSize.width) / 2f,
                top = (target.height - drawSize.height) / 2f,
            ) {
                with(painter) { draw(drawSize) }
            }
        } else {
            with(painter) { draw(target) }
        }
    }
}

@Composable
private fun IntroductionSummary(
    text: String,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    var hasVisualOverflow by remember(text) { mutableStateOf(false) }
    val showMore = !expanded && hasVisualOverflow
    val descriptionStyle = MiuixTheme.textStyles.paragraph.copy(lineHeight = 22.sp)
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxWidth(),
    ) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(16.dp)) {
            val surface = MiuixTheme.colorScheme.surfaceContainer
            Box(Modifier.fillMaxWidth()) {
                Text(
                    text = text,
                    modifier = Modifier.fillMaxWidth(),
                    color = MiuixTheme.colorScheme.onSurface,
                    style = descriptionStyle,
                    maxLines = if (expanded) Int.MAX_VALUE else 3,
                    overflow = TextOverflow.Clip,
                    onTextLayout = { result ->
                        if (!expanded) hasVisualOverflow = result.hasVisualOverflow
                    },
                )
                if (showMore) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .height(IntrinsicSize.Min)
                            .clickable { expanded = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .width(58.dp)
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(
                                        0f to Color.Transparent,
                                        1f to surface,
                                    ),
                                ),
                        )
                        Text(
                            text = stringResource(Res.string.view_more),
                            modifier = Modifier.background(surface),
                            color = MiuixTheme.colorScheme.primary,
                            style = descriptionStyle,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Zero vertical inside margin: the list's 20.dp item spacing and this column's 8.dp gap
        // own the vertical rhythm, matching the app-wide SectionTitle look.
        SmallTitle(
            text = title,
            insideMargin = PaddingValues(horizontal = 28.dp, vertical = 0.dp),
        )
        content()
    }
}

@Composable
private fun AppDetailHeading(
    detail: AppDetail,
    actionText: String,
    actionKind: AppActionKind,
    downloadStateProvider: State<DownloadState?>,
    canOpenOtherAppStore: Boolean,
    canRedownload: Boolean,
    canCancelDownload: Boolean,
    onOpenOtherAppStore: () -> Unit,
    onAction: () -> Unit,
    onResumeDownload: () -> Unit,
    onInstallDownloaded: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRedownload: () -> Unit,
    onClearDownload: () -> Unit,
    onSaveImage: (String, String) -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(top = 12.dp)
            .padding(horizontal = 12.dp)
            .height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SaveableImageBox(
            url = detail.app.icon,
            fileName = "${detail.app.packageName}_icon",
            onSaveImage = onSaveImage,
        ) {
            AppIcon(
                url = detail.app.icon,
                contentDescription = appDisplayName(detail.app.displayName, detail.app.source),
                size = 96.dp,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.Start,
        ) {
            Column {
                Text(
                    text = appDisplayName(detail.app.displayName, detail.app.source),
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    color = MiuixTheme.colorScheme.onSurface,
                    style = MiuixTheme.textStyles.title2.copy(lineHeight = 30.sp),
                    maxLines = 1,
                )
                Text(
                    text = detail.app.publisherName.ifBlank { detail.app.packageName },
                    modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
                    maxLines = 1,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DetailActionButton(
                    packageName = detail.app.packageName,
                    actionText = actionText,
                    actionKind = actionKind,
                    downloadStateProvider = downloadStateProvider,
                    enabled = actionKind == AppActionKind.OPEN || !detail.app.isDownloadBlocked(),
                    onAction = onAction,
                    onResumeDownload = onResumeDownload,
                    onInstallDownloaded = onInstallDownloaded,
                    onCancel = onCancel,
                    minHeight = 32.dp,
                )
                if (canOpenOtherAppStore || canRedownload || canCancelDownload) {
                    DetailMoreMenu(
                        canOpenOtherAppStore = canOpenOtherAppStore,
                        canRedownload = canRedownload,
                        canCancelDownload = canCancelDownload,
                        onOpenOtherAppStore = onOpenOtherAppStore,
                        onRedownload = onRedownload,
                        onClearDownload = onClearDownload,
                    )
                }
            }
        }
    }
}

/** 顶栏与展开头部共用的 ⋮ 菜单按钮，弹窗锚定在按钮处。 */
@Composable
private fun DetailMoreMenu(
    canOpenOtherAppStore: Boolean,
    canRedownload: Boolean,
    canCancelDownload: Boolean,
    onOpenOtherAppStore: () -> Unit,
    onRedownload: () -> Unit,
    onClearDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box(modifier) {
        IconButton(
            onClick = { showMenu = true },
            holdDownState = showMenu,
            backgroundColor = MiuixTheme.colorScheme.secondaryVariant,
            cornerRadius = 100.dp,
            minHeight = 32.dp,
            minWidth = 32.dp,
        ) {
            Icon(
                imageVector = MiuixIcons.More,
                contentDescription = stringResource(Res.string.more_options),
                tint = MiuixTheme.colorScheme.onSecondaryVariant,
                modifier = Modifier.size(20.dp),
            )
        }
        OverlayListPopup(
            show = showMenu,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showMenu = false },
        ) {
            ListPopupColumn {
                val optionSize =
                    (if (canOpenOtherAppStore) 1 else 0) +
                            (if (canRedownload) 1 else 0) +
                            (if (canCancelDownload) 1 else 0)
                var optionIndex = 0
                if (canOpenOtherAppStore) {
                    DropdownImpl(
                        text = stringResource(Res.string.open_in_other_app_store),
                        optionSize = optionSize,
                        isSelected = false,
                        index = optionIndex++,
                        onSelectedIndexChange = {
                            showMenu = false
                            onOpenOtherAppStore()
                        },
                    )
                }
                if (canRedownload) {
                    DropdownImpl(
                        text = stringResource(Res.string.reinstall),
                        optionSize = optionSize,
                        isSelected = false,
                        index = optionIndex++,
                        onSelectedIndexChange = {
                            showMenu = false
                            onRedownload()
                        },
                    )
                }
                if (canCancelDownload) {
                    DropdownImpl(
                        text = stringResource(Res.string.cancel_download),
                        optionSize = optionSize,
                        isSelected = false,
                        index = optionIndex,
                        onSelectedIndexChange = {
                            showMenu = false
                            onClearDownload()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailActionButton(
    packageName: String,
    actionText: String,
    actionKind: AppActionKind,
    downloadStateProvider: State<DownloadState?>,
    enabled: Boolean = true,
    onAction: () -> Unit,
    onResumeDownload: () -> Unit,
    onInstallDownloaded: (String) -> Unit,
    onCancel: (String) -> Unit,
    minHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val downloadState = downloadStateProvider.value
    if (downloadState == null) {
        AppButton(
            text = actionText,
            onClick = onAction,
            enabled = enabled && actionKind != AppActionKind.RESERVE,
            colors = if (actionKind == AppActionKind.OPEN || actionKind == AppActionKind.RESERVE) {
                ButtonDefaults.buttonColors()
            } else {
                ButtonDefaults.buttonColorsPrimary()
            },
            cornerRadius = 100.dp,
            minHeight = minHeight,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            modifier = modifier,
        )
    } else {
        AppActionButton(
            packageName = packageName,
            actionText = actionText,
            actionKind = actionKind,
            downloadState = downloadState,
            enabled = enabled && actionKind != AppActionKind.RESERVE,
            onAction = onAction,
            onResumeDownload = onResumeDownload,
            onInstallDownloaded = onInstallDownloaded,
            onCancel = onCancel,
            minHeight = minHeight,
            insideMargin = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
            modifier = modifier,
        )
    }
}

private fun Modifier.saveImageOnLongPress(
    url: String,
    fileName: String,
    onClick: (() -> Unit)? = null,
    onSave: (String, String) -> Unit,
): Modifier = if (onClick == null) {
    pointerInput(url, fileName) {
        detectTapGestures(onLongPress = { onSave(url, fileName) })
    }
} else {
    combinedClickable(
        onClick = onClick,
        onLongClick = { onSave(url, fileName) },
    )
}

@Composable
private fun SaveableImageBox(
    url: String,
    fileName: String,
    onClick: (() -> Unit)? = null,
    onSaveImage: (String, String) -> Unit,
    content: @Composable () -> Unit,
) {
    var showMenu by remember(url, fileName) { mutableStateOf(false) }
    Box(
        modifier = Modifier.saveImageOnLongPress(
            url = url,
            fileName = fileName,
            onClick = onClick,
            onSave = { _, _ -> showMenu = true },
        ),
    ) {
        content()
        OverlayListPopup(
            show = showMenu,
            alignment = PopupPositionProvider.Align.End,
            onDismissRequest = { showMenu = false },
        ) {
            ListPopupColumn {
                DropdownImpl(
                    text = stringResource(Res.string.save_image),
                    optionSize = 1,
                    isSelected = false,
                    index = 0,
                    onSelectedIndexChange = {
                        showMenu = false
                        onSaveImage(url, fileName)
                    },
                )
            }
        }
    }
}

@Composable
private fun Screenshot(
    screenshot: AppScreenshot,
    ratio: Float,
    modifier: Modifier = Modifier,
) {
    AppAsyncImage(
        url = screenshot.url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .height(PREVIEW_ITEM_HEIGHT)
            .aspectRatio(ratio),
    )
}

// 全屏截图查看层：变换阶段绘制独立图层，浏览阶段是纯 Pager，二者在进度端点处像素对齐无缝切换
@Composable
private fun ScreenshotViewer(
    screenshots: List<AppScreenshot>,
    screenshotRatios: Map<String, Float>,
    cardBounds: Map<String, Rect>,
    initialPage: Int,
    onPageChange: (Int) -> Unit,
    onClosed: () -> Unit,
) {
    val pagerState = rememberPagerState(initialPage = initialPage) { screenshots.size }
    val currentOnPageChange by rememberUpdatedState(onPageChange)
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { currentOnPageChange(it) }
    }

    // 0 = 卡片位置，1 = 全屏
    val progress = remember { Animatable(0f) }
    var browsing by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(SCREENSHOT_VIEWER_ANIM_MS))
        browsing = true
    }
    val animationScope = rememberCoroutineScope()
    val dismiss: () -> Unit = {
        if (!closing) {
            closing = true
            browsing = false
            animationScope.launch {
                progress.animateTo(0f, tween(SCREENSHOT_VIEWER_ANIM_MS))
                onClosed()
            }
        }
    }

    val backState = rememberNavigationEventState(NavigationEventInfo.None)
    NavigationBackHandler(
        state = backState,
        isBackEnabled = !closing,
        onBackCompleted = dismiss,
    )

    // 卡片位置以根坐标系上报，需换算到查看层自身坐标系（宽屏侧栏等场景下两者原点不同）
    var viewerOrigin by remember { mutableStateOf(Offset.Zero) }
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { viewerOrigin = it.positionInRoot() }
            .pointerInput(Unit) { detectTapGestures { dismiss() } },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value }
                .background(Color.Black),
        )

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = if (browsing) 1f else 0f },
            userScrollEnabled = browsing,
        ) { page ->
            val shot = screenshots[page]
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppAsyncImage(
                    url = shot.expandedUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    placeholderMemoryCacheUrl = shot.url,
                    modifier = Modifier.aspectRatio(
                        screenshotRatios[shot.url] ?: screenshotAspectRatio(shot.orientation)
                    ),
                )
            }
        }

        if (!browsing) {
            val shot = screenshots[pagerState.currentPage]
            val ratio = screenshotRatios[shot.url] ?: screenshotAspectRatio(shot.orientation)
            val containerWidth = constraints.maxWidth.toFloat()
            val containerHeight = constraints.maxHeight.toFloat()
            val fitWidth = minOf(containerWidth, containerHeight * ratio)
            val fitRect = Rect(
                Offset((containerWidth - fitWidth) / 2f, (containerHeight - fitWidth / ratio) / 2f),
                Size(fitWidth, fitWidth / ratio),
            )
            val cardRect = cardBounds[shot.url]?.translate(-viewerOrigin)
                ?: offscreenCardRect(screenshots, pagerState.currentPage, cardBounds, containerWidth, viewerOrigin)
                ?: fitRect
            // 圆角量化：squircleClip 每次半径变化都要重建笔刷
            val cornerDp by remember(progress) {
                derivedStateOf { (SCREENSHOT_CORNER_DP * (1f - progress.value)).roundToInt() }
            }
            AppAsyncImage(
                url = shot.expandedUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                placeholderMemoryCacheUrl = shot.url,
                modifier = Modifier
                    .animatedRect { lerp(cardRect, fitRect, progress.value) }
                    .squircleClip(cornerDp.dp),
            )
        }
    }
}

// 目标卡片已滚出视口时的收回终点：按最近已知卡片推算，飞向对应一侧屏幕外
private fun offscreenCardRect(
    screenshots: List<AppScreenshot>,
    targetIndex: Int,
    cardBounds: Map<String, Rect>,
    containerWidth: Float,
    viewerOrigin: Offset,
): Rect? {
    val known = screenshots.withIndex().mapNotNull { (index, shot) ->
        cardBounds[shot.url]?.let { index to it.translate(-viewerOrigin) }
    }
    val nearest = known.minByOrNull { abs(it.first - targetIndex) } ?: return null
    val reference = nearest.second
    val left = if (targetIndex > nearest.first) containerWidth else -reference.width
    return Rect(Offset(left, reference.top), Size(reference.width, reference.height))
}

internal fun screenshotAspectRatio(orientation: ScreenshotOrientation): Float =
    when (orientation) {
        ScreenshotOrientation.PORTRAIT -> PORTRAIT_SCREENSHOT_RATIO
        ScreenshotOrientation.LANDSCAPE -> LANDSCAPE_SCREENSHOT_RATIO
    }

@Composable
private fun CommentContent(comment: AppComment) {
    val body = remember(comment.content) {
        comment.content.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CommentHeader(comment)
        Text(
            text = body,
            color = MiuixTheme.colorScheme.onSurface,
            style = MiuixTheme.textStyles.paragraph.copy(lineHeight = 22.sp),
        )
    }
}

@Composable
private fun CommentHeader(comment: AppComment) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = comment.userName,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.headline1.copy(lineHeight = 22.sp),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (comment.score > 0) {
            Row {
                // 无 key 的 remember 在 item 位置复用时会保留上一条评论的评分
                val filled = remember(comment.score) { comment.score.roundToInt().coerceIn(1, 5) }
                repeat(5) { index ->
                    if (index < filled) {
                        Icon(
                            painter = painterResource(
                                Res.drawable.ic_star_fill
                            ),
                            contentDescription = null,
                            tint = Color(0xFFFFC100),
                            modifier = Modifier.size(16.dp)
                        )
                    } else {
                        Icon(
                            painter = painterResource(
                                Res.drawable.ic_star
                            ),
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.onSurface.copy(0.2f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoLine(
    label: String,
    value: String,
    valueColor: Color = MiuixTheme.colorScheme.onSurface,
    onValueClick: (() -> Unit)? = null,
) {
    if (value.isBlank()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.headline1.copy(lineHeight = 22.sp),
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (onValueClick != null) {
                        Modifier.clickable(
                            onClick = onValueClick,
                            indication = null,
                            interactionSource = null,
                        )
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.TopEnd,
        ) {
            Text(
                text = value,
                modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE),
                color = valueColor,
                style = MiuixTheme.textStyles.body2.copy(lineHeight = 18.sp),
                textAlign = TextAlign.End,
                maxLines = 1,
            )
        }
    }
}

private fun formatRating(score: Double): String =
    ((score * 10).roundToInt() / 10.0).toString()
