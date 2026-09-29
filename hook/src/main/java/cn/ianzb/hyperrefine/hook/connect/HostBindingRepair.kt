package cn.ianzb.hyperrefine.hook.connect

import java.lang.ref.WeakReference
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * 仅在 MiLink 自身返回 `HostNotBound(215)` 后，重放官方绑定序列。
 *
 * 移植自 HyperConnectToolkit（Apache-2.0，见应用内「第三方许可」页）。
 */
class HostBindingRepair private constructor(
    private val headsetDiscoveriesField: Field,
    private val boundResultField: Field,
    private val getProxyMethod: Method,
    private val doBindMethod: Method,
    private val bindStatusMethod: Method,
    private val compatibilityMethod: Method,
    private val multiplatformProcessor: Any,
    private val overrideMethod: Method,
    private val startDiscoveryMethod: Method,
) {

    @Volatile
    private var host: WeakReference<Any?> = WeakReference(null)

    fun attach(discoveryHost: Any?) {
        host = WeakReference(discoveryHost)
    }

    @Throws(ReflectiveOperationException::class)
    fun bind(hostIdValue: Any?): Boolean {
        val hostId = hostIdValue as? String ?: return false
        if (hostId.isBlank()) return false
        val discoveryHost = host.get() ?: return false
        val discoveries = headsetDiscoveriesField.get(discoveryHost) as? Map<*, *> ?: return false
        val proxy = discoveries[hostId] ?: return false
        val remoteProxy = getProxyMethod.invoke(proxy) ?: return false

        synchronized(remoteProxy) {
            if (discoveries[hostId] !== proxy || boundResultField.getBoolean(proxy)) {
                return false
            }
            val bindResult = doBindMethod.invoke(remoteProxy, hostId)
            val statusValue = bindStatusMethod.invoke(bindResult)
            val status = (statusValue as? Number)?.toInt() ?: return false
            if (status != SUCCESS) return false
            overrideMethod.invoke(
                multiplatformProcessor,
                hostId,
                true,
                compatibilityMethod.invoke(bindResult),
            )
            boundResultField.setBoolean(proxy, true)
            startDiscoveryMethod.invoke(proxy)
        }
        return true
    }

    companion object {
        const val SUCCESS = 100
        const val HOST_NOT_BOUND = 215

        private const val DISCOVERY_HOST = "com.miui.headset.runtime.DiscoveryHost"
        private const val DISCOVERY_PROXY = "com.miui.headset.runtime.DiscoveryProxy"
        private const val MULTIPLATFORM_PROCESSOR = "com.miui.headset.runtime.MultiplatformProcessor"
        private const val COMPATIBILITY_EXTRA = "com.miui.headset.runtime.CompatibilityExtra"

        @Throws(ReflectiveOperationException::class)
        fun resolve(classLoader: ClassLoader): HostBindingRepair {
            val discoveryHostClass = Class.forName(DISCOVERY_HOST, false, classLoader)
            val discoveryProxyClass = Class.forName(DISCOVERY_PROXY, false, classLoader)
            val processorClass = Class.forName(MULTIPLATFORM_PROCESSOR, false, classLoader)
            val compatibilityExtraClass = Class.forName(COMPATIBILITY_EXTRA, false, classLoader)

            val headsetDiscoveriesField = field(discoveryHostClass, "headsetDiscoveries")
            val boundResultField = field(discoveryProxyClass, "boundResult")
            val getProxyMethod = method(discoveryProxyClass, "getProxy")
            val startDiscoveryMethod = method(discoveryProxyClass, "startDiscovery")

            val remoteProxyClass = getProxyMethod.returnType
            val doBindMethod = method(remoteProxyClass, "doBind", String::class.java)
            val bindResultClass = doBindMethod.returnType
            val bindStatusMethod = method(bindResultClass, "component1")
            val compatibilityMethod = method(bindResultClass, "component2")

            val processor = field(processorClass, "INSTANCE").get(null)
                ?: throw NoSuchFieldException("$MULTIPLATFORM_PROCESSOR.INSTANCE")
            val overrideMethod = method(
                processorClass,
                "override",
                String::class.java,
                Boolean::class.java,
                compatibilityExtraClass,
            )

            return HostBindingRepair(
                headsetDiscoveriesField,
                boundResultField,
                getProxyMethod,
                doBindMethod,
                bindStatusMethod,
                compatibilityMethod,
                processor,
                overrideMethod,
                startDiscoveryMethod,
            )
        }

        private fun field(owner: Class<*>, name: String): Field =
            owner.getDeclaredField(name).apply { isAccessible = true }

        private fun method(owner: Class<*>, name: String, vararg parameters: Class<*>): Method =
            owner.getDeclaredMethod(name, *parameters).apply { isAccessible = true }
    }
}
