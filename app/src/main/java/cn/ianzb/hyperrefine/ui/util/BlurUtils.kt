package cn.ianzb.hyperrefine.ui.util

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ScrollBehavior
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.material.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 二级页面脚手架提供的滚动行为，供内容页 LazyColumn 绑定顶栏收起。 */
val LocalSubPageScrollBehavior: ProvidableCompositionLocal<ScrollBehavior?> =
    staticCompositionLocalOf { null }

/**
 * 二级页列表是否存在真实滚动余量。
 *
 * miuix 的 [MiuixScrollBehavior] 提供 `canScroll` 钩子（默认恒为 `true`）。内容不足一屏时，
 * 上滑手势会先被顶栏的 nested scroll 连接整段吃掉（`onPreScroll` 折叠大标题），
 * 列表拿不到任何位移：既没有弹性回弹，也没有玻璃带区/模糊，且大标题会停在折叠态。
 *
 * 由 [pageScrollModifiers] 把列表的 `canScrollForward/Backward` 写进来，
 * 二级页脚手架据此给滚动行为传入 `canScroll`：短页面不再折叠顶栏，手势直接交给
 * `overScrollVertical` 做弹性回弹，回弹位移通过 `TopAppBarState.overscrollOffset`
 * 驱动玻璃顶栏的带区与模糊；同时按回弹位移同步顶栏折叠量，避免大标题与上移的内容重叠。
 */
class SubPageScrollGate {
    /** 列表内容是否真的能滚动；由内容页的 [LazyListState] 持续同步。 */
    internal var contentScrollable: Boolean = true
}

/** 当前二级页的滚动余量闸门；仅由 [cn.ianzb.hyperrefine.ui.component.SubPageScaffold] 提供。 */
val LocalSubPageScrollGate: ProvidableCompositionLocal<SubPageScrollGate?> =
    staticCompositionLocalOf { null }

/**
 * 供 miuix 玻璃弹层（`GlassDropdownPreference`、`GlassDialog` 等）采样用的 [LayerBackdrop]。
 *
 * 弹层默认渲染在最外层 `Scaffold` 里，采样源必须是页面自建的顶栏 backdrop
 * （不含弹层自身），否则玻璃会采样到自己。页面里若没有可用的
 * backdrop（模糊关闭），保持 null 即可，玻璃弹层会自动退化为实色表面。
 */
val LocalPreferenceBackdrop: ProvidableCompositionLocal<LayerBackdrop?> = staticCompositionLocalOf { null }

/**
 * 宽屏侧边导航栏占用的内容起始留白（DP），默认 0。
 *
 * 与 demo（`rememberAppPageInsets` 的 `overlayStart`）一致：侧栏是覆盖层，
 * 页面内容自己通过该值让位。注意留白只作用于「内容」，页面背景/渐变仍铺满全宽，
 * 这样侧栏玻璃采样到的是连续的全宽内容，而不是被裁窄后的平色区域（m02678）。
 */
val LocalAppPageStartInset: ProvidableCompositionLocal<Dp> = staticCompositionLocalOf { 0.dp }

/**
 * 把 [LocalAppPageStartInset] 应用到内容容器的起始边。
 *
 * 只给滚动容器/内容列使用，不要加到页面根布局或背景图层上。
 */
@Composable
fun Modifier.pageStartInset(): Modifier {
    val inset = LocalAppPageStartInset.current
    return if (inset == 0.dp) this else this.padding(start = inset)
}

/**
 * 创建模糊所需的 [LayerBackdrop]；设备不支持运行时着色器时返回 null（不启用模糊）。
 *
 * 返回的 backdrop 记录 `surface` 底色 + 被采样内容，可作为 `Modifier.layerBackdrop(...)`
 * 的源，或直接交给 `GlassNavigationBar` / `GlassTopAppBar` 采样。
 */
@Composable
fun rememberBlurBackdrop(): LayerBackdrop? {
    if (!isRuntimeShaderSupported() || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    val surfaceColor = MiuixTheme.colorScheme.surface
    return rememberLayerBackdrop {
        drawRect(surfaceColor)
        drawContent()
    }
}

/**
 * 顶栏玻璃采样专用的 [LayerBackdrop]。
 *
 * 使用时必须把它作为 `Modifier.layerBackdrop(...)` 施加在**页面内容**的容器上，
 * 且该容器不能包含顶栏本身——否则顶栏会采样到自己的图层，触发 RenderNode 递归。
 */
@Composable
fun rememberTopBarBackdrop(blurActive: Boolean): LayerBackdrop? =
    if (blurActive) rememberBlurBackdrop() else null

/**
 * 页面根容器：把 [backdrop] 录入采样图层，并通过 [LocalPreferenceBackdrop] 下发给页面内的玻璃组件
 * （玻璃下拉气泡、[cn.ianzb.hyperrefine.ui.component.AppDialog] 等）。
 *
 * 两件事必须成对发生：只 [layerBackdrop] 而不下发，弹层取到的 backdrop 为 null，
 * 会退化成不透明材质（看起来就是旧版对话框）；只下发而不 [layerBackdrop]，弹层采样到的是空图层。
 *
 * @param backdrop 页面顶栏使用的模糊图层，null 表示当前未启用背景模糊
 * @param modifier 作用于内容容器的 Modifier
 * @param content 页面内容
 */
@Composable
fun PageBackdrop(
    backdrop: LayerBackdrop?,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    CompositionLocalProvider(LocalPreferenceBackdrop provides backdrop) {
        Box(
            modifier = modifier.then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier),
            content = content,
        )
    }
}


