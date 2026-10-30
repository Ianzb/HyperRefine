package cn.ianzb.hyperrefine.ui.component.statusbar

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.BatteryManager
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconComponent
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconComponentConfig
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconCondition
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconKeys
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconLogic
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconMode
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconRegistry
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarKeys
import cn.ianzb.hyperrefine.prefs.ConfigState
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text as MiuixText

@StringRes
fun componentTitleRes(id: String): Int = when (id) {
    StatusBarIconRegistry.CLOCK -> R.string.sb_icon_clock
    StatusBarIconRegistry.PAD_CLOCK -> R.string.sb_icon_pad_clock
    StatusBarIconRegistry.OPERATOR -> R.string.sb_icon_operator
    StatusBarIconRegistry.PRIVACY -> R.string.sb_icon_privacy
    StatusBarIconRegistry.NOTIFICATIONS -> R.string.sb_icon_notifications
    StatusBarIconRegistry.NETWORK_SPEED -> R.string.sb_icon_network_speed
    StatusBarIconRegistry.WIFI -> R.string.sb_icon_wifi
    StatusBarIconRegistry.MOBILE -> R.string.sb_icon_mobile
    StatusBarIconRegistry.BLUETOOTH -> R.string.sb_icon_bluetooth
    StatusBarIconRegistry.ALARM -> R.string.sb_icon_alarm
    StatusBarIconRegistry.BATTERY -> R.string.sb_icon_battery
    StatusBarIconRegistry.LOCATION -> R.string.sb_icon_location
    StatusBarIconRegistry.HEADSET -> R.string.sb_icon_headset
    StatusBarIconRegistry.VPN -> R.string.sb_icon_vpn
    StatusBarIconRegistry.ZEN -> R.string.sb_icon_zen
    StatusBarIconRegistry.MUTE -> R.string.sb_icon_mute
    StatusBarIconRegistry.AIRPLANE -> R.string.sb_icon_airplane
    StatusBarIconRegistry.HOTSPOT -> R.string.sb_icon_hotspot
    StatusBarIconRegistry.ETHERNET -> R.string.sb_icon_ethernet
    StatusBarIconRegistry.NFC -> R.string.sb_icon_nfc
    StatusBarIconRegistry.CAST -> R.string.sb_icon_cast
    StatusBarIconRegistry.IME -> R.string.sb_icon_ime
    StatusBarIconRegistry.MANAGED_PROFILE -> R.string.sb_icon_managed_profile
    StatusBarIconRegistry.MISSED_CALL -> R.string.sb_icon_missed_call
    StatusBarIconRegistry.MICPHONE -> R.string.sb_icon_micphone
    StatusBarIconRegistry.MIKEY -> R.string.sb_icon_mikey
    StatusBarIconRegistry.PRIVACY_MODE -> R.string.sb_icon_privacy_mode
    StatusBarIconRegistry.GPS -> R.string.sb_icon_gps
    StatusBarIconRegistry.SECOND_SPACE -> R.string.sb_icon_second_space
    StatusBarIconRegistry.STEALTH -> R.string.sb_icon_stealth
    StatusBarIconRegistry.TTY -> R.string.sb_icon_tty
    StatusBarIconRegistry.BLUETOOTH_BATTERY -> R.string.sb_icon_bluetooth_battery
    StatusBarIconRegistry.SOUND_BOX_GROUP -> R.string.sb_icon_sound_box_group
    StatusBarIconRegistry.STEREO -> R.string.sb_icon_stereo
    StatusBarIconRegistry.SOUND_BOX_SCREEN -> R.string.sb_icon_sound_box_screen
    StatusBarIconRegistry.SOUND_BOX -> R.string.sb_icon_sound_box
    StatusBarIconRegistry.WIRELESS_HEADSET -> R.string.sb_icon_wireless_headset
    StatusBarIconRegistry.VOLUME -> R.string.sb_icon_volume
    StatusBarIconRegistry.DIST_COMPUTE -> R.string.sb_icon_dist_compute
    StatusBarIconRegistry.CAMERA -> R.string.sb_icon_camera
    StatusBarIconRegistry.GLASSES -> R.string.sb_icon_glasses
    StatusBarIconRegistry.CAR -> R.string.sb_icon_car
    StatusBarIconRegistry.TV -> R.string.sb_icon_tv
    StatusBarIconRegistry.PC -> R.string.sb_icon_pc
    StatusBarIconRegistry.PAD -> R.string.sb_icon_pad
    StatusBarIconRegistry.PHONE -> R.string.sb_icon_phone
    StatusBarIconRegistry.NO_SIM -> R.string.sb_icon_no_sim
    StatusBarIconRegistry.HD -> R.string.sb_icon_hd
    else -> R.string.sb_icon_title
}

