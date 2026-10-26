package cn.ianzb.hyperrefine.hook.systemui.wallpaper

/**
 * 「深色模式自动压暗壁纸」配置键。
 *
 * 与 App 侧 `FeaturesPage` 的声明保持一致。
 */
object WallpaperDimKeys {

    /** 总开关。 */
    const val ENABLED = "wallpaper_dark_dim"

    /** 锁屏 / AOD 玻璃是否跟随压暗。 */
    const val GLASS_ENABLED = "wallpaper_dark_dim_glass"

    /** 桌面压暗程度（0–100，滑条以 Float 存储）。 */
    const val AMOUNT = "wallpaper_dark_dim_amount"

    /** 锁屏压暗程度（0–100，滑条以 Float 存储）。 */
    const val LOCK_AMOUNT = "wallpaper_dark_dim_lock_amount"

    /** 默认压暗程度（百分比），桌面与锁屏保持一致以兼容旧配置。 */
    const val DEFAULT_AMOUNT = 30f
}
