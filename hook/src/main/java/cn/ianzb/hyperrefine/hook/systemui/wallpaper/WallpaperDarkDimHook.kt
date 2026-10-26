package cn.ianzb.hyperrefine.hook.systemui.wallpaper

import android.app.Application
import android.app.KeyguardManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.ref.WeakReference
import java.lang.reflect.Method

/**
 * 深色模式自动压暗壁纸。
 *
 * 系统界面一直持有 `android.permission.SET_WALLPAPER_DIM_AMOUNT`，通过框架的
 * `android.app.WallpaperManager#setWallpaperDimAmount(float)`（@SystemApi，由
 * `IWallpaperManager.setWallpaperDimAmount` 落到 `WallpaperManagerService` 的
 * `mUidToDimAmount`）即可把壁纸整体压暗，深色模式恢复时置 0。
 *
 * 实时跟随：拿到应用 Context 后，挂钩其**具体 Application 类**上最靠下的
 * `onConfigurationChanged(Configuration)` 实现——系统界面的 Application 未必会把配置变化
 * 继续分发给 `ComponentCallbacks`，直接挂钩该方法才能保证深浅色切换时被通知；同时仍注册
 * [ComponentCallbacks] 作为兜底，并监听远程偏好让滑条改动即时生效（宿主不支持监听时退化为
 * 「重启系统界面」后生效）。只在数值真正变化时才发起跨进程调用，避免频繁 IPC。
 *
 * 取壁纸做模糊的界面（控制中心、负一屏）读的是壁纸纹理本身、看不到这层 alpha，由
 * [WallpaperDimBlurDim] 在这些界面上补一层同浓度的压暗。
 *
 * 仅调用系统自身接口，不复制任何系统代码。
 */
class WallpaperDarkDimHook : BaseHook() {

    override val key: String = WallpaperDimKeys.ENABLED

