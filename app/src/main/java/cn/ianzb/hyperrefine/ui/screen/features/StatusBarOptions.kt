package cn.ianzb.hyperrefine.ui.screen.features

import cn.ianzb.hyperrefine.R
import cn.ianzb.hyperrefine.hook.systemui.statusbar.StatusBarKeys as Keys
import cn.ianzb.hyperrefine.prefs.OptionSpec
import cn.ianzb.hyperrefine.prefs.OptionType
import cn.ianzb.hyperrefine.prefs.ConfigState

const val KEY_STATUS_BAR = "feature_statusbar"

internal fun statusBarFeatureSpecs(): List<OptionSpec> {
    fun toggle(key: String, title: Int) = OptionSpec(
        key = key, type = OptionType.SWITCH, titleRes = title,
        defaultBoolean = false, targetPackages = listOf(Keys.TARGET), showStatus = true,
    )
    // 规范（docs/API.md §5.7）：滑条必须设置 sliderValueLabelRes，标题才会与数值同行显示。
    fun slider(
        key: String, title: Int, value: Float, min: Float, max: Float, unit: Int, step: Float = 0.5f,
    ) = OptionSpec(
        key = key, type = OptionType.SLIDER, titleRes = title,
        sliderValueLabelRes = title, defaultFloat = value,
        sliderMin = min, sliderMax = max, sliderStep = step, sliderDecimals = 1,
        sliderUnitRes = unit,
        targetPackages = listOf(Keys.TARGET), dependsOn = Keys.DUAL_MODE,
        dependsOnValues = setOf(Keys.DUAL_AUTO, Keys.DUAL_ALWAYS),
    )
    return listOf(
        OptionSpec(key = KEY_STATUS_BAR, type = OptionType.ARROW, titleRes = R.string.sb_title),
        toggle(Keys.WIFI_ACTIVITY, R.string.sb_wifi_hide_activity),
        toggle(Keys.HIDE_TYPE, R.string.sb_mobile_hide_type),
        OptionSpec(key = Keys.DUAL_MODE, type = OptionType.DROPDOWN, titleRes = R.string.sb_dual_rows,
            summaryRes = R.string.sb_dual_rows_summary,
            defaultString = if (ConfigState.bool(Keys.DUAL_ROWS, false)) Keys.DUAL_AUTO else Keys.DUAL_HIDDEN,
            entryResIds = listOf(R.string.sb_dual_hidden, R.string.sb_dual_auto, R.string.sb_dual_always),
            entryValues = listOf(Keys.DUAL_HIDDEN, Keys.DUAL_AUTO, Keys.DUAL_ALWAYS),
            targetPackages = listOf(Keys.TARGET), showStatus = true),
        slider(Keys.DUAL_SCALE, R.string.sb_icon_scale, 1f, 0.5f, 2f, R.string.sb_unit_scale, 0.1f),
        // 垂直偏移统一使用“移动网络”二级页的布局项，不再在双排区重复一个滑条。
        slider(Keys.DUAL_LEFT_GAP, R.string.sb_left_gap, 0f, 0f, 8f, R.string.sb_unit_dp),
        slider(Keys.DUAL_RIGHT_GAP, R.string.sb_right_gap, 0f, 0f, 8f, R.string.sb_unit_dp),
    )
}

internal fun statusBarSpecs(specs: List<OptionSpec>): List<OptionSpec> =
    Keys.ALL.mapNotNull { key -> specs.firstOrNull { it.key == key } }
