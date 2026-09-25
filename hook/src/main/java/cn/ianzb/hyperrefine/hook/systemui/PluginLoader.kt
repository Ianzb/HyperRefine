package cn.ianzb.hyperrefine.hook.systemui

import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * SystemUI 插件 ClassLoader 捕获器。
 *
 * HyperOS 的控制中心 / 音量面板类位于 MIUISystemUIPlugin（`miui.systemui.plugin`）插件中，
 * 由 SystemUI 的 `PluginInstance$PluginFactory` 在插件加载时创建独立 ClassLoader。
 *
 * 捕获方式（两者互补，保证热重载 / 后开启开关也能生效）：
 * 1. **入口 hook**：挂 `PluginFactory.createPluginContext` / `createClassLoader`，捕获后续新加载的插件；
 * 2. **宿主恢复**：通过 `Dependency.get(PluginManager)` 遍历 `PluginManagerImpl.pluginMap` →
 *    `PluginActionManager.pluginInstances` → `PluginInstance.getPlugin()` 读取**已加载**插件的 ClassLoader。
 *
 * 由于热重载会重载模块代码（本对象状态重置），仅靠入口 hook 无法覆盖「插件早于开关加载」的场景，
 * 故每次 [register] 都做一次宿主恢复并补发回调。
 *
 * 定位思路参考 HyperCeiler / Hyper5GSwitch，未复用其代码。
 */
object PluginLoader {

    /** 探针类：存在于控制中心插件 ClassLoader 中，用于确认插件已就绪。 */
    private const val PROBE_CLASS = "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController"

    private const val FACTORY_CLASS = "com.android.systemui.shared.plugins.PluginInstance\$PluginFactory"

    private val callbacks = LinkedHashMap<String, (ClassLoader) -> Unit>()

    private val knownPluginClassLoaders = LinkedHashSet<ClassLoader>()

    /** 已向某插件 ClassLoader 分发过的回调键，避免同一代次重复安装。 */
    private val dispatched = HashSet<Pair<String, ClassLoader>>()

    /**
     * 进程就绪时无条件安装入口 hook，并尝试从宿主恢复已加载插件的 ClassLoader。
     *
     * 与功能开关无关，确保「先加载插件、后开启开关 + 热重载」也能补装。
     */
    @Synchronized
    fun bootstrap(systemClassLoader: ClassLoader) {
        installEntryHooks(systemClassLoader)
        seedFromHost(systemClassLoader)
    }

    /**
     * 注册插件就绪回调（按 [key] 去重，热重载时以新回调覆盖）。
     *
     * 注册时立即尝试宿主恢复并补发，覆盖插件已加载的情况。
     */
    @Synchronized
    fun register(key: String, systemClassLoader: ClassLoader, callback: (ClassLoader) -> Unit) {
        callbacks[key] = callback
        seedFromHost(systemClassLoader)
        knownPluginClassLoaders.toList().forEach { pluginCl -> dispatch(key, callback, pluginCl) }
    }

    private fun installEntryHooks(systemClassLoader: ClassLoader) {
        val factory = Reflect.findClassIfExists(FACTORY_CLASS, systemClassLoader)
        if (factory == null) {
            HookHelper.log("PluginLoader: $FACTORY_CLASS not found, entry hooks disabled")
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
            is android.content.ContextWrapper -> result.classLoader
            else -> return
        } ?: return
        registerPluginClassLoader(pluginCl)
    }

    private fun registerPluginClassLoader(pluginCl: ClassLoader) {
        if (!isPluginClassLoader(pluginCl)) return
        synchronized(this) {
            knownPluginClassLoaders.add(pluginCl)
        }
        HookHelper.log("PluginLoader: plugin classloader captured")
        callbacks.entries.toList().forEach { (key, callback) -> dispatch(key, callback, pluginCl) }
    }

    private fun dispatch(key: String, callback: (ClassLoader) -> Unit, pluginCl: ClassLoader) {
        val token = key to pluginCl
        synchronized(this) {
            if (!dispatched.add(token)) return
        }
        runCatching { callback(pluginCl) }
            .onFailure { HookHelper.log("PluginLoader: dispatch failed for $key", it) }
    }

    private fun isPluginClassLoader(cl: ClassLoader): Boolean =
        runCatching { Class.forName(PROBE_CLASS, false, cl) }.isSuccess

    private fun seedFromHost(systemClassLoader: ClassLoader) {
        recoverPluginClassLoaders(systemClassLoader).forEach { pluginCl ->
            if (isPluginClassLoader(pluginCl)) {
                synchronized(this) { knownPluginClassLoaders.add(pluginCl) }
            }
        }
    }

    /** 从宿主的 PluginManager 中恢复已加载插件的 ClassLoader。 */
    private fun recoverPluginClassLoaders(hostClassLoader: ClassLoader): List<ClassLoader> = runCatching {
        val manager = resolvePluginManager(hostClassLoader) ?: return emptyList()

        val out = LinkedHashSet<ClassLoader>()
        val pluginMap = runCatching { Reflect.getObjectField(manager, "pluginMap") }.getOrNull() as? Map<*, *>
            ?: return emptyList()
        pluginMap.values.forEach { value ->
            val actionManagers: List<Any?> = (value as? Iterable<*>)?.toList() ?: listOf(value)
            actionManagers.forEach { actionManager ->
                collectInstances(actionManager, out)
            }
        }
        if (out.isNotEmpty()) {
            HookHelper.log("PluginLoader: recovered ${out.size} plugin classloader(s) from host")
        }
        out.toList()
    }.onFailure {
        HookHelper.log("PluginLoader: host recovery failed", it)
    }.getOrDefault(emptyList())

    /**
     * 解析宿主 PluginManager 实例：
     * - OS4：`Dependency.sDependency.mPluginManager`（`dagger.Lazy`）→ `get()`；
     * - 旧版：静态 `Dependency.get(PluginManager::class.java)`（OS4 已无该方法）。
     */
    private fun resolvePluginManager(hostClassLoader: ClassLoader): Any? {
        val dependency = Reflect.findClassIfExists("com.android.systemui.Dependency", hostClassLoader) ?: return null
        val singleton = runCatching { Reflect.getStaticObjectField(dependency, "sDependency") }.getOrNull()
        if (singleton != null) {
            val lazy = runCatching { Reflect.getObjectField(singleton, "mPluginManager") }.getOrNull()
            if (lazy != null) {
                runCatching { Reflect.callMethod(lazy, "get") }.getOrNull()?.let { return it }
            }
        }
        val pluginManager = Reflect.findClassIfExists("com.android.systemui.plugins.PluginManager", hostClassLoader) ?: return null
        val get = Reflect.findMethodIfExists(dependency, "get", Class::class.java) ?: return null
        return runCatching { get.invoke(null, pluginManager) }.getOrNull()
    }

    private fun collectInstances(actionManager: Any?, out: MutableSet<ClassLoader>) {
        if (actionManager == null) return
        val instances = runCatching { Reflect.getObjectField(actionManager, "pluginInstances") }.getOrNull() as? Iterable<*>
            ?: return
        instances.forEach { instance ->
            if (instance == null) return@forEach
            val plugin = runCatching { Reflect.callMethod(instance, "getPlugin") }.getOrNull() ?: return@forEach
            plugin.javaClass.classLoader?.let { out.add(it) }
        }
    }
}
