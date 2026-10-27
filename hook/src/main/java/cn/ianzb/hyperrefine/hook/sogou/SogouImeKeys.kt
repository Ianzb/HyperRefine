package cn.ianzb.hyperrefine.hook.sogou

/** 搜狗输入法（小米定制包）功能配置键。 */
object SogouImeKeys {

    /** 目标包：小米商店版搜狗输入法。 */
    const val PKG_SOGOU_IME = "com.sohu.inputmethod.sogou.xiaomi"

    /** 输入法键盘玻璃材质（背景模糊 + 圆角）。 */
    const val GLASS = "sogou_ime_glass"

    /** 背景模糊半径（dp）。 */
    const val BLUR_RADIUS = "sogou_ime_blur_radius"

    /** 键盘面板圆角半径（dp）。 */
    const val CORNER_RADIUS = "sogou_ime_corner_radius"

    const val DEFAULT_BLUR_RADIUS = 32f
    const val DEFAULT_CORNER_RADIUS = 24f
}
