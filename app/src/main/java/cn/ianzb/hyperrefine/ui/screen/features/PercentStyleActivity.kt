package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers

/**
 * 单个位置（控制中心音量条 / 控制中心亮度条 / 侧边音量条）的百分比数值显示配置页。
 *
 * 开关开启后出现样式配置：字号、字重、字体颜色是否实时跟随图标。
 */
class PercentStyleActivity : BaseSubPageActivity() {

    override val titleRes: Int
        get() = when (location()) {
            PercentLocation.CC_BRIGHTNESS -> R.string.appearance_cc_brightness
            PercentLocation.SIDE_VOLUME -> R.string.appearance_side_volume
            else -> R.string.appearance_cc_volume
        }

    override val topBarActions: (@Composable () -> Unit)? =
        { QuickActionsAction(listOf("com.android.systemui")) }

    private fun location(): String =
        intent?.getStringExtra(EXTRA_LOCATION) ?: PercentLocation.CC_VOLUME

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val location = location()
        val masterKey = PercentLocation.masterKey(location)
        val scrollBehavior = LocalSubPageScrollBehavior.current

        val interactionSection = if (location == PercentLocation.SIDE_VOLUME) {
            HookSection(
                titleRes = R.string.side_volume_interaction_section,
                specs = listOf(featureSpec(SIDE_LONGPRESS_KEY)),
            )
        } else {
            null
        }
        // 百分比相关选项统一放在一个卡片组（总开关 + 样式 + 显示位置）。
        val percentSection = HookSection(
            titleRes = R.string.percent_display_section,
            specs = buildList {
                add(featureSpec(masterKey))
                add(featureSpec("${location}_font_size"))
                add(featureSpec("${location}_font_weight"))
                add(featureSpec("${location}_follow_icon"))
                add(featureSpec(PercentLocation.positionKey(location)))
                if (location == PercentLocation.SIDE_VOLUME) add(featureSpec(SIDE_INSIDE_KEY))
            },
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
            // 长按打开音量面板：始终显示且置于页面最前。
            interactionSection?.let { section ->
                item {
                    HookSectionCard(section) {
                        section.specs.forEach { HookOptionView(it) }
                    }
                }
            }
            // 规范：依赖百分比总开关的样式项始终显示，未开启时禁用灰显（不隐藏）。
            item {
                HookSectionCard(percentSection) {
                    percentSection.specs.forEach { HookOptionView(it) }
                }
            }
        }
    }

    companion object {
        const val EXTRA_LOCATION = "location"
    }
}
