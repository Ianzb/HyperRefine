package cn.ianzb.hyperrefine.hook.connect

/**
 * 设备互联功能配置键。
 *
 * Hook 侧 [cn.ianzb.hyperrefine.hook.base.BaseHook.key] 与 App 侧 `OptionSpec.key` 共用本常量，
 * 保证「已安装回报」能正确映射到 UI 配置项。
 */
object ConnectKeys {

    /** 解锁跨设备通知流转（平板）。 */
    const val CROSS_DEVICE_NOTIFICATION = "connect_cross_device_notification"

    /** 允许平板竖屏流转应用（平板）。 */
    const val PORTRAIT_STREAMING = "connect_portrait_streaming"

    /** MiLink Multi-Channel。 */
    const val MILINK_MULTI_CHANNEL = "connect_milink_multi_channel"

    /** 融合设备中心流转卡片补柔光玻璃。 */
    const val CARD_GLASS = "device_center_card_glass"
}
