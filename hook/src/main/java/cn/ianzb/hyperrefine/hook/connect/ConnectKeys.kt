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

    /** 提高设备发现频率。 */
    const val DISCOVERY_FREQUENCY = "connect_discovery_frequency"

    /** 跨设备剪贴板同步加速。 */
    const val CLIPBOARD_SYNC_BOOST = "connect_clipboard_sync_boost"

    /** 融合设备中心流转卡片补柔光玻璃。 */
    const val CARD_GLASS = "device_center_card_glass"

    /**
     * 「跨设备通知流转设置」跳板 extra。
     *
     * `FeatureNotificationActivity` 受签名级权限保护，普通应用无法直接启动；App 侧以该 extra 启动
     * 目标应用的导出入口 `com.milink.ui.setting.SettingActivity`，由 Hook 侧在同一进程内改启目标页面。
     */
    const val EXTRA_OPEN_NOTIFICATION_SETTINGS = "cn.ianzb.hyperrefine.open_notification_settings"
}
