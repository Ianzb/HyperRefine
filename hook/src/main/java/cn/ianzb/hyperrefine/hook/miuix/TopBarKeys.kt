package cn.ianzb.hyperrefine.hook.miuix

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
}
