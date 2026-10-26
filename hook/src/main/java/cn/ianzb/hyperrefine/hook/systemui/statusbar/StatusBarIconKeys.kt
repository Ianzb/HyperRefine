package cn.ianzb.hyperrefine.hook.systemui.statusbar

/**
 * 状态栏图标自定义的配置键。
 *
 * 与项目既有约定一致：App 侧写入、Hook 侧只读；持久化时由两端自动补 `prefs_key_` 前缀。
 * 这里只描述“系统栏图标视图”的可调项，不引入新的作用域或系统权限。
 */
object StatusBarIconKeys {
    const val TARGET = StatusBarKeys.TARGET

    /** 总开关；关闭时不改写任何原生视图。 */
    const val ENABLED = "statusbar_icon_custom"

    /** 右侧系统图标区顺序，逗号分隔的组件 id；空表示保持系统顺序。 */
    const val ORDER = "statusbar_icon_order"

    private const val PREFIX = "statusbar_icon_"

    /** 统一显示条件：system / always / hidden / cc_only_* / condition:<id>。 */
    fun display(id: String) = "${PREFIX}${id}_display"

    fun mode(id: String) = "${PREFIX}${id}_mode"
    fun condition(id: String) = "${PREFIX}${id}_condition"
    fun logic(id: String) = "${PREFIX}${id}_logic"
    fun alarmWithin(id: String) = "${PREFIX}${id}_alarm_within"
    fun offsetY(id: String) = "${PREFIX}${id}_offset_y"
    fun gapStart(id: String) = "${PREFIX}${id}_gap_start"
    fun gapEnd(id: String) = "${PREFIX}${id}_gap_end"
    fun scale(id: String) = "${PREFIX}${id}_scale"
    fun alpha(id: String) = "${PREFIX}${id}_alpha"
    fun align(id: String) = "${PREFIX}${id}_align"
    fun fontWeightEnabled(id: String) = "${PREFIX}${id}_font_weight_enabled"
    fun fontWeight(id: String) = "${PREFIX}${id}_font_weight"
    fun fontSizeEnabled(id: String) = "${PREFIX}${id}_font_size_enabled"
    fun fontSize(id: String) = "${PREFIX}${id}_font_size"

    /** 时钟各系统场景的自定义 SimpleDateFormat 格式；仅在对应自定义开关开启时生效。 */
    const val CLOCK_PATTERN_STATUS = "${PREFIX}clock_pattern_status"
    const val CLOCK_PATTERN_NOTIFICATION_BIG = "${PREFIX}clock_pattern_notification_big"
    const val CLOCK_PATTERN_NOTIFICATION_SMALL = "${PREFIX}clock_pattern_notification_small"
    const val CLOCK_PATTERN_CONTROL_CENTER = "${PREFIX}clock_pattern_control_center"
    const val CLOCK_PATTERN_HORIZONTAL = "${PREFIX}clock_pattern_horizontal"

    /** 把系统或自定义格式中的中文时段替换为 AM/PM；24 小时制文本不含时段词。 */
    const val CLOCK_AM_PM = "${PREFIX}clock_am_pm"

    /** 平板时间右侧的月份/日期/星期，仅在平板显示入口。 */
    const val CLOCK_PATTERN_PAD = "${PREFIX}clock_pattern_pad"

    /** 某个时钟场景是否启用自定义格式；关闭时完全跟随系统，不读取/应用格式值。 */
    fun clockCustom(patternKey: String) = "${patternKey}_custom"

    /** 旧版本曾把横屏默认格式写成该值；仅用于一次性迁移。 */
    const val LEGACY_HORIZONTAL_CLOCK_PATTERN = "HH:mm EEE MMMd"

    /** 是否用主线程 1s ticker 驱动所有已注册时钟；没有秒的格式可关闭。 */
    const val CLOCK_REFRESH = "${PREFIX}clock_refresh"

    /** 状态栏时钟固定宽度开关与宽度值（dp），避免秒/日期变化推动右侧图标。 */
    const val CLOCK_LOCK_WIDTH = "${PREFIX}clock_lock_width"
    const val CLOCK_WIDTH = "${PREFIX}clock_width"

    fun speedInterval(id: String) = "${PREFIX}${id}_speed_interval"
    fun speedShowUnit(id: String) = "${PREFIX}${id}_speed_show_unit"
    fun speedStyle(id: String) = "${PREFIX}${id}_speed_style"
    fun speedFontSize(id: String) = "${PREFIX}${id}_speed_font_size"
    fun speedRowGap(id: String) = "${PREFIX}${id}_speed_row_gap"
    fun speedDualArrowStyle(id: String) = "${PREFIX}${id}_speed_dual_arrow_style"
    fun speedDualArrowPosition(id: String) = "${PREFIX}${id}_speed_dual_arrow_position"
    fun speedUnitStyle(id: String) = "${PREFIX}${id}_speed_unit_style"
    /** 旧版箭头开关，仅用于迁移到 [speedDualArrowStyle]。 */
    fun speedDualArrows(id: String) = "${PREFIX}${id}_speed_dual_arrows"
    fun speedDualUploadFirst(id: String) = "${PREFIX}${id}_speed_dual_upload_first"
    fun speedSlowHide(id: String) = "${PREFIX}${id}_speed_slow_hide"
    fun speedSlowThreshold(id: String) = "${PREFIX}${id}_speed_slow_threshold"
    fun maxNotifications(id: String) = "${PREFIX}${id}_max_notifications"

    /** 通知溢出时按隐藏图标数量绘制 1..3 个圆点；关闭时保持系统固定三点。 */
    fun notificationMultiDot(id: String) = "${PREFIX}${id}_notification_multi_dot"
}