    /** 已注册回调的 Application，避免重复注册。 */
    private val attached =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Application, Boolean>())

    private var managerRef: WeakReference<Any>? = null

    private var dimMethod: Method? = null

    /** 最近一次已写入的压暗值（null 表示尚未写入），用于跳过无变化的调用。 */
    private var applied: Float? = null

    override fun init() {
        val cl = target.classLoader ?: return
        val app = currentApplication()
        WallpaperDimBlurDim.install(app)
        if (app != null) {
            attach(app)
        } else {
            // 应用尚未创建时，等 Application#onCreate 之后拿到 Context 再挂。
            hookApplicationCreate(cl)
        }
    }

    private fun currentApplication(): Application? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Application
    }.getOrNull()

    private fun hookApplicationCreate(cl: ClassLoader) {
        val cls = Reflect.findClassIfExists("android.app.Application", cl) ?: return
        val onCreate = cls.declaredMethods.firstOrNull { it.name == "onCreate" && it.parameterCount == 0 }
            ?: return
        onCreate.isAccessible = true
        runCatching {
            HookHelper.hookAfter(onCreate) { param ->
                (param.thisObject as? Application)?.let { attach(it) }
            }
        }.onFailure { HookHelper.log("$tag: hook Application#onCreate failed", it) }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private fun attach(app: Application) {
        if (!attached.add(app)) return
        hookConfigChanged(app)
        hookKeyguardState(app)
        runCatching {
            app.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) = applyDim(app, force = true)

                override fun onLowMemory() {}
            })
        }.onFailure { HookHelper.log("$tag: registerComponentCallbacks failed", it) }
        runCatching { HookPrefs.registerListener { applyDim(app, force = true) } }
            .onFailure { HookHelper.log("$tag: prefs listener unavailable", it) }
        applyDim(app, force = true)
    }

    /**
     * 挂钩具体 Application 类上最靠下的 `onConfigurationChanged(Configuration)`。
     *
     * 应用级配置变化的实际入口是这一层实现，若子类覆写后没有 `super` 调用，
     * 只注册 [ComponentCallbacks] 会收不到通知。
     */
    private fun hookConfigChanged(app: Application) {
        val paramType = Configuration::class.java
        var current: Class<*>? = app.javaClass
        while (current != null) {
            val declared = current.declaredMethods.firstOrNull {
                it.name == "onConfigurationChanged" && it.parameterTypes.contentEquals(arrayOf(paramType))
            }
            if (declared != null) {
                val owner = current
                declared.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(declared) { applyDim(app, force = true) }
                }.onFailure {
                    HookHelper.log("$tag: hook ${owner.name}#onConfigurationChanged failed", it)
                }
                return
            }
            current = current.superclass
        }
        HookHelper.log("$tag: onConfigurationChanged not found on ${app.javaClass.name}")
    }

    /**
     * 锁屏 / 解锁时重新下发当前应使用的压暗值。
     *
     * system_server 侧按 `DisplayConnector` 拆分是最准确的方案，但 system_server 的 hook 需要
     * 重启后才注入；这里同时按锁屏状态切换公开接口的写入值，保证未重启时锁屏滑条也不会失效。
     */
    private fun hookKeyguardState(app: Application) {
        val cls = Reflect.findClassIfExists(KEYGUARD_STATE_CONTROLLER, app.classLoader) ?: run {
            HookHelper.log("$tag: $KEYGUARD_STATE_CONTROLLER not found")
            return
        }
        val method = cls.declaredMethods.firstOrNull {
            it.name == "notifyKeyguardState" &&
                it.parameterTypes.contentEquals(arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType))
        } ?: run {
            HookHelper.log("$tag: notifyKeyguardState not found")
            return
        }
        method.isAccessible = true
        runCatching {
            HookHelper.hookAfter(method) { param ->
                applyDim(
                    app,
                    force = true,
                    keyguardOverride = param.args.getOrNull(0) as? Boolean,
                )
            }
        }.onFailure { HookHelper.log("$tag: hook keyguard state failed", it) }
    }

    private fun isKeyguardLocked(context: Context): Boolean = runCatching {
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }.getOrDefault(false)

    /**
     * 按当前深浅色状态与配置计算并写入压暗值。
     *
     * @param force 为 true 时忽略「值未变化」的短路，强制重写一次（配置变化 / 首次挂载时
     * 需要，避免系统其它逻辑把壁纸压暗值改掉后不再恢复）。
     * @param keyguardOverride 来自 `notifyKeyguardState` 的最新状态；为空时回退到
     * `KeyguardManager.isKeyguardLocked`。
     */
    private fun applyDim(context: Context, force: Boolean = false, keyguardOverride: Boolean? = null) {
        runCatching {
            val enabled = HookPrefs.getBoolean(WallpaperDimKeys.ENABLED, false)
            val amount = if (enabled) {
                val locked = keyguardOverride ?: isKeyguardLocked(context)
                val value = if (locked && HookPrefs.contains(WallpaperDimKeys.LOCK_AMOUNT)) {
                    HookPrefs.getFloat(WallpaperDimKeys.LOCK_AMOUNT, WallpaperDimKeys.DEFAULT_AMOUNT)
                } else {
                    HookPrefs.getFloat(WallpaperDimKeys.AMOUNT, WallpaperDimKeys.DEFAULT_AMOUNT)
                }
                value.coerceIn(0f, 100f) / 100f
            } else {
                0f
            }
            val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            setDim(context, if (night) amount else 0f, force)
        }.onFailure { HookHelper.log("$tag: apply failed", it) }
    }

    private fun setDim(context: Context, dim: Float, force: Boolean) {
        if (!force && applied == dim) return
        val manager = manager(context) ?: return
        val method = method(manager) ?: return
        runCatching { method.invoke(manager, dim) }
            .onSuccess {
                applied = dim
                HookHelper.log("$tag: wallpaper dim -> $dim")
            }
            .onFailure { HookHelper.log("$tag: setWallpaperDimAmount($dim) failed", it) }
    }

    private fun manager(context: Context): Any? {
        managerRef?.get()?.let { return it }
        val cls = Reflect.findClassIfExists(WALLPAPER_MANAGER, context.classLoader) ?: return null
        val instance = runCatching { Reflect.callStaticMethod(cls, "getInstance", context) }.getOrNull()
            ?: return null
        managerRef = WeakReference(instance)
        return instance
    }

    private fun method(manager: Any): Method? {
        dimMethod?.let { return it }
        val found = runCatching {
            manager.javaClass.getMethod("setWallpaperDimAmount", Float::class.javaPrimitiveType)
        }.onFailure { HookHelper.log("$tag: setWallpaperDimAmount not found", it) }.getOrNull() ?: return null
        dimMethod = found
        return found
    }

    companion object {
        private const val WALLPAPER_MANAGER = "android.app.WallpaperManager"
        private const val KEYGUARD_STATE_CONTROLLER =
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl"
    }
}
