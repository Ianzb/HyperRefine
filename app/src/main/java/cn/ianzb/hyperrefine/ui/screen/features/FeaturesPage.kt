package cn.ianzb.hyperrefine.ui.screen.features

import android.content.Context
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
 * 功能页：模块全部功能入口（系统界面 → 控制中心 / 侧边音量条；安全服务 → 快充加速通知）。
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
    val sideVolumeEntry = PercentLocation.entryKey(PercentLocation.SIDE_VOLUME)
    val sections = remember(specs) {
        listOf(
            HookSection(
                titleRes = R.string.section_system_ui,
                specs = listOf(
                    specByKey(specs, KEY_CONTROL_CENTER),
                    specByKey(specs, sideVolumeEntry),
                ),
            ),
            HookSection(
                titleRes = R.string.section_security_center,
                specs = listOf(
                    specByKey(specs, KEY_SECURITY_CENTER),
                ),
            ),
        )
    }
    // 控制中心 / 侧边音量条为二级页，各自的样式页为三级页；安全服务下功能为二级页：通过嵌套 subPages 让搜索直达多级功能。
    val subPages = remember(specs) {
        listOf(
            HookSubPage(
                titleRes = R.string.feature_control_center,
                specs = listOf(
                    specByKey(specs, PercentLocation.entryKey(PercentLocation.CC_BRIGHTNESS)),
                    specByKey(specs, PercentLocation.entryKey(PercentLocation.CC_VOLUME)),
                    specByKey(specs, KEY_DEVICE_CENTER_HIDE_MORE),
                ),
                onOpen = { context.startActivity(Intent(context, ControlCenterActivity::class.java)) },
                subPages = listOf(
                    HookSubPage(
                        titleRes = R.string.appearance_cc_brightness,
                        specs = locationSpecs(specs, PercentLocation.CC_BRIGHTNESS),
                        onOpen = { context.startActivity(percentStyleIntent(context, PercentLocation.CC_BRIGHTNESS)) },
                    ),
                    HookSubPage(
                        titleRes = R.string.appearance_cc_volume,
                        specs = locationSpecs(specs, PercentLocation.CC_VOLUME),
                        onOpen = { context.startActivity(percentStyleIntent(context, PercentLocation.CC_VOLUME)) },
                    ),
                ),
            ),
            HookSubPage(
                titleRes = R.string.appearance_side_volume,
                specs = locationSpecs(specs, PercentLocation.SIDE_VOLUME),
                onOpen = { context.startActivity(percentStyleIntent(context, PercentLocation.SIDE_VOLUME)) },
            ),
            HookSubPage(
                titleRes = R.string.fast_charge_notify,
                specs = securityCenterSpecs(specs),
                onOpen = { context.startActivity(Intent(context, SecurityCenterActivity::class.java)) },
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
        onArrowClick = { spec ->
            when (spec.key) {
                KEY_CONTROL_CENTER ->
                    context.startActivity(Intent(context, ControlCenterActivity::class.java))
                KEY_SECURITY_CENTER ->
                    context.startActivity(Intent(context, SecurityCenterActivity::class.java))
                sideVolumeEntry ->
                    context.startActivity(percentStyleIntent(context, PercentLocation.SIDE_VOLUME))
            }
        },
        topBarActions = { QuickActionsAction(listOf("com.android.systemui", "com.miui.securitycenter")) },
    )
}

const val KEY_CONTROL_CENTER = "feature_control_center"
const val KEY_DEVICE_CENTER_HIDE_MORE = "device_center_hide_more"
const val KEY_SECURITY_CENTER = "feature_security_center"
const val KEY_FAST_CHARGE_ENTER = "security_center_fast_charge_enter_notify"
const val KEY_FAST_CHARGE_EXIT = "security_center_fast_charge_exit_notify"
const val SIDE_INSIDE_KEY = "side_volume_inside"
const val SIDE_LONGPRESS_KEY = "side_volume_longpress"

private fun percentStyleIntent(context: Context, location: String): Intent =
    Intent(context, PercentStyleActivity::class.java)
        .putExtra(PercentStyleActivity.EXTRA_LOCATION, location)

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

/** 安全服务二级页（快充加速通知）内的配置项，用于功能页搜索直达。 */
private fun securityCenterSpecs(specs: List<OptionSpec>): List<OptionSpec> =
    listOf(
        specByKey(specs, KEY_FAST_CHARGE_ENTER),
        specByKey(specs, KEY_FAST_CHARGE_EXIT),
    )

/** 按键取功能配置项（供各功能子页复用同一份声明）。 */
fun featureSpec(key: String): OptionSpec =
    featureSpecs().first { it.key == key }

/** 功能页的全部配置项（App 启动时注册，供全局搜索与作用域申请使用）。 */
internal fun featureSpecs(): List<OptionSpec> {
    val systemUi = listOf("com.android.systemui")
    val securityCenter = listOf("com.miui.securitycenter")
    val specs = mutableListOf(
        OptionSpec(
            key = KEY_CONTROL_CENTER,
            type = OptionType.ARROW,
            titleRes = R.string.feature_control_center,
        ),
        OptionSpec(
            key = KEY_DEVICE_CENTER_HIDE_MORE,
            type = OptionType.SWITCH,
            titleRes = R.string.device_center_hide_more,
            summaryRes = R.string.device_center_hide_more_summary,
            defaultBoolean = false,
            targetPackages = systemUi,
        ),
        OptionSpec(
            key = KEY_SECURITY_CENTER,
            type = OptionType.ARROW,
            titleRes = R.string.fast_charge_notify,
        ),
        OptionSpec(
            key = KEY_FAST_CHARGE_ENTER,
            type = OptionType.SWITCH,
            titleRes = R.string.fast_charge_notify_enter,
            summaryRes = R.string.fast_charge_notify_enter_summary,
            defaultBoolean = false,
            targetPackages = securityCenter,
        ),
        OptionSpec(
            key = KEY_FAST_CHARGE_EXIT,
            type = OptionType.SWITCH,
            titleRes = R.string.fast_charge_notify_exit,
            summaryRes = R.string.fast_charge_notify_exit_summary,
            defaultBoolean = false,
            targetPackages = securityCenter,
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
        // 入口卡片用「音量条 / 亮度条」；开关标题保留「百分比」字样。
        val switchTitleRes = when (location) {
            PercentLocation.CC_VOLUME -> R.string.percent_volume_title
            PercentLocation.CC_BRIGHTNESS -> R.string.percent_brightness_title
            else -> R.string.percent_side_volume_title
        }
        specs += OptionSpec(
            key = PercentLocation.entryKey(location),
            type = OptionType.ARROW,
            titleRes = titleRes,
        )
        specs += OptionSpec(
            key = PercentLocation.masterKey(location),
            type = OptionType.SWITCH,
            titleRes = switchTitleRes,
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
