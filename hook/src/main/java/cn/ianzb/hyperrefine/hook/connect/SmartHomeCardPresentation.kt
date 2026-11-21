package cn.ianzb.hyperrefine.hook.connect

import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

class SmartHomeCardAnimationHook : BaseHook() {
    override val key: String = ConnectKeys.SMART_HOME_CARD_ANIMATION

    override fun init() {
        SmartHomeCardPresentation(target.classLoader).hookOpeningAnimation()
    }
}

/** Aligns Mi Home's provider-side card defaults with the MiLink host. */
internal class SmartHomeCardPresentation(private val loader: ClassLoader?) {
    private val openingAlpha = ThreadLocal<Float>()
    private val backdrops = java.util.WeakHashMap<View, Boolean>()
    private val backdropMethods by lazy {
        val view = View::class.java
        val integer = Int::class.javaPrimitiveType
        BackdropMethods(
            view.getMethod("setMiGlass", FloatArray::class.java),
            view.getMethod("setMiGlassBlurRadius", integer, integer),
            view.getMethod("setMiBloomStroke", FloatArray::class.java),
        )
    }

    fun hookOpeningAnimation() {
        val host = Reflect.findClassIfExists("com.xiaomi.smarthome.fusion.MiJiaControlViewHostService", loader)
            ?: return
        val register = host.declaredMethods.firstOrNull { it.name == "registerBlurView" && it.parameterCount == 3 }
            ?: return
        HookHelper.hookAfter(register) { param ->
            if ((param.args.getOrNull(1) as? Enum<*>)?.name == "MAIN_CARD") {
                (param.args.getOrNull(0) as? View)?.let { backdrops[it] = true }
            }
        }
        val helper = Reflect.findClassIfExists("_m_j.sia", loader) ?: return
        val animState = Reflect.findClassIfExists("miuix.animation.controller.AnimState", loader) ?: return
        val property = Reflect.findClassIfExists("miuix.animation.property.ViewProperty", loader) ?: return
        val alpha = Reflect.getStaticObjectField(property, "ALPHA") ?: return
        runCatching {
            val add = animState.getMethod("add", property, Int::class.javaPrimitiveType, LongArray::class.java)
            val addFloat = animState.getMethod("add", property, Float::class.javaPrimitiveType, LongArray::class.java)
            val show = helper.getDeclaredMethod("OooO00o", View::class.java)
            HookHelper.deoptimize(show)
            HookHelper.intercept(add) { chain ->
                val start = openingAlpha.get()
                val state = chain.thisObject
                if (start == null || state == null || chain.args[0] !== alpha || chain.args[1] != 0) {
                    return@intercept chain.proceed()
                }
                addFloat.invoke(state, alpha, start, chain.args[2])
            }
            HookHelper.intercept(show) { chain ->
                val card = chain.args.getOrNull(0) as? View ?: return@intercept chain.proceed()
                if (!enabled()) return@intercept chain.proceed()
                val loaderHeight = if (card.resources.configuration.smallestScreenWidthDp in 300..339) 148.8f else 186f
                val shell = findBackdrop(card) ?: return@intercept chain.proceed()
                val height = shell.height.takeIf { it > 0 } ?: shell.measuredHeight.takeIf { it > 0 }
                    ?: shell.layoutParams?.height?.takeIf { it > 0 }
                    ?: return@intercept chain.proceed()
                val previous = openingAlpha.get()
                openingAlpha.set((loaderHeight * card.resources.displayMetrics.density / height).coerceIn(0f, 1f))
                try {
                    chain.proceed()
                } finally {
                    if (previous == null) openingAlpha.remove() else openingAlpha.set(previous)
                }
            }
        }.onFailure { HookHelper.log("Mi Home card opening animation hook failed", it) }
    }

    private fun findBackdrop(root: View): View? {
        if (backdrops[root] == true) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findBackdrop(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    fun applyBackdrop(view: View) {
        runCatching {
            if (!canUseGlass(view)) return
            val methods = backdropMethods
            methods.setGlass.invoke(view, MILINK_PANEL_GLASS.clone())
            // The provider already samples the blurred host backdrop.
            methods.setRadius.invoke(view, 0, 0)
            methods.setBloom.invoke(view, MILINK_PANEL_BLOOM.clone())
            view.invalidate()
        }.onFailure { HookHelper.log("Mi Home card backdrop sync failed", it) }
    }

    private fun enabled(): Boolean = HookPrefs.getBoolean(ConnectKeys.SMART_HOME_CARD_ANIMATION, false)

    private data class BackdropMethods(
        val setGlass: java.lang.reflect.Method,
        val setRadius: java.lang.reflect.Method,
        val setBloom: java.lang.reflect.Method,
    )

    companion object {
        fun canUseGlass(view: View): Boolean {
            val resolver = view.context.contentResolver
            return Settings.Secure.getInt(resolver, "background_blur_enable", 0) == 1 &&
                Settings.Secure.getInt(resolver, "material_style", -1) == 1 &&
                runCatching { View::class.java.getMethod("setMiGlass", FloatArray::class.java) }.isSuccess
        }

        // MiLink 18.2.4.1, MaterialUtils.apply(FORCE_MATERIAL) → panel renderer, type 0.
        private val MILINK_PANEL_GLASS = floatArrayOf(
            0.67f, 0.16f, 0.09f, 0f, 0.24f, 1.4f, -0.02f, 0.3f, 0.6f, 1f, 0.03f,
            1f, 1f, 1f, 0.1f, 0.2f, 0.3f, 1f, 1f, 72f, 3.8f, 80f, 800f, 1.2f,
            1f, -0.4f, 0.6f, -0.8f, 1.4f, 0.7f, 0.8f, 1.15f, 4f, 2f,
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
        )
        private val MILINK_PANEL_BLOOM = floatArrayOf(
            3f, 180f, 1f, 1f, 1f, 0.05f, 8f, 0.5f, 0.5f, -0.5f, 1f, 1f, 1f,
            0.6f, 0.5f, 0.95f, -0.5f, 1f, 1f, 1f, 0.35f,
        )
    }
}