@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean {
    val surface = MiuixTheme.colorScheme.surface
    val luminance = 0.2126f * surface.red + 0.7152f * surface.green + 0.0722f * surface.blue
    return luminance < 0.5f
}

/**
 * 页面滚动容器（LazyColumn）的通用滚动配置。
 *
 * 与 miuix 官方 demo（`utils/PageUtils.kt` 的 `pageScrollModifiers`）保持同一条代码路径：
 * - `overScrollVertical(onOffsetChanged = ...)` 把弹性回弹位移写进
 *   [top.yukonga.miuix.kmp.basic.TopAppBarState.overscrollOffset]，
 *   玻璃顶栏的带区、模糊、按钮材质都由 `visualContentOffset = contentOffset + overscrollOffset`
 *   驱动，因此「内容不足一屏也能上滑回弹，并在回弹过程中出模糊」。
 * - `nestedScroll(topAppBarScrollBehavior.nestedScrollConnection)` 负责大标题折叠。
 *
 * @param contentState 页面列表的滚动状态。传入后会把「是否真的有滚动余量」同步给
 *   [LocalSubPageScrollGate]，短页面因此不会被顶栏吃掉上滑手势（见 [SubPageScrollGate]）。
 */
@Composable
fun Modifier.pageScrollModifiers(
    showTopAppBar: Boolean,
    topAppBarScrollBehavior: ScrollBehavior,
    contentState: LazyListState? = null,
): Modifier {
    val scrollGate = LocalSubPageScrollGate.current
    if (scrollGate != null && contentState != null) {
        LaunchedEffect(scrollGate, contentState) {
            snapshotFlow {
                contentState.canScrollBackward || contentState.canScrollForward
            }.collect { scrollable -> scrollGate.contentScrollable = scrollable }
        }
    }

    // 弹性回弹位移交给顶栏：带区透明度 / 模糊 / 胶囊材质都由 overscrollOffset 驱动。
    val state = topAppBarScrollBehavior.state
    val onOffsetChanged: (Float) -> Unit = remember(state, scrollGate) {
        { offset ->
            state.overscrollOffset = offset
            // 内容不足一屏时顶栏不接管手势（canScroll = false），不会自己折叠；
            // 这里按同一段回弹位移同步折叠量：大标题随内容上移淡出、松手后随回弹复位，
            // 不会出现「大标题压在已经上移的卡片上」。长页面不会走到这里（回弹时已折叠到位）。
            if (scrollGate != null && !scrollGate.contentScrollable) {
                state.heightOffset = offset
            }
        }
    }

    return this
        .scrollEndHaptic()
        .overScrollVertical(onOffsetChanged = onOffsetChanged)
        .then(if (showTopAppBar) Modifier.nestedScroll(topAppBarScrollBehavior.nestedScrollConnection) else Modifier)
}

@Composable
fun pageContentPadding(
    innerPadding: PaddingValues,
    outerPadding: PaddingValues,
    isWideScreen: Boolean,
    extraTop: Dp = 0.dp,
    extraStart: Dp = 0.dp,
    extraEnd: Dp = 0.dp,
    extraBottom: Dp = 0.dp,
): PaddingValues {
    val startInset = LocalAppPageStartInset.current
    val topPadding = innerPadding.calculateTopPadding() + extraTop
    val bottomPadding = if (isWideScreen) {
        outerPadding.calculateBottomPadding() + extraBottom +
            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
    } else {
        outerPadding.calculateBottomPadding() + extraBottom
    }
    return remember(topPadding, bottomPadding, extraStart, extraEnd, startInset) {
        PaddingValues(
            top = topPadding,
            start = extraStart + startInset,
            end = extraEnd,
            bottom = bottomPadding,
        )
    }
}

@Composable
fun shouldShowSplitPane(): Boolean {
    val windowInfo = LocalWindowInfo.current
    val density = LocalDensity.current
    return with(density) {
        val widthDp = windowInfo.containerSize.width.toDp()
        val heightDp = windowInfo.containerSize.height.toDp()
        val ratio = heightDp / widthDp
        widthDp >= 840.dp || (widthDp >= 600.dp && ratio < 1.2f)
    }
}

object ColorBlendToken {
    val Pured_Regular_Light = listOf(
        BlendColorEntry(Color(0x340034F9), BlurBlendMode.Overlay),
        BlendColorEntry(Color(0xB3FFFFFF), BlurBlendMode.HardLight),
    )
    val Overlay_Thin_Light = listOf(
        BlendColorEntry(Color(0x4DA9A9A9), BlurBlendMode.Luminosity),
        BlendColorEntry(Color(0x1A9C9C9C), BlurBlendMode.PlusDarker),
    )
}