@StringRes
fun categoryTitleRes(id: String): Int = when (id) {
    StatusBarIconRegistry.CLOCK,
    StatusBarIconRegistry.PAD_CLOCK -> R.string.sb_category_clock
    StatusBarIconRegistry.OPERATOR -> R.string.sb_category_operator
    StatusBarIconRegistry.PRIVACY -> R.string.sb_category_privacy
    StatusBarIconRegistry.NOTIFICATIONS -> R.string.sb_category_notifications
    StatusBarIconRegistry.NETWORK_SPEED,
    StatusBarIconRegistry.WIFI,
    StatusBarIconRegistry.MOBILE,
    StatusBarIconRegistry.BLUETOOTH,
    StatusBarIconRegistry.VPN,
    StatusBarIconRegistry.AIRPLANE,
    StatusBarIconRegistry.HOTSPOT,
    StatusBarIconRegistry.ETHERNET,
    StatusBarIconRegistry.NFC,
    StatusBarIconRegistry.CAST -> R.string.sb_category_network
    StatusBarIconRegistry.BATTERY -> R.string.sb_category_battery
    StatusBarIconRegistry.BLUETOOTH_BATTERY,
    StatusBarIconRegistry.SOUND_BOX_GROUP,
    StatusBarIconRegistry.STEREO,
    StatusBarIconRegistry.SOUND_BOX_SCREEN,
    StatusBarIconRegistry.SOUND_BOX,
    StatusBarIconRegistry.WIRELESS_HEADSET,
    StatusBarIconRegistry.GLASSES,
    StatusBarIconRegistry.CAR,
    StatusBarIconRegistry.TV,
    StatusBarIconRegistry.PC,
    StatusBarIconRegistry.PAD,
    StatusBarIconRegistry.PHONE -> R.string.sb_category_devices
    else -> R.string.sb_category_status
}

@StringRes
fun modeTitleRes(mode: String): Int = when (StatusBarIconMode.from(mode)) {
    StatusBarIconMode.SYSTEM -> R.string.sb_mode_system
    StatusBarIconMode.ALWAYS -> R.string.sb_mode_always
    StatusBarIconMode.CC_ONLY_HIDE -> R.string.sb_mode_cc_only_hide
    StatusBarIconMode.CC_ONLY_SHOW -> R.string.sb_mode_cc_only_show
    StatusBarIconMode.HIDDEN -> R.string.sb_mode_hidden
}

@StringRes
fun conditionTitleRes(condition: String): Int = when (StatusBarIconCondition.from(condition)) {
    StatusBarIconCondition.NONE -> R.string.sb_cond_none
    StatusBarIconCondition.CONNECTED -> R.string.sb_cond_connected
    StatusBarIconCondition.DISCONNECTED -> R.string.sb_cond_disconnected
    StatusBarIconCondition.ENABLED -> R.string.sb_cond_enabled
    StatusBarIconCondition.DISABLED -> R.string.sb_cond_disabled
    StatusBarIconCondition.DATA_ON -> R.string.sb_cond_data_on
    StatusBarIconCondition.DATA_OFF -> R.string.sb_cond_data_off
    StatusBarIconCondition.ALARM_WITHIN -> R.string.sb_cond_alarm_within
    StatusBarIconCondition.NO_ALARM -> R.string.sb_cond_no_alarm
    StatusBarIconCondition.CHARGING -> R.string.sb_cond_charging
    StatusBarIconCondition.NOT_CHARGING -> R.string.sb_cond_not_charging
    StatusBarIconCondition.TRAFFIC -> R.string.sb_cond_traffic
    StatusBarIconCondition.NO_TRAFFIC -> R.string.sb_cond_no_traffic
    StatusBarIconCondition.HAS_NOTIFICATIONS -> R.string.sb_cond_has_notifications
    StatusBarIconCondition.NO_NOTIFICATIONS -> R.string.sb_cond_no_notifications
}

