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
import cn.ianzb.hyperrefine.hook.browser.BrowserKeys
import cn.ianzb.hyperrefine.hook.connect.ConnectKeys
import cn.ianzb.hyperrefine.hook.connect.MirrorKeys
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.miuix.MiuixAppLoad
import cn.ianzb.hyperrefine.hook.miuix.TopBarKeys
import cn.ianzb.hyperrefine.hook.misound.AppVolumeKeys
import cn.ianzb.hyperrefine.hook.passkey.PasskeyKeys
import cn.ianzb.hyperrefine.hook.systemui.VolumeBarKeys
import cn.ianzb.hyperrefine.hook.systemui.glass.CcGlassKeys
import cn.ianzb.hyperrefine.hook.systemui.radius.CcRadiusKeys
import cn.ianzb.hyperrefine.hook.weather.WeatherKeys
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
            HookSection(
                titleRes = R.string.section_passkey,
                specs = listOf(
                    specByKey(specs, KEY_PASSKEY),
                ),
            ),
            HookSection(
                titleRes = R.string.section_browser,
                specs = listOf(
                    specByKey(specs, KEY_BROWSER),
                ),
            ),
            HookSection(
                titleRes = R.string.section_experimental,
                specs = listOf(
                    specByKey(specs, KEY_EXPERIMENTAL),
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
                    specByKey(specs, KEY_LAYOUT_ADJUST),
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
                    HookSubPage(
                        titleRes = R.string.section_layout_adjust,
                        specs = listOf(specByKey(specs, KEY_VOLUME_BAR)),
                        onOpen = { context.startActivity(Intent(context, LayoutAdjustActivity::class.java)) },
                        subPages = listOf(
                            HookSubPage(
                                titleRes = R.string.volume_bar_section,
                                specs = volumeBarSpecs(specs),
                                onOpen = { context.startActivity(Intent(context, VolumeBarActivity::class.java)) },
                            ),
                        ),
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
            HookSubPage(
                titleRes = R.string.feature_passkey,
                specs = passkeySpecs(specs),
                onOpen = { context.startActivity(Intent(context, PasskeyActivity::class.java)) },
            ),
            HookSubPage(
                titleRes = R.string.feature_browser,
                specs = browserSpecs(specs),
                onOpen = { context.startActivity(Intent(context, BrowserActivity::class.java)) },
            ),
            HookSubPage(
                titleRes = R.string.section_experimental,
                specs = listOf(specByKey(specs, KEY_WEATHER)),
                onOpen = { context.startActivity(Intent(context, ExperimentalActivity::class.java)) },
                subPages = listOf(
                    HookSubPage(
                        titleRes = R.string.feature_weather,
                        specs = weatherSpecs(specs),
                        onOpen = { context.startActivity(Intent(context, WeatherActivity::class.java)) },
                    ),
                ),
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
                KEY_PASSKEY ->
                    context.startActivity(Intent(context, PasskeyActivity::class.java))
                KEY_BROWSER ->
                    context.startActivity(Intent(context, BrowserActivity::class.java))
                KEY_EXPERIMENTAL ->
                    context.startActivity(Intent(context, ExperimentalActivity::class.java))
                KEY_WEATHER ->
                    context.startActivity(Intent(context, WeatherActivity::class.java))
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
                    BrowserKeys.PKG_MISHARE,
                    BrowserKeys.PKG_AI_ENGINE,
                    BrowserKeys.PKG_VOICE_ASSIST,
                    BrowserKeys.PKG_MARKET,
                    BrowserKeys.PKG_CONTENT_CATCHER,
                    BrowserKeys.PKG_AI_VISION,
                    PasskeyKeys.TARGET_SCANNER,
                )
            )
        },
    )
}

const val KEY_APPEARANCE = "feature_appearance"
const val KEY_GLASS = "feature_glass"
const val KEY_TOP_BAR = "feature_top_bar"
const val KEY_EXPERIMENTAL = "feature_experimental"
const val KEY_WEATHER = "feature_weather"
const val KEY_LAYOUT_ADJUST = "feature_layout_adjust"
const val KEY_VOLUME_BAR = "feature_volume_bar"
const val KEY_DEVICE_CENTER = "feature_device_center"
const val KEY_APP_VOLUME = "feature_app_volume"
const val KEY_DEVICE_CENTER_HIDE_MORE = "device_center_hide_more"
const val KEY_DEVICE_CENTER_LANDSCAPE_RIGHT = "device_center_landscape_right"
const val KEY_DEVICE_CENTER_SHRINK_HIT_AREA = "device_center_shrink_hit_area"
const val KEY_DEVICE_CENTER_CARD_GLASS = "device_center_card_glass"
const val KEY_SECURITY_CENTER = "feature_security_center"
const val KEY_DEVICE_CONNECT = "feature_device_connect"
const val KEY_PASSKEY = "feature_passkey"
const val KEY_BROWSER = "feature_browser"
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
        specByKey(specs, TopBarKeys.ADVANCED),
        specByKey(specs, TopBarKeys.ADVANCED_NOTES),
        specByKey(specs, TopBarKeys.ADVANCED_CALCULATOR),
    )

