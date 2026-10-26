package cn.ianzb.hyperrefine.ui.screen.features

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconComponent
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconCondition
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconKeys
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconMode
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconRegistry
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarKeys
import cn.ianzb.hyperrefine.prefs.ConfigState
import cn.ianzb.hyperrefine.prefs.OptionRegistry
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.prefs.OptionType
import cn.ianzb.hyperrefine.ui.component.pref.HookOverlayDropdownCard
import cn.ianzb.hyperrefine.ui.component.pref.HookOptionView
import cn.ianzb.hyperrefine.ui.component.pref.HookSection
import cn.ianzb.hyperrefine.ui.component.pref.HookSectionCard
import cn.ianzb.hyperrefine.ui.component.pref.HookSliderCard
import cn.ianzb.hyperrefine.ui.component.pref.HookSwitchCard
import cn.ianzb.hyperrefine.ui.component.pref.HookTextCard
import cn.ianzb.hyperrefine.ui.component.pref.rememberEffectiveDeviceType
import cn.ianzb.hyperrefine.ui.component.statusbar.StatusBarIconPreview
import cn.ianzb.hyperrefine.ui.component.statusbar.categoryTitleRes
import cn.ianzb.hyperrefine.ui.component.statusbar.componentTitleRes
import cn.ianzb.hyperrefine.ui.component.statusbar.conditionTitleRes
import cn.ianzb.hyperrefine.ui.component.statusbar.currentComponentConfig
import cn.ianzb.hyperrefine.ui.component.statusbar.displayTitleRes
import cn.ianzb.hyperrefine.ui.component.statusbar.effectiveDisplay
import cn.ianzb.hyperrefine.ui.screen.subpage.BaseSubPageActivity
import cn.ianzb.hyperrefine.ui.util.LocalSubPageScrollBehavior
import cn.ianzb.hyperrefine.ui.util.pageScrollModifiers
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Help
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** 单个组件的二级设置：已验证正常的窗口结构承载全部组件内容。 */
class IconSettingsActivity : BaseSubPageActivity() {
    override val titleRes = R.string.sb_icon_detail

    private val componentId: String
        get() = intent.getStringExtra(EXTRA_COMPONENT)
            ?.takeIf { StatusBarIconRegistry.byId(it) != null }
            ?: StatusBarIconRegistry.CLOCK

    override val topBarActions: (@Composable () -> Unit)? = {
        if (componentId == StatusBarIconRegistry.CLOCK ||
            componentId == StatusBarIconRegistry.PAD_CLOCK
        ) {
            ClockFormatDocsAction()
        }
    }

