package cn.ianzb.hyperrefine.hook.connect

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/**
 * MiLink（`com.milink.service`）目标 Load：按进程精确路由设备互联功能。
 *
 * - `:ui`：跨设备通知流转的本机发现纠正；
 * - `crossdeviceservice`：通知点击 PIN_APP 桥接；
 * - `:core`：Multi-Channel 通道策略与 Host 绑定修复。
 */
class MiLinkLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        when (target.processName) {
            PROCESS_UI -> initHook(
                MiLinkDiscoveryHook(),
                HookPrefs.getBoolean(ConnectKeys.CROSS_DEVICE_NOTIFICATION, false),
            )
            PROCESS_FLOW -> initHook(
                MiLinkNotificationFlowHook(),
                HookPrefs.getBoolean(ConnectKeys.CROSS_DEVICE_NOTIFICATION, false),
            )
            PROCESS_CORE -> initHook(
                MiLinkMultiChannelHook(),
                HookPrefs.getBoolean(ConnectKeys.MILINK_MULTI_CHANNEL, false),
            )
        }
    }

    companion object {
        const val TARGET_PACKAGE = "com.milink.service"
        const val PROCESS_UI = "com.milink.service:ui"
        const val PROCESS_FLOW = "com.milink.crossdeviceservice"
        const val PROCESS_CORE = "com.milink.service:core"
    }
}
