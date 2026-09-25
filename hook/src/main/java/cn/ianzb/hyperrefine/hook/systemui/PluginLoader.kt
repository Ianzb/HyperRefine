package cn.ianzb.hyperrefine.hook.systemui

import android.content.ContextWrapper
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.WeakHashMap

/**
 * SystemUI 插件 ClassLoader 捕获器。
 *
 * HyperOS 的控制中心 / 音量面板类位于 MIUISystemUIPlugin（`miui.systemui.plugin`）插件中，
 * 由 SystemUI 的 `PluginInstance$PluginFactory` 在插件加载时创建独立 ClassLoader。
 * 本类在插件上下文创建后捕获该 ClassLoader，探测到目标类后分发给各功能 Hook。
 *
 * 说明：插件 ClassLoader 仅在进程内插件加载时创建一次，故开启开关后需**重启系统界面**生效。
 *
 * 定位思路参考 HyperCeiler / Hyper5GSwitch，未复用其代码。
 */
object PluginLoader {

    /** 探针类：存在于控制中心插件 ClassLoader 中，用于确认插件已就绪。 */
    private const val PROBE_CLASS = "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController"

    private const val FACTORY_CLASS = "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"

    private val callbacks = LinkedHashMap<String, (ClassLoader) -> Unit>()

    private val knownPluginClassLoaders = mutableListOf<ClassLoader>()

    private val entryHookedClassLoaders = WeakHashMap<ClassLoader, Boolean>()

    /**
     * 注册插件就绪回调（按 [key] 去重，以新回调覆盖）。
     * 若插件已经加载，立即补发一次。
     */
    @Synchronized
    fun register(key: String, systemClassLoader: ClassLoader, callback: (ClassLoader) -> Unit) {
        callbacks[key] = callback
        knownPluginClassLoaders.toList().forEach { pluginCl ->
            runCatching { callback(pluginCl) }
                .onFailure { HookHelper.log("PluginLoader: late dispatch failed for $key", it) }
        }
        installEntryHooks(systemClassLoader)
    }

    private fun installEntryHooks(systemClassLoader: ClassLoader) {
        if (entryHookedClassLoaders[systemClassLoader] == true) return
        entryHookedClassLoaders[systemClassLoader] = true
        val factory = Reflect.findClassIfExists(FACTORY_CLASS, systemClassLoader)
        if (factory == null) {
            HookHelper.log("PluginLoader: $FACTORY_CLASS not found, plugin hooks disabled")
            return
        }
        var hooked = false
        factory.declaredMethods
            .filter { it.name == "createPluginContext" || it.name == "createClassLoader" }
            .forEach { method ->
                runCatching {
                    HookHelper.hookAfter(method) { param -> onEntryResult(param.result) }
                    hooked = true
                }.onFailure { HookHelper.log("PluginLoader: hook ${method.name} failed", it) }
            }
        if (!hooked) HookHelper.log("PluginLoader: no entry method hooked on $FACTORY_CLASS")
    }

    private fun onEntryResult(result: Any?) {
        val pluginCl = when (result) {
            is ClassLoader -> result
            is ContextWrapper -> result.classLoader
            else -> return
        } ?: return
        if (!isPluginClassLoader(pluginCl)) return
        synchronized(this) {
            if (knownPluginClassLoaders.any { it === pluginCl }) return
            if (knownPluginClassLoaders.size >= 4) knownPluginClassLoaders.removeAt(0)
            knownPluginClassLoaders.add(pluginCl)
        }
        HookHelper.log("PluginLoader: plugin classloader captured")
        callbacks.values.toList().forEach { callback ->
            runCatching { callback(pluginCl) }
                .onFailure { HookHelper.log("PluginLoader: dispatch failed", it) }
        }
    }

    private fun isPluginClassLoader(cl: ClassLoader): Boolean =
        runCatching { Class.forName(PROBE_CLASS, false, cl) }.isSuccess
}
