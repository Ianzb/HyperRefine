package cn.ianzb.hyperrefine.prefs

import cn.ianzb.hyperrefine.hook.misound.AppVolumeKeys
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconKeys
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarIconRegistry
import cn.ianzb.hyperrefine.hook.systemui.wallpaper.WallpaperDimKeys

/**
 * 一次性配置迁移。应用启动时执行，已执行的迁移由标记键去重，不会重复改写用户配置。
 */
object PrefsMigrations {

    /** 百分比「显示位置」的各个位置前缀（与 `PercentLocation` 保持一致）。 */
    private val POSITION_LOCATIONS = listOf("cc_volume", "cc_brightness", "side_volume")

    /** 旧版本默认值。 */
    private const val OLD_DEFAULT_POSITION = 100f

    /** 新版本默认值。 */
    private const val NEW_DEFAULT_POSITION = 90f

    private const val MARKER_POSITION_90 = "migration_position_default_90"
    private const val MARKER_HORIZONTAL_CLOCK = "migration_horizontal_clock_empty"
    private const val MARKER_CLOCK_CUSTOM = "migration_clock_custom_switches"
    private const val MARKER_SPEED_STYLE = "migration_speed_style_v2"
    private const val MARKER_SPEED_OPTIONS_V3 = "migration_speed_options_v3"
    private const val MARKER_SPEED_UNIT_VISIBILITY_V4 = "migration_speed_unit_visibility_v4"
    private const val MARKER_WALLPAPER_DIM_LOCK = "migration_wallpaper_dim_lock_amount"
    private const val MARKER_VOLUME_ENTRY_VISIBILITY = "migration_volume_entry_visibility"

    /** 执行所有迁移。 */
    fun run() {
        migratePositionDefaultTo90()
        migrateHorizontalClockDefault()
        migrateClockCustomSwitches()
        migrateSpeedStyle()
        migrateSpeedOptionsV3()
        migrateSpeedUnitVisibilityV4()
        migrateWallpaperDimLockAmount()
        migrateVolumeEntryVisibility()
    }

    /**
     * 百分比「显示位置」默认值由 100 调整为 90。
     *
     * 旧版本默认值 100（紧贴上边缘）；新版本默认 90。把仍为旧默认值 100 的存量用户改为 90，
     * 用户主动调整过的值不受影响。
     */
    private fun migratePositionDefaultTo90() {
        if (PrefsStore.getBoolean(MARKER_POSITION_90, false)) return
        POSITION_LOCATIONS.forEach { location ->
            val key = "${location}_position"
            if (PrefsStore.getFloat(key, Float.NaN) == OLD_DEFAULT_POSITION) {
                PrefsStore.put(key, NEW_DEFAULT_POSITION)
            }
        }
        PrefsStore.put(MARKER_POSITION_90, true)
    }

    /**
     * 旧版本把 `HH:mm EEE MMMd` 当成横屏默认值并可能写入配置；
     * 新版本要求留空跟随系统，这里只清除仍等于该旧默认值的存量配置。
     */
    private fun migrateHorizontalClockDefault() {
        val key = StatusBarIconKeys.CLOCK_PATTERN_HORIZONTAL
        if (PrefsStore.getString(key, "") == StatusBarIconKeys.LEGACY_HORIZONTAL_CLOCK_PATTERN) {
            PrefsStore.remove(key)
        }
        if (PrefsStore.getBoolean(MARKER_HORIZONTAL_CLOCK, false)) return
        PrefsStore.put(MARKER_HORIZONTAL_CLOCK, true)
    }

    /**
     * 时钟格式从“空值跟随系统”改为“先开自定义开关再写格式”。
     * 旧安装里已经写过非空格式的，补开对应开关，避免升级后自定义格式失效。
     */
    private fun migrateClockCustomSwitches() {
        if (PrefsStore.getBoolean(MARKER_CLOCK_CUSTOM, false)) return
        CLOCK_PATTERN_KEYS.forEach { key ->
            if (PrefsStore.getString(key, "").orEmpty().trim().isNotEmpty()) {
                PrefsStore.put(StatusBarIconKeys.clockCustom(key), true)
            }
        }
        PrefsStore.put(MARKER_CLOCK_CUSTOM, true)
    }

    private val CLOCK_PATTERN_KEYS = listOf(
        StatusBarIconKeys.CLOCK_PATTERN_STATUS,
        StatusBarIconKeys.CLOCK_PATTERN_NOTIFICATION_BIG,
        StatusBarIconKeys.CLOCK_PATTERN_NOTIFICATION_SMALL,
        StatusBarIconKeys.CLOCK_PATTERN_CONTROL_CENTER,
        StatusBarIconKeys.CLOCK_PATTERN_HORIZONTAL,
        StatusBarIconKeys.CLOCK_PATTERN_PAD,
    )

