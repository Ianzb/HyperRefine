package cn.ianzb.hyperrefine.hook.connect

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.miuix.TopBarKeys
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * MiLink（`com.milink.service`）目标 Load：按进程精确路由设备互联功能。
 *
 * - `:ui`：跨设备通知流转的本机发现纠正、流转卡片玻璃、融合设备中心顶栏渐变模糊；
 * - `crossdeviceservice`：通知点击 PIN_APP 桥接；
 * - `:core`：Multi-Channel 通道策略与 Host 绑定修复；
 * - 含 netbus 发现选项类的进程：提高设备发现频率（开关）。
 */
class MiLinkLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        val cardGlass = HookPrefs.getBoolean(ConnectKeys.CARD_GLASS, false)
        when (target.processName) {
            PROCESS_MAIN -> initHook(MiLinkSettingsTrampolineHook(), true)
            PROCESS_UI -> {
                initHook(
                    MiLinkDiscoveryHook(),
                    HookPrefs.getBoolean(ConnectKeys.CROSS_DEVICE_NOTIFICATION, false),
                )
                initHook(MiLinkCardGlassHook(), cardGlass)
                // 融合设备中心（CirculateWorldActivity）：常驻顶栏渐变模糊（随「顶栏渐变」总开关）。
                initHook(CirculateWorldTopBarGradientHook(), HookPrefs.getBoolean(TopBarKeys.KEY, false))
            }
            PROCESS_FLOW -> initHook(
                MiLinkNotificationFlowHook(),
                HookPrefs.getBoolean(ConnectKeys.CROSS_DEVICE_NOTIFICATION, false),
            )
            PROCESS_CORE -> initHook(
                MiLinkMultiChannelHook(),
                HookPrefs.getBoolean(ConnectKeys.MILINK_MULTI_CHANNEL, false),
            )
        }
        // 提高设备发现频率：安装到含 netbus 发现选项类的进程（不限定具体进程名）。
        val loader = target.classLoader
        if (loader != null && Reflect.findClassIfExists(START_DISCOVERY_OPTIONS, loader) != null) {
            initHook(MiLinkDiscoveryFrequencyHook(), HookPrefs.getBoolean(ConnectKeys.DISCOVERY_FREQUENCY, false))
        }
    }

    companion object {
        const val TARGET_PACKAGE = "com.milink.service"
        const val PROCESS_MAIN = "com.milink.service"
        const val PROCESS_UI = "com.milink.service:ui"
        const val PROCESS_FLOW = "com.milink.crossdeviceservice"
        const val PROCESS_CORE = "com.milink.service:core"
        const val START_DISCOVERY_OPTIONS = "com.xiaomi.continuity.netbus.StartDiscoveryOptions"
    }
}
