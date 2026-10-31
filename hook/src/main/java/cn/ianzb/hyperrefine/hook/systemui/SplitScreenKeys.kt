package cn.ianzb.hyperrefine.hook.systemui

/**
 * 分屏 / 多分屏（小米 WMShell，运行于 `com.android.systemui` 进程）配置键。
 */
object SplitScreenKeys {

    /** 解除平板分屏方向限制（竖屏允许左右、横屏允许上下）。 */
    const val UNLOCK_DIRECTION = "sosc_unlock_split_direction"

    /** 多分屏数量上限（滑块值）。 */
    const val MULTIPLE_SPLIT_MAX_STAGES = "multiple_split_max_stages"

    /** 自定义多分屏数量（总开关：控制 Hook 是否生效）。 */
    const val MULTIPLE_SPLIT_CUSTOM = "multiple_split_stages_custom"

    /** 多分屏无级调节窗口宽度（分割线不再吸附固定档位）。 */
    const val MULTIPLE_SPLIT_FREE_RATIO = "multiple_split_free_ratio"

    /** 解除「单个应用分屏（另一半是桌面）」的比例限制。 */
    const val SINGLE_SPLIT_FREE_RATIO = "sosc_single_split_free_ratio"

    /** 全局分屏比例无级调节（两个应用分屏与单应用分屏都可在任意比例停留）。 */
    const val SPLIT_FREE_RATIO_GLOBAL = "sosc_split_free_ratio_global"

    /** 切换分屏方向（左右 ↔ 上下）时保持每个应用的占比不变。 */
    const val SPLIT_RATIO_MEMORY = "sosc_split_ratio_memory"

    /** 1:3 ↔ 1:9 拖拽过渡：小窗移出屏幕的量随分割线位置连续变化，不再跨档位瞬间跳变。 */
    const val SPLIT_PEEK_TRANSITION = "sosc_split_peek_transition"

    /** 禁用全屏应用底部角落（左下 / 右下）拖出无极小窗的手势。 */
    const val DISABLE_CORNER_FREEFORM = "sosc_disable_corner_freeform"

    /** 普通小窗四角拖动时自由调整宽高比（不再等比缩放）。 */
    const val FREEFORM_FREE_RATIO = "freeform_free_ratio"

    /** 自由比例接近官方档位时吸附到官方比例。 */
    const val FREEFORM_FREE_RATIO_SNAP = "freeform_free_ratio_snap"

    /** 全屏应用「···」把手菜单同时显示左右与上下两对分屏按钮。 */
    const val CAPTION_BOTH_DIRECTIONS = "mulwin_caption_both_split_directions"

    /** 官方区间：`MultipleSplitOrganizer.MIN_STAGES` / `DEFAULT_HIGH_RAM_MAX_STAGES`。 */
    const val MIN_STAGES = 3
    const val MAX_STAGES = 6
    const val DEFAULT_STAGES = 6
}