/** 天气高级外观二级页内的配置项，用于功能页搜索直达。 */
fun weatherSpecs(specs: List<OptionSpec>): List<OptionSpec> =
    listOf(
        specByKey(specs, WeatherKeys.ADVANCED),
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

/** 「音量条」二级页内的配置项，用于功能页搜索直达。 */
private fun volumeBarSpecs(specs: List<OptionSpec>): List<OptionSpec> =
    listOf(
        specByKey(specs, VolumeBarKeys.PANEL_BG),
        specByKey(specs, VolumeBarKeys.PANEL_PAD_VERTICAL),
        specByKey(specs, VolumeBarKeys.PANEL_PAD_HORIZONTAL),
        specByKey(specs, VolumeBarKeys.BAR_SIZE),
        specByKey(specs, VolumeBarKeys.BAR_HEIGHT),
        specByKey(specs, VolumeBarKeys.BAR_WIDTH),
        specByKey(specs, VolumeBarKeys.SPACING),
        specByKey(specs, VolumeBarKeys.COLUMN_SPACING),
        specByKey(specs, VolumeBarKeys.SIDE_POS),
        specByKey(specs, VolumeBarKeys.SIDE_POS_PORTRAIT),
        specByKey(specs, VolumeBarKeys.SIDE_POS_LANDSCAPE),
        specByKey(specs, VolumeBarKeys.L2_POS),
        specByKey(specs, VolumeBarKeys.L2_POS_PORTRAIT),
        specByKey(specs, VolumeBarKeys.L2_POS_LANDSCAPE),
        specByKey(specs, VolumeBarKeys.AUTO_BALANCE_PORTRAIT),
        specByKey(specs, VolumeBarKeys.AUTO_BALANCE_LANDSCAPE),
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
        specByKey(specs, ConnectKeys.DISCOVERY_FREQUENCY),
        specByKey(specs, MirrorKeys.FLOATING_WINDOW),
        specByKey(specs, MirrorKeys.FLOATING_RADIUS),
        specByKey(specs, MirrorKeys.HIDE_POLE),
        specByKey(specs, MirrorKeys.MINIMIZE_ON_SHADE),
        specByKey(specs, MirrorKeys.REFRESH_RATE),
        specByKey(specs, MirrorKeys.REFRESH_RATE_VALUE),
    )

/** 密码二级页内的配置项，用于功能页搜索直达。 */
fun passkeySpecs(specs: List<OptionSpec>): List<OptionSpec> = listOf(
    specByKey(specs, PasskeyKeys.MASTER),
    specByKey(specs, PasskeyKeys.SYSTEM_SERVER),
    specByKey(specs, PasskeyKeys.SETTINGS),
    specByKey(specs, PasskeyKeys.BLOCK_SECURITY_CENTER),
    specByKey(specs, PasskeyKeys.SCANNER),
)

/** 浏览器二级页内的配置项，用于功能页搜索直达。 */
fun browserSpecs(specs: List<OptionSpec>): List<OptionSpec> = listOf(
    specByKey(specs, BrowserKeys.MASTER),
    specByKey(specs, BrowserKeys.INTENT_INTERCEPT),
    specByKey(specs, BrowserKeys.FAKE_INSTALLED),
    specByKey(specs, BrowserKeys.PENDING_INTENT),
    specByKey(specs, BrowserKeys.NOTIFICATION_ICON),
    specByKey(specs, BrowserKeys.MISHARE),
    specByKey(specs, BrowserKeys.VOICE_ASSIST),
    specByKey(specs, BrowserKeys.COPY_DIRECT),
    specByKey(specs, BrowserKeys.ROUTER_SETTINGS),
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
    // 密码：设置 / 安全中心 / 扫描器（system_server 部分随设备重启生效，不在此申请）。
    val passkeyTargets = listOf(
        PasskeyKeys.TARGET_SETTINGS,
        PasskeyKeys.TARGET_SECURITY_CENTER,
        PasskeyKeys.TARGET_SCANNER,
    )
    // 浏览器：小米互传 / AI 引擎 / 超级小爱 / 设置 / 应用商店 / 内容捕手 / AI 视觉助手。
    val browserTargets = listOf(
        BrowserKeys.PKG_MISHARE,
        BrowserKeys.PKG_AI_ENGINE,
        BrowserKeys.PKG_VOICE_ASSIST,
        BrowserKeys.PKG_SETTINGS,
        BrowserKeys.PKG_MARKET,
        BrowserKeys.PKG_CONTENT_CATCHER,
        BrowserKeys.PKG_AI_VISION,
    )
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
            key = KEY_EXPERIMENTAL,
            type = OptionType.ARROW,
            titleRes = R.string.section_experimental,
        ),
        OptionSpec(
            key = KEY_WEATHER,
            type = OptionType.ARROW,
            titleRes = R.string.feature_weather,
        ),
        OptionSpec(
            key = KEY_LAYOUT_ADJUST,
            type = OptionType.ARROW,
            titleRes = R.string.section_layout_adjust,
        ),
        OptionSpec(
            key = KEY_VOLUME_BAR,
            type = OptionType.ARROW,
            titleRes = R.string.volume_bar_section,
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
            key = ConnectKeys.DISCOVERY_FREQUENCY,
            type = OptionType.SWITCH,
            titleRes = R.string.connect_discovery_frequency,
            summaryRes = R.string.connect_discovery_frequency_summary,
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
            defaultFloat = MirrorKeys.FLOATING_RADIUS_DEFAULT.toFloat(),
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
            sliderUnitRes = R.string.dp_unit,
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
        OptionSpec(
            key = TopBarKeys.ADVANCED,
            type = OptionType.SWITCH,
            titleRes = R.string.top_bar_advanced,
            summaryRes = R.string.top_bar_advanced_summary,
            defaultBoolean = true,
            targetPackages = listOf("com.miui.notes", "com.miui.calculator"),
            dependsOn = TopBarKeys.KEY,
        ),
        OptionSpec(
            key = TopBarKeys.ADVANCED_NOTES,
            type = OptionType.SWITCH,
            titleRes = R.string.top_bar_advanced_notes,
            summaryRes = R.string.top_bar_advanced_notes_summary,
            defaultBoolean = true,
            targetPackages = listOf("com.miui.notes"),
            dependsOn = TopBarKeys.ADVANCED,
        ),
        OptionSpec(
            key = TopBarKeys.ADVANCED_CALCULATOR,
            type = OptionType.SWITCH,
            titleRes = R.string.top_bar_advanced_calculator,
            summaryRes = R.string.top_bar_advanced_calculator_summary,
            defaultBoolean = true,
            targetPackages = listOf("com.miui.calculator"),
            dependsOn = TopBarKeys.ADVANCED,
        ),
        OptionSpec(
            key = WeatherKeys.ADVANCED,
            type = OptionType.SWITCH,
            titleRes = R.string.weather_advanced,
            summaryRes = R.string.weather_advanced_summary,
            defaultBoolean = false,
            targetPackages = listOf(WeatherKeys.TARGET_PACKAGE),
            showStatus = true,
        ),
    )
    specs += passkeyFeatureSpecs(passkeyTargets)
    specs += browserFeatureSpecs(browserTargets)
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
        specs += buildVolumeBarSpecs(systemUi)
        specs += buildSidePosSpecs(systemUi)
        specs += buildL2PosSpecs(systemUi)
        specs += buildAutoBalanceSpecs(systemUi)
    specs += ccRadiusSpecs(systemUi)
    return specs
}

