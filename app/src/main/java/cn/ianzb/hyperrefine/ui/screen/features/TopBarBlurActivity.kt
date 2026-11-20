package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.miuix.MiuixAppLoad
import cn.ianzb.hyperrefine.hook.miuix.TopBarKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.SearchLandingLazyColumn
import cn.ianzb.hyperrefine.ui.component.pref.searchSectionItem
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 「渐变模糊」二级页（应用美化 → 渐变模糊）。
 *
 * 把系统应用（设置 / 短信 / 联系人等 MIUIX 应用）顶栏的原生遮罩替换为「顶部模糊、向下渐变透明」的模糊层。
 *
 * - 顶栏渐变：总开关 / 模糊强度 / 不透明度 / 多模糊行为
 * - 应用专属适配：统一开关 + 笔记 / 计算器 / 文件管理
 */
class TopBarBlurActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.feature_top_bar_blur

    override val topBarActions: @Composable () -> Unit = TopBarBlurActions

    @Composable
    override fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
        TopBarBlurContent(isBlurEnabled, contentPadding)
    }
}

internal val TopBarBlurActions: @Composable () -> Unit = { QuickActionsAction(MiuixAppLoad.MIUIX_PACKAGES) }

@Composable
internal fun TopBarBlurContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
    val scrollBehavior = LocalSubPageScrollBehavior.current
    val section = HookSection(
        titleRes = R.string.section_top_bar,
        specs = listOf(
            featureSpec(TopBarKeys.KEY),
            featureSpec(TopBarKeys.STRENGTH),
            featureSpec(TopBarKeys.OPACITY),
            featureSpec(TopBarKeys.MULTI_BLUR),
            featureSpec(TopBarKeys.MULTI_BLUR_FALLBACK),
        ),
    )
    // 应用专属适配：统一开关 + 笔记 / 计算器 / 文件管理单独开关。
    val advancedSection = HookSection(
        titleRes = R.string.section_top_bar_advanced,
        specs = listOf(
            featureSpec(TopBarKeys.ADVANCED),
            featureSpec(TopBarKeys.ADVANCED_NOTES),
            featureSpec(TopBarKeys.ADVANCED_CALCULATOR),
            featureSpec(TopBarKeys.ADVANCED_FILE_EXPLORER),
        ),
    )
    val listState = rememberLazyListState()
    SearchLandingLazyColumn(
        scrollBehavior = scrollBehavior,
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (scrollBehavior != null) {
                    Modifier.pageScrollModifiers(
                        showTopAppBar = true,
                        topAppBarScrollBehavior = scrollBehavior,
                        contentState = listState,
                    )
                } else {
                    Modifier
                }
            ),
        contentPadding = contentPadding,
    ) {
        listOf(section, advancedSection).forEach { sec ->
            searchSectionItem(sec) {
                HookSectionCard(sec) {
                    sec.specs.forEach { HookOptionView(it) }
                }
            }
        }
    }
}