    @Composable
    override fun SubPageContent(isBlurEnabled: Boolean, contentPadding: PaddingValues) {
        val context = LocalContext.current
        val id = componentId
        val component = StatusBarIconRegistry.byId(id) ?: return
        val scroll = LocalSubPageScrollBehavior.current
        val config = currentComponentConfig(id)
        val display = effectiveDisplay(config).let { current ->
            if (!StatusBarIconRegistry.supportsControlCenterDisplay(id) &&
                (current == StatusBarIconMode.CC_ONLY_SHOW.value ||
                    current == StatusBarIconMode.CC_ONLY_HIDE.value)
            ) {
                StatusBarIconMode.SYSTEM.value
            } else {
                current
            }
        }
        val detailSummary = listOf(
            stringResource(categoryTitleRes(id)),
            stringResource(displayTitleRes(display)),
        ).joinToString(" · ")
        val wifiSpecs = remember { statusBarSpecs(featureSpecs()).filter { it.key == StatusBarKeys.WIFI_ACTIVITY } }
        val mobileSpecs = remember {
            statusBarSpecs(featureSpecs()).filter { it.key != StatusBarKeys.WIFI_ACTIVITY }
        }
        val deviceType = rememberEffectiveDeviceType()
        val supportsPad = deviceType == DeviceType.PAD || deviceType == DeviceType.FOLD
        val supportsHorizontalClock = deviceType == DeviceType.PHONE

        LaunchedEffect(id, supportsPad, supportsHorizontalClock) {
            // 自定义二级页不会经过 HookOptionsPage 的自动注册路径，必须把本页
            // 实际渲染的 OptionSpec 全部登记，否则搜索/状态与标准页面不一致。
            OptionRegistry.register(masterSpec())
            OptionRegistry.register(displaySpec(component))
            alarmWithinSpec(component)?.let(OptionRegistry::register)
            layoutSpecs(id).forEach(OptionRegistry::register)
            if (id == StatusBarIconRegistry.CLOCK) {
                clockPatternSpecs(
                    context = context,
                    includePad = supportsPad,
                    includeHorizontal = supportsHorizontalClock,
                ).forEach(OptionRegistry::register)
                OptionRegistry.register(alignSpec(id))
                OptionRegistry.register(clockRefreshSpec())
                OptionRegistry.register(clockWidthSpec())
            }
            if (id == StatusBarIconRegistry.PAD_CLOCK) {
                clockPatternSpecs(context, includePad = true, includeHorizontal = false)
                    .firstOrNull { it.key == StatusBarIconKeys.CLOCK_PATTERN_PAD }
                    ?.let(OptionRegistry::register)
            }
            if (id == StatusBarIconRegistry.NETWORK_SPEED) {
                speedDropdownSpecs(id).forEach(OptionRegistry::register)
            }
            if (id == StatusBarIconRegistry.NOTIFICATIONS) {
                val multiDot = notificationMultiDotSpec(id)
                val max = maxNotificationSpec(id)
                OptionRegistry.register(multiDot)
                OptionRegistry.register(max)
            }
            wifiSpecs.forEach(OptionRegistry::register)
            mobileSpecs.forEach(OptionRegistry::register)
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().then(
                if (scroll != null) {
                    Modifier.pageScrollModifiers(showTopAppBar = true, topAppBarScrollBehavior = scroll)
                } else {
                    Modifier
                },
            ),
            contentPadding = contentPadding,
        ) {
            item {
                HookSectionCard(
                    HookSection(
                        titleRes = R.string.sb_icon_preview,
                        specs = emptyList(),
                    ),
                ) {
                    BasicComponent(
                        title = stringResource(componentTitleRes(id)),
                        summary = detailSummary,
                        startAction = {
                            Box(modifier = Modifier.padding(end = 12.dp)) {
                                StatusBarIconPreview(component)
                            }
                        },
                    )
                }
            }

            item {
                val display = displaySpec(component)
                val alarm = alarmWithinSpec(component)
                val specs = listOfNotNull(display, alarm)
                HookSectionCard(
                    HookSection(
                        titleRes = R.string.sb_icon_display,
                        specs = specs,
                    ),
                ) {
                    HookOverlayDropdownCard(display)
                    if (alarm != null) HookSliderCard(alarm)
                }
            }

            item {
                HookSectionCard(
                    HookSection(
                        titleRes = R.string.sb_icon_layout,
                        specs = layoutSpecs(id),
                    ),
                ) {
                    layoutSpecs(id).forEach { spec -> HookSliderCard(spec) }
                }
            }

            if (id == StatusBarIconRegistry.CLOCK) {
                item {
                    val patterns = clockPatternSpecs(
                        context = context,
                        includePad = supportsPad,
                        includeHorizontal = supportsHorizontalClock,
                    )
                    val align = alignSpec(id)
                    val refresh = clockRefreshSpec()
                    val width = clockWidthSpec()
                    HookSectionCard(
                        HookSection(
                            titleRes = R.string.sb_icon_clock,
                            specs = patterns + align + refresh + width,
                        ),
                    ) {
                        patterns.forEach { spec -> HookTextCard(spec) }
                        HookOverlayDropdownCard(align)
                        HookSwitchCard(refresh)
                        HookSliderCard(width)
                    }
                }
            }

            if (id == StatusBarIconRegistry.PAD_CLOCK) {
                item {
                    val spec = clockPatternSpecs(
                        context,
                        includePad = true,
                        includeHorizontal = false,
                    ).firstOrNull { it.key == StatusBarIconKeys.CLOCK_PATTERN_PAD }
                    HookSectionCard(
                        HookSection(
                            titleRes = R.string.sb_icon_pad_clock,
                            specs = listOfNotNull(spec),
                        ),
                    ) {
                        spec?.let { HookTextCard(it) }
                    }
                }
            }

            if (id == StatusBarIconRegistry.NETWORK_SPEED) {
                item {
                    val specs = speedDropdownSpecs(id)
                    HookSectionCard(HookSection(titleRes = R.string.sb_icon_network_speed, specs = specs)) {
                        specs.forEach { spec -> HookOptionView(spec) }
                    }
                }
            }

            if (id == StatusBarIconRegistry.NOTIFICATIONS) {
                item {
                    val multiDot = notificationMultiDotSpec(id)
                    val spec = maxNotificationSpec(id)
                    HookSectionCard(
                        HookSection(
                            titleRes = R.string.sb_icon_notifications,
                            specs = listOf(multiDot, spec),
                        ),
                    ) {
                        HookSwitchCard(multiDot)
                        HookSliderCard(spec)
                    }
                }
            }

            if (id == StatusBarIconRegistry.WIFI && wifiSpecs.isNotEmpty()) {
                item {
                    HookSectionCard(HookSection(titleRes = R.string.sb_icon_wifi, specs = wifiSpecs)) {
                        wifiSpecs.forEach { spec -> HookOptionView(spec) }
                    }
                }
            }

            if (id == StatusBarIconRegistry.MOBILE && mobileSpecs.isNotEmpty()) {
                item {
                    HookSectionCard(HookSection(titleRes = R.string.sb_icon_mobile, specs = mobileSpecs)) {
                        mobileSpecs.forEach { spec -> HookOptionView(spec) }
                    }
                }
            }
        }
    }