/** 密码页配置项（通行密钥修复）。 */
private fun passkeyFeatureSpecs(targets: List<String>): List<OptionSpec> = listOf(
    OptionSpec(key = KEY_PASSKEY, type = OptionType.ARROW, titleRes = R.string.feature_passkey),
    OptionSpec(
        key = PasskeyKeys.MASTER, type = OptionType.SWITCH,
        titleRes = R.string.passkey_master, summaryRes = R.string.passkey_master_summary,
        defaultBoolean = false, targetPackages = targets, showStatus = true,
    ),
    OptionSpec(
        key = PasskeyKeys.SYSTEM_SERVER, type = OptionType.SWITCH,
        titleRes = R.string.passkey_system_server, summaryRes = R.string.passkey_system_server_summary,
        defaultBoolean = true, targetPackages = targets, dependsOn = PasskeyKeys.MASTER, showStatus = true,
    ),
    OptionSpec(
        key = PasskeyKeys.SETTINGS, type = OptionType.SWITCH,
        titleRes = R.string.passkey_settings, summaryRes = R.string.passkey_settings_summary,
        defaultBoolean = true, targetPackages = targets, dependsOn = PasskeyKeys.MASTER, showStatus = true,
    ),
    OptionSpec(
        key = PasskeyKeys.BLOCK_SECURITY_CENTER, type = OptionType.SWITCH,
        titleRes = R.string.passkey_block_security_center,
        summaryRes = R.string.passkey_block_security_center_summary,
        defaultBoolean = true, targetPackages = targets, dependsOn = PasskeyKeys.MASTER, showStatus = true,
    ),
    OptionSpec(
        key = PasskeyKeys.SCANNER, type = OptionType.SWITCH,
        titleRes = R.string.passkey_scanner, summaryRes = R.string.passkey_scanner_summary,
        defaultBoolean = true, targetPackages = targets, dependsOn = PasskeyKeys.MASTER, showStatus = true,
    ),
)

