package cn.ianzb.hyperrefine.ui.screen.features

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.prefs.OptionType
import cn.ianzb.hyperrefine.ui.component.SystemUiQuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionsPage
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.xposed.HookStatusReader

/**
 * 功能页：模块全部功能入口（系统界面 → 外观 → 各功能）。
 *
 * 页面布局由通用组件 [HookOptionsPage] 提供。
 */
@Composable
fun FeaturesPageView(
    isBlurEnabled: Boolean = true,
    extraBottomPadding: Dp = 0.dp,
) {
    val context = LocalContext.current
    val specs = remember { featureSpecs() }
    val sections = remember(specs) {
        listOf(
            HookSection(
                titleRes = R.string.section_system_ui,
                titleEn = "System UI",
                specs = listOf(specByKey(specs, KEY_APPEARANCE)),
            ),
        )
    }

    LaunchedEffect(Unit) {
        HookStatusReader.refresh()
    }

    HookOptionsPage(
        title = stringResource(R.string.tab_features),
        sections = sections,
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        onArrowClick = {
            context.startActivity(Intent(context, AppearanceActivity::class.java))
        },
        topBarActions = { SystemUiQuickActionsAction() },
    )
}

const val KEY_APPEARANCE = "feature_appearance"

/** 位置标识：与配置键前缀一致。 */
object PercentLocation {
    const val CC_VOLUME = "cc_volume"
    const val CC_BRIGHTNESS = "cc_brightness"
    const val SIDE_VOLUME = "side_volume"

    fun all(): List<String> = listOf(CC_VOLUME, CC_BRIGHTNESS, SIDE_VOLUME)

    fun masterKey(location: String): String = "${location}_percent"

    fun entryKey(location: String): String = "appearance_$location"
}

private fun specByKey(specs: List<OptionSpec>, key: String): OptionSpec =
    specs.first { it.key == key }

/** 按键取功能配置项（供各功能子页复用同一份声明）。 */
fun featureSpec(key: String): OptionSpec =
    featureSpecs().first { it.key == key }

/** 功能页的全部配置项（App 启动时注册，供全局搜索与作用域申请使用）。 */
internal fun featureSpecs(): List<OptionSpec> {
    val systemUi = listOf("com.android.systemui")
    val specs = mutableListOf(
        OptionSpec(
            key = KEY_APPEARANCE,
            type = OptionType.ARROW,
            titleRes = R.string.feature_appearance,
            summaryRes = R.string.feature_appearance_summary,
        ),
    )
    PercentLocation.all().forEach { location ->
        val titleRes = when (location) {
            PercentLocation.CC_VOLUME -> R.string.appearance_cc_volume
            PercentLocation.CC_BRIGHTNESS -> R.string.appearance_cc_brightness
            else -> R.string.appearance_side_volume
        }
        val summaryRes = when (location) {
            PercentLocation.CC_VOLUME -> R.string.appearance_cc_volume_summary
            PercentLocation.CC_BRIGHTNESS -> R.string.appearance_cc_brightness_summary
            else -> R.string.appearance_side_volume_summary
        }
        val followSummaryRes = if (location == PercentLocation.CC_BRIGHTNESS) {
            R.string.brightness_follow_icon_summary
        } else {
            R.string.volume_follow_icon_summary
        }
        specs += OptionSpec(
            key = PercentLocation.entryKey(location),
            type = OptionType.ARROW,
            titleRes = titleRes,
            summaryRes = summaryRes,
        )
        specs += OptionSpec(
            key = PercentLocation.masterKey(location),
            type = OptionType.SWITCH,
            titleRes = titleRes,
            summaryRes = R.string.percent_switch_summary,
            defaultBoolean = false,
            targetPackages = systemUi,
        )
        specs += OptionSpec(
            key = "${location}_font_size",
            type = OptionType.SLIDER,
            titleRes = R.string.percent_font_size,
            summaryRes = R.string.percent_font_size_summary,
            defaultFloat = 13f,
            sliderMin = 8f,
            sliderMax = 24f,
            sliderStep = 0.5f,
            sliderDecimals = 1,
            sliderUnitRes = R.string.percent_font_size_unit,
            sliderValueLabelRes = R.string.percent_font_size_label,
            targetPackages = systemUi,
        )
        specs += OptionSpec(
            key = "${location}_font_weight",
            type = OptionType.DROPDOWN,
            titleRes = R.string.percent_font_weight,
            summaryRes = R.string.percent_font_weight_summary,
            defaultString = "normal",
            entryResIds = listOf(
                R.string.font_weight_default,
                R.string.font_weight_light,
                R.string.font_weight_medium,
                R.string.font_weight_bold,
            ),
            entryValues = listOf("normal", "light", "medium", "bold"),
            targetPackages = systemUi,
        )
        specs += OptionSpec(
            key = "${location}_follow_icon",
            type = OptionType.SWITCH,
            titleRes = R.string.percent_follow_icon,
            summaryRes = followSummaryRes,
            defaultBoolean = true,
            targetPackages = systemUi,
        )
    }
    return specs
}
