package cn.ianzb.hyperrefine.hook.systemui.radius

/**
 * 控制中心「圆角调整」配置键。
 *
 * 与 App 侧 `FeaturesPage` / `CornerRadiusActivity` 的声明保持一致。
 *
 * 结构：
 * - 总开关 [MASTER]
 * - 统一「组件圆角」[COMPONENT]（所有非背景组件）
 * - 统一「背景圆角」[BACKGROUND]（仅模糊区域背景）
 * - 各子项可选自定义（`customKey` + `valueKey`）
 */
object CcRadiusKeys {

    /** 总开关。 */
    const val MASTER = "cc_radius"

    /** 统一「组件圆角」（dp）：所有非背景组件未自定义时使用。 */
    const val COMPONENT = "cc_radius_component"

    /** 统一「背景圆角」（dp）：模糊区域背景未自定义时使用。 */
    const val BACKGROUND = "cc_radius_background"

    // ---------------- 组件 ----------------

    /** 横向磁贴（WLAN / 数据等大卡片）。 */
    const val HORIZONTAL_TILE = "horizontal_tile"

    /** 小磁贴（下方 1x1 组件）。 */
    const val SMALL_TILE = "small_tile"

    /** 播放器（一级媒体卡片）。 */
    const val MEDIA = "media"

    /** 一级亮度 / 音量滑块（二者共用同一视图，合并）。 */
    const val SLIDER_L1 = "slider_l1"

    /** 亮度二级滑块。 */
    const val BRIGHTNESS_L2 = "brightness_l2"

    /** 侧边音量列（一级，收起态）。 */
    const val SIDE_VOLUME_L1 = "side_volume_l1"

    /** 侧边音量列（二级，展开态）。 */
    const val SIDE_VOLUME_L2 = "side_volume_l2"

    /** 控制中心二级音量条（控制中心二级音量面板内的音量条）。 */
    const val CC_VOLUME_L2 = "cc_volume_l2"

    /** 静音 / 勿扰按钮。 */
    const val RINGER = "ringer"

    /** 定时滑块。 */
    const val TIMER = "timer"

    /** 融合设备中心入口。 */
    const val DEVICE_CENTER = "device_center"

    /** 多应用音量面板内部音量条。 */
    const val APP_VOLUME_BAR = "app_volume_bar"

    // ---------------- 背景（模糊区域） ----------------

    /** 亮度二级面板背景。 */
    const val BRIGHTNESS_L2_BG = "brightness_l2_bg"

    /** 播放器二级面板背景。 */
    const val MEDIA_L2_BG = "media_l2_bg"

    /** 详情（WLAN / 数据等）二级面板背景。 */
    const val DETAIL_BG = "detail_bg"

    /** 控制中心音量二级面板模糊背景。 */
    const val CC_VOLUME_L2_BG = "cc_volume_l2_bg"

    /** 侧边音量展开面板模糊背景。 */
    const val SIDE_VOLUME_L2_BG = "side_volume_l2_bg"

    /** 多应用音量面板背景。 */
    const val APP_VOLUME_PANEL = "app_volume_panel"

    /** 组件子项（顺序即设置页展示顺序）：一级组件在前，二级 / 其它在后。 */
    val COMPONENT_ITEMS: List<String> = listOf(
        HORIZONTAL_TILE,
        MEDIA,
        SLIDER_L1,
        BRIGHTNESS_L2,
        CC_VOLUME_L2,
        SIDE_VOLUME_L1,
        SIDE_VOLUME_L2,
        DEVICE_CENTER,
        SMALL_TILE,
        RINGER,
        TIMER,
        APP_VOLUME_BAR,
    )

    /** 背景子项。 */
    val BACKGROUND_ITEMS: List<String> = listOf(
        BRIGHTNESS_L2_BG,
        MEDIA_L2_BG,
        DETAIL_BG,
        CC_VOLUME_L2_BG,
        SIDE_VOLUME_L2_BG,
        APP_VOLUME_PANEL,
    )

    /** 全部子项。 */
    val ITEMS: List<String> = COMPONENT_ITEMS + BACKGROUND_ITEMS

    /** 是否背景类。 */
    fun isBackground(item: String): Boolean = item in BACKGROUND_ITEMS

    /** 子项自定义开关键。 */
    fun customKey(item: String): String = "cc_radius_${item}_custom"

    /** 子项圆角值键（dp）。 */
    fun valueKey(item: String): String = "cc_radius_$item"

    /** 统一组件圆角默认值（dp）。 */
    const val DEFAULT_COMPONENT = 35f

    /** 统一背景圆角默认值（dp）。 */
    const val DEFAULT_BACKGROUND = 40f

    /** 单项自定义默认值（dp）。 */
    const val DEFAULT_ITEM = 35f

    /** 亮度二级滑块默认圆角（dp，单项自定义开启后的默认值）。 */
    const val BRIGHTNESS_L2_VALUE_DEFAULT = 40f

    /** 多应用音量内部音量条「未自定义」时的默认圆角（dp，1.3.0 的默认值）。 */
    const val APP_VOLUME_BAR_RADIUS_DEFAULT = 20f

    /** 多应用音量面板背景「未自定义」时的默认圆角（dp，1.3.0 的默认值）。 */
    const val APP_VOLUME_PANEL_RADIUS_DEFAULT = 30f

    /** 单项数值默认值（dp，即单项自定义开启后的默认值）。 */
    fun itemValueDefault(item: String): Float = when (item) {
        BRIGHTNESS_L2 -> BRIGHTNESS_L2_VALUE_DEFAULT
        else -> DEFAULT_ITEM
    }

    /** 单项「未自定义」时的默认圆角（dp，1.3.0 / 开启自定义圆角前的值）。 */
    fun itemUncustomizedDefault(item: String): Float = when (item) {
        APP_VOLUME_BAR -> APP_VOLUME_BAR_RADIUS_DEFAULT
        APP_VOLUME_PANEL -> APP_VOLUME_PANEL_RADIUS_DEFAULT
        else -> itemValueDefault(item)
    }

    /** 单项自定义开关默认值（默认不自定义）。 */
    fun itemCustomDefault(item: String): Boolean = false
}
