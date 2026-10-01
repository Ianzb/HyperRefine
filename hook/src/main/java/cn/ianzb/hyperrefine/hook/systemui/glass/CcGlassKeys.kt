package cn.ianzb.hyperrefine.hook.systemui.glass

/**
 * 控制中心「柔光玻璃」配置键。
 *
 * 与 App 侧 `FeaturesPage` 的声明保持一致。
 */
object CcGlassKeys {
    /** 总开关：控制所有二级面板按钮 / 卡片的柔光玻璃。 */
    const val MASTER = "cc_glass"

    /**
     * 第三方主题下强制启用材质：绕过系统「非默认主题不启用高级材质」的门禁，
     * 让官方柔光玻璃 / 材质在使用第三方主题时照常生效。
     */
    const val THEME_MATERIAL = "cc_glass_theme_material"
}
