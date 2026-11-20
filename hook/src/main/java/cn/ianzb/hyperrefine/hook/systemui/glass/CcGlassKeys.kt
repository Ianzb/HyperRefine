package cn.ianzb.hyperrefine.hook.systemui.glass

import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/**
 * 控制中心「柔光玻璃」配置键。
 *
 * 与 App 侧 `FeaturesPage` 的声明保持一致。
 */
object CcGlassKeys {
    /** 总开关：控制所有二级面板按钮 / 卡片的柔光玻璃。 */
    const val MASTER = "cc_glass"

    const val BRIGHTNESS = "cc_glass_brightness"
    const val NETWORK = "cc_glass_network"
    const val MEDIA = "cc_glass_media"
    const val CC_VOLUME = "cc_glass_cc_volume"
    const val SIDE_VOLUME = "cc_glass_side_volume"
    const val DEVICE_CENTER = "cc_glass_device_center"
    const val DEVICE_CARD = "cc_glass_device_card"

    fun isEnabled(scene: String): Boolean =
        HookPrefs.getBoolean(MASTER, false) && HookPrefs.getBoolean(scene, true)

    /**
     * 第三方主题下强制启用材质：绕过系统「非默认主题不启用高级材质」的门禁，
     * 让官方柔光玻璃 / 材质在使用第三方主题时照常生效。
     */
    const val THEME_MATERIAL = "cc_glass_theme_material"
}
