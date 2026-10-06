package cn.ianzb.hyperrefine.hook.connect

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 提高跨设备「发现」频率。
 *
 * 目标进程：`com.milink.service` 中加载了 netbus 发现选项类的进程。
 *
 * Milink 的发现 / 广播频率由 `com.xiaomi.continuity.netbus` 的
 * `StartDiscoveryOptions(V2).Builder.setFrequency(DiscoveryFrequency)` 与
 * `StartAdvertisingOptions(V2).Builder.setFrequency(AdvertisingFrequency)` 设置
 * （内部只存枚举 ordinal）。多处默认只到 `MEDIUM`，导致设备发现 / 列表刷新偏慢。
 *
 * 本 Hook 在开启后，把低于目标档位的调用**提升**为更高档位：
 * 发现（DiscoveryFrequency）抬到 `HIGH`，广播（AdvertisingFrequency）抬到 `MEDIUM_HIGH`；
 * 已是更高档位的不动。属于「更快发现设备」的加速，非连接稳定性修复。
 */
class MiLinkDiscoveryFrequencyHook : BaseHook() {

    override val key: String = ConnectKeys.DISCOVERY_FREQUENCY

    /** 防止重入（回调用反射重新调用被 hook 的 setter）。 */
    private val reentrant = ThreadLocal<Boolean>()

    override fun init() {
        val loader = target.classLoader ?: return

        hookSetter(loader, DISCOVERY_BUILDER, DISCOVERY_FREQUENCY_ENUM, "HIGH")
        hookSetter(loader, DISCOVERY_BUILDER_V2, DISCOVERY_FREQUENCY_ENUM, "HIGH")
        hookSetter(loader, ADVERTISING_BUILDER, ADVERTISING_FREQUENCY_ENUM, "MEDIUM_HIGH")
        hookSetter(loader, ADVERTISING_BUILDER_V2, ADVERTISING_FREQUENCY_ENUM, "MEDIUM_HIGH")
    }

    private fun hookSetter(loader: ClassLoader, builderName: String, enumName: String, targetName: String) {
        val builderClass = Reflect.findClassIfExists("$builderName\$Builder", loader) ?: return
        val enumClass = Reflect.findClassIfExists(enumName, loader) ?: return
        val enumConstants = enumClass.enumConstants ?: return
        val target = enumConstants.firstOrNull { (it as? Enum<*>)?.name == targetName } ?: return
        val targetOrdinal = (target as Enum<*>).ordinal

        val setter = Reflect.findMethodIfExists(builderClass, "setFrequency", enumClass) ?: return
        HookHelper.intercept(setter) { chain ->
            val incoming = chain.getArg(0) as? Enum<*>
            if (enabled() && reentrant.get() != true && incoming != null && incoming.ordinal < targetOrdinal) {
                reentrant.set(true)
                try {
                    Reflect.callMethod(chain.thisObject, "setFrequency", target)
                } finally {
                    reentrant.set(false)
                }
            } else {
                chain.proceed()
            }
        }
    }

    private fun enabled(): Boolean = HookPrefs.getBoolean(ConnectKeys.DISCOVERY_FREQUENCY, false)

    private companion object {
        const val NETBUS = "com.xiaomi.continuity.netbus"
        const val DISCOVERY_BUILDER = "$NETBUS.StartDiscoveryOptions"
        const val DISCOVERY_BUILDER_V2 = "$NETBUS.StartDiscoveryOptionsV2"
        const val ADVERTISING_BUILDER = "$NETBUS.StartAdvertisingOptions"
        const val ADVERTISING_BUILDER_V2 = "$NETBUS.StartAdvertisingOptionsV2"
        const val DISCOVERY_FREQUENCY_ENUM = "$NETBUS.DiscoveryOptions\$DiscoveryFrequency"
        const val ADVERTISING_FREQUENCY_ENUM = "$NETBUS.AdvertisingOptions\$AdvertisingFrequency"
    }
}
