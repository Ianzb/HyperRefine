package cn.ianzb.hyperrefine.hook.miuix

import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/**
 * 顶栏渐变配置键。
 *
 * 移植自 HyperBackground（MIT）的顶栏模糊（`dynamic/topbar/DynamicActionBarHook`）。
 */
object TopBarKeys {

    /** 顶栏渐变模糊总开关。 */
    const val KEY = "top_bar_gradient"

    /** 模糊强度（相对顶栏高度的百分比，0–100）。 */
    const val STRENGTH = "top_bar_gradient_strength"

    /** 模糊不透明度（0–100）。 */
    const val OPACITY = "top_bar_gradient_opacity"

    /** 应用专属适配（高级功能）总开关：统一控制各应用的单独处理。 */
    const val ADVANCED = "top_bar_gradient_advanced"

    /** 笔记专属适配：屏蔽笔记「筛选 / 分类标签」栏内置的重复渐变遮罩。 */
    const val ADVANCED_NOTES = "top_bar_gradient_advanced_notes"

    /** 计算器专属适配：新版 MIUIX 遮罩特征 + 各页滚动区间距。 */
    const val ADVANCED_CALCULATOR = "top_bar_gradient_advanced_calculator"

    /** 文件管理专属适配：修复文件夹页文件列表被路径栏遮挡。 */
    const val ADVANCED_FILE_EXPLORER = "top_bar_gradient_advanced_file_explorer"

    /** 应用专属适配总开关是否开启（默认开启，保持原有行为）。 */
    fun advancedEnabled(): Boolean = HookPrefs.getBoolean(ADVANCED, true)

    /** 笔记专属适配是否开启。 */
    fun notesAdvancedEnabled(): Boolean =
        advancedEnabled() && HookPrefs.getBoolean(ADVANCED_NOTES, true)

    /** 计算器专属适配是否开启。 */
    fun calculatorAdvancedEnabled(): Boolean =
        advancedEnabled() && HookPrefs.getBoolean(ADVANCED_CALCULATOR, true)

    /** 文件管理专属适配是否开启。 */
    fun fileExplorerAdvancedEnabled(): Boolean =
        advancedEnabled() && HookPrefs.getBoolean(ADVANCED_FILE_EXPLORER, true)
}
