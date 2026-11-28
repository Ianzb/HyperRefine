package cn.ianzb.hyperrefine.hook.connect

import android.content.Context
import android.content.res.Resources
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.systemui.glass.CcGlassKeys
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Method
import java.util.WeakHashMap

/** Uses the card framework's own glass renderer for shared control surfaces. */
class ExpandedDeviceCardGlassHook : BaseHook() {
    override val key: String = CcGlassKeys.DEVICE_CARD

    override fun init() {
        val utils = Reflect.findClass(MATERIAL_UTILS, target.classLoader)
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
            val headsetCard = Reflect.findClassIfExists(HEADSET_CARD_VIEW, target.classLoader)
            HookHelper.intercept(panelMaterial) { chain ->
                val view = chain.args.getOrNull(0) as? View ?: return@intercept chain.proceed()
                val type = chain.args.getOrNull(1) as? Int ?: return@intercept chain.proceed()
                if (type !in 1..2 || !CcGlassKeys.isEnabled(key) || !isSharedCardControl(view, deviceCard)) {
                    return@intercept chain.proceed()
                }
                val shape = view.background
                val result = chain.proceed()
                val drawable = CONTROL_OUTLINES[resourceName(view)]
                applyGlass(view, type, drawable?.let { loadControlOutline(view, it) } ?: shape ?: view.background)
                result
            }
            hookSynergyState(applyGlass)
            if (headsetCard != null) {
                hookHeadsetPreferences(headsetCard)
                hookHeadsetVolumeSection(headsetCard, applyGlass)
            }
            hookHeadsetRouteRows(applyGlass)
            logHeadsetPreferences("MiLink UI startup")
        }
    }

    private fun logHeadsetPreferences(stage: String) {
        val master = HookPrefs.getBoolean(CcGlassKeys.MASTER, false)
        val scene = HookPrefs.getBoolean(key, true)
        HookHelper.log("$tag: $stage (master=$master, scene=$scene)")
    }

    private fun hookHeadsetPreferences(card: Class<*>) {
        val attach = Reflect.findMethodIfExists(card, "onAttachedToWindow") ?: return
        var lastSettings: Pair<Boolean, Boolean>? = null
        HookHelper.hookAfter(attach) {
            val current = HookPrefs.getBoolean(CcGlassKeys.MASTER, false) to HookPrefs.getBoolean(key, true)
            if (lastSettings != current) {
                lastSettings = current
                HookHelper.log("$tag: headset attached (master=${current.first}, scene=${current.second})")
            }
        }
    }

    private fun hookHeadsetVolumeSection(card: Class<*>, applyGlass: (View, Int, Drawable?) -> Unit) {
        val section = Reflect.findClassIfExists("com.miui.circulateplus.world.headset.v1", target.classLoader) ?: return
        val constructor = section.declaredConstructors.singleOrNull {
            it.parameterTypes.contentEquals(arrayOf(card))
        } ?: return
        val volume = Reflect.findMethodIfExists(section, "u")?.takeIf { it.returnType == View::class.java } ?: return
        val pendingLayouts = java.util.Collections.newSetFromMap(WeakHashMap<View, Boolean>())
        fun applyVolume(view: View) {
            if (!CcGlassKeys.isEnabled(key) || !view.isAttachedToWindow ||
                resourceName(view) !in HEADSET_VOLUME_SURFACES
            ) return
            val shape = loadControlOutline(view, "miplay_detail_header_bg_shape") ?: view.background
            applyGlass(view, 2, shape)
        }
        fun refreshVolume(view: View) {
            if (!CcGlassKeys.isEnabled(key)) return
            if (view.isLaidOut && !view.isLayoutRequested) {
                applyVolume(view)
            } else if (pendingLayouts.add(view)) {
                view.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        v: View, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                    ) {
                        v.removeOnLayoutChangeListener(this)
                        pendingLayouts.remove(v)
                        if (right > left && bottom > top) applyVolume(v)
                    }
                })
            }
        }
        val volumes = WeakHashMap<View, java.lang.ref.WeakReference<View>>()
        HookHelper.hookAfter(constructor) { param ->
            val owner = param.args.firstOrNull() as? View ?: return@hookAfter
            val landscapeId = owner.resources.getIdentifier("miplay_volume", "id", target.packageName)
            val view = landscapeId.takeIf { it != 0 }?.let { owner.findViewById<View>(it) }
                ?: volume.invoke(param.thisObject) as? View ?: return@hookAfter
            volumes[owner] = java.lang.ref.WeakReference(view)
            if (view.isAttachedToWindow) refreshVolume(view)
        }
        listOfNotNull(
            Reflect.findMethodIfExists(card, "onAttachedToWindow"),
            Reflect.findMethodIfExists(card, "onConfigurationChanged", Configuration::class.java),
        ).forEach { refresh ->
            HookHelper.hookAfter(refresh) { param ->
                volumes[param.thisObject]?.get()?.let { view ->
                    view.post { if (view.isAttachedToWindow) refreshVolume(view) }
                }
            }
        }
    }

    private fun hookHeadsetRouteRows(applyGlass: (View, Int, Drawable?) -> Unit) {
        val fragment = Reflect.findClassIfExists(HEADSET_ROUTE_FRAGMENT, target.classLoader) ?: return
        val bind = fragment.declaredMethods.singleOrNull {
            it.name == "a1" && it.parameterCount == 2 &&
                it.parameterTypes[0] == View::class.java && Enum::class.java.isAssignableFrom(it.parameterTypes[1])
        } ?: return
        val refresh = fragment.declaredMethods.singleOrNull { it.name == "U0" && it.parameterCount == 0 }
            ?: return
        val rowsByFragment = WeakHashMap<Any, WeakHashMap<View, Int>>()
        HookHelper.hookAfter(bind) { param ->
            if (!CcGlassKeys.isEnabled(key)) return@hookAfter
            val owner = param.thisObject ?: return@hookAfter
            val item = param.args.getOrNull(0) as? View ?: return@hookAfter
            val type = when ((param.args.getOrNull(1) as? Enum<*>)?.name) {
                "HIGHLIGHTED" -> ROUTE_SELECTED_MATERIAL
                "DEFAULT", "LOADING" -> ROUTE_DEFAULT_MATERIAL
                else -> return@hookAfter
            }
            val id = item.resources.getIdentifier(HEADSET_ROUTE_ROW, "id", item.context.packageName)
            val row = if (id != 0) item.findViewById<View>(id) else null
            if (row != null) {
                rowsByFragment.getOrPut(owner) { WeakHashMap() }[row] = type
                applyGlass(row, type, row.background)
            }
        }
        HookHelper.hookAfter(refresh) { param ->
            if (!CcGlassKeys.isEnabled(key)) return@hookAfter
            // The stock refresh clears the material on every unselected destination.
            rowsByFragment[param.thisObject]?.forEach { (row, type) ->
                if (type == ROUTE_DEFAULT_MATERIAL) applyGlass(row, type, row.background)
            }
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
        val name = resourceName(view)
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
            if (gradient != null && (name in CONTROL_OUTLINES || name == HEADSET_ROUTE_ROW)) {
                val radius = gradient.cornerRadius
                view.outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(target: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, target.width, target.height, radius)
                        outline.alpha = 1f
                    }
                }
            }
            view.invalidateOutline()
        }.onFailure { HookHelper.log("$tag: apply expanded card material failed", it) }
    }

    private fun isSharedCardControl(view: View, deviceCard: Class<*>?): Boolean {
        var ancestor: View? = view
        while (ancestor != null) {
            when (ancestor.javaClass.name) {
                BASE_CARD_VIEW -> return ancestor !== view
                SYNERGY_VIEW -> return resourceName(view) in SYNERGY_SURFACES
                WATCH_CARD_VIEW -> return resourceName(view) == WATCH_MORE_BUTTON
                HEADSET_CARD_VIEW -> return resourceName(view) in HEADSET_SURFACES
            }
            if (deviceCard?.isInstance(ancestor) == true) return ancestor !== view
            ancestor = ancestor.parent as? View
        }
        return false
    }

    private fun resourceName(view: View): String? =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()

    private fun loadControlOutline(view: View, name: String): Drawable? = runCatching {
        val resources = view.resources
        val packageName = resources.getResourcePackageName(view.id)
        val id = resources.getIdentifier(name, "drawable", packageName)
        if (id == 0) null else resources.getDrawable(id, view.context.theme)
    }.getOrNull()

    private companion object {
        const val MATERIAL_UTILS = "com.milink.util.MaterialUtils"
        const val BASE_CARD_VIEW = "com.milink.cardframelibrary.adapters.BaseCardView"
        const val SYNERGY_VIEW = "com.miui.circulate.world.sticker.ui.SynergyView"
        val SYNERGY_SURFACES = setOf("item_container", "item_icon_container")
        const val DEVICE_CARD_VIEW = "com.miui.circulate.world.sticker.blecard.BleView"
        const val WATCH_CARD_VIEW = "com.miui.circulate.world.watch.WatchControlView"
        const val WATCH_MORE_BUTTON = "watch_more_btn"
        const val HEADSET_CARD_VIEW = "com.miui.circulateplus.world.headset.HeadSetsDetail"
        const val HEADSET_ROUTE_FRAGMENT = "com.miui.circulate.world.headset.ui.HeadsetCirculateFragment"
        const val HEADSET_ROUTE_ROW = "circulate_device_item_container"
        const val ROUTE_DEFAULT_MATERIAL = 2
        const val ROUTE_SELECTED_MATERIAL = 3
        val HEADSET_VOLUME_SURFACES = setOf("volume_bar", "miplay_volume")
        val HEADSET_SURFACES = HEADSET_VOLUME_SURFACES +
            setOf("headset_more_settings", "anc_select_card", "mi_audio_effect_select_card")
        val CONTROL_OUTLINES = mapOf(
            WATCH_MORE_BUTTON to "watch_more_btn_shape",
            "headset_more_settings" to "headset_detail_more_settings_shape",
            "volume_bar" to "miplay_detail_header_bg_shape",
            "miplay_volume" to "miplay_detail_header_bg_shape",
        )
        const val SMART_HOME_CARD_HOST = "com.xiaomi.smarthome.fusion.MiJiaControlViewHostService"
        const val SMART_HOME_MATERIAL = "com.xiaomi.smarthome.control.utils.material.MaterialUtils"
        const val SMART_HOME_SKETCH = "com.xiaomi.smarthome.newui.widget.topnavi.widgets.SketchCardView"
    }
}
