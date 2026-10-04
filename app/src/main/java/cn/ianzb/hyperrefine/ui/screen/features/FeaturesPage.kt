package cn.ianzb.hyperrefine.ui.screen.features

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.connect.ConnectKeys
import cn.ianzb.hyperrefine.hook.connect.MirrorKeys
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.miuix.MiuixAppLoad
import cn.ianzb.hyperrefine.hook.miuix.TopBarKeys
import cn.ianzb.hyperrefine.hook.misound.AppVolumeKeys
import cn.ianzb.hyperrefine.hook.systemui.glass.CcGlassKeys
import cn.ianzb.hyperrefine.hook.systemui.radius.CcRadiusKeys
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.prefs.OptionType
import cn.ianzb.hyperrefine.ui.component.QuickActionsAction
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionsPage
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSubPage

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
    val sections = remember(specs) {
        listOf(
            HookSection(
                titleRes = R.string.section_system_ui,
                specs = listOf(
                    specByKey(specs, KEY_APPEARANCE),
                ),
            ),
            HookSection(
                titleRes = R.string.section_security_center,
                specs = listOf(
                    specByKey(specs, KEY_SECURITY_CENTER),
                ),
            ),
            HookSection(
                titleRes = R.string.section_device_connect,
                specs = listOf(
                    specByKey(specs, KEY_DEVICE_CONNECT),
                ),
            ),
        )
    }
    // 「外观」为二级页，其下再分子页；通过嵌套 subPages 让搜索直达多级功能。
    val subPages = remember(specs) {
        listOf(
            HookSubPage(
                titleRes = R.string.feature_appearance,
                specs = listOf(
                    specByKey(specs, KEY_GLASS),
                    specByKey(specs, KEY_TOP_BAR),
                    specByKey(specs, KEY_CC_RADIUS),
                    specByKey(specs, KEY_DEVICE_CENTER),
                    specByKey(specs, PercentLocation.entryKey(PercentLocation.CC_BRIGHTNESS)),
                    specByKey(specs, PercentLocation.entryKey(PercentLocation.CC_VOLUME)),
                    specByKey(specs, PercentLocation.entryKey(PercentLocation.SIDE_VOLUME)),
                    specByKey(specs, KEY_APP_VOLUME),
                ),
                onOpen = { context.startActivity(Intent(context, AppearanceActivity::class.java)) },
                subPages = listOf(
                    HookSubPage(
                        titleRes = R.string.feature_glass,
                        specs = glassSpecs(specs),
                        onOpen = { context.startActivity(Intent(context, GlassActivity::class.java)) },
                    ),
                    HookSubPage(
                        titleRes = R.string.section_top_bar,
                        specs = topBarSpecs(specs),
                        onOpen = { context.startActivity(Intent(context, TopBarActivity::class.java)) },
                    ),
                    HookSubPage(
                        titleRes = R.string.cc_radius_title,
                        specs = cornerRadiusSpecs(specs),
                        onOpen = { context.startActivity(Intent(context, CornerRadiusActivity::class.java)) },
                    ),
                    HookSubPage(
                        titleRes = R.string.section_device_center,
                        specs = deviceCenterSpecs(specs),
                        onOpen = { context.startActivity(Intent(context, DeviceCenterActivity::class.java)) },
                    ),
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
                    HookSubPage(
                        titleRes = R.string.appearance_side_volume,
                        specs = locationSpecs(specs, PercentLocation.SIDE_VOLUME),
                        onOpen = { context.startActivity(percentStyleIntent(context, PercentLocation.SIDE_VOLUME)) },
                    ),
                    HookSubPage(
                        titleRes = R.string.app_volume_section,
                        specs = appVolumeSpecs(specs),
                        onOpen = { context.startActivity(Intent(context, AppVolumeActivity::class.java)) },
                    ),
                ),
            ),
            HookSubPage(
                titleRes = R.string.fast_charge_notify,
                specs = securityCenterSpecs(specs),
                onOpen = { context.startActivity(Intent(context, SecurityCenterActivity::class.java)) },
            ),
            HookSubPage(
                titleRes = R.string.device_connect,
                specs = deviceConnectSpecs(specs),
                onOpen = { context.startActivity(Intent(context, DeviceConnectActivity::class.java)) },
            ),
        )
    }

    HookOptionsPage(
        title = stringResource(R.string.tab_features),
        sections = sections,
        subPages = subPages,
        isBlurEnabled = isBlurEnabled,
        extraBottomPadding = extraBottomPadding,
        onArrowClick = { spec ->
            when (spec.key) {
                KEY_APPEARANCE ->
                    context.startActivity(Intent(context, AppearanceActivity::class.java))
                KEY_SECURITY_CENTER ->
                    context.startActivity(Intent(context, SecurityCenterActivity::class.java))
                KEY_DEVICE_CONNECT ->
                    context.startActivity(Intent(context, DeviceConnectActivity::class.java))
            }
        },
        topBarActions = {
            QuickActionsAction(
                listOf(
                    "com.android.systemui",
                    "com.miui.securitycenter",
                    "com.milink.service",
                    "com.xiaomi.mirror",
                    AppVolumeKeys.TARGET_PACKAGE,
                    *MiuixAppLoad.MIUIX_PACKAGES.toTypedArray(),
                )
            )
        },
    )
}

