package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.miuix.MiuixAppLoad
import cn.ianzb.hyperrefine.hook.miuix.TopBarKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 「顶栏渐变」二级页（外观 → 美化）。
 *
 * 总开关 / 模糊强度 / 不透明度；顶栏提供对目标 MIUIX 应用的重启入口。
 */
class TopBarActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.section_top_bar

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(MiuixAppLoad.MIUIX_PACKAGES) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val section = HookSection(
            titleRes = R.string.section_top_bar,
            specs = listOf(
                featureSpec(TopBarKeys.KEY),
                featureSpec(TopBarKeys.STRENGTH),
                featureSpec(TopBarKeys.OPACITY),
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
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (scrollBehavior != null) {
                        Modifier.pageScrollModifiers(
                            showTopAppBar = true,
                            topAppBarScrollBehavior = scrollBehavior,
                        )
                    } else {
                        Modifier
                    }
                ),
            contentPadding = contentPadding,
        ) {
            item {
                HookSectionCard(section) {
                    section.specs.forEach { HookOptionView(it) }
                }
            }
            item {
                HookSectionCard(advancedSection) {
                    advancedSection.specs.forEach { HookOptionView(it) }
                }
            }
        }
    }
}
