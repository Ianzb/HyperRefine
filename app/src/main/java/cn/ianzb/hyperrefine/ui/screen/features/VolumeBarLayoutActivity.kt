package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.systemui.VolumeBarKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 「音量条布局」二级页（系统界面 → 音量）。
 *
 * 面板背景调节 / 音量条尺寸 / 音量条间距 / 竖直位置 / 二级面板位置 / 自动平衡高度。
 */
class VolumeBarLayoutActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.volume_bar_layout_title

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf("com.android.systemui")) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val sections = listOf(
            HookSection(
                titleRes = R.string.volume_bar_panel_section,
                specs = listOf(
                    featureSpec(VolumeBarKeys.PANEL_BG),
                    featureSpec(VolumeBarKeys.PANEL_PAD_VERTICAL),
                    featureSpec(VolumeBarKeys.PANEL_PAD_HORIZONTAL),
                ),
            ),
            HookSection(
                titleRes = R.string.volume_bar_size_section,
                specs = listOf(
                    featureSpec(VolumeBarKeys.BAR_SIZE),
                    featureSpec(VolumeBarKeys.BAR_HEIGHT),
                    featureSpec(VolumeBarKeys.BAR_WIDTH),
                ),
            ),
            HookSection(
                titleRes = R.string.volume_bar_spacing_section,
                specs = listOf(
                    featureSpec(VolumeBarKeys.SPACING),
                    featureSpec(VolumeBarKeys.COLUMN_SPACING),
                ),
            ),
            HookSection(
                titleRes = R.string.side_volume_pos_section,
                specs = listOf(
                    featureSpec(VolumeBarKeys.SIDE_POS),
                    featureSpec(VolumeBarKeys.SIDE_POS_PORTRAIT),
                    featureSpec(VolumeBarKeys.SIDE_POS_LANDSCAPE),
                ),
            ),
            HookSection(
                titleRes = R.string.volume_bar_l2_pos_section,
                specs = listOf(
                    featureSpec(VolumeBarKeys.L2_POS),
                    featureSpec(VolumeBarKeys.L2_POS_PORTRAIT),
                    featureSpec(VolumeBarKeys.L2_POS_LANDSCAPE),
                ),
            ),
            HookSection(
                titleRes = R.string.volume_bar_auto_balance_section,
                specs = listOf(
                    featureSpec(VolumeBarKeys.AUTO_BALANCE_PORTRAIT),
                    featureSpec(VolumeBarKeys.AUTO_BALANCE_LANDSCAPE),
                ),
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
            sections.forEach { section ->
                item {
                    HookSectionCard(section) {
                        section.specs.forEach { HookOptionView(it) }
                    }
                }
            }
        }
    }
}
