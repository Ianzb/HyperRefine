package cn.ianzb.hyperrefine.hook.connect

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import java.lang.reflect.Method

/**
 * 解锁跨设备通知流转——本机设备发现前置检查纠正。
 *
 * 仅在 MiLink 评估其同步本机前置条件时绕过 Cast 位；远端设备能力协商仍走原生实现。
 * 目标进程：`com.milink.service:ui`。
 *
 * 移植自 HyperConnectToolkit（Apache-2.0，见应用内「第三方许可」页）。
 */
class MiLinkDiscoveryHook : BaseHook() {

    override val key: String = ConnectKeys.CROSS_DEVICE_NOTIFICATION

    override val deviceScope: Set<DeviceType> = setOf(DeviceType.PAD)

    private val localDiscoveryDepth = ThreadLocal<Int>()

    override fun init() {
        val classLoader = target.classLoader
            ?: throw IllegalStateException("$tag: classLoader unavailable")
        val discoverClass = classLoader.loadClass(DISCOVER_CLASS)
        val serviceNameClass = classLoader.loadClass(CONTINUITY_SERVICE_NAME_CLASS)
        val localServiceSupport = method(discoverClass, "isLocalServiceDataSupport", serviceNameClass)
        val castSupport = method(discoverClass, "isCastServiceSupport", String::class.java)

        HookHelper.intercept(localServiceSupport) { chain ->
            enterLocalDiscovery()
            try {
                chain.proceed()
            } finally {
                exitLocalDiscovery()
            }
        }

        HookHelper.intercept(castSupport) { chain ->
            if (isLocalDiscoveryActive()) true else chain.proceed()
        }
    }

    private fun enterLocalDiscovery() {
        val depth = localDiscoveryDepth.get()
        localDiscoveryDepth.set(if (depth == null) 1 else depth + 1)
    }

    private fun exitLocalDiscovery() {
        val depth = localDiscoveryDepth.get()
        if (depth == null || depth <= 1) {
            localDiscoveryDepth.remove()
        } else {
            localDiscoveryDepth.set(depth - 1)
        }
    }

    private fun isLocalDiscoveryActive(): Boolean {
        val depth = localDiscoveryDepth.get()
        return depth != null && depth > 0
    }

    private fun method(owner: Class<*>, name: String, vararg parameters: Class<*>): Method =
        owner.getDeclaredMethod(name, *parameters).apply { isAccessible = true }

    private companion object {
        const val DISCOVER_CLASS = "com.xiaomi.dist.notification.discover.LyraDiscover"
        const val CONTINUITY_SERVICE_NAME_CLASS = "com.xiaomi.continuity.ServiceName"
    }
}