@StringRes
fun logicTitleRes(logic: String): Int = when (StatusBarIconLogic.from(logic)) {
    StatusBarIconLogic.DEFAULT -> R.string.sb_logic_default
    StatusBarIconLogic.INVERTED -> R.string.sb_logic_inverted
    StatusBarIconLogic.CUSTOM -> R.string.sb_logic_custom
}

/** 统一显示条件下的展示文案。 */
@StringRes
fun displayTitleRes(value: String): Int =
    if (value.startsWith("condition:")) {
        conditionTitleRes(value.removePrefix("condition:"))
    } else {
        modeTitleRes(value)
    }

fun effectiveDisplay(config: StatusBarIconComponentConfig): String = when {
    config.display.isNotBlank() -> config.display
    config.logic == StatusBarIconLogic.CUSTOM &&
        config.condition != StatusBarIconCondition.NONE ->
        "condition:${config.condition.value}"
    else -> config.mode.value
}

fun effectiveDisplay(component: StatusBarIconComponent, config: StatusBarIconComponentConfig): String {
    val display = effectiveDisplay(config)
    return if (!StatusBarIconRegistry.supportsControlCenterDisplay(component.id) &&
        (display == StatusBarIconMode.CC_ONLY_SHOW.value ||
            display == StatusBarIconMode.CC_ONLY_HIDE.value)
    ) {
        StatusBarIconMode.SYSTEM.value
    } else {
        display
    }
}

fun currentComponentConfig(id: String): StatusBarIconComponentConfig {
    val rawSpeedFontSize = ConfigState.float(StatusBarIconKeys.speedFontSize(id), 0f)
    return StatusBarIconComponentConfig(
        display = ConfigState.string(StatusBarIconKeys.display(id), ""),
        mode = StatusBarIconMode.from(ConfigState.string(StatusBarIconKeys.mode(id), "system")),
        condition = StatusBarIconCondition.from(
            ConfigState.string(StatusBarIconKeys.condition(id), "none"),
        ),
        logic = StatusBarIconLogic.from(ConfigState.string(StatusBarIconKeys.logic(id), "default")),
        alarmWithin = ConfigState.float(StatusBarIconKeys.alarmWithin(id), 24f).toInt(),
        fontWeightEnabled = ConfigState.bool(StatusBarIconKeys.fontWeightEnabled(id), false),
        fontSizeEnabled = if (id == StatusBarIconRegistry.NETWORK_SPEED) {
            ConfigState.bool(StatusBarIconKeys.fontSizeEnabled(id), rawSpeedFontSize > 0f)
        } else {
            ConfigState.bool(StatusBarIconKeys.fontSizeEnabled(id), false)
        },
        offsetY = ConfigState.float(StatusBarIconKeys.offsetY(id), 0f),
        gapStart = ConfigState.float(StatusBarIconKeys.gapStart(id), 0f),
        gapEnd = ConfigState.float(StatusBarIconKeys.gapEnd(id), 0f),
        scale = ConfigState.float(StatusBarIconKeys.scale(id), 1f),
        alpha = ConfigState.float(StatusBarIconKeys.alpha(id), 1f),
        align = ConfigState.string(StatusBarIconKeys.align(id), "system"),
        fontWeight = ConfigState.float(
            StatusBarIconKeys.fontWeight(id),
            StatusBarIconRegistry.recommendedFontWeight(id).toFloat(),
        ).toInt().let { stored ->
            val recommended = StatusBarIconRegistry.recommendedFontWeight(id)
            if (stored in 100..900) stored else recommended
        },
        fontSize = ConfigState.float(
            StatusBarIconKeys.fontSize(id),
            StatusBarIconRegistry.recommendedFontSize(id).toFloat(),
        ),
        speedStyle = ConfigState.string(StatusBarIconKeys.speedStyle(id), "system"),
        speedFontSize = rawSpeedFontSize,
        speedRowGap = ConfigState.float(StatusBarIconKeys.speedRowGap(id), 0f),
        speedDualArrowStyle = ConfigState.string(
            StatusBarIconKeys.speedDualArrowStyle(id),
            "arrow",
        ),
        speedDualArrowPosition = ConfigState.string(
            StatusBarIconKeys.speedDualArrowPosition(id),
            "left",
        ),
        speedDualUploadFirst = ConfigState.bool(
            StatusBarIconKeys.speedDualUploadFirst(id),
            true,
        ),
        speedUnitStyle = ConfigState.string(StatusBarIconKeys.speedUnitStyle(id), "kb_s"),
        speedSlowHide = ConfigState.bool(StatusBarIconKeys.speedSlowHide(id), false),
        speedSlowThreshold = ConfigState.float(StatusBarIconKeys.speedSlowThreshold(id), 10f).toInt(),
        maxNotifications = ConfigState.float(StatusBarIconKeys.maxNotifications(id), 3f).toInt(),
        notificationMultiDot = ConfigState.bool(
            StatusBarIconKeys.notificationMultiDot(id),
            false,
        ),
    )
}

