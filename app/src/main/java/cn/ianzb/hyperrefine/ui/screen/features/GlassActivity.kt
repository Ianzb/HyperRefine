package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.systemui.glass.CcGlassKeys
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.MiuixExpandSpec
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 柔光玻璃子页：总开关控制各二级面板按钮 / 卡片的玻璃材质接入。
 *
 * 总开关开启后出现各面板小开关（默认开启）。
 */
class GlassActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.feature_cc_glass

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf("com.android.systemui")) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val enabled = ConfigState.bool(CcGlassKeys.MASTER, false)
        val scrollBehavior = LocalSubPageScrollBehavior.current

        val masterSection = HookSection(
            titleRes = R.string.section_cc_glass,
            specs = listOf(featureSpec(CcGlassKeys.MASTER)),
        )
        val panelSection = remember {
            HookSection(
                titleRes = R.string.cc_glass_panel_section,
                specs = listOf(
                    featureSpec(CcGlassKeys.SIDE_VOLUME),
                    featureSpec(CcGlassKeys.CC_VOLUME),
                    featureSpec(CcGlassKeys.BRIGHTNESS),
                    featureSpec(CcGlassKeys.MOBILE_DATA),
                    featureSpec(CcGlassKeys.WLAN),
                    featureSpec(CcGlassKeys.MEDIA),
                    featureSpec(CcGlassKeys.DEBUG),
                ),
            )
        }

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
                HookSectionCard(masterSection) {
                    masterSection.specs.forEach { HookOptionView(it) }
                }
            }
            item {
                AnimatedVisibility(
                    visible = enabled,
                    enter = expandVertically(animationSpec = MiuixExpandSpec),
                    exit = shrinkVertically(animationSpec = MiuixExpandSpec),
                ) {
                    HookSectionCard(panelSection) {
                        panelSection.specs.forEach { HookOptionView(it) }
                    }
                }
            }
        }
    }
}
