package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.systemserver.FreeformDensityKeys
import cn.ianzb.hyperrefine.hook.systemui.SplitScreenKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 「窗口管理」二级页（系统界面 → 分屏与窗口）。
 *
 * - 分屏：方向限制、多分屏数量、无级比例、比例记忆、1:9 拖拽过渡、把手菜单双向分屏
 * - 小窗：禁用全屏应用底部角落拖出无极小窗的手势、无极小窗字号随窗口缩放
 */
class WindowManagerActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.feature_window_manager

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf("com.android.systemui")) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val splitSection = HookSection(
            titleRes = R.string.section_split_screen,
            specs = listOf(
                featureSpec(SplitScreenKeys.UNLOCK_DIRECTION),
                featureSpec(SplitScreenKeys.MULTIPLE_SPLIT_CUSTOM),
                featureSpec(SplitScreenKeys.MULTIPLE_SPLIT_MAX_STAGES),
                featureSpec(SplitScreenKeys.MULTIPLE_SPLIT_FREE_RATIO),
                featureSpec(SplitScreenKeys.SINGLE_SPLIT_FREE_RATIO),
                featureSpec(SplitScreenKeys.SPLIT_FREE_RATIO_GLOBAL),
                featureSpec(SplitScreenKeys.SPLIT_RATIO_MEMORY),
                featureSpec(SplitScreenKeys.SPLIT_PEEK_TRANSITION),
                featureSpec(SplitScreenKeys.CAPTION_BOTH_DIRECTIONS),
            ),
        )
        val freeformSection = HookSection(
            titleRes = R.string.section_freeform_window,
            specs = listOf(
                featureSpec(SplitScreenKeys.DISABLE_CORNER_FREEFORM),
                featureSpec(SplitScreenKeys.FREEFORM_FREE_RATIO),
                featureSpec(SplitScreenKeys.FREEFORM_FREE_RATIO_SNAP),
                featureSpec(FreeformDensityKeys.DENSITY),
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
                HookSectionCard(splitSection) {
                    splitSection.specs.forEach { HookOptionView(it) }
                }
            }
            item {
                HookSectionCard(freeformSection) {
                    freeformSection.specs.forEach { HookOptionView(it) }
                }
            }
        }
    }
}
