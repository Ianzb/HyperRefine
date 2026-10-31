package cn.ianzb.hyperrefine.hook.systemserver

/**
 * 小窗密度（system_server）配置键。
 *
 * 无极小窗没有固定的系统缩放档位，窗口拉大 / 缩小时应用仍按全屏密度渲染；
 * 开启后由 `FreeformDensityHook` 在配置下发前按窗口面积比例降低 densityDpi。
 */
object FreeformDensityKeys {

    /** system_server 进程。 */
    const val TARGET = "system"

    /** 小窗字号 / 界面随窗口等比缩放。 */
    const val DENSITY = "freeform_density_scale"
}
