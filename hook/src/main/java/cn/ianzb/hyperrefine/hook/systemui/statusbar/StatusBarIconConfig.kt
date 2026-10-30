package cn.ianzb.hyperrefine.hook.systemui.statusbar

import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/** 单个状态栏组件的持久化配置；字段均带缺省值，旧安装无需迁移。 */
data class StatusBarIconComponentConfig(
    /** 统一显示条件；空串表示旧配置，用 mode/condition/logic 迁移。 */
    val display: String = "",
    val mode: StatusBarIconMode = StatusBarIconMode.SYSTEM,
    val condition: StatusBarIconCondition = StatusBarIconCondition.NONE,
    val logic: StatusBarIconLogic = StatusBarIconLogic.DEFAULT,
    val alarmWithin: Int = 24,
    val fontWeightEnabled: Boolean = false,
    val fontSizeEnabled: Boolean = false,
    val offsetY: Float = 0f,
    val gapStart: Float = 0f,
    val gapEnd: Float = 0f,
    val scale: Float = 1f,
    val alpha: Float = 1f,
    val align: String = "system",
    val fontWeight: Int = 0,
    val fontSize: Float = 0f,
    val speedStyle: String = "system",
    val speedFontSize: Float = 0f,
    val speedRowGap: Float = 0f,
    val speedDualArrowStyle: String = "arrow",
    val speedDualArrowPosition: String = "left",
    val speedDualUploadFirst: Boolean = true,
    val speedUnitStyle: String = "kb_s",
    val speedSlowHide: Boolean = false,
    val speedSlowThreshold: Int = 10,
    val maxNotifications: Int = 3,
    val notificationMultiDot: Boolean = false,
)

data class StatusBarIconSnapshot(
    val enabled: Boolean,
    val order: List<String>,
    private val configs: Map<String, StatusBarIconComponentConfig>,
) {
    fun config(id: String): StatusBarIconComponentConfig =
        configs[id] ?: StatusBarIconComponentConfig()

    /** 统一显示条件，兼容旧版 mode/condition/logic。 */
    fun display(id: String): String {
        val config = config(id)
        if (config.display.isNotBlank()) return config.display
        if (config.logic == StatusBarIconLogic.CUSTOM &&
            config.condition != StatusBarIconCondition.NONE
        ) {
            return "condition:${config.condition.value}"
        }
        return config.mode.value
    }
}

/** display 是唯一显示入口；旧 mode 只在 display 为空时生效。 */
internal fun normalizedMode(display: String, storedMode: StatusBarIconMode): StatusBarIconMode =
    when (display) {
        StatusBarIconMode.SYSTEM.value,
        StatusBarIconMode.ALWAYS.value,
        StatusBarIconMode.HIDDEN.value,
        StatusBarIconMode.CC_ONLY_SHOW.value,
        StatusBarIconMode.CC_ONLY_HIDE.value,
        -> StatusBarIconMode.from(display)
        // 条件模式由 evaluator 决定显隐，不再沿用旧的 mode，避免切换条件时残留 always/hidden。
        else -> if (display.startsWith("condition:")) {
            StatusBarIconMode.SYSTEM
        } else {
            storedMode
        }
    }

object StatusBarIconDefaults {
    val rightOrder: List<String> = StatusBarIconRegistry.right.map { it.id }
    val leftOrder: List<String> = StatusBarIconRegistry.left.map { it.id }
}

object StatusBarIconConfigReader {