    private fun masterSpec() = OptionSpec(
        key = StatusBarIconKeys.ENABLED,
        type = OptionType.SWITCH,
        titleRes = R.string.sb_icon_custom,
        summaryRes = R.string.sb_icon_custom_summary,
        targetPackages = listOf(StatusBarKeys.TARGET),
        showStatus = true,
    )

    private fun layoutSpecs(id: String): List<OptionSpec> {
        val target = listOf(StatusBarKeys.TARGET)
        val specs = mutableListOf(
            slider(
                key = StatusBarIconKeys.offsetY(id),
                title = R.string.sb_icon_offset_y,
                default = 0f,
                min = -24f,
                max = 24f,
                step = 0.5f,
                decimals = 1,
                unit = R.string.sb_unit_dp,
                target = target,
            ),
            slider(
                key = StatusBarIconKeys.gapStart(id),
                title = R.string.sb_icon_gap_start,
                default = 0f,
                min = -16f,
                max = 32f,
                step = 0.5f,
                decimals = 1,
                unit = R.string.sb_unit_dp,
                target = target,
            ),
            slider(
                key = StatusBarIconKeys.gapEnd(id),
                title = R.string.sb_icon_gap_end,
                default = 0f,
                min = -16f,
                max = 32f,
                step = 0.5f,
                decimals = 1,
                unit = R.string.sb_unit_dp,
                target = target,
            ),
            slider(
                key = StatusBarIconKeys.scale(id),
                title = R.string.sb_icon_item_scale,
                default = 1f,
                min = 0.3f,
                max = 3f,
                step = 0.05f,
                decimals = 2,
                unit = R.string.sb_unit_scale,
                target = target,
            ),
            slider(
                key = StatusBarIconKeys.alpha(id),
                title = R.string.sb_icon_alpha,
                default = 1f,
                min = 0f,
                max = 1f,
                step = 0.05f,
                decimals = 2,
                unit = 0,
                target = target,
            ),
        )
        if (StatusBarIconRegistry.byId(id)?.textual == true) {
            specs += slider(
                key = StatusBarIconKeys.fontWeight(id),
                title = R.string.sb_icon_font_weight,
                default = StatusBarIconRegistry.recommendedFontWeight(id).toFloat(),
                min = 100f,
                max = 900f,
                step = 10f,
                decimals = 0,
                unit = 0,
                target = target,
                masterKey = StatusBarIconKeys.fontWeightEnabled(id),
                masterTitle = R.string.sb_icon_font_weight_enable,
                masterSummary = R.string.sb_icon_font_weight_summary,
            )
            // 字号统一放在布局区、紧挨字重，保持时间/网速等组件的位置和操作顺序一致。
            if (id == StatusBarIconRegistry.NETWORK_SPEED) {
                specs += slider(
                    key = StatusBarIconKeys.speedFontSize(id),
                    title = R.string.sb_speed_font_size,
                    default = 7f,
                    min = 6f,
                    max = 24f,
                    step = 0.5f,
                    decimals = 1,
                    unit = R.string.sb_unit_sp,
                    target = target,
                    masterKey = StatusBarIconKeys.fontSizeEnabled(id),
                    masterTitle = R.string.sb_icon_font_size_enable,
                    masterSummary = R.string.sb_icon_font_size_summary,
                    masterDefault = ConfigState.float(StatusBarIconKeys.speedFontSize(id), 0f) > 0f,
                )
            } else {
                specs += slider(
                    key = StatusBarIconKeys.fontSize(id),
                    title = R.string.sb_icon_font_size,
                    default = StatusBarIconRegistry.recommendedFontSize(id).toFloat(),
                    min = 8f,
                    max = 32f,
                    step = 1f,
                    decimals = 0,
                    unit = R.string.sb_unit_sp,
                    target = target,
                    masterKey = StatusBarIconKeys.fontSizeEnabled(id),
                    masterTitle = R.string.sb_icon_font_size_enable,
                    masterSummary = R.string.sb_icon_font_size_summary,
                )
            }
        }
        return specs
    }

