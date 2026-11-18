package cn.ianzb.hyperrefine.ui.component.pref

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.ui.component.AppSectionCard
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.prefs.OptionRegistry
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.prefs.OptionType
import cn.ianzb.hyperrefine.ui.component.AppLazyPage
import cn.ianzb.hyperrefine.ui.component.AppTopAppBarIconAction
import cn.ianzb.hyperrefine.ui.component.search.AppSearchIndex
import cn.ianzb.hyperrefine.ui.util.LocalPreferenceBackdrop
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.glass.GlassTopAppBarDefaults
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 一个组件分区。
 *
 * @param titleRes 分区标题（中文描述）字符串资源；传 `0` 表示该分区不显示小标题
 *   （用于「一级就是一张扁平卡片列表」的页面，避免再套一层无意义的小标题）。
 * @param specs 分区内包含的配置项，按顺序渲染。
 * @param titleEn 英文组件名，非空时以「中文（English）」拼接。**仅用于模板示例展示 API 英文名**；
 *   实际功能页应省略该参数，保持单语言小标题。
 */
data class HookSection(
    val titleRes: Int = 0,
    val specs: List<OptionSpec>,
    val titleEn: String = "",
)

/**
 * 子页面搜索入口：把子页面内的配置项并入父页面搜索（支持多级嵌套）。
 *
 * 子页面自身不必再放搜索栏，其功能统一由最上层页面的搜索栏检索；命中后调用对应层级的
 * [onOpen] 直接打开目标页面。嵌套时搜索结果摘要以「父 / 子」路径展示。
 *
 * @param titleRes 子页面标题，作为搜索结果摘要（多级路径）的一部分。
 * @param specs 该层子页面直接包含的配置项（通常已在 App 启动时注册到 [OptionRegistry]，此处用于建立搜索映射）。
 * @param onOpen 点击搜索结果时打开该子页面。
 * @param subPages 更深一层的子页面，递归并入搜索。
 */
data class HookSubPage(
    val titleRes: Int,
    val specs: List<OptionSpec>,
    val onOpen: (String) -> Unit,
    val subPages: List<HookSubPage> = emptyList(),
)

private sealed interface SearchTarget {
    data class Section(val section: HookSection) : SearchTarget
    data class SubPage(val subPage: HookSubPage, val path: List<Int>) : SearchTarget
}

/** 递归展开子页面，并记录每层的标题路径（用于搜索结果摘要）。 */
private fun flattenSubPages(
    subPages: List<HookSubPage>,
    prefix: List<Int> = emptyList(),
): List<Pair<HookSubPage, List<Int>>> = subPages.flatMap { subPage ->
    val path = prefix + subPage.titleRes
    listOf(subPage to path) + flattenSubPages(subPage.subPages, path)
}

/**
 * 分区标题：`titleRes` 为 `0` 时返回空串（该分区不显示小标题）；
 * `titleEn` 非空时拼接为 `中文（English）`（仅示例 / API 展示用）。
 */
@Composable
fun hookSectionTitle(section: HookSection): String = when {
    section.titleRes != 0 -> buildString {
        append(stringResource(section.titleRes))
        if (section.titleEn.isNotBlank()) {
            append("（").append(section.titleEn).append("）")
        }
    }
    else -> section.titleEn
}

/**
 * 通用组件页面：顶栏 + 分区列表。
 *
 * - 自动注册分区内全部 [OptionSpec] 到 [OptionRegistry]，标题 / 摘要接入全局搜索
 *   （搜索界面见 [cn.ianzb.hyperrefine.ui.component.search.AppSearchOverlay]，
 *   入口在底部玻璃底栏与各主页面顶栏，写法与 miuix 示例 GlassPage 一致）。
 * - 页面本身不再内联搜索栏：全局搜索命中本页的配置项后，通过 [externalSearchKey] 滚动到对应分区，
 *   子页面内的功能则直接打开对应子页面。
 * - 通过 [subPages] 可把子页面内的功能并入搜索（支持多级 [HookSubPage.subPages] 递归）。
 * - 背景模糊等模块配置由 [isBlurEnabled] 控制。
 *
 * @param title 页面标题。
 * @param sections 组件分区列表。
 * @param subPages 子页面入口：把子页面（及其嵌套子页面）内的配置项并入搜索（不渲染在本页）。
 * @param isBlurEnabled 是否启用背景模糊。
 * @param extraBottomPadding 额外底部内边距（用于底部导航栏遮挡）。
 * @param topContent 页面顶部提示区（显示在列表上方）；为空则不显示。
 * @param onArrowClick 箭头卡片点击回调，参数为被点击的 [OptionSpec]。
 * @param onBack 非空时在顶栏左侧显示返回按钮；根页面（如功能页）保持为空。
 * @param userScrollEnabled 是否允许外层列表滚动；拖拽排序等自定义手势可临时关闭。
 * @param showSearch 是否显示页面搜索栏；状态栏图标等自定义拖拽页面可关闭。
 * @param customActionPackages 额外注入重启应用的包名（供二次开发直接暴露自定义应用）。
 * @param topBarActions 顶栏右侧扩展槽（显示在自动生成的「重启应用」按钮之前），为空则不显示。
 * @param externalSearchKey 由全局搜索（底栏 / 顶栏搜索入口）命中的配置键：本页据此滚动到对应分区，
 *   或直接打开对应子页面；为 null 表示没有待处理项。
 * @param onExternalSearchHandled [externalSearchKey] 处理完毕后的回调（父级据此清空状态）。
 */
