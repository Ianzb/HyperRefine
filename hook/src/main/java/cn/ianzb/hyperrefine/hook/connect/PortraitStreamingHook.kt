package cn.ianzb.hyperrefine.hook.connect

import android.annotation.SuppressLint
import android.app.Activity
import android.content.res.Configuration
import android.content.res.Resources
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import java.lang.reflect.Method

/**
 * 允许平板竖屏流转应用：纠正 reason-9 首包的横竖尺寸倒置，并放开目标 sink Activity 的方向策略。
 *
 * 目标进程：`com.xiaomi.mirror`。
 *
 * 移植自 HyperConnectToolkit（Apache-2.0，见应用内「第三方许可」页）。
 */
class PortraitStreamingHook : BaseHook() {

    override val key: String = ConnectKeys.PORTRAIT_STREAMING

    override val deviceScope: Set<DeviceType> = setOf(DeviceType.PAD)

    override fun init() {
        val classLoader = target.classLoader
            ?: throw IllegalStateException("$tag: classLoader unavailable")

        installGeometryHook(classLoader)
        installOrientationHook(classLoader)
    }

    private fun installGeometryHook(classLoader: ClassLoader) {
        val messageClass = classLoader.loadClass(REQUEST_APP_SCREEN_MESSAGE_CLASS)
        val openMessageFactory = messageClass.getDeclaredMethod(
            "getRequestAppScreenOpenMessage",
            String::class.java,
            Int::class.java,
            Int::class.java,
            Int::class.java,
            Int::class.java,
            Int::class.java,
            Int::class.java,
            Int::class.java,
        ).apply { isAccessible = true }

        HookHelper.intercept(openMessageFactory) { chain ->
            val width = chain.getArg(2) as Int
            val height = chain.getArg(3) as Int
            val screenFrom = chain.getArg(6) as Int
            if (!CompatibilityPolicy.shouldRestorePortraitInitialBounds(currentOrientation(), screenFrom, width, height)) {
                chain.proceed()
            } else {
                val args = chain.args.toTypedArray()
                args[2] = height
                args[3] = width
                HookHelper.log("$tag: restored portrait PIN_APP bounds: ${width}x$height -> ${height}x$width")
                chain.proceed(args)
            }
        }
    }

    private fun installOrientationHook(classLoader: ClassLoader) {
        val sinkActivityClass = Class.forName(TABLET_SINK_ACTIVITY_CLASS, false, classLoader)
        val setRequestedOrientation: Method = Activity::class.java
            .getDeclaredMethod("setRequestedOrientation", Int::class.java)
            .apply { isAccessible = true }

        HookHelper.intercept(setRequestedOrientation) { chain ->
            val requested = chain.getArg(0) as Int
            val resolved = CompatibilityPolicy.resolveRequestedOrientation(
                sinkActivityClass.isInstance(chain.thisObject),
                requested,
            )
            if (resolved == requested) {
                chain.proceed()
            } else {
                chain.proceed(arrayOf<Any?>(resolved))
            }
        }
    }

    private fun currentOrientation(): Int {
        return try {
            val context = currentApplication()
            val resources = context?.resources ?: Resources.getSystem()
            resources.configuration.orientation
        } catch (t: Throwable) {
            HookHelper.log("$tag: could not read Xiaomi Mirror orientation", t)
            Configuration.ORIENTATION_UNDEFINED
        }
    }

    @SuppressLint("PrivateApi")
    private fun currentApplication(): android.content.Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? android.content.Context
    }.getOrNull()

    private companion object {
        const val REQUEST_APP_SCREEN_MESSAGE_CLASS = "com.xiaomi.mirror.message.RequestAppScreenMessage"
        const val TABLET_SINK_ACTIVITY_CLASS = "com.xiaomi.mirror.sink.g"
    }
}
