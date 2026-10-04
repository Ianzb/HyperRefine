package cn.ianzb.hyperrefine.hook.connect

/**
 * 妙享桌面（`com.xiaomi.mirror`）增强功能配置键。
 *
 * 移植自「妙享桌面增强」（酷安 @ayyya）的接收端浮窗 / 下拉最小化 / 投屏刷新率请求。
 */
object MirrorKeys {

    /** 自由浮窗：接收端投屏窗口可拖动、四角缩放、收起为气泡并记住位置。 */
    const val FLOATING_WINDOW = "mirror_floating_window"

    /** 自由浮窗圆角大小（dp）。 */
    const val FLOATING_RADIUS = "mirror_floating_radius"

    /** 隐藏窗口左侧竖条（侧边关闭条）。 */
    const val HIDE_POLE = "mirror_hide_pole"

    /** 下拉通知栏时最小化：拉下通知 / 控制中心时收起投屏浮窗（仅接收端）。 */
    const val MINIMIZE_ON_SHADE = "mirror_minimize_on_shade"

    /** 投屏刷新率请求：向发送 / 接收端协商并请求指定刷新率。 */
    const val REFRESH_RATE = "mirror_refresh_rate"

    /** 投屏刷新率取值（60 / 90 / 120）。 */
    const val REFRESH_RATE_VALUE = "mirror_refresh_rate_value"
}
