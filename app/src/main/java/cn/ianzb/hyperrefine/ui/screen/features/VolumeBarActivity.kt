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
 * 「音量条」三级页（外观 → 美化 → 布局调整 → 音量条）。
 *
 * 面板背景调节 / 音量条高度宽度 / 多应用音量条间距。
 */
class VolumeBarActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.volume_bar_section

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf("com.android.systemui")) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val panelSection = HookSection(
            titleRes = R.string.volume_bar_panel_section,
            specs = listOf(
                featureSpec(VolumeBarKeys.PANEL_BG),
                featureSpec(VolumeBarKeys.PANEL_PAD_VERTICAL),
                featureSpec(VolumeBarKeys.PANEL_PAD_HORIZONTAL),
            ),
        )
        val sizeSection = HookSection(
            titleRes = R.string.volume_bar_size_section,
            specs = listOf(
                featureSpec(VolumeBarKeys.BAR_SIZE),
                featureSpec(VolumeBarKeys.BAR_HEIGHT),
                featureSpec(VolumeBarKeys.BAR_WIDTH),
            ),
        )
        val spacingSection = HookSection(
            titleRes = R.string.volume_bar_spacing_section,
            specs = listOf(
                featureSpec(VolumeBarKeys.SPACING),
                featureSpec(VolumeBarKeys.COLUMN_SPACING),
            ),
        )
        val sidePosSection = HookSection(
            titleRes = R.string.side_volume_pos_section,
            specs = listOf(
                featureSpec(VolumeBarKeys.SIDE_POS),
                featureSpec(VolumeBarKeys.SIDE_POS_PORTRAIT),
                featureSpec(VolumeBarKeys.SIDE_POS_LANDSCAPE),
            ),
        )
        val l2PosSection = HookSection(
            titleRes = R.string.volume_bar_l2_pos_section,
            specs = listOf(
                featureSpec(VolumeBarKeys.L2_POS),
                featureSpec(VolumeBarKeys.L2_POS_PORTRAIT),
                featureSpec(VolumeBarKeys.L2_POS_LANDSCAPE),
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
                HookSectionCard(panelSection) {
                    panelSection.specs.forEach { HookOptionView(it) }
                }
            }
            item {
                HookSectionCard(sizeSection) {
                    sizeSection.specs.forEach { HookOptionView(it) }
                }
            }
            item {
                HookSectionCard(spacingSection) {
                    spacingSection.specs.forEach { HookOptionView(it) }
                }
            }
            item {
                HookSectionCard(sidePosSection) {
                    sidePosSection.specs.forEach { HookOptionView(it) }
                }
            }
            item {
                HookSectionCard(l2PosSection) {
                    l2PosSection.specs.forEach { HookOptionView(it) }
                }
            }
        }
    }
}
