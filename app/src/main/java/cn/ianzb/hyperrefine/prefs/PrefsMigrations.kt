package cn.ianzb.hyperrefine.prefs

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

    /** 执行所有迁移。 */
    fun run() {
        migratePositionDefaultTo90()
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
}
