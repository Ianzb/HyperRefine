package cn.ianzb.hyperrefine.hook.connect

import android.content.Context
import android.content.res.Resources
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.systemui.glass.CcGlassKeys
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Uses the card framework's own glass renderer for shared control surfaces. */
class ExpandedDeviceCardGlassHook : BaseHook() {
    override val key: String = CcGlassKeys.DEVICE_CARD

    override fun init() {
        val utils = Reflect.findClassIfExists(MATERIAL_UTILS, target.classLoader) ?: return
        val miHome = target.packageName == MiLinkLoad.SMART_HOME_PACKAGE
        val supportsMaterial = if (miHome) null else utils.getDeclaredMethod("s", View::class.java)
        val materialStyle = utils.getDeclaredMethod(if (miHome) "OooOo0o" else "o", Context::class.java)
        val rendererFactory = utils.getDeclaredMethod(if (miHome) "OooOo" else "p", Context::class.java)
            .apply { isAccessible = true }
        val cardMaterial = rendererFactory.returnType.getMethod(
            if (miHome) "OooO0OO" else "b", View::class.java, Int::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        val applyGlass: (View, Int, Drawable?) -> Unit = { view, type, shape ->
            applyCardGlass(view, type, shape, supportsMaterial, materialStyle, rendererFactory, cardMaterial)
        }
        if (miHome) {
            val presentation = SmartHomeCardPresentation(target.classLoader)
            hookSmartHomeCardControls(applyGlass, presentation)
        } else {
            val panelMaterial = utils.getDeclaredMethod("k", View::class.java, Int::class.javaPrimitiveType)
            val deviceCard = Reflect.findClassIfExists(DEVICE_CARD_VIEW, target.classLoader)
            HookHelper.intercept(panelMaterial) { chain ->
                val view = chain.args.getOrNull(0) as? View ?: return@intercept chain.proceed()
                val type = chain.args.getOrNull(1) as? Int ?: return@intercept chain.proceed()
                if (type !in 1..2 || !CcGlassKeys.isEnabled(key) || !isSharedCardControl(view, deviceCard)) {
                    return@intercept chain.proceed()
                }
                val shape = view.background
                val result = chain.proceed()
                applyGlass(view, type, shape ?: view.background)
                result
            }
            hookSynergyState(applyGlass)
        }
    }

    private fun hookSynergyState(applyGlass: (View, Int, Drawable?) -> Unit) {
        val synergy = Reflect.findClassIfExists(SYNERGY_VIEW, target.classLoader) ?: return
        val state = synergy.declaredMethods.firstOrNull {
            it.name == "setState" && it.parameterCount == 1 && it.parameterTypes[0].isEnum
        } ?: return
        HookHelper.hookAfter(state) { param ->
            if (!CcGlassKeys.isEnabled(key) || (param.args.getOrNull(0) as? Enum<*>)?.name != "NORMAL") {
                return@hookAfter
            }
            val host = param.thisObject as? View ?: return@hookAfter
            val id = host.resources.getIdentifier("item_container", "id", host.context.packageName)
            if (id != 0) host.findViewById<View>(id)?.let { applyGlass(it, 2, it.background) }
        }
    }

    private fun hookSmartHomeCardControls(
        applyGlass: (View, Int, Drawable?) -> Unit,
        presentation: SmartHomeCardPresentation,
    ) {
        val host = Reflect.findClassIfExists(SMART_HOME_CARD_HOST, target.classLoader) ?: return
        val registered = WeakHashMap<View, Int>()
        val roots = WeakHashMap<View, Boolean>()
        val attachRoot = host.getDeclaredMethod("attachRoot", FrameLayout::class.java, View::class.java)
        HookHelper.hookBefore(attachRoot) { param ->
            (param.args.getOrNull(1) as? View)?.let { roots[it] = true }
        }
        host.declaredMethods.firstOrNull { it.name == "registerBlurView" && it.parameterCount == 3 }?.let { register ->
            HookHelper.hookAfter(register) { param ->
                val view = param.args.getOrNull(0) as? View ?: return@hookAfter
                val resource = param.args.getOrNull(2) as? Int ?: return@hookAfter
                registered[view] = resource
            }
        }
        val providerUtils = Reflect.findClassIfExists(SMART_HOME_MATERIAL, target.classLoader) ?: return
        val applyPanel = providerUtils.getDeclaredMethod("OooOo00", View::class.java, Int::class.javaPrimitiveType)
        HookHelper.intercept(applyPanel) { chain ->
            val view = chain.args.getOrNull(0) as? View ?: return@intercept chain.proceed()
            val type = chain.args.getOrNull(1) as? Int ?: return@intercept chain.proceed()
            val resource = registered[view]
            val shape = view.background
            val result = chain.proceed()
            if (CcGlassKeys.isEnabled(key)) {
                if (type == 0 && resource != null) {
                    presentation.applyBackdrop(view)
                } else if (type in 1..2 && (resource != null || isInsideRoot(view, roots))) {
                    val original = resource?.let {
                        runCatching { view.resources.getDrawable(it, view.context.theme) }.getOrNull()
                    } ?: shape
                    applyGlass(view, type, original)
                }
            }
            result
        }
        val helper = Reflect.findClassIfExists("_m_j.vza", target.classLoader) ?: return
        val sketch = Reflect.findClassIfExists(SMART_HOME_SKETCH, target.classLoader) ?: return
        val applySketch = helper.getDeclaredMethod("OooOOo", sketch, Resources::class.java)
        HookHelper.intercept(applySketch) { chain ->
            val view = chain.args.getOrNull(0) as? View ?: return@intercept chain.proceed()
            val shape = view.background
            val result = chain.proceed()
            if (CcGlassKeys.isEnabled(key) && isInsideRoot(view, roots) && view.background is ColorDrawable) {
                applyGlass(view, 2, shape ?: view.background)
            }
            result
        }
    }

    private fun isInsideRoot(view: View, roots: Map<View, Boolean>): Boolean {
        var current: View? = view
        while (current != null) {
            if (roots[current] == true) return true
            current = current.parent as? View
        }
        return false
    }

    private fun applyCardGlass(
        view: View,
        type: Int,
        shape: Drawable?,
        supportsMaterial: Method?,
        materialStyle: Method,
        rendererFactory: Method,
        cardMaterial: Method,
    ) {
        // Keep the stock shape and round-rect outline while removing its solid fill.
        val gradient = (shape?.current as? GradientDrawable)
            ?.constantState?.newDrawable(view.resources)?.mutate() as? GradientDrawable
        val transparent = when {
            gradient != null -> gradient.apply { setColor(Color.TRANSPARENT) }
            shape is ColorDrawable || view.outlineProvider !== ViewOutlineProvider.BACKGROUND ->
                ColorDrawable(Color.TRANSPARENT)
            else -> return
        }
        runCatching {
            if (materialStyle.invoke(null, view.context) != 1) return
            if (supportsMaterial != null && supportsMaterial.invoke(null, view) != true) return
            // Mi Home's provider already validated its blur path before applying this material.
            // The bundled card SDK's separate support method references a missing symbol on 11.10.623.
            if (supportsMaterial == null && !SmartHomeCardPresentation.canUseGlass(view)) return
            val renderer = rendererFactory.invoke(null, view.context) ?: return
            cardMaterial.invoke(renderer, view, type)
            view.background = transparent
            view.invalidateOutline()
        }.onFailure { HookHelper.log("$tag: apply expanded card material failed", it) }
    }

    private fun isSharedCardControl(view: View, deviceCard: Class<*>?): Boolean {
        var ancestor: View? = view
        while (ancestor != null) {
            when (ancestor.javaClass.name) {
                BASE_CARD_VIEW -> return ancestor !== view
                SYNERGY_VIEW -> return runCatching {
                    view.resources.getResourceEntryName(view.id) in SYNERGY_SURFACES
                }.getOrDefault(false)
            }
            if (deviceCard?.isInstance(ancestor) == true) return ancestor !== view
            ancestor = ancestor.parent as? View
        }
        return false
    }

    private companion object {
        const val MATERIAL_UTILS = "com.milink.util.MaterialUtils"
        const val BASE_CARD_VIEW = "com.milink.cardframelibrary.adapters.BaseCardView"
        const val SYNERGY_VIEW = "com.miui.circulate.world.sticker.ui.SynergyView"
        val SYNERGY_SURFACES = setOf("item_container", "item_icon_container")
        const val DEVICE_CARD_VIEW = "com.miui.circulate.world.sticker.blecard.BleView"
        const val SMART_HOME_CARD_HOST = "com.xiaomi.smarthome.fusion.MiJiaControlViewHostService"
        const val SMART_HOME_MATERIAL = "com.xiaomi.smarthome.control.utils.material.MaterialUtils"
        const val SMART_HOME_SKETCH = "com.xiaomi.smarthome.newui.widget.topnavi.widgets.SketchCardView"
    }
}