    private fun displaySpec(component: StatusBarIconComponent): OptionSpec {
        val entries = buildList {
            add(R.string.sb_mode_system to StatusBarIconMode.SYSTEM.value)
            add(R.string.sb_mode_always to StatusBarIconMode.ALWAYS.value)
            add(R.string.sb_mode_hidden to StatusBarIconMode.HIDDEN.value)
            if (StatusBarIconRegistry.supportsControlCenterDisplay(component.id)) {
                add(R.string.sb_mode_cc_only_show to StatusBarIconMode.CC_ONLY_SHOW.value)
                add(R.string.sb_mode_cc_only_hide to StatusBarIconMode.CC_ONLY_HIDE.value)
            }
            component.conditions.forEach { condition ->
                add(
                    conditionTitleRes(condition.value) to
                        "condition:${condition.value}",
                )
            }
        }
        return dropdown(
            key = StatusBarIconKeys.display(component.id),
            title = R.string.sb_icon_display_condition,
            entries = entries,
        ).copy(
            // 旧版 mode/condition/logic 没有 display 键时，下拉仍显示迁移后的真实条件。
            defaultString = effectiveDisplay(currentComponentConfig(component.id)).let { current ->
                if (!StatusBarIconRegistry.supportsControlCenterDisplay(component.id) &&
                    (current == StatusBarIconMode.CC_ONLY_SHOW.value ||
                        current == StatusBarIconMode.CC_ONLY_HIDE.value)
                ) {
                    StatusBarIconMode.SYSTEM.value
                } else {
                    current
                }
            },
        )
    }

    private fun alarmWithinSpec(component: StatusBarIconComponent): OptionSpec? {
        if (StatusBarIconCondition.ALARM_WITHIN !in component.conditions) return null
        return slider(
            key = StatusBarIconKeys.alarmWithin(component.id),
            title = R.string.sb_icon_alarm_within,
            default = 24f,
            min = 0f,
            max = 72f,
            step = 1f,
            decimals = 0,
            unit = R.string.sb_unit_hour,
            target = listOf(StatusBarKeys.TARGET),
            summary = R.string.sb_icon_alarm_within_summary,
            dependsOn = StatusBarIconKeys.display(component.id),
            dependsOnValues = setOf("condition:${StatusBarIconCondition.ALARM_WITHIN.value}"),
        )
    }

