package cn.ianzb.hyperrefine.ui.screen.features

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.MiuixExpandSpec
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

    override val showSystemUiActions: Boolean = true

    private fun location(): String =
        intent?.getStringExtra(EXTRA_LOCATION) ?: PercentLocation.CC_VOLUME

    @Composable
    override fun SubPageContent(
        isBlurEnabled: Boolean,
        contentPadding: PaddingValues,
    ) {
        val location = location()
        val masterKey = PercentLocation.masterKey(location)
        val enabled = ConfigState.bool(masterKey, false)
        val scrollBehavior = LocalSubPageScrollBehavior.current

        val displaySection = HookSection(
            titleRes = R.string.percent_display_section,
            titleEn = "Percentage",
            specs = listOf(featureSpec(masterKey)),
        )
        val styleSection = HookSection(
            titleRes = R.string.percent_style_section,
            titleEn = "Style",
            specs = listOf(
                featureSpec("${location}_font_size"),
                featureSpec("${location}_font_weight"),
                featureSpec("${location}_follow_icon"),
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
                HookSectionCard(displaySection) {
                    displaySection.specs.forEach { HookOptionView(it) }
                }
            }
            item {
                AnimatedVisibility(
                    visible = enabled,
                    enter = expandVertically(animationSpec = MiuixExpandSpec),
                    exit = shrinkVertically(animationSpec = MiuixExpandSpec),
                ) {
                    HookSectionCard(styleSection) {
                        styleSection.specs.forEach { HookOptionView(it) }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_LOCATION = "location"
    }
}