    /**
     * 网速旧样式（数值在上/单位在上/仅单位）已经废弃，统一回到系统双行；
     * 单位换算开关也一并移除，避免下拉框显示一个不存在的选项。
     */
    private fun migrateSpeedStyle() {
        if (PrefsStore.getBoolean(MARKER_SPEED_STYLE, false)) return
        val id = StatusBarIconRegistry.NETWORK_SPEED
        val styleKey = StatusBarIconKeys.speedStyle(id)
        if (PrefsStore.getString(styleKey, "") in setOf(
                "number_top",
                "unit_top",
                "unit_only",
            )
        ) {
            PrefsStore.put(styleKey, "system")
        }
        PrefsStore.remove("statusbar_icon_${id}_speed_unit")
        PrefsStore.put(MARKER_SPEED_STYLE, true)
    }

    /**
     * 去掉单行样式；箭头从布尔开关迁移为箭头/三角/不显示，
     * 并给单位样式补默认值，避免旧安装显示一个空下拉。
     */
    private fun migrateSpeedOptionsV3() {
        if (PrefsStore.getBoolean(MARKER_SPEED_OPTIONS_V3, false)) return
        val id = StatusBarIconRegistry.NETWORK_SPEED
        val styleKey = StatusBarIconKeys.speedStyle(id)
        if (PrefsStore.getString(styleKey, "") in setOf(
                "inline",
                "number_top",
                "unit_top",
                "unit_only",
            )
        ) {
            PrefsStore.put(styleKey, "system")
        }
        val all = PrefsStore.getAll()
        val arrowKey = StatusBarIconKeys.speedDualArrowStyle(id)
        if (!all.containsKey(arrowKey)) {
            val arrowsEnabled = PrefsStore.getBoolean(
                StatusBarIconKeys.speedDualArrows(id),
                true,
            )
            PrefsStore.put(arrowKey, if (arrowsEnabled) "arrow" else "none")
        }
        val unitKey = StatusBarIconKeys.speedUnitStyle(id)
        if (!all.containsKey(unitKey)) {
            PrefsStore.put(unitKey, "kb_s")
        }
        PrefsStore.remove(StatusBarIconKeys.speedDualArrows(id))
        PrefsStore.put(MARKER_SPEED_OPTIONS_V3, true)
    }

    /**
     * 「显示单位」开关与单位样式里的「不显示单位」重复，移除开关。
     * 旧安装关闭过开关的，把单位样式改为 `none`，保持升级前后一致；随后删除旧键。
     */
    private fun migrateSpeedUnitVisibilityV4() {
        if (PrefsStore.getBoolean(MARKER_SPEED_UNIT_VISIBILITY_V4, false)) return
        val id = StatusBarIconRegistry.NETWORK_SPEED
        val showUnitKey = StatusBarIconKeys.speedShowUnit(id)
        if (!PrefsStore.getBoolean(showUnitKey, true)) {
            PrefsStore.put(StatusBarIconKeys.speedUnitStyle(id), "none")
        }
        PrefsStore.remove(showUnitKey)
        PrefsStore.put(MARKER_SPEED_UNIT_VISIBILITY_V4, true)
    }

    /**
     * 壁纸压暗从单一值拆成桌面 / 锁屏两个值。
     * 旧安装没有锁屏键时，用当前桌面值补齐，保持升级前后的观感一致；
     * 用户已经单独设置过锁屏值时不覆盖。
     */
    private fun migrateWallpaperDimLockAmount() {
        if (PrefsStore.getBoolean(MARKER_WALLPAPER_DIM_LOCK, false)) return
        if (!PrefsStore.getAll().containsKey(WallpaperDimKeys.LOCK_AMOUNT)) {
            val home = PrefsStore.getFloat(WallpaperDimKeys.AMOUNT, WallpaperDimKeys.DEFAULT_AMOUNT)
            PrefsStore.put(WallpaperDimKeys.LOCK_AMOUNT, home)
        }
        PrefsStore.put(MARKER_WALLPAPER_DIM_LOCK, true)
    }

    /**
     * 多应用音量入口由「常显 / 始终隐藏」两个开关合并为「自动 / 常显 / 始终隐藏」三选一。
     * 只迁移一次；已有新值时以新值为准。
     */
    private fun migrateVolumeEntryVisibility() {
        if (PrefsStore.getBoolean(MARKER_VOLUME_ENTRY_VISIBILITY, false)) return
        if (PrefsStore.getString(AppVolumeKeys.ENTRY_VISIBILITY, null).isNullOrEmpty()) {
            val value = when {
                PrefsStore.getBoolean(AppVolumeKeys.ALWAYS_HIDE, false) -> AppVolumeKeys.VISIBILITY_NEVER
                PrefsStore.getBoolean(AppVolumeKeys.ALWAYS_SHOW, false) -> AppVolumeKeys.VISIBILITY_ALWAYS
                else -> AppVolumeKeys.VISIBILITY_AUTO
            }
            PrefsStore.put(AppVolumeKeys.ENTRY_VISIBILITY, value)
        }
        PrefsStore.put(MARKER_VOLUME_ENTRY_VISIBILITY, true)
    }
}
