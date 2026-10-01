package cn.ianzb.hyperrefine.hook.systemui.glass

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.systemui.PluginLoader
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 第三方主题下强制启用材质。
 *
 * HyperOS 在非默认主题下会关闭高级材质（`ThemeUtils.getDefaultPluginTheme` /
 * `getDefaultSysUiTheme` 返回 false），导致一级磁贴、二级面板等位置的柔光玻璃全部失效。
 * 开启后把这两个 getter 恒置为 true，让官方材质在第三方主题下照常生效。
 *
 * 仅反射调用系统自身接口，不复制任何系统代码。
 */
class CcMaterialGateHook : BaseHook() {

    override val key: String = CcGlassKeys.THEME_MATERIAL

    /** 已挂载的类，避免主 SystemUI 与插件 ClassLoader 命中同一 Class 时重复挂载。 */
    private val hooked =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Class<*>, Boolean>())

    override fun init() {
        val systemCl = target.classLoader ?: return
        PluginLoader.register(key, systemCl) { pluginCl -> hook(pluginCl) }
        // 个别版本 ThemeUtils 由主 SystemUI 类加载器提供，兜底再挂一次。
        runCatching { hook(systemCl) }
    }

    private fun hook(classLoader: ClassLoader) {
        val cls = Reflect.findClassIfExists(CLASS, classLoader) ?: return
        if (!hooked.add(cls)) return
        GETTERS.forEach { name ->
            cls.declaredMethods
                .firstOrNull { it.name == name && it.parameterCount == 0 }
                ?.let { method ->
                    method.isAccessible = true
                    runCatching {
                        HookHelper.hookBefore(method) { param -> param.setResultValue(true) }
                    }.onFailure { HookHelper.log("$tag: hook $name failed", it) }
                }
        }
    }

    companion object {
        private const val CLASS = "miui.systemui.util.ThemeUtils"
        private val GETTERS = listOf("getDefaultPluginTheme", "getDefaultSysUiTheme")
    }
}
