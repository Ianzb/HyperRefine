package cn.ianzb.hyperrefine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.ianzb.hyperrefine.ui.component.UpdateDialog
import cn.ianzb.hyperrefine.ui.component.LocalAppTopBarCompact
import cn.ianzb.hyperrefine.ui.component.LocalAppTopBarTitleAlpha
import cn.ianzb.hyperrefine.ui.component.liquid.IosLiquidGlassNavigationBar
import cn.ianzb.hyperrefine.ui.component.search.AppSearchOverlay
import cn.ianzb.hyperrefine.ui.component.search.AppSearchResults
import cn.ianzb.hyperrefine.ui.component.search.AppSearchState
import cn.ianzb.hyperrefine.ui.component.search.LocalAppSearchState
import cn.ianzb.hyperrefine.ui.screen.about.AboutPageContent
import cn.ianzb.hyperrefine.ui.screen.features.FeaturesPageView
import cn.ianzb.hyperrefine.ui.screen.home.HomePageView
import cn.ianzb.hyperrefine.ui.screen.settings.SettingsPageView
import cn.ianzb.hyperrefine.ui.theme.AppTheme
import cn.ianzb.hyperrefine.ui.util.LocalAppPageStartInset
import cn.ianzb.hyperrefine.ui.util.applyWindowBackground
import cn.ianzb.hyperrefine.ui.util.pagerSwipeOverride
import cn.ianzb.hyperrefine.ui.util.LocalPreferenceBackdrop
import cn.ianzb.hyperrefine.ui.util.rememberBlurBackdrop
import cn.ianzb.hyperrefine.ui.util.shouldShowSplitPane
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassNavigationBar
import top.yukonga.miuix.kmp.glass.GlassNavigationBarDefaults
import top.yukonga.miuix.kmp.glass.GlassNavigationItem
import top.yukonga.miuix.kmp.glass.GlassNavigationRail
import top.yukonga.miuix.kmp.glass.GlassNavigationRailDefaults
import top.yukonga.miuix.kmp.glass.rememberGlassNavigationRailState
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.glass.HomeOutline
import top.yukonga.miuix.kmp.icon.glass.Info
import top.yukonga.miuix.kmp.icon.glass.ListView
import top.yukonga.miuix.kmp.icon.glass.Search
import top.yukonga.miuix.kmp.icon.glass.SettingsOutline
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PagerGestureNestedScrollConnection
import top.yukonga.miuix.kmp.utils.springAnimateToPage

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val language = LocaleHelper.getSavedLanguage(newBase)
        super.attachBaseContext(LocaleHelper.wrapContext(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.isNavigationBarContrastEnforced = false

        val savedSettings = AppSettings.load(this)

        // 同步桌面图标可见状态，避免偏好与系统组件状态不一致。
        LauncherIconController.apply(this, savedSettings.hideLauncherIcon)

        // 覆盖 XML 主题的窗口背景，兼容「系统浅色但应用内手动强制深色」的情况，避免启动白屏闪烁。
        applyWindowBackground(savedSettings.themeMode)

        setContent {
            var themeMode by remember {
                mutableStateOf(
                    try { ColorSchemeMode.valueOf(savedSettings.themeMode) }
                    catch (_: Exception) { ColorSchemeMode.System }
                )
            }
            var bottomBarStyle by remember { mutableStateOf(savedSettings.bottomBarStyle) }
            var railStyle by remember { mutableStateOf(savedSettings.railStyle) }
            var isBlurEnabled by remember { mutableStateOf(savedSettings.isBlurEnabled) }
            var checkUpdateOnLaunch by remember { mutableStateOf(savedSettings.checkUpdateOnLaunch) }
            var hideLauncherIcon by remember { mutableStateOf(savedSettings.hideLauncherIcon) }

            var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
            var isCheckingUpdate by remember { mutableStateOf(false) }
            val uriHandler = LocalUriHandler.current
            val scope = rememberCoroutineScope()

            LaunchedEffect(Unit) {
                if (checkUpdateOnLaunch) {
                    UpdateChecker.checkForUpdate(this@MainActivity)
                        .onSuccess { info -> if (info.hasUpdate) updateInfo = info }
                }
            }

            fun checkUpdate() {
                if (isCheckingUpdate) return
                isCheckingUpdate = true
                scope.launch {
                    val result = UpdateChecker.checkForUpdate(this@MainActivity)
                    isCheckingUpdate = false
                    result.fold(
                        onSuccess = { info ->
                            if (info.hasUpdate) {
                                updateInfo = info
                            } else {
                                Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.update_latest),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                        onFailure = {
                            Toast.makeText(
                                this@MainActivity,
                                getString(R.string.update_check_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                    )
                }
            }

            fun persistState() {
                AppSettings.save(
                    this@MainActivity,
                    AppSettings(
                        themeMode = themeMode.name,
                        bottomBarStyle = bottomBarStyle,
                        railStyle = railStyle,
                        isBlurEnabled = isBlurEnabled,
                        checkUpdateOnLaunch = checkUpdateOnLaunch,
                        hideLauncherIcon = hideLauncherIcon,
                        language = LocaleHelper.getSavedLanguage(this@MainActivity).code,
                    )
                )
            }

            AppTheme(themeMode = themeMode) {
                // 与 miuix 示例的 AppContent 一致：全应用只在这里放一个「只负责弹层」的外层
                // Scaffold（无顶栏/底栏、不消费 insets），它同时包住 pager、悬浮底栏与宽屏侧栏，
                // 因此它的 popupHost 在绘制顺序上位于这些导航 chrome 之上。
                // 弹层统一用 renderInRootScaffold = true 注册到这里，就天然「永远盖在底栏/侧栏
                // 上面」，既不会被底栏遮挡，也不需要任何额外的避让垫高。
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0),
                ) {
                    MainScreen(
                        themeMode = themeMode,
                        bottomBarStyle = bottomBarStyle,
                        railStyle = railStyle,
                        isBlurEnabled = isBlurEnabled,
                        checkUpdateOnLaunch = checkUpdateOnLaunch,
                        hideLauncherIcon = hideLauncherIcon,
                        isCheckingUpdate = isCheckingUpdate,
                        onThemeModeChange = { themeMode = it; persistState() },
                        onBottomBarStyleChange = { bottomBarStyle = it; persistState() },
                        onRailStyleChange = { railStyle = it; persistState() },
                        onBlurEnabledChange = { isBlurEnabled = it; persistState() },
                        onCheckUpdateOnLaunchChange = { checkUpdateOnLaunch = it; persistState() },
                        onHideLauncherIconChange = {
                            hideLauncherIcon = it
                            LauncherIconController.apply(this@MainActivity, it)
                            persistState()
                        },
                        onCheckUpdate = { checkUpdate() },
                    ) {
                        updateInfo?.let { info ->
                            UpdateDialog(
                                info = info,
                                onDismissRequest = { updateInfo = null },
                                onConfirm = {
                                    updateInfo = null
                                    runCatching { uriHandler.openUri(info.releaseUrl) }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MainScreen(
    themeMode: ColorSchemeMode,
    bottomBarStyle: BottomBarStyle,
    railStyle: RailStyle,
    isBlurEnabled: Boolean,
    checkUpdateOnLaunch: Boolean,
    hideLauncherIcon: Boolean,
    isCheckingUpdate: Boolean,
    onThemeModeChange: (ColorSchemeMode) -> Unit,
    onBottomBarStyleChange: (BottomBarStyle) -> Unit,
    onRailStyleChange: (RailStyle) -> Unit,
    onBlurEnabledChange: (Boolean) -> Unit,
    onCheckUpdateOnLaunchChange: (Boolean) -> Unit,
    onHideLauncherIconChange: (Boolean) -> Unit,
    onCheckUpdate: () -> Unit,
    overlay: @Composable () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pagerState = rememberPagerState(pageCount = { 4 })
    var selectedIndex by remember { mutableIntStateOf(0) }
    var isNavigating by remember { mutableStateOf(false) }
    var navJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var homeRefreshKey by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val items = listOf(
        stringResource(R.string.tab_home),
        stringResource(R.string.tab_features),
        stringResource(R.string.tab_settings),
        stringResource(R.string.tab_about)
    )
    val icons = listOf(
        MiuixIcons.Glass.HomeOutline,
        MiuixIcons.Glass.ListView,
        MiuixIcons.Glass.SettingsOutline,
        MiuixIcons.Glass.Info
    )

    LaunchedEffect(pagerState.currentPage) {
        if (!isNavigating && selectedIndex != pagerState.currentPage) {
            selectedIndex = pagerState.currentPage
            homeRefreshKey++
        }
    }

    val surfaceColor = MiuixTheme.colorScheme.surface
    // 全局搜索：状态挂在导航容器上，底栏搜索按钮与各主页面顶栏搜索按钮共用同一份状态。
    val searchState = remember { AppSearchState() }
    var pendingFeatureSearchKey by remember { mutableStateOf<String?>(null) }
    val backdrop = if (isBlurEnabled) rememberBlurBackdrop() else null

    val isWideScreen = shouldShowSplitPane()
    // 宽屏且用户未关闭侧边导航栏时才走官方 GlassNavigationRail；
    // 关闭侧栏（或窄屏）时退回底部悬浮栏，保持完整的四页导航。
    val useRail = isWideScreen && railStyle.isEnabled
    // 侧栏形态由设置决定：「左面」进入即展开成左侧面板，「上面」进入停在顶部折叠胶囊。
    val expandSidebar = railStyle == RailStyle.Left
    val sidebarState = rememberGlassNavigationRailState(initialExpanded = expandSidebar)
    LaunchedEffect(expandSidebar) {
        sidebarState.expanded = expandSidebar
    }
    // 内容让位的宽度：与 demo 的 overlayStart 完全一致 —— 侧栏宽度乘以形变进度，
    // 形变过程中逐帧跟随，因此内容与面板始终严丝合缝，不会出现面板先展开、内容后让位的遮挡。
    // progress 每帧变化，这里在组合期读取，形变期间会逐帧触发本函数重组以重算 padding。
    // 侧栏被关掉（或窄屏改用底部悬浮栏）时必须归零：GlassNavigationRail 不再参与组合，
    // 它的 state 会停留在原来的展开进度上，若继续沿用就会让页面整体右移出一条空白。
    val sidebarInset: Dp =
        if (useRail) {
            (GlassNavigationRailDefaults.Margin + GlassNavigationRailDefaults.ExpandedWidth) *
                sidebarState.progress
        } else {
            0.dp
        }
    val railItems = items.mapIndexed { index, label ->
        GlassNavigationItem(icon = icons[index], label = label)
    }

    val onItemSelected: (Int) -> Unit = select@{ index ->
        if (index == selectedIndex) return@select
        homeRefreshKey++
        navJob?.cancel()
        selectedIndex = index
        isNavigating = true
        navJob = scope.launch {
            val myJob = coroutineContext.job
            try {
                pagerState.springAnimateToPage(index)
            } finally {
                if (navJob == myJob) {
                    isNavigating = false
                    if (pagerState.currentPage != index) {
                        selectedIndex = pagerState.currentPage
                    }
                }
            }
        }
    }

    // 宽屏侧栏是覆盖层：留白不能加在 Pager 上（那会让页面整体被裁窄，侧栏背后只剩平色，
    // 玻璃只采样到「最右边一小条内容」）。改为通过 LocalAppPageStartInset 下发给每个页面，
    // 由页面自身的内容列/顶栏让位；页面背景与渐变仍铺满全宽，侧栏背后因此始终是连续的全宽
    // 内容，滑动时经过的也是相邻页的空白带而不是它的卡片（对齐 demo 的 overlayStart 语义）。
    val pagerContent: @Composable (Dp) -> Unit = { navBarHeight ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier)
                .background(surfaceColor)
        ) {
            CompositionLocalProvider(
                // 顶栏采样的是「页面内容」图层，必须由各页面自己提供（见 rememberTopBarBackdrop），
                // 这里传应用级 backdrop 会让顶栏所在的图层包含顶栏自身，触发 RenderNode 无限递归。
                // 侧栏占位留给页面自己消费；Pager 保持全宽，玻璃才能采样到连续背景。
                LocalAppPageStartInset provides sidebarInset,
            ) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        // Cross-Axis 拦截 + 子组件（滑条）手势放行：见 PagerSwipeOverride.kt
                        .pagerSwipeOverride(pagerState = pagerState),
                    userScrollEnabled = false,
                    pageNestedScrollConnection = PagerGestureNestedScrollConnection,
                ) { page ->
                    when (page) {
                        0 -> HomePageView(
                            isBlurEnabled = isBlurEnabled,
                            refreshKey = homeRefreshKey,
                            extraBottomPadding = navBarHeight,
                            onOpenFeatures = { onItemSelected(1) },
                        )
                        1 -> FeaturesPageView(
                            isBlurEnabled = isBlurEnabled,
                            extraBottomPadding = navBarHeight,
                            pendingSearchKey = pendingFeatureSearchKey,
                            onSearchKeyHandled = { pendingFeatureSearchKey = null },
                        )
                        2 -> SettingsPageView(
                            currentMode = themeMode,
                            onModeChange = onThemeModeChange,
                            bottomBarStyle = bottomBarStyle,
                            onBottomBarStyleChange = onBottomBarStyleChange,
                            railStyle = railStyle,
                            onRailStyleChange = onRailStyleChange,
                            isBlurEnabled = isBlurEnabled,
                            onBlurEnabledChange = onBlurEnabledChange,
                            checkUpdateOnLaunch = checkUpdateOnLaunch,
                            onCheckUpdateOnLaunchChange = onCheckUpdateOnLaunchChange,
                            hideLauncherIcon = hideLauncherIcon,
                            onHideLauncherIconChange = onHideLauncherIconChange,
                            onCheckUpdate = onCheckUpdate,
                            isCheckingUpdate = isCheckingUpdate,
                            extraBottomPadding = navBarHeight,
                        )
                        3 -> AboutPageContent(
                            openLicensePage = {
                                context.startActivity(Intent(context, LicenseActivity::class.java))
                            },
                            isBlurEnabled = isBlurEnabled,
                            extraBottomPadding = navBarHeight,
                        )
                    }
                }
            }
        }
    }

    CompositionLocalProvider(LocalAppSearchState provides searchState) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (useRail) {
                // 宽屏且侧栏开启：按 miuix example 的 PersistentGlassNavigationRail 做法，把官方
                // GlassNavigationRail 作为全屏 overlay 叠在内容层之上——它自身 fillMaxSize()，
                // 并负责折叠胶囊（顶部水平居中）⇄ 展开面板（贴左 GlassNavigationRailDefaults.Margin）
                // 的形变与系统栏 insets。
                val navigationBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                Box(modifier = Modifier.fillMaxSize()) {
                    CompositionLocalProvider(
                        // 折叠胶囊占据顶部中央，页面顶栏标题随之淡出、展开时再浮现（demo 的 chromeTitleAlpha）。
                        LocalAppTopBarTitleAlpha provides sidebarState.progress,
                        // 宽屏不用大标题（对应 demo 的 expandable = !isWideScreen），否则大标题会顶到胶囊上。
                        LocalAppTopBarCompact provides true,
                    ) {
                        pagerContent(navigationBarBottom)
                    }
                    GlassNavigationRail(
                        items = railItems,
                        selectedIndex = selectedIndex,
                        onSelect = onItemSelected,
                        // 直接复用包裹页面内容的 backdrop 图层：侧栏才能真正模糊到底下的页面内容。
                        backdrop = backdrop,
                        state = sidebarState,
                    )
                }
            } else {
                Scaffold(
                    bottomBar = {
                        BottomNavigationBar(
                            style = bottomBarStyle,
                            items = items,
                            icons = icons,
                            selectedIndex = selectedIndex,
                            backdrop = backdrop,
                            onItemSelected = onItemSelected,
                            // 底栏搜索入口只在功能页出现（功能项都在那一页，其它页面用顶栏入口）。
                            onSearchClick = if (selectedIndex == 1) searchState::open else null,
                        )
                    }
                ) { globalPadding ->
                    pagerContent(globalPadding.calculateBottomPadding())
                }
            }

            // 搜索界面放在导航容器最后：绘制顺序因此在悬浮底栏 / 宽屏侧栏之上
            // （与 miuix 示例 GlassPage 的搜索层级一致），遮住底栏并独占输入。
            AppSearchOverlay(
                expanded = searchState.expanded,
                onDismissRequest = { searchState.close() },
                label = stringResource(R.string.search_hint),
            ) { query ->
                AppSearchResults(
                    query = query,
                    onPick = { key ->
                        searchState.close()
                        pendingFeatureSearchKey = key
                        onItemSelected(1)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            CompositionLocalProvider(LocalPreferenceBackdrop provides backdrop) {
                overlay()
            }
        }
    }
}

/** 玻璃底栏收敛后的胶囊宽度（miuix 示例 4 个 tab 的实测值，本项目同为 4 个 tab）。 */
private val NavigationBarCapsuleMaxWidth = 344.dp

/** 胶囊可用宽度超过该值就收敛，否则铺满（示例阈值）。 */
private val NavigationBarCapsuleMaxAvailable = 400.dp

/** 每个 tab 在示例里额外计入可用宽度的间距。 */
private val NavigationBarItemSpacing = 10.dp

/** 示例里从 tab 间距中扣掉的固定量。 */
private val NavigationBarSpacingPadding = 6.dp

/**
 * 底部导航栏。
 *
 * @param onSearchClick 非空时在玻璃底栏右侧渲染独立的搜索按钮（全局搜索入口之一；另一个入口是
 *   各主页面的顶栏搜索按钮），点击唤起搜索界面。
 */
@Composable
private fun BottomNavigationBar(
    style: BottomBarStyle,
    items: List<String>,
    icons: List<ImageVector>,
    selectedIndex: Int,
    backdrop: LayerBackdrop?,
    onItemSelected: (Int) -> Unit,
    onSearchClick: (() -> Unit)? = null,
) {
    when (style) {
        // HyperOS 玻璃底栏：官方 miuix-glass 组件（与 miuix example 的 AppContent 一致）。
        // 高度、图标/文字尺寸、指示器内缩、边缘高光、模糊与折射全部由 GlassNavigationBar 负责，
        // 这里只保留 demo 的左右 24.dp 边距、宽度收敛逻辑、搜索按钮与「吞掉空白点按」的手势壳。
        BottomBarStyle.HyperOS -> {
            val glassItems = items.mapIndexed { index, label ->
                GlassNavigationItem(icon = icons[index], label = label)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 吞掉落在底栏空白处的点按（含两侧留白），避免穿透到底层 Pager。
                    .pointerInput(Unit) {
                        detectTapGestures { }
                    },
                contentAlignment = Alignment.Center,
            ) {
                GlassNavigationBar(
                    items = glassItems,
                    selectedIndex = selectedIndex,
                    onSelect = onItemSelected,
                    backdrop = backdrop,
                    // 搜索按钮是胶囊之外独立的一颗玻璃圆钮（与 demo 的 trailingAction 一致）。
                    trailingAction = onSearchClick?.let { openSearch ->
                        {
                            GlassIconButton(
                                onClick = openSearch,
                                backdrop = backdrop,
                                size = GlassNavigationBarDefaults.Height,
                                material = GlassNavigationBarDefaults.material(),
                            ) {
                                Icon(
                                    imageVector = MiuixIcons.Glass.Search,
                                    contentDescription = stringResource(R.string.search_hint),
                                    modifier = Modifier.size(28.dp),
                                    tint = MiuixTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .glassNavigationBarWidth(items.size),
                )
            }
        }

        // iOS 液态玻璃底栏：移植自 miuix example 的 component/liquid（见 ui/component/liquid/）。
        BottomBarStyle.IOS -> {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                IosLiquidGlassNavigationBar(
                    items = items,
                    icons = icons,
                    selectedIndex = selectedIndex,
                    onItemClick = onItemSelected,
                    backdrop = backdrop,
                    isBlurActive = backdrop != null,
                    modifier = Modifier
                        .padding(horizontal = 24.dp)
                        .glassNavigationBarWidth(items.size, trailingActionWidth = 0.dp),
                )
            }
        }
    }
}

/**
 * 玻璃底栏宽度（复刻 miuix 示例 `GlassPage` 的底栏写法）。
 *
 * 手机上底栏本就该铺满（左右各 24.dp 留白）；但宽度变大后继续拉伸会让胶囊被拉得很长、
 * tab 间距变得松散。示例的做法是：按「胶囊 + 右侧搜索按钮」估算胶囊可用宽度，
 * 超过 400.dp 时把底栏收成 344.dp 胶囊加预留的动作按钮宽度，由父级 `Box` 居中，
 * 否则保持铺满。液态玻璃底栏没有独立动作按钮，因此无需预留宽度。
 */
private fun Modifier.glassNavigationBarWidth(
    itemCount: Int,
    trailingActionWidth: Dp = GlassNavigationBarDefaults.Height + GlassNavigationBarDefaults.ActionSpacing,
): Modifier = layout { measurable, constraints ->
    val actionWidth = trailingActionWidth.roundToPx()
    // 示例里 4 个 tab 时胶囊宽 344.dp、每个 tab 的额外间距 10.dp - 6.dp 归入可用宽度判断，
    // 阈值 400.dp；这里保持同一组数值，仅把 tab 数换成参数。
    val extraSpacing = (NavigationBarItemSpacing * (itemCount - 1) - NavigationBarSpacingPadding).roundToPx()
    val available = constraints.maxWidth - actionWidth + extraSpacing
    val width = if (constraints.hasBoundedWidth && available > NavigationBarCapsuleMaxAvailable.roundToPx()) {
        (NavigationBarCapsuleMaxWidth.roundToPx() + actionWidth).coerceIn(constraints.minWidth, constraints.maxWidth)
    } else {
        constraints.maxWidth
    }
    val placeable = measurable.measure(constraints.copy(minWidth = width, maxWidth = width))
    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
}
