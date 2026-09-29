package cn.ianzb.hyperrefine.hook.connect

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import java.lang.reflect.Method

/**
 * 解锁跨设备通知流转——通知点击流转桥接。
 *
 * 把一次通知点击转换为一次原生 `PIN_APP` 事务，并吞掉 MiLink 紧随其后的重复被动串流命令。
 * 目标进程：`com.milink.crossdeviceservice`。
 *
 * 移植自 HyperConnectToolkit（Apache-2.0，见应用内「第三方许可」页）。
 */
class MiLinkNotificationFlowHook : BaseHook() {

    override val key: String = ConnectKeys.CROSS_DEVICE_NOTIFICATION

    override val deviceScope: Set<DeviceType> = setOf(DeviceType.PAD)

    private val clickSession = NotificationClickSession()

    override fun init() {
        val classLoader = target.classLoader
            ?: throw IllegalStateException("$tag: classLoader unavailable")
        val bindings = Bindings.resolve(classLoader)

        HookHelper.intercept(bindings.launchNotificationSink) { chain ->
            val context = chain.getArg(0) as? Context
            val deviceId = chain.getArg(1) as? String
            val packageName = chain.getArg(2) as? String
            if (
                context == null ||
                deviceId == null ||
                packageName == null ||
                !CompatibilityPolicy.isValidNotificationClick(deviceId, packageName)
            ) {
                chain.proceed()
            } else {
                val dispatched = dispatchPinApp(context, deviceId, packageName)
                // 原生仍会在本回调后调用 handleNotification，吞掉那次成对命令。
                clickSession.begin(deviceId, packageName, SystemClock.elapsedRealtime())
                notifyRelayResult(
                    chain.getArg(3),
                    dispatched,
                    bindings.relaySuccess,
                    bindings.relayFailure,
                )
                if (dispatched) {
                    HookHelper.log("$tag: notification click dispatched via stock PIN_APP flow")
                }
                null
            }
        }

        HookHelper.intercept(bindings.publishNotification) { chain ->
            val deviceId = chain.getArg(0) as? String
            val message = chain.getArg(1)
            val packageName = runCatching {
                bindings.getPackageName.invoke(message) as? String
            }.getOrNull()

            if (packageName == null) {
                clickSession.clear()
                chain.proceed()
            } else if (!clickSession.consumeMatching(deviceId, packageName, SystemClock.elapsedRealtime())) {
                chain.proceed()
            } else {
                notifyPublishSuccess(chain.getArg(2), bindings.publishResult)
                HookHelper.log("$tag: consumed duplicate passive notification stream request")
                null
            }
        }
    }

    private fun dispatchPinApp(context: Context, deviceId: String, packageName: String): Boolean {
        return try {
            val extras = Bundle().apply {
                putString("remoteDeviceId", deviceId)
                putString("package", packageName)
            }
            val result = context.contentResolver.call(
                MIRROR_PROVIDER_AUTHORITY,
                CompatibilityPolicy.PIN_APP_PROVIDER_METHOD,
                null,
                extras,
            )
            if (result != null) {
                true
            } else {
                HookHelper.log("$tag: PIN_APP provider returned no result")
                false
            }
        } catch (t: Throwable) {
            HookHelper.log("$tag: could not dispatch notification through PIN_APP", t)
            false
        }
    }

    private fun notifyRelayResult(
        callback: Any?,
        success: Boolean,
        onSuccess: Method,
        onFailure: Method,
    ) {
        if (callback == null) return
        try {
            if (success) {
                onSuccess.invoke(callback)
            } else {
                onFailure.invoke(callback, -1)
            }
        } catch (t: Throwable) {
            HookHelper.log("$tag: could not report PIN_APP dispatch result", t)
        }
    }

    private fun notifyPublishSuccess(callback: Any?, onResult: Method) {
        if (callback == null) return
        try {
            onResult.invoke(callback, 1, null)
        } catch (t: Throwable) {
            HookHelper.log("$tag: could not acknowledge consumed notification command", t)
        }
    }

    private class Bindings(
        val launchNotificationSink: Method,
        val relaySuccess: Method,
        val relayFailure: Method,
        val publishNotification: Method,
        val getPackageName: Method,
        val publishResult: Method,
    ) {
        companion object {
            @Throws(ReflectiveOperationException::class)
            fun resolve(classLoader: ClassLoader): Bindings {
                val synergySdkClass = classLoader.loadClass(MIUI_SYNERGY_SDK_CLASS)
                val relayCallbackClass = classLoader.loadClass(RELAY_APP_CALLBACK_CLASS)
                val transClientClass = classLoader.loadClass(NOTIFICATION_TRANS_CLIENT_CLASS)
                val notificationMessageClass = classLoader.loadClass(NOTIFICATION_MESSAGE_CLASS)
                val transCallbackClass = classLoader.loadClass(NOTIFICATION_TRANS_CALLBACK_CLASS)

                return Bindings(
                    method(
                        synergySdkClass,
                        "launchAppFromPendingIntentSink",
                        Context::class.java,
                        String::class.java,
                        String::class.java,
                        relayCallbackClass,
                    ),
                    method(relayCallbackClass, "onSuccess"),
                    method(relayCallbackClass, "onFailure", Int::class.java),
                    method(
                        transClientClass,
                        "handleNotification",
                        String::class.java,
                        notificationMessageClass,
                        transCallbackClass,
                    ),
                    method(notificationMessageClass, "getPackageName"),
                    method(transCallbackClass, "onResult", Int::class.java, Any::class.java),
                )
            }

            private fun method(owner: Class<*>, name: String, vararg parameters: Class<*>): Method =
                owner.getDeclaredMethod(name, *parameters).apply { isAccessible = true }

            private const val MIUI_SYNERGY_SDK_CLASS = "com.xiaomi.mirror.synergy.MiuiSynergySdk"
            private const val RELAY_APP_CALLBACK_CLASS = "com.xiaomi.mirror.synergy.MiuiSynergySdk\$RelayAppCallback"
            private const val NOTIFICATION_TRANS_CLIENT_CLASS = "com.xiaomi.dist.notification.trans.client.NotifTransClient"
            private const val NOTIFICATION_MESSAGE_CLASS = "com.xiaomi.dist.notification.common.data.NotificationMessage"
            private const val NOTIFICATION_TRANS_CALLBACK_CLASS =
                "com.xiaomi.dist.notification.trans.client.callback.INotificationTransCallback"
        }
    }

    private companion object {
        const val MIRROR_PROVIDER_AUTHORITY = "com.xiaomi.mirror.callprovider"
    }
}
