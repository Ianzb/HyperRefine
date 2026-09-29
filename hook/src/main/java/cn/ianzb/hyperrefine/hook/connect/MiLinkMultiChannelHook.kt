package cn.ianzb.hyperrefine.hook.connect

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * MiLink Multi-Channel：AndroidPad 通道上限 1 → 2，并仅在本机返回 `HostNotBound(215)` 时补做官方绑定。
 *
 * 目标进程：`com.milink.service:core`。
 *
 * 移植自 HyperConnectToolkit（Apache-2.0，见应用内「第三方许可」页）。
 */
class MiLinkMultiChannelHook : BaseHook() {

    override val key: String = ConnectKeys.MILINK_MULTI_CHANNEL

    override fun init() {
        val classLoader = target.classLoader
            ?: throw IllegalStateException("$tag: classLoader unavailable")
        val bindings = Bindings.resolve(classLoader)

        HookHelper.intercept(bindings.channelPolicyConstructor) { chain ->
            val result = chain.proceed()
            when (ChannelLimitPolicy.ensureAndroidPadDualChannel(chain.thisObject)) {
                ChannelLimitPolicy.Result.UPDATED ->
                    HookHelper.log("$tag: MiLink AndroidPad channel limit set to 2")
                ChannelLimitPolicy.Result.UNSUPPORTED ->
                    HookHelper.log("$tag: MiLink channel policy layout is unsupported")
                ChannelLimitPolicy.Result.ALREADY_SUPPORTED -> Unit
            }
            result
        }

        HookHelper.intercept(bindings.discoveryHostConstructor) { chain ->
            val result = chain.proceed()
            bindings.hostBinding.attach(chain.thisObject)
            result
        }

        HookHelper.intercept(bindings.circulateCheck) { chain ->
            val result = chain.proceed()
            val status = (result as? Number)?.toInt()
            if (status != HostBindingRepair.HOST_NOT_BOUND) {
                result
            } else {
                val hostId = chain.getArg(0)
                try {
                    if (bindings.hostBinding.bind(hostId)) {
                        HookHelper.log("$tag: MiLink official Host bind completed: $hostId")
                        HostBindingRepair.SUCCESS
                    } else {
                        result
                    }
                } catch (t: ReflectiveOperationException) {
                    HookHelper.log("$tag: MiLink official Host bind failed: $hostId", t)
                    result
                } catch (t: RuntimeException) {
                    HookHelper.log("$tag: MiLink official Host bind failed: $hostId", t)
                    result
                }
            }
        }
    }

    private class Bindings(
        val channelPolicyConstructor: Constructor<*>,
        val discoveryHostConstructor: Constructor<*>,
        val circulateCheck: Method,
        val hostBinding: HostBindingRepair,
    ) {
        companion object {
            @Throws(ReflectiveOperationException::class)
            fun resolve(classLoader: ClassLoader): Bindings {
                val sharedChannelImpl = Class.forName(SHARED_CHANNEL_IMPL_CLASS, false, classLoader)
                val policyField: Field = sharedChannelImpl.getDeclaredField(SHARED_CHANNEL_POLICY_FIELD)
                val discoveryHost = Class.forName(DISCOVERY_HOST_CLASS, false, classLoader)
                val processor = Class.forName(MULTIPLATFORM_PROCESSOR_CLASS, false, classLoader)
                val circulateCheck: Method = processor.getDeclaredMethod("circulateCheck", String::class.java)
                    .apply { isAccessible = true }

                return Bindings(
                    onlyConstructor(policyField.type),
                    onlyConstructor(discoveryHost),
                    circulateCheck,
                    HostBindingRepair.resolve(classLoader),
                )
            }

            @Throws(NoSuchMethodException::class)
            private fun onlyConstructor(type: Class<*>): Constructor<*> {
                val constructors = type.declaredConstructors
                if (constructors.size != 1) {
                    throw NoSuchMethodException(
                        "${type.name} expected one constructor, found ${constructors.size}"
                    )
                }
                return constructors[0].apply { isAccessible = true }
            }

            private const val SHARED_CHANNEL_IMPL_CLASS = "com.miui.circulate.channel.SharedChannelImpl"
            private const val SHARED_CHANNEL_POLICY_FIELD = "sharedChannelPolicy"
            private const val DISCOVERY_HOST_CLASS = "com.miui.headset.runtime.DiscoveryHost"
            private const val MULTIPLATFORM_PROCESSOR_CLASS = "com.miui.headset.runtime.MultiplatformProcessor"
        }
    }
}