    private fun clockPatternSpecs(
        context: android.content.Context,
        includePad: Boolean,
        includeHorizontal: Boolean,
    ): List<OptionSpec> {
        val target = listOf(StatusBarKeys.TARGET)
        val timePattern = runCatching {
            (android.text.format.DateFormat.getTimeFormat(context) as? java.text.SimpleDateFormat)
                ?.toPattern()
        }.getOrNull().orEmpty().ifBlank { "HH:mm" }
        val datePattern = runCatching {
            android.text.format.DateFormat.getBestDateTimePattern(
                java.util.Locale.getDefault(),
                "EMMMd",
            )
        }.getOrNull().orEmpty().ifBlank { "E, MMM d" }
        val horizontalPattern = "$timePattern ${spaceBeforeWeekday(datePattern)}"
        return buildList {
            add(text(StatusBarIconKeys.CLOCK_PATTERN_STATUS, R.string.sb_clock_pattern_status, target, timePattern))
            add(text(StatusBarIconKeys.CLOCK_PATTERN_NOTIFICATION_BIG, R.string.sb_clock_pattern_notification_big, target, timePattern))
            add(
                text(
                    StatusBarIconKeys.CLOCK_PATTERN_NOTIFICATION_SMALL,
                    R.string.sb_clock_pattern_notification_small,
                    target,
                    datePattern,
                ),
            )
            add(
                text(
                    StatusBarIconKeys.CLOCK_PATTERN_CONTROL_CENTER,
                    R.string.sb_clock_pattern_control_center,
                    target,
                    datePattern,
                ),
            )
            if (includeHorizontal) {
                add(
                    text(
                        StatusBarIconKeys.CLOCK_PATTERN_HORIZONTAL,
                        R.string.sb_clock_pattern_horizontal,
                        target,
                        horizontalPattern,
                    ),
                )
            }
            if (includePad) {
                add(text(StatusBarIconKeys.CLOCK_PATTERN_PAD, R.string.sb_clock_pattern_pad, target, datePattern))
            }
        }
    }

    /** 日期与星期之间补一个空格；星期在最前或已有分隔符时保持原样。 */
    private fun spaceBeforeWeekday(pattern: String): String {
        val index = pattern.indexOf("EEE")
        if (index <= 0) return pattern
        val previous = pattern[index - 1]
        if (previous.isWhitespace() ||
            previous == '(' || previous == '（' ||
            previous == ',' || previous == '，'
        ) {
            return pattern
        }
        return pattern.substring(0, index) + " " + pattern.substring(index)
    }

    private fun text(
        key: String,
        title: Int,
        target: List<String>,
        defaultPattern: String,
    ) = OptionSpec(
        key = key,
        type = OptionType.TEXT,
        titleRes = title,
        defaultString = defaultPattern,
        targetPackages = target,
        dependsOn = StatusBarIconKeys.ENABLED,
        masterKey = StatusBarIconKeys.clockCustom(key),
        masterTitleRes = R.string.sb_clock_custom,
    )

    private fun clockRefreshSpec() = OptionSpec(
        key = StatusBarIconKeys.CLOCK_REFRESH,
        type = OptionType.SWITCH,
        titleRes = R.string.sb_clock_refresh,
        summaryRes = R.string.sb_clock_refresh_summary,
        targetPackages = listOf(StatusBarKeys.TARGET),
        dependsOn = StatusBarIconKeys.ENABLED,
    )

    private fun clockWidthSpec() = slider(
        key = StatusBarIconKeys.CLOCK_WIDTH,
        title = R.string.sb_clock_width,
        default = 60f,
        min = 20f,
        max = 240f,
        step = 1f,
        decimals = 0,
        unit = R.string.sb_unit_dp,
        target = listOf(StatusBarKeys.TARGET),
        masterKey = StatusBarIconKeys.CLOCK_LOCK_WIDTH,
        masterTitle = R.string.sb_clock_lock_width,
        masterSummary = R.string.sb_clock_lock_width_summary,
    )