const val KEY_APPEARANCE = "feature_appearance"
const val KEY_GLASS = "feature_glass"
const val KEY_TOP_BAR = "feature_top_bar"
const val KEY_DEVICE_CENTER = "feature_device_center"
const val KEY_APP_VOLUME = "feature_app_volume"
const val KEY_DEVICE_CENTER_HIDE_MORE = "device_center_hide_more"
const val KEY_DEVICE_CENTER_LANDSCAPE_RIGHT = "device_center_landscape_right"
const val KEY_DEVICE_CENTER_SHRINK_HIT_AREA = "device_center_shrink_hit_area"
const val KEY_DEVICE_CENTER_CARD_GLASS = "device_center_card_glass"
const val KEY_SECURITY_CENTER = "feature_security_center"
const val KEY_DEVICE_CONNECT = "feature_device_connect"
const val KEY_CC_RADIUS = "feature_cc_radius"
const val KEY_FAST_CHARGE_ENTER = "security_center_fast_charge_enter_notify"
const val KEY_FAST_CHARGE_EXIT = "security_center_fast_charge_exit_notify"
const val SIDE_INSIDE_KEY = "side_volume_inside"
const val SIDE_LONGPRESS_KEY = "side_volume_longpress"

fun percentStyleIntent(context: Context, location: String): Intent =
    Intent(context, PercentStyleActivity::class.java)
        .putExtra(PercentStyleActivity.EXTRA_LOCATION, location)

/** 位置标识：与配置键前缀一致。 */
object PercentLocation {
    const val CC_VOLUME = "cc_volume"
    const val CC_BRIGHTNESS = "cc_brightness"
    const val SIDE_VOLUME = "side_volume"

    fun all(): List<String> = listOf(CC_VOLUME, CC_BRIGHTNESS, SIDE_VOLUME)

    fun masterKey(location: String): String = "${location}_percent"

    fun positionKey(location: String): String = "${location}_position"

    fun entryKey(location: String): String = "appearance_$location"
}

private fun specByKey(specs: List<OptionSpec>, key: String): OptionSpec =
    specs.first { it.key == key }

/** 各位置页面内（三级页面）的配置项，用于多级搜索直达。 */
private fun locationSpecs(specs: List<OptionSpec>, location: String): List<OptionSpec> {
    val keys = mutableListOf(
        PercentLocation.masterKey(location),
        PercentLocation.positionKey(location),
        "${location}_font_size",
        "${location}_font_weight",
        "${location}_follow_icon",
    )
    if (location == PercentLocation.SIDE_VOLUME) {
        keys += SIDE_INSIDE_KEY
        keys += SIDE_LONGPRESS_KEY
    keys += AppVolumeKeys.ENTRY
    keys += AppVolumeKeys.HIDE_FLOAT
    keys += AppVolumeKeys.ALWAYS_SHOW
    keys += AppVolumeKeys.AUTO_SHOW
    keys += AppVolumeKeys.HEIGHT_AUTO
    keys += AppVolumeKeys.HEIGHT_PERCENT
    keys += AppVolumeKeys.HIDE_PANEL_BG
    }
    return keys.map { specByKey(specs, it) }
}

