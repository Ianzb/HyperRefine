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
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionsPage
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSubPage
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
                specs = listOf(specByKey(specs, KEY_APPEARANCE)),
            ),
        )
    }
    // 外观为二级页，各位置功能页为三级页：通过嵌套 subPages 让搜索直达多级功能。
    val subPages = remember(specs) {
        listOf(
            HookSubPage(
                titleRes = R.string.feature_appearance,
                specs = PercentLocation.all().map { specByKey(specs, PercentLocation.entryKey(it)) },
                onOpen = { context.startActivity(Intent(context, AppearanceActivity::class.java)) },
                subPages = PercentLocation.all().map { location ->
                    HookSubPage(
                        titleRes = appearanceTitleRes(location),
                        specs = locationSpecs(specs, location),
                        onOpen = {
                            context.startActivity(
                                Intent(context, PercentStyleActivity::class.java)
                                    .putExtra(PercentStyleActivity.EXTRA_LOCATION, location)
                            )
                        },
                    )
                },
            ),
        )
    }

    LaunchedEffect(Unit) {
        HookStatusReader.refresh()
    }

    HookOptionsPage(
        title = stringResource(R.string.tab_features),
        sections = sections,
        subPages = subPages,
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        onArrowClick = {
            context.startActivity(Intent(context, AppearanceActivity::class.java))
        },
        topBarActions = { QuickActionsAction(listOf("com.android.systemui")) },
    )
}

const val KEY_APPEARANCE = "feature_appearance"
const val SIDE_INSIDE_KEY = "side_volume_inside"
const val SIDE_LONGPRESS_KEY = "side_volume_longpress"

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

/** 各位置页面标题（二级 / 三级页面共用）。 */
private fun appearanceTitleRes(location: String): Int = when (location) {
    PercentLocation.CC_BRIGHTNESS -> R.string.appearance_cc_brightness
    PercentLocation.SIDE_VOLUME -> R.string.appearance_side_volume
    else -> R.string.appearance_cc_volume
}

/** 各位置页面内（三级页面）的配置项，用于多级搜索直达。 */
private fun locationSpecs(specs: List<OptionSpec>, location: String): List<OptionSpec> {
    val keys = mutableListOf(
        PercentLocation.masterKey(location),
        "${location}_font_size",
        "${location}_font_weight",
        "${location}_follow_icon",
    )
    if (location == PercentLocation.SIDE_VOLUME) {
        keys += SIDE_INSIDE_KEY
        keys += SIDE_LONGPRESS_KEY
    }
    return keys.map { specByKey(specs, it) }
}

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
        ),
    )
    PercentLocation.all().forEach { location ->
        val titleRes = when (location) {
            PercentLocation.CC_VOLUME -> R.string.appearance_cc_volume
            PercentLocation.CC_BRIGHTNESS -> R.string.appearance_cc_brightness
            else -> R.string.appearance_side_volume
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
            defaultString = "black",
            entryResIds = listOf(
                R.string.font_weight_light,
                R.string.font_weight_default,
                R.string.font_weight_medium,
                R.string.font_weight_semibold,
                R.string.font_weight_bold,
                R.string.font_weight_black,
            ),
            entryValues = listOf("light", "normal", "medium", "semibold", "bold", "black"),
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
        if (location == PercentLocation.SIDE_VOLUME) {
            specs += OptionSpec(
                key = SIDE_INSIDE_KEY,
                type = OptionType.SWITCH,
                titleRes = R.string.side_volume_inside,
                summaryRes = R.string.side_volume_inside_summary,
                defaultBoolean = false,
                targetPackages = systemUi,
            )
            specs += OptionSpec(
                key = SIDE_LONGPRESS_KEY,
                type = OptionType.SWITCH,
                titleRes = R.string.side_volume_longpress,
                summaryRes = R.string.side_volume_longpress_summary,
                defaultBoolean = false,
                targetPackages = systemUi,
            )
        }
    }
    return specs
}