    private fun alignSpec(id: String) = dropdown(
        key = StatusBarIconKeys.align(id),
        title = R.string.sb_icon_align,
        entries = alignEntries(),
    )

    private fun speedDropdownSpecs(id: String): List<OptionSpec> {
        val target = listOf(StatusBarKeys.TARGET)
        val styleKey = StatusBarIconKeys.speedStyle(id)
        val dualLine = setOf("dual_line")
        return listOf(
            dropdown(
                key = styleKey,
                title = R.string.sb_speed_style,
                entries = listOf(
                    R.string.sb_speed_style_system to "system",
                    R.string.sb_speed_style_number_only to "number_only",
                    R.string.sb_speed_style_single to "single_line",
                    R.string.sb_speed_style_dual to "dual_line",
                ),
            ),
            OptionSpec(
                key = StatusBarIconKeys.speedShowUnit(id),
                type = OptionType.SWITCH,
                titleRes = R.string.sb_speed_show_unit,
                summaryRes = R.string.sb_speed_show_unit_summary,
                defaultBoolean = true,
                targetPackages = target,
                dependsOn = StatusBarIconKeys.ENABLED,
            ),
            dropdown(
                key = StatusBarIconKeys.speedUnitStyle(id),
                title = R.string.sb_speed_unit_style,
                entries = listOf(
                    R.string.sb_speed_unit_style_kb_s to "kb_s",
                    R.string.sb_speed_unit_style_k to "k",
                    R.string.sb_speed_unit_style_kb to "kb",
                    R.string.sb_speed_unit_style_kbit to "kbit",
                    R.string.sb_speed_unit_style_kbit_s to "kbit_s",
                ),
            ).copy(
                defaultString = "kb_s",
                dependsOn = StatusBarIconKeys.speedShowUnit(id),
                dependsOnValue = true,
            ),
            dropdown(
                key = StatusBarIconKeys.speedDualArrowStyle(id),
                title = R.string.sb_speed_arrow_style,
                entries = listOf(
                    R.string.sb_speed_arrow_arrow to "arrow",
                    R.string.sb_speed_arrow_triangle to "triangle",
                    R.string.sb_speed_arrow_none to "none",
                ),
            ).copy(
                defaultString = "arrow",
                dependsOn = styleKey,
                dependsOnValues = dualLine,
            ),
            dropdown(
                key = StatusBarIconKeys.speedDualArrowPosition(id),
                title = R.string.sb_speed_arrow_position,
                entries = listOf(
                    R.string.sb_speed_arrow_left to "left",
                    R.string.sb_speed_arrow_right to "right",
                ),
            ).copy(
                defaultString = "left",
                dependsOn = StatusBarIconKeys.speedDualArrowStyle(id),
                dependsOnValues = setOf("arrow", "triangle"),
            ),
            OptionSpec(
                key = StatusBarIconKeys.speedDualUploadFirst(id),
                type = OptionType.SWITCH,
                titleRes = R.string.sb_speed_dual_upload_first,
                summaryRes = R.string.sb_speed_dual_upload_first_summary,
                defaultBoolean = true,
                targetPackages = target,
                dependsOn = styleKey,
                dependsOnValues = dualLine,
            ),
            slider(
                key = StatusBarIconKeys.speedRowGap(id),
                title = R.string.sb_speed_row_gap,
                default = 0f,
                min = -4f,
                max = 12f,
                step = 0.5f,
                decimals = 1,
                unit = R.string.sb_unit_dp,
                target = target,
                summary = R.string.sb_speed_row_gap_summary,
                dependsOn = styleKey,
                dependsOnValues = dualLine,
            ),
            slider(
                key = StatusBarIconKeys.speedSlowThreshold(id),
                title = R.string.sb_speed_slow_threshold,
                default = 10f,
                min = 0f,
                max = 1024f,
                step = 1f,
                decimals = 0,
                unit = R.string.sb_speed_unit_kb,
                target = target,
                summary = R.string.sb_speed_slow_threshold_summary,
                masterKey = StatusBarIconKeys.speedSlowHide(id),
                masterTitle = R.string.sb_speed_slow_hide,
                masterSummary = R.string.sb_speed_slow_hide_summary,
            ),
            slider(
                key = StatusBarIconKeys.speedInterval(id),
                title = R.string.sb_speed_interval,
                default = 1f,
                min = 1f,
                max = 10f,
                step = 1f,
                decimals = 0,
                unit = R.string.sb_speed_interval_unit,
                target = target,
            ),
            dropdown(
                key = StatusBarIconKeys.align(id),
                title = R.string.sb_icon_align,
                entries = alignEntries(),
            ),
        )
    }

