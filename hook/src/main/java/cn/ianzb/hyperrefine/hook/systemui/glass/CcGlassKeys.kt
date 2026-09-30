package cn.ianzb.hyperrefine.hook.systemui.glass

/**
 * 控制中心「柔光玻璃」配置键。
 *
 * 与 App 侧 `FeaturesPage` 的声明保持一致。
 */
object CcGlassKeys {

    /** 总开关：关闭时所有二级面板小开关不生效。 */
    const val MASTER = "cc_glass"

    /** 侧边音量条二级面板。 */
    const val SIDE_VOLUME = "cc_glass_side_volume"

    /** 控制中心音量二级面板。 */
    const val CC_VOLUME = "cc_glass_cc_volume"

    /** 亮度二级面板。 */
    const val BRIGHTNESS = "cc_glass_brightness"

    /** 移动数据二级详情面板。 */
    const val MOBILE_DATA = "cc_glass_mobile_data"

    /** WLAN 二级详情面板。 */
    const val WLAN = "cc_glass_wlan"

    /** 播放器（媒体）二级面板。 */
    const val MEDIA = "cc_glass_media"

    /** 调试日志：输出各面板视图树，便于适配。 */
    const val DEBUG = "cc_glass_debug"

    val all: List<String> = listOf(
        MASTER,
        SIDE_VOLUME,
        CC_VOLUME,
        BRIGHTNESS,
        MOBILE_DATA,
        WLAN,
        MEDIA,
        DEBUG,
    )
}
