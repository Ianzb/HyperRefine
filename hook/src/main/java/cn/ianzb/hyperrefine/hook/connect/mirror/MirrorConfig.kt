package cn.ianzb.hyperrefine.hook.connect.mirror

import cn.ianzb.hyperrefine.hook.connect.MirrorKeys
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import kotlin.math.roundToInt

/**
 * 妙享桌面增强的配置读取与刷新率换算。
 *
 * 刷新率取值先按原始值（60–120）读取，再换算为 60 / 90 / 120 三档，
 * 与「妙享桌面增强」的处理保持一致。
 */
internal object MirrorConfig {

    fun floatingWindow(): Boolean = HookPrefs.getBoolean(MirrorKeys.FLOATING_WINDOW, false)

    fun minimizeOnShade(): Boolean = HookPrefs.getBoolean(MirrorKeys.MINIMIZE_ON_SHADE, false)

    /** 隐藏窗口左侧竖条（侧边关闭条）。 */
    fun hidePole(): Boolean = HookPrefs.getBoolean(MirrorKeys.HIDE_POLE, false)

    /**
     * 自由浮窗圆角（dp），默认 9 与原生 `card_view_round_radius` 一致。
     *
     * App 侧滑块以 Float 存储，优先按 Float 读取，再兼容整数存储。
     */
    fun floatingRadius(): Int {
        val value = HookPrefs.getFloat(MirrorKeys.FLOATING_RADIUS, Float.NaN)
        if (!value.isNaN()) return value.roundToInt().coerceIn(0, 60)
        return HookPrefs.getInt(MirrorKeys.FLOATING_RADIUS, 9).coerceIn(0, 60)
    }

    fun refreshRateEnabled(): Boolean = HookPrefs.getBoolean(MirrorKeys.REFRESH_RATE, false)

    /** 换算后的投屏刷新率（60 / 90 / 120）。 */
    fun fps(): Int = normalize(rawValue())

    /** 原始刷新率取值：App 侧下拉以字符串存储，兼容整数存储。 */
    private fun rawValue(): Int {
        HookPrefs.getString(MirrorKeys.REFRESH_RATE_VALUE, null)?.toIntOrNull()?.let { return it }
        return HookPrefs.getInt(MirrorKeys.REFRESH_RATE_VALUE, 120)
    }

    /** 把原始刷新率（60–120）换算为 60 / 90 / 120。 */
    fun normalize(raw: Int): Int = when {
        raw < 75 -> 60
        raw < 105 -> 90
        else -> 120
    }
}