/** 浏览器页配置项。 */
private fun browserFeatureSpecs(targets: List<String>): List<OptionSpec> = listOf(
    OptionSpec(key = KEY_BROWSER, type = OptionType.ARROW, titleRes = R.string.feature_browser),
    OptionSpec(
        key = BrowserKeys.MASTER, type = OptionType.SWITCH,
        titleRes = R.string.browser_master, summaryRes = R.string.browser_master_summary,
        defaultBoolean = false, targetPackages = targets, showStatus = true,
    ),
    OptionSpec(
        key = BrowserKeys.INTENT_INTERCEPT, type = OptionType.SWITCH,
        titleRes = R.string.browser_intent_intercept, summaryRes = R.string.browser_intent_intercept_summary,
        defaultBoolean = true, targetPackages = targets, dependsOn = BrowserKeys.MASTER, showStatus = true,
    ),
    OptionSpec(
        key = BrowserKeys.FAKE_INSTALLED, type = OptionType.SWITCH,
        titleRes = R.string.browser_fake_installed, summaryRes = R.string.browser_fake_installed_summary,
        defaultBoolean = true, targetPackages = targets, dependsOn = BrowserKeys.MASTER, showStatus = true,
    ),
    OptionSpec(
        key = BrowserKeys.PENDING_INTENT, type = OptionType.SWITCH,
        titleRes = R.string.browser_pending_intent, summaryRes = R.string.browser_pending_intent_summary,
        defaultBoolean = true, targetPackages = targets, dependsOn = BrowserKeys.MASTER, showStatus = true,
    ),
    OptionSpec(
        key = BrowserKeys.NOTIFICATION_ICON, type = OptionType.SWITCH,
        titleRes = R.string.browser_notification_icon, summaryRes = R.string.browser_notification_icon_summary,
        defaultBoolean = true, targetPackages = targets, dependsOn = BrowserKeys.MASTER, showStatus = true,
    ),
    OptionSpec(
        key = BrowserKeys.MISHARE, type = OptionType.SWITCH,
        titleRes = R.string.browser_mishare, summaryRes = R.string.browser_mishare_summary,
        defaultBoolean = true, targetPackages = targets, dependsOn = BrowserKeys.MASTER, showStatus = true,
    ),
    OptionSpec(
        key = BrowserKeys.VOICE_ASSIST, type = OptionType.SWITCH,
        titleRes = R.string.browser_voice_assist, summaryRes = R.string.browser_voice_assist_summary,
        defaultBoolean = true, targetPackages = targets, dependsOn = BrowserKeys.MASTER, showStatus = true,
    ),
    OptionSpec(
        key = BrowserKeys.COPY_DIRECT, type = OptionType.SWITCH,
        titleRes = R.string.browser_copy_direct, summaryRes = R.string.browser_copy_direct_summary,
        defaultBoolean = true, targetPackages = targets, dependsOn = BrowserKeys.MASTER, showStatus = true,
    ),
    OptionSpec(
        key = BrowserKeys.ROUTER_SETTINGS, type = OptionType.SWITCH,
        titleRes = R.string.browser_router_settings, summaryRes = R.string.browser_router_settings_summary,
        defaultBoolean = true, targetPackages = targets, dependsOn = BrowserKeys.MASTER, showStatus = true,
    ),
)

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