/** 控制中心「圆角调整」二级页内的配置项，用于功能页搜索直达。 */
private fun cornerRadiusSpecs(specs: List<OptionSpec>): List<OptionSpec> =
    buildList {
        add(specByKey(specs, CcRadiusKeys.MASTER))
        add(specByKey(specs, CcRadiusKeys.COMPONENT))
        add(specByKey(specs, CcRadiusKeys.BACKGROUND))
        CcRadiusKeys.ITEMS.forEach { add(specByKey(specs, CcRadiusKeys.valueKey(it))) }
    }

/** 柔光玻璃二级页内的配置项，用于功能页搜索直达。 */
private fun glassSpecs(specs: List<OptionSpec>): List<OptionSpec> =
    listOf(
        specByKey(specs, CcGlassKeys.MASTER),
        specByKey(specs, CcGlassKeys.THEME_MATERIAL),
    )

/** 顶栏渐变二级页内的配置项，用于功能页搜索直达。 */
fun topBarSpecs(specs: List<OptionSpec>): List<OptionSpec> =
    listOf(
        specByKey(specs, TopBarKeys.KEY),
        specByKey(specs, TopBarKeys.STRENGTH),
        specByKey(specs, TopBarKeys.OPACITY),
    )

/** 融合设备中心二级页内的配置项，用于功能页搜索直达。 */
private fun deviceCenterSpecs(specs: List<OptionSpec>): List<OptionSpec> =
    listOf(
        specByKey(specs, KEY_DEVICE_CENTER_HIDE_MORE),
        specByKey(specs, KEY_DEVICE_CENTER_LANDSCAPE_RIGHT),
        specByKey(specs, KEY_DEVICE_CENTER_SHRINK_HIT_AREA),
        specByKey(specs, KEY_DEVICE_CENTER_CARD_GLASS),
    )

/** 多应用音量二级页内的配置项，用于功能页搜索直达。 */
private fun appVolumeSpecs(specs: List<OptionSpec>): List<OptionSpec> =
    listOf(
        specByKey(specs, AppVolumeKeys.ENTRY),
        specByKey(specs, AppVolumeKeys.HIDE_FLOAT),
        specByKey(specs, AppVolumeKeys.ALWAYS_SHOW),
        specByKey(specs, AppVolumeKeys.AUTO_SHOW),
        specByKey(specs, AppVolumeKeys.HEIGHT_AUTO),
        specByKey(specs, AppVolumeKeys.HEIGHT_PERCENT),
        specByKey(specs, AppVolumeKeys.HIDE_PANEL_BG),
    )

/** 安全服务二级页（快充加速通知）内的配置项，用于功能页搜索直达。 */
private fun securityCenterSpecs(specs: List<OptionSpec>): List<OptionSpec> =
    listOf(
        specByKey(specs, KEY_FAST_CHARGE_ENTER),
        specByKey(specs, KEY_FAST_CHARGE_EXIT),
    )

/** 设备互联二级页内的配置项，用于功能页搜索直达。 */
fun deviceConnectSpecs(specs: List<OptionSpec>): List<OptionSpec> =
    listOf(
        specByKey(specs, ConnectKeys.CROSS_DEVICE_NOTIFICATION),
        specByKey(specs, ConnectKeys.PORTRAIT_STREAMING),
        specByKey(specs, ConnectKeys.MILINK_MULTI_CHANNEL),
        specByKey(specs, MirrorKeys.FLOATING_WINDOW),
        specByKey(specs, MirrorKeys.FLOATING_RADIUS),
        specByKey(specs, MirrorKeys.HIDE_POLE),
        specByKey(specs, MirrorKeys.MINIMIZE_ON_SHADE),
        specByKey(specs, MirrorKeys.REFRESH_RATE),
        specByKey(specs, MirrorKeys.REFRESH_RATE_VALUE),
    )