    private fun maxNotificationSpec(id: String) = slider(
        key = StatusBarIconKeys.maxNotifications(id),
        title = R.string.sb_icon_max_notifications,
        default = 3f,
        min = 1f,
        max = 16f,
        step = 1f,
        decimals = 0,
        unit = 0,
        target = listOf(StatusBarKeys.TARGET),
    )

    private fun notificationMultiDotSpec(id: String) = OptionSpec(
        key = StatusBarIconKeys.notificationMultiDot(id),
        type = OptionType.SWITCH,
        titleRes = R.string.sb_icon_notification_multi_dot,
        summaryRes = R.string.sb_icon_notification_multi_dot_summary,
        defaultBoolean = false,
        targetPackages = listOf(StatusBarKeys.TARGET),
        dependsOn = StatusBarIconKeys.ENABLED,
    )

    private fun alignEntries(): List<Pair<Int, String>> = listOf(
        R.string.sb_align_system to "system",
        R.string.sb_align_start to "start",
        R.string.sb_align_center to "center",
        R.string.sb_align_end to "end",
    )

    private fun dropdown(
        key: String,
        title: Int,
        entries: List<Pair<Int, String>>,
    ) = OptionSpec(
        key = key,
        type = OptionType.DROPDOWN,
        titleRes = title,
        defaultString = entries.first().second,
        entryResIds = entries.map { it.first },
        entryValues = entries.map { it.second },
        targetPackages = listOf(StatusBarKeys.TARGET),
        dependsOn = StatusBarIconKeys.ENABLED,
    )

    private fun slider(
        key: String,
        title: Int,
        default: Float,
        min: Float,
        max: Float,
        step: Float,
        decimals: Int,
        unit: Int,
        target: List<String>,
        summary: Int = 0,
        masterKey: String? = null,
        masterTitle: Int = 0,
        masterSummary: Int = 0,
        masterDefault: Boolean = false,
        dependsOn: String? = null,
        dependsOnValues: Set<String> = emptySet(),
    ) = OptionSpec(
        key = key,
        type = OptionType.SLIDER,
        titleRes = title,
        summaryRes = summary,
        sliderValueLabelRes = title,
        defaultFloat = default,
        sliderMin = min,
        sliderMax = max,
        sliderStep = step,
        sliderDecimals = decimals,
        sliderUnitRes = unit,
        targetPackages = target,
        dependsOn = dependsOn ?: StatusBarIconKeys.ENABLED,
        dependsOnValues = dependsOnValues,
        masterKey = masterKey,
        masterTitleRes = masterTitle,
        masterSummaryRes = masterSummary,
        masterDefault = masterDefault,
    )

    companion object {
        const val EXTRA_COMPONENT = "statusbar_icon_component"
    }
}

@Composable
private fun ClockFormatDocsAction() {
    val context = LocalContext.current
    IconButton(
        onClick = {
            runCatching {
                context.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://zhuti.designer.xiaomi.com/docs/grammar/#时间日期"),
                    ),
                )
            }
        },
    ) {
        Icon(
            imageVector = MiuixIcons.Help,
            contentDescription = stringResource(R.string.sb_clock_format_docs),
            tint = MiuixTheme.colorScheme.onBackground,
            modifier = Modifier.size(24.dp),
        )
    }
}