/** 侧边音量条竖直位置调节配置项（系统界面）。 */
private fun buildSidePosSpecs(systemUi: List<String>): List<OptionSpec> = listOf(
    OptionSpec(
        key = VolumeBarKeys.SIDE_POS,
        type = OptionType.SWITCH,
        titleRes = R.string.side_volume_pos,
        summaryRes = R.string.side_volume_pos_summary,
        defaultBoolean = false,
        targetPackages = systemUi,
        showStatus = true,
    ),
    OptionSpec(
        key = VolumeBarKeys.SIDE_POS_PORTRAIT,
        type = OptionType.SLIDER,
        titleRes = R.string.side_volume_pos_portrait,
        summaryRes = R.string.side_volume_pos_percent_summary,
        defaultFloat = VolumeBarKeys.DEFAULT_SIDE_POS_PORTRAIT,
        sliderMin = 0f,
        sliderMax = 100f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.percent_unit,
        sliderValueLabelRes = R.string.side_volume_pos_portrait,
        targetPackages = systemUi,
        dependsOn = VolumeBarKeys.SIDE_POS,
    ),
    OptionSpec(
        key = VolumeBarKeys.SIDE_POS_LANDSCAPE,
        type = OptionType.SLIDER,
        titleRes = R.string.side_volume_pos_landscape,
        summaryRes = R.string.side_volume_pos_percent_summary,
        defaultFloat = VolumeBarKeys.DEFAULT_SIDE_POS_LANDSCAPE,
        sliderMin = 0f,
        sliderMax = 100f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.percent_unit,
        sliderValueLabelRes = R.string.side_volume_pos_landscape,
        targetPackages = systemUi,
        dependsOn = VolumeBarKeys.SIDE_POS,
    ),
)

/** 侧边音量条二级（展开）面板整体竖直位置调节配置项（系统界面）。 */
private fun buildL2PosSpecs(systemUi: List<String>): List<OptionSpec> = listOf(
    OptionSpec(
        key = VolumeBarKeys.L2_POS,
        type = OptionType.SWITCH,
        titleRes = R.string.volume_bar_l2_pos,
        summaryRes = R.string.volume_bar_l2_pos_summary,
        defaultBoolean = false,
        targetPackages = systemUi,
        showStatus = true,
    ),
    OptionSpec(
        key = VolumeBarKeys.L2_POS_PORTRAIT,
        type = OptionType.SLIDER,
        titleRes = R.string.volume_bar_l2_pos_portrait,
        summaryRes = R.string.volume_bar_l2_pos_percent_summary,
        defaultFloat = VolumeBarKeys.DEFAULT_L2_POS_PORTRAIT,
        sliderMin = 0f,
        sliderMax = 100f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.percent_unit,
        sliderValueLabelRes = R.string.volume_bar_l2_pos_portrait,
        targetPackages = systemUi,
        dependsOn = VolumeBarKeys.L2_POS,
    ),
    OptionSpec(
        key = VolumeBarKeys.L2_POS_LANDSCAPE,
        type = OptionType.SLIDER,
        titleRes = R.string.volume_bar_l2_pos_landscape,
        summaryRes = R.string.volume_bar_l2_pos_percent_summary,
        defaultFloat = VolumeBarKeys.DEFAULT_L2_POS_LANDSCAPE,
        sliderMin = 0f,
        sliderMax = 100f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.percent_unit,
        sliderValueLabelRes = R.string.volume_bar_l2_pos_landscape,
        targetPackages = systemUi,
        dependsOn = VolumeBarKeys.L2_POS,
    ),
)

/** 显示多应用音量入口时是否自动平衡整体平均高度（系统界面，竖屏 / 横屏分别控制）。 */
private fun buildAutoBalanceSpecs(systemUi: List<String>): List<OptionSpec> = listOf(
    OptionSpec(
        key = VolumeBarKeys.AUTO_BALANCE_PORTRAIT,
        type = OptionType.SWITCH,
        titleRes = R.string.volume_bar_auto_balance_portrait,
        summaryRes = R.string.volume_bar_auto_balance_summary,
        defaultBoolean = true,
        targetPackages = systemUi,
    ),
    OptionSpec(
        key = VolumeBarKeys.AUTO_BALANCE_LANDSCAPE,
        type = OptionType.SWITCH,
        titleRes = R.string.volume_bar_auto_balance_landscape,
        summaryRes = R.string.volume_bar_auto_balance_summary,
        defaultBoolean = true,
        targetPackages = systemUi,
    ),
)