    fun load(): StatusBarIconSnapshot {
        val enabled = HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)
        val order = readOrder()
        val configs = StatusBarIconRegistry.components.associate { component ->
            component.id to readComponent(component.id)
        }
        return StatusBarIconSnapshot(enabled, order, configs)
    }

    /** 只读排序，不展开 47 个组件的全部配置；用于每个图标插入时快速求目标位置。 */
    fun loadOrderedIds(): List<String> {
        val order = readOrder()
        if (order.isEmpty()) return emptyList()
        return StatusBarIconSnapshot(true, order, emptyMap()).reorderableOrder()
    }

    fun loadComponent(id: String): StatusBarIconComponentConfig = readComponent(id)

    private fun readOrder(): List<String> =
        HookPrefs.getString(StatusBarIconKeys.ORDER, "")
            .orEmpty()
            .split(',')
            .map(String::trim)
            .filter { it.isNotEmpty() && StatusBarIconRegistry.byId(it) != null }

    private fun readComponent(id: String): StatusBarIconComponentConfig {
        val fallback = StatusBarIconComponentConfig()
        val recommendedWeight = StatusBarIconRegistry.recommendedFontWeight(id)
        val storedWeight = HookPrefs.getIntFlexible(StatusBarIconKeys.fontWeight(id), recommendedWeight)
        val rawDisplay = (HookPrefs.getString(StatusBarIconKeys.display(id), "") ?: "").trim()
        // 左侧图标没有独立的控制中心宿主；旧的 CC_ONLY 值要归一化掉。
        // 权限指示现在也有独立的 CC 宿主，由运行时按 View 所在宿主判断。
        val display = if (!StatusBarIconRegistry.supportsControlCenterDisplay(id) &&
            rawDisplay in CC_ONLY_DISPLAYS
        ) {
            ""
        } else {
            rawDisplay
        }
        val storedMode = StatusBarIconMode.from(
            HookPrefs.getString(StatusBarIconKeys.mode(id), fallback.mode.value),
        )
        val rawSpeedFontSize = HookPrefs.getFloat(
            StatusBarIconKeys.speedFontSize(id),
            fallback.speedFontSize,
        ).coerceIn(0f, 24f)
        // 旧版本网速字号没有独立开关；已有字号值视为开启，保留用户设置。
        val fontSizeEnabled = if (id == StatusBarIconRegistry.NETWORK_SPEED) {
            HookPrefs.getBoolean(
                StatusBarIconKeys.fontSizeEnabled(id),
                rawSpeedFontSize > 0f,
            )
        } else {
            HookPrefs.getBoolean(
                StatusBarIconKeys.fontSizeEnabled(id),
                fallback.fontSizeEnabled,
            )
        }
        val mode = if (!StatusBarIconRegistry.supportsControlCenterDisplay(id) &&
            storedMode in CC_ONLY_MODES
        ) {
            StatusBarIconMode.SYSTEM
        } else {
            // display 是唯一可写入口；旧 mode 只在 display 为空时参与迁移。
            // 这里同时归一化 mode，供运行时所有“强制可见/解除 blocked”判断复用。
            normalizedMode(display, storedMode)
        }
        return StatusBarIconComponentConfig(
            display = display,
            mode = mode,
            condition = StatusBarIconCondition.from(
                HookPrefs.getString(StatusBarIconKeys.condition(id), fallback.condition.value),
            ),
            logic = StatusBarIconLogic.from(
                HookPrefs.getString(StatusBarIconKeys.logic(id), fallback.logic.value),
            ),
            alarmWithin = HookPrefs.getIntFlexible(
                StatusBarIconKeys.alarmWithin(id),
                fallback.alarmWithin,
            ).coerceIn(0, 72),
            fontWeightEnabled = HookPrefs.getBoolean(
                StatusBarIconKeys.fontWeightEnabled(id),
                fallback.fontWeightEnabled,
            ),
            fontSizeEnabled = fontSizeEnabled,
            offsetY = HookPrefs.getFloat(StatusBarIconKeys.offsetY(id), fallback.offsetY),
            gapStart = HookPrefs.getFloat(StatusBarIconKeys.gapStart(id), fallback.gapStart),
            gapEnd = HookPrefs.getFloat(StatusBarIconKeys.gapEnd(id), fallback.gapEnd),
            scale = HookPrefs.getFloat(StatusBarIconKeys.scale(id), fallback.scale),
            alpha = HookPrefs.getFloat(StatusBarIconKeys.alpha(id), fallback.alpha),
            align = HookPrefs.getString(StatusBarIconKeys.align(id), fallback.align) ?: fallback.align,
            fontWeight = if (storedWeight in 100..900) storedWeight else recommendedWeight,
            fontSize = HookPrefs.getFloat(
                StatusBarIconKeys.fontSize(id),
                StatusBarIconRegistry.recommendedFontSize(id).toFloat(),
            ),
            speedStyle = (HookPrefs.getString(
                StatusBarIconKeys.speedStyle(id),
                fallback.speedStyle,
            ) ?: fallback.speedStyle).takeIf { it in SPEED_STYLES } ?: fallback.speedStyle,
            speedFontSize = rawSpeedFontSize,
            speedRowGap = HookPrefs.getFloat(
                StatusBarIconKeys.speedRowGap(id),
                fallback.speedRowGap,
            ).coerceIn(-4f, 12f),
            speedDualArrowStyle = run {
                val stored = HookPrefs.getString(
                    StatusBarIconKeys.speedDualArrowStyle(id),
                    "",
                ).orEmpty()
                when {
                    stored in SPEED_ARROW_STYLES -> stored
                    // 旧版只有“显示箭头”开关；未写新键时按旧值迁移。
                    HookPrefs.getBoolean(
                        StatusBarIconKeys.speedDualArrows(id),
                        true,
                    ) -> "arrow"
                    else -> "none"
                }
            },
            speedDualArrowPosition = (HookPrefs.getString(
                StatusBarIconKeys.speedDualArrowPosition(id),
                fallback.speedDualArrowPosition,
            ) ?: fallback.speedDualArrowPosition).takeIf { it in SPEED_ARROW_POSITIONS }
                ?: fallback.speedDualArrowPosition,
            speedDualUploadFirst = HookPrefs.getBoolean(
                StatusBarIconKeys.speedDualUploadFirst(id),
                fallback.speedDualUploadFirst,
            ),
            speedUnitStyle = run {
                val stored = HookPrefs.getString(
                    StatusBarIconKeys.speedUnitStyle(id),
                    "",
                ).orEmpty()
                when {
                    stored in SPEED_UNIT_STYLES -> stored
                    // 旧版「显示单位」开关已移除；未迁移前仍按旧值隐藏单位。
                    !HookPrefs.getBoolean(StatusBarIconKeys.speedShowUnit(id), true) -> "none"
                    else -> fallback.speedUnitStyle
                }
            },
            speedSlowHide = HookPrefs.getBoolean(
                StatusBarIconKeys.speedSlowHide(id),
                fallback.speedSlowHide,
            ),
            speedSlowThreshold = HookPrefs.getIntFlexible(
                StatusBarIconKeys.speedSlowThreshold(id),
                fallback.speedSlowThreshold,
            ).coerceIn(0, 10_240),
            maxNotifications = HookPrefs.getIntFlexible(
                StatusBarIconKeys.maxNotifications(id),
                fallback.maxNotifications,
            ).coerceIn(1, 16),
            notificationMultiDot = HookPrefs.getBoolean(
                StatusBarIconKeys.notificationMultiDot(id),
                fallback.notificationMultiDot,
            ),
        )
    }

    private val CC_ONLY_DISPLAYS = setOf(
        StatusBarIconMode.CC_ONLY_SHOW.value,
        StatusBarIconMode.CC_ONLY_HIDE.value,
    )
    private val CC_ONLY_MODES = setOf(
        StatusBarIconMode.CC_ONLY_SHOW,
        StatusBarIconMode.CC_ONLY_HIDE,
    )
    private val SPEED_STYLES = setOf(
        "system",
        "number_only",
        "single_line",
        "dual_line",
    )
    private val SPEED_ARROW_STYLES = setOf("arrow", "triangle", "none")
    private val SPEED_ARROW_POSITIONS = setOf("left", "right")
    private val SPEED_UNIT_STYLES = setOf("k", "kb", "kbit", "kbit_s", "kb_s", "none")
}

/** 顺序配置只对 slot 型系统图标生效；独立 View 组件由 SystemUI 固定位置。 */
fun StatusBarIconSnapshot.reorderableOrder(): List<String> {
    val seen = HashSet<String>()
    return buildList {
        order.forEach { id ->
            val component = StatusBarIconRegistry.byId(id) ?: return@forEach
            if (component.reorderable && seen.add(id)) add(id)
        }
        StatusBarIconDefaults.rightOrder.forEach { id ->
            val component = StatusBarIconRegistry.byId(id) ?: return@forEach
            if (component.reorderable && seen.add(id)) add(id)
        }
    }
}