@Composable
fun HookOptionsPage(
    title: String,
    sections: List<HookSection>,
    subPages: List<HookSubPage> = emptyList(),
    isBlurEnabled: Boolean = true,
    extraBottomPadding: Dp = 0.dp,
    topContent: (@Composable () -> Unit)? = null,
    onArrowClick: (OptionSpec) -> Unit = {},
    onBack: (() -> Unit)? = null,
    userScrollEnabled: Boolean = true,
    showSearch: Boolean = true,
    customActionPackages: List<String> = emptyList(),
    topBarActions: (@Composable () -> Unit)? = null,
    externalSearchKey: String? = null,
    onExternalSearchHandled: () -> Unit = {},
    onSearchEntryClick: ((OptionSpec) -> Unit)? = null,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val listState = rememberLazyListState()
    var showQuickActions by remember { mutableStateOf(false) }
    // 全局搜索命中分区后：目标分区闪烁高亮（卡片变灰两次），并让顶栏像手指滑动那样收起。
    var flashedSectionIndex by remember { mutableIntStateOf(-1) }
    var flashToken by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // 顶栏「折叠饱和距离」：与 demo 一致，越过这段距离后带区/渐变模糊达到满强度。
    val saturatePx = with(density) {
        maxOf(
            GlassTopAppBarDefaults.RampStart + GlassTopAppBarDefaults.RampDistance,
            GlassTopAppBarDefaults.ProgressiveBlurRampDistance,
        ).toPx()
    }

    // 递归展开子页面（记录标题路径），供搜索 / 包名汇总 / 注册使用。
    val flatSubPages = remember(subPages) { flattenSubPages(subPages) }

    // 汇总「包名列表」选项中的包名，并叠加二次开发注入的自定义包名。
    val allSpecs = sections.flatMap { it.specs } + flatSubPages.flatMap { it.first.specs }
    val configActionPackages = allSpecs
        .filter { it.type == OptionType.PACKAGE_LIST }
        .flatMap { parsePackageList(ConfigState.string(it.key, it.defaultString)) }
    val quickActionPackages = remember(configActionPackages, customActionPackages) {
        (configActionPackages + customActionPackages).distinct()
    }

    // 配置键 → 搜索目标（本页分区 / 子页面路径），含滑块主开关等被引用的键。
    val searchTargets = remember(sections, flatSubPages) {
        buildMap<String, SearchTarget> {
            sections.forEach { section ->
                section.specs.forEach { spec ->
                    put(spec.key, SearchTarget.Section(section))
                    spec.masterKey?.let { put(it, SearchTarget.Section(section)) }
                }
            }
            flatSubPages.forEach { (subPage, path) ->
                subPage.specs.forEach { spec ->
                    put(spec.key, SearchTarget.SubPage(subPage, path))
                    spec.masterKey?.let { put(it, SearchTarget.SubPage(subPage, path)) }
                }
            }
        }
    }

    LaunchedEffect(sections, flatSubPages) {
        OptionRegistry.registerAll(allSpecs)
    }

    // 搜索结果摘要：把「配置键 → 分区 / 子页面路径」登记给全局搜索界面。
    LaunchedEffect(sections, flatSubPages, showSearch) {
        if (!showSearch) return@LaunchedEffect
        val labels = buildMap<String, List<Int>> {
            sections.forEach { section ->
                section.specs.forEach { spec ->
                    put(spec.key, listOfNotNull(section.titleRes.takeIf { it != 0 }))
                    spec.masterKey?.let { put(it, listOfNotNull(section.titleRes.takeIf { id -> id != 0 })) }
                }
            }
            flatSubPages.forEach { (subPage, path) ->
                subPage.specs.forEach { spec ->
                    put(spec.key, path)
                    spec.masterKey?.let { put(it, path) }
                }
            }
        }
        AppSearchIndex.putAll(labels)
    }

    // 全局搜索命中后：滚动到对应分区（并高亮 / 收起顶栏），或直接打开对应子页面。
    LaunchedEffect(externalSearchKey) {
        val key = externalSearchKey ?: return@LaunchedEffect
        when (val target = searchTargets[key]) {
            is SearchTarget.Section -> {
                val entry = target.section.specs.firstOrNull { it.key == key }
                if (onSearchEntryClick != null && target.section.titleRes == 0 && entry?.type == OptionType.ARROW) {
                    onExternalSearchHandled()
                    onSearchEntryClick(entry)
                    return@LaunchedEffect
                }
                val index = sections.indexOf(target.section)
                val offset = if (topContent != null) 1 else 0
                listState.animateScrollToItem(offset + index)
                // animateScrollToItem 是程序化滚动，不经过 nestedScroll，顶栏不会像手指下滑那样
                // 自动收起、也不会出现渐变模糊；这里补上「手动滑到该位置」后的顶栏状态。
                run {
                    val state = scrollBehavior.state
                    // 顶栏还没量出折叠高度时 heightOffsetLimit 是 -Float.MAX_VALUE，不能直接用。
                    if (state.heightOffsetLimit < 0f && state.heightOffsetLimit > -10_000f) {
                        state.heightOffset = state.heightOffsetLimit
                        state.contentOffset = -saturatePx
                    }
                }
                // 等滚动落位后再闪，用户能直接看到目标分区的位置。
                flashedSectionIndex = index
                flashToken++
            }
            is SearchTarget.SubPage -> target.subPage.onOpen(key)
            null -> Unit
        }
        onExternalSearchHandled()
    }

    AppLazyPage(
        title = title,
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        listState = listState,
        scrollBehavior = scrollBehavior,
        // 本页是唯一可搜索的页面：顶栏搜索入口只在这里出现（底栏入口同样只在功能页）。
        topBarSearchAction = showSearch,
        onBack = onBack,
        userScrollEnabled = userScrollEnabled,
        topBarActions = {
            topBarActions?.invoke()
            if (quickActionPackages.isNotEmpty()) {
                AppTopAppBarIconAction(onClick = { showQuickActions = true }) {
                    Icon(
                        imageVector = MiuixIcons.Glass.Refresh,
                        contentDescription = stringResource(R.string.quick_action_title),
                        tint = MiuixTheme.colorScheme.onSurface,
                    )
                }
                if (showQuickActions) {
                    QuickActionDialog(
                        packages = quickActionPackages,
                        onDismiss = { showQuickActions = false },
                    )
                }
            }
        },
    ) {
        // 页面搜索入口统一放在容器层（底栏 / 顶栏，见 AppSearchOverlay），列表本身不再内联搜索栏，
        // 因此这里始终渲染完整内容，命中结果时由上面的 LaunchedEffect 滚动/跳转。
        if (topContent != null) {
            item(key = "top_content") { topContent() }
        }

        sections.forEachIndexed { index, section ->
            item(key = if (section.titleRes != 0) section.titleRes else section.specs.firstOrNull()?.key) {
                HookSectionCard(
                    section = section,
                    flashToken = if (flashedSectionIndex == index) flashToken else 0,
                ) {
                    section.specs.forEach { spec ->
                        HookOptionView(
                            spec = spec,
                            onArrowClick = { onArrowClick(spec) },
                            backdrop = LocalPreferenceBackdrop.current,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 组件分区卡片：`SmallTitle`（`中文（English）`）+ 卡片容器。
 *
 * @param flashToken 非 0 时播放一次「强调」动画（卡片底色变灰闪烁两次），用于搜索结果跳转后
 *   指出目标分区的位置；递增该值即可重新触发。
 */
@Composable
fun HookSectionCard(
    section: HookSection,
    modifier: Modifier = Modifier,
    flashToken: Int = 0,
    content: @Composable () -> Unit,
) {
    val landed = LocalSearchFlash.current
    val flash = remember { Animatable(0f) }
    LaunchedEffect(flashToken, landed) {
        if (flashToken == 0 && landed?.invoke() != true) return@LaunchedEffect
        flash.snapTo(0f)
        repeat(2) {
            flash.animateTo(1f, tween(durationMillis = 160))
            flash.animateTo(0f, tween(durationMillis = 300))
        }
    }
    val baseColor = MiuixTheme.colorScheme.surfaceContainer
    val cardColor = if (flash.value > 0f) {
        lerp(baseColor, MiuixTheme.colorScheme.onSurface, 0.12f * flash.value)
    } else {
        baseColor
    }
    AppSectionCard(
        title = hookSectionTitle(section),
        modifier = modifier,
        colors = CardDefaults.defaultColors(color = cardColor),
    ) {
        content()
    }
}