/** 「音量条」配置项（系统界面：多应用面板 + 官方侧边音量条）。 */
private fun buildVolumeBarSpecs(systemUi: List<String>): List<OptionSpec> = listOf(
    OptionSpec(
        key = VolumeBarKeys.PANEL_BG,
        type = OptionType.SWITCH,
        titleRes = R.string.volume_bar_panel_bg,
        summaryRes = R.string.volume_bar_panel_bg_summary,
        defaultBoolean = false,
        targetPackages = systemUi,
    ),
    OptionSpec(
        key = VolumeBarKeys.PANEL_PAD_VERTICAL,
        type = OptionType.SLIDER,
        titleRes = R.string.volume_bar_panel_pad_vertical,
        summaryRes = R.string.volume_bar_panel_pad_vertical_summary,
        defaultFloat = VolumeBarKeys.DEFAULT_PAD,
        sliderMin = 0f,
        sliderMax = 48f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.cc_radius_unit,
        sliderValueLabelRes = R.string.volume_bar_panel_pad_vertical,
        targetPackages = systemUi,
        dependsOn = VolumeBarKeys.PANEL_BG,
    ),
    OptionSpec(
        key = VolumeBarKeys.PANEL_PAD_HORIZONTAL,
        type = OptionType.SLIDER,
        titleRes = R.string.volume_bar_panel_pad_horizontal,
        summaryRes = R.string.volume_bar_panel_pad_horizontal_summary,
        defaultFloat = VolumeBarKeys.DEFAULT_PAD,
        sliderMin = 0f,
        sliderMax = 48f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.cc_radius_unit,
        sliderValueLabelRes = R.string.volume_bar_panel_pad_horizontal,
        targetPackages = systemUi,
        dependsOn = VolumeBarKeys.PANEL_BG,
    ),
    OptionSpec(
        key = VolumeBarKeys.BAR_SIZE,
        type = OptionType.SWITCH,
        titleRes = R.string.volume_bar_size,
        summaryRes = R.string.volume_bar_size_summary,
        defaultBoolean = false,
        targetPackages = systemUi,
        showStatus = true,
    ),
    OptionSpec(
        key = VolumeBarKeys.BAR_HEIGHT,
        type = OptionType.SLIDER,
        titleRes = R.string.volume_bar_height,
        summaryRes = R.string.volume_bar_height_summary,
        defaultFloat = VolumeBarKeys.DEFAULT_HEIGHT,
        sliderMin = 80f,
        sliderMax = 320f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.cc_radius_unit,
        sliderValueLabelRes = R.string.volume_bar_height,
        targetPackages = systemUi,
        dependsOn = VolumeBarKeys.BAR_SIZE,
    ),
    OptionSpec(
        key = VolumeBarKeys.BAR_WIDTH,
        type = OptionType.SLIDER,
        titleRes = R.string.volume_bar_width,
        summaryRes = R.string.volume_bar_width_summary,
        defaultFloat = VolumeBarKeys.DEFAULT_WIDTH,
        sliderMin = 20f,
        sliderMax = 140f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.cc_radius_unit,
        sliderValueLabelRes = R.string.volume_bar_width,
        targetPackages = systemUi,
        dependsOn = VolumeBarKeys.BAR_SIZE,
    ),
    OptionSpec(
        key = VolumeBarKeys.SPACING,
        type = OptionType.SWITCH,
        titleRes = R.string.volume_bar_spacing,
        summaryRes = R.string.volume_bar_spacing_summary,
        defaultBoolean = false,
        targetPackages = systemUi,
    ),
    OptionSpec(
        key = VolumeBarKeys.COLUMN_SPACING,
        type = OptionType.SLIDER,
        titleRes = R.string.volume_bar_column_spacing,
        summaryRes = R.string.volume_bar_column_spacing_summary,
        defaultFloat = VolumeBarKeys.DEFAULT_SPACING,
        sliderMin = 0f,
        sliderMax = 48f,
        sliderStep = 1f,
        sliderDecimals = 0,
        sliderUnitRes = R.string.cc_radius_unit,
        sliderValueLabelRes = R.string.volume_bar_column_spacing,
        targetPackages = systemUi,
        dependsOn = VolumeBarKeys.SPACING,
    ),
)
