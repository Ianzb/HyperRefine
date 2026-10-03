package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.misound.AppVolumeKeys
import cn.ianzb.hyperrefine.hook.systemui.radius.CcRadiusKeys
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 控制中心「圆角调整」二级页。
 *
 * 总开关 + 统一圆角；各项目可单独开启自定义。涉及控制中心一级 / 二级、侧边音量条，
 * 以及模块自定义多应用音量面板。
 */
class CornerRadiusActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.cc_radius_title

    override val topBarActions: (@Composable () -> Unit)? = {
        QuickActionsAction(listOf("com.android.systemui", AppVolumeKeys.TARGET_PACKAGE))
    }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val masterSection = HookSection(
            titleRes = R.string.cc_radius_section_master,
            specs = listOf(
                featureSpec(CcRadiusKeys.MASTER),
                featureSpec(CcRadiusKeys.COMPONENT),
                featureSpec(CcRadiusKeys.BACKGROUND),
            ),
        )
        val componentSection = HookSection(
            titleRes = R.string.cc_radius_section_component,
            specs = CcRadiusKeys.COMPONENT_ITEMS.map { featureSpec(CcRadiusKeys.valueKey(it)) },
        )
        val backgroundSection = HookSection(
            titleRes = R.string.cc_radius_section_background,
            specs = CcRadiusKeys.BACKGROUND_ITEMS.map { featureSpec(CcRadiusKeys.valueKey(it)) },
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
                HookSectionCard(masterSection) {
                    masterSection.specs.forEach { HookOptionView(it) }
                }
            }
            item {
                HookSectionCard(componentSection) {
                    componentSection.specs.forEach { HookOptionView(it) }
                }
            }
            item {
                HookSectionCard(backgroundSection) {
                    backgroundSection.specs.forEach { HookOptionView(it) }
                }
            }
        }
    }
}
