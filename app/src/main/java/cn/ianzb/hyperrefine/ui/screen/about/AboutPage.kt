package cn.ianzb.hyperrefine.ui.screen.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.computedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.ui.component.AppPageScaffold
import cn.ianzb.hyperrefine.ui.component.effect.BgEffectBackground
import cn.ianzb.hyperrefine.ui.component.isGlassTopBarAvailable
import cn.ianzb.hyperrefine.ui.util.ColorBlendToken
import cn.ianzb.hyperrefine.ui.util.isInDarkTheme
import cn.ianzb.hyperrefine.ui.component.appPageListPadding
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers
import cn.ianzb.hyperrefine.ui.util.rememberBlurBackdrop
import kotlin.math.abs
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurDefaults
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import androidx.compose.ui.graphics.BlendMode as ComposeBlendMode
import top.yukonga.miuix.kmp.basic.Text as MiuixText

@Composable
fun AboutPageContent(
    openLicensePage: () -> Unit,
    isBlurEnabled: Boolean = true,
    extraBottomPadding: Dp = 0.dp,
) {
    val topAppBarScrollBehavior = MiuixScrollBehavior()
    val lazyListState = rememberLazyListState()

    val scrollProgress by remember {
        computedStateOf {
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

    val collapsed by remember { computedStateOf { scrollProgress == 1f } }
    // 玻璃顶栏不使用 color 参数（带区固定走 bandBrush），只有回退到 BlurredBar 时才需要
    // 「滚到只剩卡片后给实色底」。因此玻璃路径下不要读 collapsed——否则会在大标题消失的
    // 临界点触发整页一次重组，滚动到那里会明显顿一下。
    val fallbackBarColor = !isGlassTopBarAvailable()

    AppPageScaffold(
        title = stringResource(R.string.about),
        isBlurEnabled = isBlurEnabled,
        scrollBehavior = topAppBarScrollBehavior,
        titleAlpha = { ((scrollProgress - 0.35f) / 0.65f).coerceIn(0f, 1f) },
        expandable = false,
        color = when {
            !fallbackBarColor -> Color.Transparent
            collapsed -> null
            else -> Color.Transparent
        },
    ) { innerPadding ->
        AboutContent(
            scrollPadding = appPageListPadding(innerPadding, extraBottomPadding),
            topAppBarScrollBehavior = topAppBarScrollBehavior,
            lazyListState = lazyListState,
            isBlurEnabled = isBlurEnabled,
            scrollProgressProvider = { scrollProgress },
            openLicensePage = openLicensePage,
        )
    }
}

@Composable
private fun AboutContent(
    scrollPadding: PaddingValues,
    isBlurEnabled: Boolean,
    topAppBarScrollBehavior: ScrollBehavior,
    lazyListState: LazyListState,
    scrollProgressProvider: () -> Float,
    openLicensePage: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val contentBackdrop = if (isBlurEnabled) rememberBlurBackdrop() else null
    var blurRadius by remember { mutableFloatStateOf(60f) }
    var noiseCoefficient by remember { mutableFloatStateOf(BlurDefaults.NoiseCoefficient) }
    var brightness by remember { mutableFloatStateOf(0f) }
    var contrast by remember { mutableFloatStateOf(1f) }
    var saturation by remember { mutableFloatStateOf(1f) }

    val logoPadding = remember(scrollPadding) {
        object : PaddingValues by scrollPadding {
            override fun calculateTopPadding() = scrollPadding.calculateTopPadding() + 40.dp
            override fun calculateBottomPadding() = 0.dp
        }
    }

    val isInDark = isInDarkTheme()

    val cardBlend = if (isInDark) ColorBlendToken.Overlay_Thin_Light else ColorBlendToken.Pured_Regular_Light
    val logoBlend = remember(isInDark) {
        if (isInDark) {
            listOf(
                BlendColorEntry(Color(0xe6a1a1a1), BlurBlendMode.ColorDodge),
                BlendColorEntry(Color(0x4de6e6e6), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af500), BlurBlendMode.Lab),
            )
        } else {
            listOf(
                BlendColorEntry(Color(0xcc4a4a4a), BlurBlendMode.ColorBurn),
                BlendColorEntry(Color(0xff4f4f4f), BlurBlendMode.LinearLight),
                BlendColorEntry(Color(0xff1af200), BlurBlendMode.Lab),
            )
        }
    }

    val density = LocalDensity.current
    var logoHeightDp by remember { mutableStateOf(300.dp) }
    // 列表视口高度：用来把「只剩卡片」的终点固定在大标题刚好消失的位置（见下方 item 注释）。
    var viewportHeightDp by remember { mutableStateOf(0.dp) }
    val appName = stringResource(R.string.app_name)
    val ctx = LocalContext.current
    // 直接读取应用启动图标，保证与桌面图标始终一致。
    val appIcon = remember(ctx, density) {
        val iconSizePx = with(density) { 100.dp.roundToPx() }
        ctx.packageManager.getApplicationIcon(ctx.applicationInfo)
            .toBitmap(iconSizePx, iconSizePx)
            .asImageBitmap()
    }
    val versionName = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
    } catch (_: Exception) { "1.0" }

    BgEffectBackground(
        dynamicBackground = true,
        isFullSize = true,
        modifier = Modifier.fillMaxSize(),
        bgModifier = if (contentBackdrop != null) Modifier.layerBackdrop(contentBackdrop) else Modifier,
        alpha = { 1f - scrollProgressProvider() },
    ) {
        // Logo area — floating overlay
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(logoPadding)
                .padding(top = 52.dp)
                .onSizeChanged { size ->
                    with(density) { logoHeightDp = size.height.toDp() }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(100.dp)
                    .graphicsLayer {
                        val iconProgress = ((scrollProgressProvider() - 0.35f) / 0.15f).coerceIn(0f, 1f)
                        clip = true
                        alpha = 1 - iconProgress
                        scaleX = 1 - (iconProgress * 0.05f)
                        scaleY = 1 - (iconProgress * 0.05f)
                    }
                    .squircleClip(cornerRadius = 28.dp),
            ) {
                Image(
                    modifier = Modifier.size(100.dp),
                    bitmap = appIcon,
                    contentDescription = null,
                )
            }
            MiuixText(
                modifier = Modifier
                    .padding(top = 12.dp, bottom = 5.dp)
                    .graphicsLayer {
                        val projectNameProgress = ((scrollProgressProvider() - 0.20f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - projectNameProgress
                        scaleX = 1 - (projectNameProgress * 0.05f)
                        scaleY = 1 - (projectNameProgress * 0.05f)
                    }
                    .then(
                        if (contentBackdrop != null) {
                            Modifier.textureBlur(
                                backdrop = contentBackdrop,
                                shape = RoundedCornerShape(16.dp),
                                blurRadius = 150f,
                                noiseCoefficient = noiseCoefficient,
                                colors = BlurDefaults.blurColors(
                                    blendColors = logoBlend,
                                ),
                                contentBlendMode = ComposeBlendMode.DstIn,
                            )
                        } else {
                            Modifier
                        },
                    ),
                text = appName,
                color = colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 35.sp,
            )
            MiuixText(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val versionCodeProgress = ((scrollProgressProvider() - 0.05f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - versionCodeProgress
                        scaleX = 1 - (versionCodeProgress * 0.05f)
                        scaleY = 1 - (versionCodeProgress * 0.05f)
                    },
                color = colorScheme.onSurfaceVariantSummary,
                text = versionName,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
            )
            // Based on 标注：基于本脚手架的衍生项目须保留此文本（版本号随脚手架同步更新）。
            MiuixText(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        val versionCodeProgress = ((scrollProgressProvider() - 0.05f) / 0.15f).coerceIn(0f, 1f)
                        alpha = 1 - versionCodeProgress
                        scaleX = 1 - (versionCodeProgress * 0.05f)
                        scaleY = 1 - (versionCodeProgress * 0.05f)
                    },
                color = colorScheme.onSurfaceVariantSummary,
                text = stringResource(R.string.about_based_on),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
            )
        }

        // 滚动停下时在两个固定点之间吸附：大标题完整 ⇄ 只剩卡片。
        // 以前不吸附，手势会停在任意中间位置，且列表终点比「大标题消失」多滚一个顶栏内边距，
        // 表现为卡片被推到顶栏下面后突然停住；吸附后终点固定、过渡由弹簧动画完成。
        LaunchedEffect(lazyListState) {
            snapshotFlow {
                Triple(
                    lazyListState.isScrollInProgress,
                    lazyListState.firstVisibleItemIndex,
                    lazyListState.firstVisibleItemScrollOffset,
                )
            }.collect { (scrolling, firstIndex, offset) ->
                if (scrolling || firstIndex != 0) return@collect
                val spacerSize = lazyListState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == "logoSpacer" }?.size?.toFloat() ?: return@collect
                if (spacerSize <= 0f) return@collect
                // 过半就吸附到「只剩卡片」的固定点，否则退回大标题。
                val target = if (offset >= spacerSize / 2f) spacerSize else 0f
                if (abs(offset - target) < 1f) return@collect
                lazyListState.animateScrollBy(target - offset)
            }
        }

        // Scrollable content
        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size -> with(density) { viewportHeightDp = size.height.toDp() } }
                .pageScrollModifiers(
                    showTopAppBar = true,
                    topAppBarScrollBehavior = topAppBarScrollBehavior,
                ),
            contentPadding = remember(scrollPadding) {
                object : PaddingValues by scrollPadding {
                    override fun calculateBottomPadding() = 0.dp
                }
            },
        ) {
            item(key = "logoSpacer") {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(
                            logoHeightDp + 52.dp + logoPadding.calculateTopPadding() - scrollPadding.calculateTopPadding() + 126.dp,
                        ),
                )
            }

            item(key = "about") {
                // 最小高度只补到「卡片正好从顶栏下沿开始」：列表可滚动距离 = logoSpacer 高度，
                // 滚到底时大标题刚好完全消失、首张卡片停在固定位置。
                // 这里不能用 fillParentMaxHeight()：它按整个视口取高，会让终点比锚点再多滚一个
                // 顶部内边距，把首张卡片推到顶栏/状态栏下面，并在此处硬停（m02678 之后的新问题）。
                Column(
                    modifier = Modifier
                        .heightIn(
                            min = (viewportHeightDp - scrollPadding.calculateTopPadding())
                                .coerceAtLeast(0.dp),
                        )
                        .padding(bottom = scrollPadding.calculateBottomPadding()),
                ) {
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .then(
                                if (contentBackdrop != null) {
                                    Modifier.textureBlur(
                                        backdrop = contentBackdrop,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = blurRadius,
                                        noiseCoefficient = noiseCoefficient,
                                        colors = BlurDefaults.blurColors(
                                            blendColors = cardBlend,
                                            brightness = brightness,
                                            contrast = contrast,
                                            saturation = saturation,
                                        ),
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                        colors = CardDefaults.defaultColors(
                            if (contentBackdrop != null) Color.Transparent else colorScheme.surfaceContainer,
                            Color.Transparent,
                        ),
                    ) {
                        val sourceCodeUrl = stringResource(R.string.about_source_code_summary)
                        val feedbackUrl = stringResource(R.string.about_telegram_summary)
                        ArrowPreference(
                            title = stringResource(R.string.about_source_code),
                            summary = sourceCodeUrl,
                            onClick = { uriHandler.openUri(sourceCodeUrl) },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.about_telegram),
                            summary = feedbackUrl,
                            onClick = { uriHandler.openUri(feedbackUrl) },
                        )
                    }
                    Card(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .padding(top = 12.dp)
                            .then(
                                if (contentBackdrop != null) {
                                    Modifier.textureBlur(
                                        backdrop = contentBackdrop,
                                        shape = RoundedCornerShape(16.dp),
                                        blurRadius = blurRadius,
                                        noiseCoefficient = noiseCoefficient,
                                        colors = BlurDefaults.blurColors(
                                            blendColors = cardBlend,
                                            brightness = brightness,
                                            contrast = contrast,
                                            saturation = saturation,
                                        ),
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                        colors = CardDefaults.defaultColors(
                            if (contentBackdrop != null) Color.Transparent else colorScheme.surfaceContainer,
                            Color.Transparent,
                        ),
                    ) {
                        ArrowPreference(
                            title = stringResource(R.string.license_agpl),
                            summary = stringResource(R.string.license_agpl_summary),
                            onClick = { uriHandler.openUri("https://www.gnu.org/licenses/agpl-3.0.txt") },
                        )
                        ArrowPreference(
                            title = stringResource(R.string.about_dependencies),
                            onClick = openLicensePage,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}