/** 与 SystemUI 状态栏图标同风格的 28dp 预览，避免在设置页引入额外的大卡片。 */
@Composable
fun StatusBarIconPreview(
    component: StatusBarIconComponent,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Box(modifier = modifier.size(28.dp), contentAlignment = Alignment.Center) {
        when (component.id) {
            StatusBarIconRegistry.CLOCK -> MiuixText(
                text = "12:30",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
            )

            StatusBarIconRegistry.PAD_CLOCK -> MiuixText(
                text = "10/24",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
            )

            StatusBarIconRegistry.NETWORK_SPEED -> MiuixText(
                text = "1.2M",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
            )

            StatusBarIconRegistry.OPERATOR -> MiuixText(
                text = "5G",
                style = MiuixTheme.textStyles.footnote2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
            )

            StatusBarIconRegistry.BATTERY -> BatteryPreview()

            StatusBarIconRegistry.PRIVACY -> PrivacyPreview()

            else -> {
                val iconRes = remember(component.id) { xiaomiIconRes(component.id) }
                val bitmap = remember(component.id) {
                    loadLocalBitmap(context, iconRes)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(componentTitleRes(component.id)),
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(24.dp),
                    )
                } else {
                    MiuixText(
                        text = component.id.take(2).uppercase(),
                        style = MiuixTheme.textStyles.footnote2,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** 状态栏电池使用原生比例图形，预览不再借用 HyperHelper 的电池设置图标。 */
@Composable
private fun BatteryPreview() {
    val context = LocalContext.current
    val state = remember {
        runCatching {
            val intent = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            )
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charge = if (level >= 0 && scale > 0) {
                (level.toFloat() / scale).coerceIn(0f, 1f)
            } else {
                0.72f
            }
            charge to (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL)
        }.getOrDefault(0.72f to false)
    }
    val color = MiuixTheme.colorScheme.onSurface
    val fillColor = if (state.second) Color(0xFF34C759) else color
    Canvas(Modifier.size(24.dp)) {
        val stroke = 1.5.dp.toPx()
        val left = 1.5.dp.toPx()
        val top = 6.dp.toPx()
        val bodyWidth = size.width - 5.dp.toPx()
        val bodyHeight = size.height - 12.dp.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(left, top),
            size = Size(bodyWidth, bodyHeight),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
            style = Stroke(stroke),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(left + bodyWidth + 1.dp.toPx(), top + bodyHeight * 0.28f),
            size = Size(2.dp.toPx(), bodyHeight * 0.44f),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        val inset = 2.5.dp.toPx()
        val fillWidth = (bodyWidth - inset * 2f).coerceAtLeast(0f) * state.first
        if (fillWidth > 0f) {
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(left + inset, top + inset),
                size = Size(fillWidth, bodyHeight - inset * 2f),
                cornerRadius = CornerRadius(1.2.dp.toPx()),
            )
        }
    }
}

/** 隐私指示是相机/麦克风组合，不使用带底板的 HyperHelper 通用通知图标。 */
@Composable
private fun PrivacyPreview() {
    val green = Color(0xFF28D244)
    Canvas(Modifier.size(24.dp)) {
        val cameraLeft = 1.5.dp.toPx()
        val cameraTop = 7.dp.toPx()
        val cameraWidth = 10.dp.toPx()
        val cameraHeight = 8.dp.toPx()
        drawRoundRect(
            color = green,
            topLeft = Offset(cameraLeft, cameraTop),
            size = Size(cameraWidth, cameraHeight),
            cornerRadius = CornerRadius(2.dp.toPx()),
        )
        drawRoundRect(
            color = green,
            topLeft = Offset(cameraLeft + 2.dp.toPx(), cameraTop - 2.dp.toPx()),
            size = Size(4.dp.toPx(), 2.5.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx()),
        )
        drawCircle(
            color = Color.White,
            radius = 2.1.dp.toPx(),
            center = Offset(cameraLeft + cameraWidth / 2f, cameraTop + cameraHeight / 2f),
        )
        val micLeft = 14.dp.toPx()
        val micTop = 4.5.dp.toPx()
        drawRoundRect(
            color = green,
            topLeft = Offset(micLeft, micTop),
            size = Size(5.dp.toPx(), 9.dp.toPx()),
            cornerRadius = CornerRadius(2.5.dp.toPx()),
        )
        drawRoundRect(
            color = green,
            topLeft = Offset(micLeft - 1.dp.toPx(), 15.dp.toPx()),
            size = Size(7.dp.toPx(), 1.5.dp.toPx()),
            cornerRadius = CornerRadius(0.75.dp.toPx()),
        )
        drawRoundRect(
            color = green,
            topLeft = Offset(micLeft + 2.dp.toPx(), 14.dp.toPx()),
            size = Size(1.5.dp.toPx(), 3.dp.toPx()),
            cornerRadius = CornerRadius(0.75.dp.toPx()),
        )
    }
}

/**
 * 标准设置行：图标 + 名称 + 分类/状态摘要 + 可选排序手柄 + 箭头。
 *
 * 外层必须由 `HookSectionCard` 的 Card 包裹，不能在这里再套 Card 或硬编码卡片间距。
 */
@Composable
fun StatusBarIconRow(
    component: StatusBarIconComponent,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier? = null,
    onOpen: () -> Unit,
) {
    val config = currentComponentConfig(component.id)
    val display = stringResource(displayTitleRes(effectiveDisplay(component, config)))
    val category = stringResource(categoryTitleRes(component.id))
    val actionColor = if (enabled) {
        MiuixTheme.colorScheme.onSurfaceVariantActions
    } else {
        MiuixTheme.colorScheme.disabledOnSecondaryVariant
    }

    BasicComponent(
        modifier = modifier,
        title = stringResource(componentTitleRes(component.id)),
        summary = listOf(category, display).joinToString(" · "),
        enabled = enabled,
        onClick = onOpen,
        startAction = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 抓手放到最左侧，右手滑动页面时不会误触拖动。
                if (dragHandleModifier != null) {
                    val dragLabel = stringResource(R.string.sb_icon_drag_handle)
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .semantics { contentDescription = dragLabel }
                            .then(dragHandleModifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Canvas(modifier = Modifier.size(20.dp)) {
                            val radius = 1.5.dp.toPx()
                            val xs = listOf(size.width * 0.32f, size.width * 0.68f)
                            val ys = listOf(
                                size.height * 0.25f,
                                size.height * 0.5f,
                                size.height * 0.75f,
                            )
                            xs.forEach { x ->
                                ys.forEach { y ->
                                    drawCircle(
                                        color = actionColor,
                                        radius = radius,
                                        center = Offset(x, y),
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Box(modifier = Modifier.padding(end = 12.dp).alpha(if (enabled) 1f else 0.45f)) {
                    StatusBarIconPreview(component)
                }
            }
        },
        endActions = {
            Icon(
                imageVector = MiuixIcons.ChevronForward,
                contentDescription = null,
                tint = actionColor,
                modifier = Modifier.size(16.dp),
            )
        },
    )
}

/**
 * 普通图标预览使用 HyperHelper 同源的 MIUI 状态栏资源，而不是直接取 SystemUI 的
 * `stat_sys_*`（后者在部分版本是 AOSP 风格或带信号底的合成图）；隐私/电池单独按原生状态自绘。
 */
@DrawableRes
private fun xiaomiIconRes(id: String): Int = when (id) {
    StatusBarIconRegistry.NOTIFICATIONS -> R.drawable.ic_stat_sys_compound
    StatusBarIconRegistry.NETWORK_SPEED -> R.drawable.ic_stat_sys_net_speed
    StatusBarIconRegistry.WIFI -> R.drawable.ic_stat_sys_wifi
    StatusBarIconRegistry.MOBILE -> R.drawable.ic_stat_sys_mobile
    StatusBarIconRegistry.BLUETOOTH -> R.drawable.ic_stat_sys_bluetooth
    StatusBarIconRegistry.ALARM -> R.drawable.ic_stat_sys_alarm_clock
    StatusBarIconRegistry.LOCATION -> R.drawable.ic_stat_sys_location
    StatusBarIconRegistry.HEADSET -> R.drawable.ic_stat_sys_headset
    StatusBarIconRegistry.VPN -> R.drawable.ic_stat_sys_vpn
    StatusBarIconRegistry.ZEN -> R.drawable.ic_stat_sys_zen
    StatusBarIconRegistry.MUTE -> R.drawable.ic_stat_sys_call_mute
    StatusBarIconRegistry.VOLUME -> R.drawable.ic_stat_sys_volume
    StatusBarIconRegistry.AIRPLANE -> R.drawable.ic_stat_sys_airplane
    StatusBarIconRegistry.HOTSPOT -> R.drawable.ic_stat_sys_hotspot
    StatusBarIconRegistry.NFC -> R.drawable.ic_stat_sys_nfc
    StatusBarIconRegistry.HD -> R.drawable.ic_stat_sys_speech_hd
    StatusBarIconRegistry.MICPHONE -> R.drawable.ic_stat_sys_micphone
    StatusBarIconRegistry.SECOND_SPACE -> R.drawable.ic_stat_sys_second_space
    StatusBarIconRegistry.STEALTH -> R.drawable.ic_stat_sys_stealth
    StatusBarIconRegistry.BLUETOOTH_BATTERY ->
        R.drawable.ic_stat_sys_bluetooth_handsfree_battery
    StatusBarIconRegistry.SOUND_BOX_GROUP -> R.drawable.ic_stat_sys_sound_box_group
    StatusBarIconRegistry.STEREO -> R.drawable.ic_stat_sys_stereo
    StatusBarIconRegistry.SOUND_BOX_SCREEN -> R.drawable.ic_stat_sys_sound_box_screen
    StatusBarIconRegistry.SOUND_BOX -> R.drawable.ic_stat_sys_sound_box
    StatusBarIconRegistry.WIRELESS_HEADSET -> R.drawable.ic_stat_sys_wireless_headset
    StatusBarIconRegistry.DIST_COMPUTE -> R.drawable.ic_stat_sys_dist_compute
    StatusBarIconRegistry.CAMERA -> R.drawable.ic_stat_sys_camera
    StatusBarIconRegistry.GLASSES -> R.drawable.ic_stat_sys_glasses
    StatusBarIconRegistry.CAR -> R.drawable.ic_stat_sys_car
    StatusBarIconRegistry.TV -> R.drawable.ic_stat_sys_tv
    StatusBarIconRegistry.PC -> R.drawable.ic_stat_sys_pc
    StatusBarIconRegistry.PAD -> R.drawable.ic_stat_sys_pad
    StatusBarIconRegistry.PHONE -> R.drawable.ic_stat_sys_phone
    StatusBarIconRegistry.NO_SIM -> R.drawable.ic_stat_sys_no_sim
    else -> R.drawable.ic_stat_sys_unknown
}

private fun loadLocalBitmap(context: Context, @DrawableRes res: Int): Bitmap? = runCatching {
    val drawable = ContextCompat.getDrawable(context, res) ?: return null
    val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 64
    val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 64
    drawable.toBitmap(width, height, Bitmap.Config.ARGB_8888)
}.getOrNull()
