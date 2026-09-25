package cn.ianzb.hyperrefine.ui.screen.features

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 控制中心子页：控制中心亮度条 / 音量条百分比数值显示入口，以及融合设备中心相关功能。
 */
class ControlCenterActivity : BaseSubPageActivity() {

    override val titleRes: Int = R.string.feature_control_center

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf("com.android.systemui")) }

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val context = LocalContext.current
        val scrollBehavior = LocalSubPageScrollBehavior.current
        val entries = listOf(PercentLocation.CC_BRIGHTNESS, PercentLocation.CC_VOLUME).map { location ->
            location to featureSpec(PercentLocation.entryKey(location))
        }
        val displaySection = HookSection(
            titleRes = R.string.percent_display_section,
            specs = entries.map { it.second },
        )
        val deviceSection = HookSection(
            titleRes = R.string.section_device_center,
            specs = listOf(featureSpec(KEY_DEVICE_CENTER_HIDE_MORE)),
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
                HookSectionCard(displaySection) {
                    entries.forEach { (location, spec) ->
                        HookOptionView(
                            spec = spec,
                            onArrowClick = {
                                context.startActivity(
                                    Intent(context, PercentStyleActivity::class.java)
                                        .putExtra(PercentStyleActivity.EXTRA_LOCATION, location)
                                )
                            },
                        )
                    }
                }
            }
            item {
                HookSectionCard(deviceSection) {
                    deviceSection.specs.forEach { HookOptionView(it) }
                }
            }
        }
    }
}
