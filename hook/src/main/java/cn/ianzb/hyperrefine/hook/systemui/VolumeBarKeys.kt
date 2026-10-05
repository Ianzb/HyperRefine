package cn.ianzb.hyperrefine.hook.systemui

/**
 * 「实验性功能 → 音量条」配置键。
 *
 * 均作用于系统界面（com.android.systemui）：
 * - 面板背景调节 / 音量条间距：模块自绘的多应用音量面板（[AppVolumePanel]）
 * - 音量条高度 / 宽度：统一覆盖 `VolumeColumnRes.getWidth` / `getHeight`，
 *   同时影响多应用面板与官方侧边音量条，且不改变音量条之间的间距。
 */
object VolumeBarKeys {

    /** 多应用面板背景调节总开关。 */
    const val PANEL_BG = "volume_bar_panel_bg"

    /** 面板背景上下内边距（dp）。 */
    const val PANEL_PAD_VERTICAL = "volume_bar_panel_pad_vertical"

    /** 面板背景左右内边距（dp）。 */
    const val PANEL_PAD_HORIZONTAL = "volume_bar_panel_pad_horizontal"

    /** 音量条高度 / 宽度调节总开关（统一多应用面板 + 官方侧边音量条）。 */
    const val BAR_SIZE = "volume_bar_size"

    /** 音量条高度（dp，收起态）。 */
    const val BAR_HEIGHT = "volume_bar_height"

    /** 音量条宽度（dp，收起态）。 */
    const val BAR_WIDTH = "volume_bar_width"

    /** 多应用音量条间距调节总开关。 */
    const val SPACING = "volume_bar_spacing"

    /** 多应用音量条之间的水平间距（dp）。 */
    const val COLUMN_SPACING = "volume_bar_column_spacing"

    /** 侧边音量条竖直位置调节总开关（关闭 = 官方自动位置，开启 = 按百分比自定义）。 */
    const val SIDE_POS = "volume_bar_side_pos"

    /** 侧边音量条竖屏位置百分比（100% 上边缘贴屏幕顶部，0% 下边缘贴屏幕底部）。 */
    const val SIDE_POS_PORTRAIT = "volume_bar_side_pos_portrait"

    /** 侧边音量条横屏位置百分比（100% 上边缘贴屏幕顶部，0% 下边缘贴屏幕底部）。 */
    const val SIDE_POS_LANDSCAPE = "volume_bar_side_pos_landscape"

    /** 侧边音量条二级（展开）面板整体竖直位置调节总开关。 */
    const val L2_POS = "volume_bar_l2_pos"

    /** 二级面板整体竖直位置：竖屏百分比（100% 上边缘贴屏幕顶部，0% 下边缘贴屏幕底部）。 */
    const val L2_POS_PORTRAIT = "volume_bar_l2_pos_portrait"

    /** 二级面板整体竖直位置：横屏百分比（100% 上边缘贴屏幕顶部，0% 下边缘贴屏幕底部）。 */
    const val L2_POS_LANDSCAPE = "volume_bar_l2_pos_landscape"

    // ---------------- 默认值（与官方 / 现状一致，改动前不改变外观） ----------------

    /** 侧边音量条竖屏位置默认百分比。 */
    const val DEFAULT_SIDE_POS_PORTRAIT = 70f

    /** 侧边音量条横屏位置默认百分比。 */
    const val DEFAULT_SIDE_POS_LANDSCAPE = 50f

    /** 二级面板竖屏位置默认百分比。 */
    const val DEFAULT_L2_POS_PORTRAIT = 70f

    /** 二级面板横屏位置默认百分比。 */
    const val DEFAULT_L2_POS_LANDSCAPE = 50f

    /** 面板背景内边距默认值（dp，等同现状的 16dp 四周内边距）。 */
    const val DEFAULT_PAD = 16f

    /** 音量条高度默认值（dp，`miui_volume_column_height`）。 */
    const val DEFAULT_HEIGHT = 172f

    /** 音量条宽度默认值（dp，`miui_volume_column_width`）。 */
    const val DEFAULT_WIDTH = 58f

    /** 多应用音量条间距默认值（dp）。 */
    const val DEFAULT_SPACING = 6f

    /** 官方音量列尺寸资源类（插件内）。 */
    const val VOLUME_COLUMN_RES_CLASS = "com.android.systemui.miui.volume.VolumeColumnRes"
}
