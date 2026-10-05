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

    // ---------------- 默认值（与官方 / 现状一致，改动前不改变外观） ----------------

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