/** 按键取功能配置项（供各功能子页复用同一份声明）。 */
fun featureSpec(key: String): OptionSpec =
    featureSpecs().first { it.key == key }

/** 功能页的全部配置项（App 启动时注册，供全局搜索与作用域申请使用）。 */
internal fun featureSpecs(): List<OptionSpec> {
    val systemUi = listOf("com.android.systemui")
    val securityCenter = listOf("com.miui.securitycenter")
    // 设备互联三项目标包：任一功能开启都同时申请 MiLink 与小米互联，避免 mirror 未授权导致流转/竖屏失效。
    val connect = listOf("com.milink.service", "com.xiaomi.mirror")
    // 妙享桌面增强只作用于小米互联。
    val mirror = listOf("com.xiaomi.mirror")
    // 顶栏渐变作用于设置 / 短信 / 联系人等 MIUIX 应用。
    val miuixApps = MiuixAppLoad.MIUIX_PACKAGES
    val specs = mutableListOf(
        OptionSpec(
            key = KEY_APPEARANCE,
            type = OptionType.ARROW,
            titleRes = R.string.feature_appearance,
        ),
        OptionSpec(
            key = KEY_GLASS,
            type = OptionType.ARROW,
            titleRes = R.string.feature_glass,
        ),
        OptionSpec(
            key = KEY_TOP_BAR,
            type = OptionType.ARROW,
            titleRes = R.string.section_top_bar,
        ),
        OptionSpec(
            key = KEY_DEVICE_CENTER,
            type = OptionType.ARROW,
            titleRes = R.string.section_device_center,
        ),
        OptionSpec(
            key = KEY_APP_VOLUME,
            type = OptionType.ARROW,
            titleRes = R.string.app_volume_section,
        ),
        OptionSpec(
            key = KEY_DEVICE_CENTER_HIDE_MORE,
            type = OptionType.SWITCH,
            titleRes = R.string.device_center_hide_more,
            summaryRes = R.string.device_center_hide_more_summary,
            defaultBoolean = false,
            targetPackages = systemUi,
            showStatus = true,
        ),
        OptionSpec(
            key = KEY_DEVICE_CENTER_LANDSCAPE_RIGHT,
            type = OptionType.SWITCH,
            titleRes = R.string.device_center_landscape_right,
            summaryRes = R.string.device_center_landscape_right_summary,
            defaultBoolean = false,
            targetPackages = systemUi,
            deviceScope = setOf(DeviceType.PHONE),
            showStatus = true,
        ),
        OptionSpec(
            key = KEY_DEVICE_CENTER_SHRINK_HIT_AREA,
            type = OptionType.SWITCH,
            titleRes = R.string.device_center_shrink_hit_area,
            summaryRes = R.string.device_center_shrink_hit_area_summary,
            defaultBoolean = false,
            targetPackages = systemUi,
            showStatus = true,
        ),
        OptionSpec(
            key = KEY_DEVICE_CENTER_CARD_GLASS,
            type = OptionType.SWITCH,
            titleRes = R.string.device_center_card_glass,
            summaryRes = R.string.device_center_card_glass_summary,
            defaultBoolean = false,
            targetPackages = listOf("com.milink.service"),
            showStatus = true,
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
            showStatus = true,
        ),
        OptionSpec(
            key = KEY_FAST_CHARGE_EXIT,
            type = OptionType.SWITCH,
            titleRes = R.string.fast_charge_notify_exit,
            summaryRes = R.string.fast_charge_notify_exit_summary,
            defaultBoolean = false,
            targetPackages = securityCenter,
            showStatus = true,
        ),
        OptionSpec(
            key = KEY_DEVICE_CONNECT,
            type = OptionType.ARROW,
            titleRes = R.string.device_connect,
        ),
        OptionSpec(
            key = ConnectKeys.CROSS_DEVICE_NOTIFICATION,
            type = OptionType.SWITCH,
            titleRes = R.string.connect_cross_device_notification,
            summaryRes = R.string.connect_cross_device_notification_summary,
            defaultBoolean = false,
            targetPackages = connect,
            deviceScope = setOf(DeviceType.PAD),
            showStatus = true,
        ),
        OptionSpec(
            key = ConnectKeys.PORTRAIT_STREAMING,
            type = OptionType.SWITCH,
            titleRes = R.string.connect_portrait_streaming,
            summaryRes = R.string.connect_portrait_streaming_summary,
            defaultBoolean = false,
            targetPackages = connect,
            deviceScope = setOf(DeviceType.PAD),
            showStatus = true,
        ),
        OptionSpec(
            key = ConnectKeys.MILINK_MULTI_CHANNEL,
            type = OptionType.SWITCH,
            titleRes = R.string.connect_milink_multi_channel,
            summaryRes = R.string.connect_milink_multi_channel_summary,
            defaultBoolean = false,
            targetPackages = connect,
            showStatus = true,
        ),
        OptionSpec(
            key = MirrorKeys.FLOATING_WINDOW,
            type = OptionType.SWITCH,
            titleRes = R.string.mirror_floating_window,
            summaryRes = R.string.mirror_floating_window_summary,
            defaultBoolean = false,
            targetPackages = mirror,
            deviceScope = setOf(DeviceType.PAD, DeviceType.FOLD),
            showStatus = true,
        ),
        OptionSpec(
            key = MirrorKeys.FLOATING_RADIUS,
            type = OptionType.SLIDER,
            titleRes = R.string.mirror_floating_radius,
            summaryRes = R.string.mirror_floating_radius_summary,
            defaultFloat = 9f,
            sliderMin = 0f,
            sliderMax = 60f,
            sliderStep = 1f,
            sliderDecimals = 0,
            sliderUnitRes = R.string.cc_radius_unit,
            sliderValueLabelRes = R.string.mirror_floating_radius,
            targetPackages = mirror,
            deviceScope = setOf(DeviceType.PAD, DeviceType.FOLD),
            dependsOn = MirrorKeys.FLOATING_WINDOW,
        ),
        OptionSpec(
            key = MirrorKeys.HIDE_POLE,
            type = OptionType.SWITCH,
            titleRes = R.string.mirror_hide_pole,
            summaryRes = R.string.mirror_hide_pole_summary,
            defaultBoolean = false,
            targetPackages = mirror,
            deviceScope = setOf(DeviceType.PAD, DeviceType.FOLD),
            dependsOn = MirrorKeys.FLOATING_WINDOW,
        ),
        OptionSpec(
            key = MirrorKeys.MINIMIZE_ON_SHADE,
            type = OptionType.SWITCH,
            titleRes = R.string.mirror_minimize_on_shade,
            summaryRes = R.string.mirror_minimize_on_shade_summary,
            defaultBoolean = false,
            targetPackages = mirror,
            deviceScope = setOf(DeviceType.PAD, DeviceType.FOLD),
            dependsOn = MirrorKeys.FLOATING_WINDOW,
        ),
        OptionSpec(
            key = MirrorKeys.REFRESH_RATE,
            type = OptionType.SWITCH,
            titleRes = R.string.mirror_refresh_rate,
            summaryRes = R.string.mirror_refresh_rate_summary,
            defaultBoolean = false,
            targetPackages = mirror,
            showStatus = true,
        ),
        OptionSpec(
            key = MirrorKeys.REFRESH_RATE_VALUE,
            type = OptionType.DROPDOWN,
            titleRes = R.string.mirror_refresh_rate_value,
            summaryRes = R.string.mirror_refresh_rate_value_summary,
            defaultString = "120",
            entryResIds = listOf(
                R.string.mirror_refresh_rate_60,
                R.string.mirror_refresh_rate_90,
                R.string.mirror_refresh_rate_120,
            ),
            entryValues = listOf("60", "90", "120"),
            targetPackages = mirror,
            dependsOn = MirrorKeys.REFRESH_RATE,
        ),
        OptionSpec(
            key = TopBarKeys.KEY,
            type = OptionType.SWITCH,
            titleRes = R.string.top_bar_gradient,
            summaryRes = R.string.top_bar_gradient_summary,
            defaultBoolean = false,
            targetPackages = miuixApps,
            showStatus = true,
        ),
        OptionSpec(
            key = TopBarKeys.STRENGTH,
            type = OptionType.SLIDER,
            titleRes = R.string.top_bar_gradient_strength,
            summaryRes = R.string.top_bar_gradient_strength_summary,
            defaultFloat = 10f,
            sliderMin = 0f,
            sliderMax = 100f,
            sliderStep = 1f,
            sliderDecimals = 0,
            sliderUnitRes = R.string.percent_unit,
            sliderValueLabelRes = R.string.top_bar_gradient_strength,
            targetPackages = miuixApps,
            dependsOn = TopBarKeys.KEY,
        ),
        OptionSpec(
            key = TopBarKeys.OPACITY,
            type = OptionType.SLIDER,
            titleRes = R.string.top_bar_gradient_opacity,
            summaryRes = R.string.top_bar_gradient_opacity_summary,
            defaultFloat = 100f,
            sliderMin = 0f,
            sliderMax = 100f,
            sliderStep = 1f,
            sliderDecimals = 0,
            sliderUnitRes = R.string.percent_unit,
            sliderValueLabelRes = R.string.top_bar_gradient_opacity,
            targetPackages = miuixApps,
            dependsOn = TopBarKeys.KEY,
        ),
    )
    specs += OptionSpec(
        key = CcGlassKeys.MASTER,
        type = OptionType.SWITCH,
        titleRes = R.string.cc_glass_master,
        summaryRes = R.string.cc_glass_master_summary,
        defaultBoolean = false,
        targetPackages = systemUi,
        showStatus = true,
    )
    specs += OptionSpec(
        key = CcGlassKeys.THEME_MATERIAL,
        type = OptionType.SWITCH,
        titleRes = R.string.cc_glass_theme_material,
        summaryRes = R.string.cc_glass_theme_material_summary,
        defaultBoolean = false,
        targetPackages = systemUi,
        showStatus = true,
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
            showStatus = true,
        )
        // 百分比样式子配置项（由所属开关控制启用 / 禁用；始终显示，不隐藏）。
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
            dependsOn = PercentLocation.masterKey(location),
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
            dependsOn = PercentLocation.masterKey(location),
        )
        specs += OptionSpec(
            key = "${location}_follow_icon",
            type = OptionType.SWITCH,
            titleRes = R.string.percent_follow_icon,
            summaryRes = followSummaryRes,
            defaultBoolean = true,
            targetPackages = systemUi,
            dependsOn = PercentLocation.masterKey(location),
        )
        specs += OptionSpec(
            key = PercentLocation.positionKey(location),
            type = OptionType.SLIDER,
            titleRes = R.string.percent_position,
            summaryRes = R.string.percent_position_summary,
            defaultFloat = 90f,
            sliderMin = 0f,
            sliderMax = 100f,
            sliderStep = 1f,
            sliderDecimals = 0,
            sliderUnitRes = R.string.percent_unit,
            sliderValueLabelRes = R.string.percent_position,
            targetPackages = systemUi,
            dependsOn = PercentLocation.masterKey(location),
        )
        if (location == PercentLocation.SIDE_VOLUME) {
            specs += OptionSpec(
                key = SIDE_INSIDE_KEY,
                type = OptionType.SWITCH,
                titleRes = R.string.side_volume_inside,
                summaryRes = R.string.side_volume_inside_summary,
                defaultBoolean = false,
                targetPackages = systemUi,
                dependsOn = PercentLocation.masterKey(PercentLocation.SIDE_VOLUME),
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
    specs += buildAppVolumeSpecs(systemUi)
    specs += ccRadiusSpecs(systemUi)
    return specs
}

/** 控制中心「圆角调整」配置项：总开关 + 组件/背景统一圆角 + 各子项自定义。 */
private fun ccRadiusSpecs(systemUi: List<String>): List<OptionSpec> = buildList {
    add(
        OptionSpec(
            key = KEY_CC_RADIUS,
            type = OptionType.ARROW,
            titleRes = R.string.cc_radius_title,
        )
    )
    add(
        OptionSpec(
            key = CcRadiusKeys.MASTER,
            type = OptionType.SWITCH,
            titleRes = R.string.cc_radius_title,
            summaryRes = R.string.cc_radius_master_summary,
            defaultBoolean = false,
            targetPackages = systemUi,
        )
    )
    add(
        ccRadiusUnifiedSpec(
            key = CcRadiusKeys.COMPONENT,
            titleRes = R.string.cc_radius_component,
            summaryRes = R.string.cc_radius_component_summary,
            defaultFloat = CcRadiusKeys.DEFAULT_COMPONENT,
            systemUi = systemUi,
        )
    )
    add(
        ccRadiusUnifiedSpec(
            key = CcRadiusKeys.BACKGROUND,
            titleRes = R.string.cc_radius_background,
            summaryRes = R.string.cc_radius_background_summary,
            defaultFloat = CcRadiusKeys.DEFAULT_BACKGROUND,
            systemUi = systemUi,
        )
    )
    CcRadiusKeys.ITEMS.forEach { item ->
        val isAppVolume = item == CcRadiusKeys.APP_VOLUME_PANEL || item == CcRadiusKeys.APP_VOLUME_BAR
        add(
            OptionSpec(
                key = CcRadiusKeys.valueKey(item),
                type = OptionType.SLIDER,
                titleRes = ccRadiusItemTitle(item),
                summaryRes = if (isAppVolume) {
                    R.string.cc_radius_app_volume_summary
                } else {
                    R.string.cc_radius_item_summary
                },
                defaultFloat = CcRadiusKeys.itemValueDefault(item),
                sliderMin = 0f,
                sliderMax = 60f,
                sliderStep = 1f,
                sliderDecimals = 0,
                sliderUnitRes = R.string.cc_radius_unit,
                sliderValueLabelRes = ccRadiusItemTitle(item),
                targetPackages = if (isAppVolume) listOf(AppVolumeKeys.TARGET_PACKAGE) else systemUi,
                // 单项自定义不依赖总开关：总开关关闭时仍可单独启用并生效。
                masterKey = CcRadiusKeys.customKey(item),
                masterDefault = CcRadiusKeys.itemCustomDefault(item),
            )
        )
    }
}

private fun ccRadiusUnifiedSpec(
    key: String,
    titleRes: Int,
    summaryRes: Int,
    defaultFloat: Float,
    systemUi: List<String>,
): OptionSpec = OptionSpec(
    key = key,
    type = OptionType.SLIDER,
    titleRes = titleRes,
    summaryRes = summaryRes,
    defaultFloat = defaultFloat,
    sliderMin = 0f,
    sliderMax = 60f,
    sliderStep = 1f,
    sliderDecimals = 0,
    sliderUnitRes = R.string.cc_radius_unit,
    sliderValueLabelRes = titleRes,
    targetPackages = systemUi,
    dependsOn = CcRadiusKeys.MASTER,
)

private fun ccRadiusItemTitle(item: String): Int = when (item) {
    CcRadiusKeys.HORIZONTAL_TILE -> R.string.cc_radius_horizontal_tile
    CcRadiusKeys.SMALL_TILE -> R.string.cc_radius_small_tile
    CcRadiusKeys.MEDIA -> R.string.cc_radius_media
    CcRadiusKeys.SLIDER_L1 -> R.string.cc_radius_slider_l1
    CcRadiusKeys.BRIGHTNESS_L2 -> R.string.cc_radius_brightness_l2
    CcRadiusKeys.SIDE_VOLUME_L1 -> R.string.cc_radius_side_volume_l1
    CcRadiusKeys.SIDE_VOLUME_L2 -> R.string.cc_radius_side_volume_l2
    CcRadiusKeys.CC_VOLUME_L2 -> R.string.cc_radius_cc_volume_l2
    CcRadiusKeys.RINGER -> R.string.cc_radius_ringer
    CcRadiusKeys.TIMER -> R.string.cc_radius_timer
    CcRadiusKeys.DEVICE_CENTER -> R.string.cc_radius_device_center
    CcRadiusKeys.APP_VOLUME_BAR -> R.string.cc_radius_app_volume_bar
    CcRadiusKeys.BRIGHTNESS_L2_BG -> R.string.cc_radius_brightness_l2_bg
    CcRadiusKeys.MEDIA_L2_BG -> R.string.cc_radius_media_l2_bg
    CcRadiusKeys.DETAIL_BG -> R.string.cc_radius_detail_bg
    CcRadiusKeys.CC_VOLUME_L2_BG -> R.string.cc_radius_cc_volume_l2_bg
    CcRadiusKeys.SIDE_VOLUME_L2_BG -> R.string.cc_radius_side_volume_l2_bg
    CcRadiusKeys.APP_VOLUME_PANEL -> R.string.cc_radius_app_volume_panel
    else -> R.string.cc_radius_horizontal_tile
}

/** 「多应用音量」配置项（侧边音量条）。 */
private fun buildAppVolumeSpecs(systemUi: List<String>): List<OptionSpec> = listOf(
    OptionSpec(
        key = AppVolumeKeys.ENTRY,
        type = OptionType.SWITCH,
        titleRes = R.string.app_volume_entry,
        summaryRes = R.string.app_volume_entry_summary,
        defaultBoolean = false,
        targetPackages = systemUi + AppVolumeKeys.TARGET_PACKAGE,
        showStatus = true,
    ),
    OptionSpec(
        key = AppVolumeKeys.HIDE_FLOAT,
        type = OptionType.SWITCH,
        titleRes = R.string.app_volume_hide_float,
        summaryRes = R.string.app_volume_hide_float_summary,
        defaultBoolean = true,
        targetPackages = listOf(AppVolumeKeys.TARGET_PACKAGE),
        dependsOn = AppVolumeKeys.ENTRY,
        showStatus = true,
    ),
    OptionSpec(
        key = AppVolumeKeys.ALWAYS_SHOW,
        type = OptionType.SWITCH,
        titleRes = R.string.app_volume_always_show,
        summaryRes = R.string.app_volume_always_show_summary,
        defaultBoolean = false,
        targetPackages = systemUi,
        dependsOn = AppVolumeKeys.ENTRY,
    ),
    OptionSpec(
        key = AppVolumeKeys.AUTO_SHOW,
        type = OptionType.SWITCH,
        titleRes = R.string.app_volume_auto_show,
        summaryRes = R.string.app_volume_auto_show_summary,
        defaultBoolean = false,
        targetPackages = systemUi,
        dependsOn = AppVolumeKeys.ENTRY,
    ),
    OptionSpec(
        key = AppVolumeKeys.HEIGHT_AUTO,
        type = OptionType.SWITCH,
        titleRes = R.string.app_volume_height_auto,
        summaryRes = R.string.app_volume_height_auto_summary,
        defaultBoolean = true,
        targetPackages = listOf(AppVolumeKeys.TARGET_PACKAGE),
        dependsOn = AppVolumeKeys.ENTRY,
    ),
    OptionSpec(
        key = AppVolumeKeys.HEIGHT_PERCENT,
        type = OptionType.SLIDER,
        titleRes = R.string.app_volume_height_percent,
        summaryRes = R.string.app_volume_height_percent_summary,
        defaultFloat = 50f,
        sliderMin = 0f,
        sliderMax = 100f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.percent_unit,
        sliderValueLabelRes = R.string.app_volume_height_percent,
        targetPackages = listOf(AppVolumeKeys.TARGET_PACKAGE),
        dependsOn = AppVolumeKeys.HEIGHT_AUTO,
        dependsOnValue = false,
    ),
    OptionSpec(
        key = AppVolumeKeys.HIDE_PANEL_BG,
        type = OptionType.SWITCH,
        titleRes = R.string.app_volume_hide_panel_bg,
        summaryRes = R.string.app_volume_hide_panel_bg_summary,
        defaultBoolean = false,
        targetPackages = listOf(AppVolumeKeys.TARGET_PACKAGE),
        dependsOn = AppVolumeKeys.ENTRY,
    ),
)
