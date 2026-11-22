package cn.ianzb.hyperrefine.hook.securitycenter

import android.content.Context
import android.view.ViewGroup
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import java.lang.reflect.Modifier

class ToolboxSidebarGlassHook : BaseHook() {
    override val key: String = KEY

    override fun init() {
        val loader = target.classLoader ?: return
        val gamePanel = loader.loadClass("com.miui.gamebooster.windowmanager.newbox.z1")
        val videoPanel = loader.loadClass("gb.p")
        val videoBackground = videoPanel.getDeclaredField("b").apply { isAccessible = true }
        val gameRefresh = gamePanel.getDeclaredMethod("o")
        val videoRefresh = videoPanel.getDeclaredMethod("t")
        val toolboxBlur = loader.loadClass("com.miui.common.utils.n")
            .getDeclaredMethod("l", ViewGroup::class.java, Int::class.javaPrimitiveType, Context::class.java)
        val sidebar = loader.loadClass("com.miui.dock.allapps.f0")
        val applySidebarMaterial = sidebar.getMethod("c", ViewGroup::class.java, Context::class.java)
        val sidebarRenderer = sidebar.getField("a").get(null) ?: return
        // Phone 13.6.3 and tablet 13.8.0 share the panel entries, but rename the material utility.
        val (supportsGlass, supportsBlur) = listOf("rq.g", "vq.g").firstNotNullOfOrNull { name ->
            runCatching {
                val capabilities = loader.loadClass(name)
                val glass = capabilities.getMethod("u", Context::class.java)
                val blur = capabilities.getMethod("s", Context::class.java)
                check(listOf(glass, blur).all {
                    Modifier.isStatic(it.modifiers) && it.returnType == Boolean::class.javaPrimitiveType
                })
                glass to blur
            }.getOrNull()
        } ?: error("Sidebar blur capability methods unavailable")
        check(ViewGroup::class.java.isAssignableFrom(gamePanel))
        check(ViewGroup::class.java.isAssignableFrom(videoBackground.type))
        check(listOf(gameRefresh, videoRefresh, toolboxBlur, applySidebarMaterial).all {
            it.returnType == Void.TYPE
        } && Modifier.isStatic(toolboxBlur.modifiers))

        // The stock refresh methods also set panel-specific rounding after applying the blur.
        val refreshingPanel = ThreadLocal<ViewGroup>()
        HookHelper.intercept(toolboxBlur) { chain ->
            val panel = refreshingPanel.get() ?: return@intercept chain.proceed()
            if (chain.args[0] !== panel) return@intercept chain.proceed()
            val context = chain.args[2] as? Context ?: return@intercept chain.proceed()

            val applied = runCatching {
                if (supportsGlass.invoke(null, context) == true || supportsBlur.invoke(null, context) == true) {
                    applySidebarMaterial.invoke(sidebarRenderer, panel, context)
                    true
                } else {
                    false
                }
            }.onFailure { HookHelper.log("$tag: applying sidebar material failed", it) }.getOrDefault(false)
            if (applied) null else chain.proceed()
        }

        listOf(gameRefresh, videoRefresh).forEach { refresh ->
            HookHelper.deoptimize(refresh)
            HookHelper.intercept(refresh) { chain ->
                if (!HookPrefs.getBoolean(key, false)) return@intercept chain.proceed()
                val receiver = chain.thisObject ?: return@intercept chain.proceed()
                val panel = if (refresh == gameRefresh) receiver as? ViewGroup
                else videoBackground.get(receiver) as? ViewGroup
                if (panel == null) return@intercept chain.proceed()

                val previous = refreshingPanel.get()
                refreshingPanel.set(panel)
                try {
                    chain.proceed()
                } finally {
                    if (previous == null) refreshingPanel.remove() else refreshingPanel.set(previous)
                }
            }
        }
    }

    companion object {
        const val KEY = "security_center_toolbox_sidebar_glass"
    }
}
