package cn.ianzb.hyperrefine.hook.systemui.glass

import android.view.View
import android.view.ViewGroup
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.systemui.PluginLoader
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 控制中心「柔光玻璃」：把二级面板内**没有玻璃效果的按钮 / 卡片**接入系统同款柔光玻璃材质。
 *
 * 只对具体组件应用材质（`MaterialBackgroundExt.setMaterialBackground` + 柔光 token），
 * **不修改面板背景 / 大容器 / 文字**，避免整块遮罩与误伤强调色。
 *
 * 受总开关 `cc_glass` 控制，开启后各面板小开关（默认开）独立生效。
 */
class CcGlassHook : BaseHook() {

    override val key: String = KEY

    private val observed = java.util.WeakHashMap<ViewGroup, Boolean>()

    override fun init() {
        val systemCl = target.classLoader ?: return
        PluginLoader.register(key, systemCl) { pluginCl ->
            CcGlassApi.init(pluginCl)
            hookSecondaryPanels(pluginCl)
            hookTiles(pluginCl)
            hookMoreButton(pluginCl)
            hookSideVolume(pluginCl)
        }
    }

    /**
     * 「更多设置」按钮点击时会由系统重新套用实心 token，导致玻璃消失。
     * 这里在其状态变化后统一重新套用默认玻璃（不随按下变色，避免难看）。
     */
    private fun hookMoreButton(pluginCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(MORE_BUTTON_CLASS, pluginCl) ?: return
        val method = cls.declaredMethods.firstOrNull {
            it.name == "dispatchSetPressed" && it.parameterCount == 1
        } ?: return
        method.isAccessible = true
        runCatching {
            HookHelper.hookAfter(method) { param ->
                if (!master()) return@hookAfter
                val view = param.thisObject as? View ?: return@hookAfter
                // 用材质 token 路径（会先清空实心底色再套玻璃），否则按下会残留系统实心色。
                CcGlassApi.apply(view, TOKEN_GLASS)
            }
        }.onFailure { HookHelper.log("$tag: hook more button failed", it) }
    }

    /**
     * 亮度面板的圆形磁贴按钮：在 `QSTileItemView.updateState` 之后按状态重新套玻璃，
     * 使「开启后有强调色、切换后不丢玻璃」。目标只取磁贴图标本体（`getBlendTarget()`，圆形轮廓）。
     */
    private fun hookTiles(pluginCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(TILE_ITEM_CLASS, pluginCl) ?: run {
            HookHelper.log("$tag: $TILE_ITEM_CLASS not found")
            return
        }
        val method = cls.declaredMethods.firstOrNull {
            it.name == "updateState" && it.parameterCount == 3
        } ?: return
        method.isAccessible = true
        runCatching {
            HookHelper.hookAfter(method) { param ->
                if (!master() || !sub(CcGlassKeys.BRIGHTNESS)) return@hookAfter
                val tile = param.thisObject as? View ?: return@hookAfter
                if (!inBrightnessPanel(tile)) return@hookAfter
                applyTileGlass(tile, param.args.getOrNull(0))
            }
            HookHelper.log("$tag: hooked brightness tiles")
        }.onFailure { HookHelper.log("$tag: hook tiles failed", it) }
    }

    private fun applyTileGlass(tile: View, state: Any?) {
        val blendTarget = call(tile, "getBlendTarget") as? View ?: return
        val on = (state?.let { runCatching { Reflect.getObjectField(it, "state") }.getOrNull() } as? Int) == 2
        blendTarget.background = null
        CcGlassApi.applyStyle(
            blendTarget,
            CcGlassApi.colorBlend(if (on) "CC_TILE_ON_BLEND_COLORS" else "CC_TILE_DEFAULT_BLEND_COLORS"),
            CcGlassApi.bionics(if (on) TOKEN_ACTIVATED else TOKEN_DEFAULT),
        )
    }

    private fun inBrightnessPanel(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent is View && idName(parent) == "brightness_panel") return true
            parent = parent.parent
        }
        return false
    }

    // ---------------- 二级面板（控制中心插件） ----------------

    private fun hookSecondaryPanels(pluginCl: ClassLoader) {
        val base = Reflect.findClassIfExists(SECONDARY_BASE, pluginCl)
        if (base == null) {
            HookHelper.log("$tag: $SECONDARY_BASE not found")
            return
        }
        val method = base.declaredMethods.firstOrNull {
            it.name == "onSecondaryVisible" && it.parameterCount == 2
        } ?: run {
            HookHelper.log("$tag: onSecondaryVisible not found")
            return
        }
        method.isAccessible = true
        runCatching {
            HookHelper.hookAfter(method) { param ->
                if ((param.args.getOrNull(0) as? Boolean) == true) {
                    val controller = param.thisObject ?: return@hookAfter
                    // 立即套一次；再在本次展开流程结束（delegate 已建好内容、动画尚未开始）后再套一次，
                    // 避免玻璃要等动画播完才出现。
                    applySecondary(controller)
                    val root = call(controller, "getSecondaryPanelContainer") as? View
                    root?.post { if (master()) runCatching { applySecondary(controller) } }
                }
            }
            HookHelper.log("$tag: hooked secondary panels")
        }.onFailure { HookHelper.log("$tag: hook secondary failed", it) }
    }

    private fun applySecondary(controller: Any) {
        if (!master()) return
        val root = call(controller, "getSecondaryPanelContainer") as? View ?: call(controller, "getView") as? View
        when (controller.javaClass.simpleName) {
            "BrightnessPanelController" -> if (sub(CcGlassKeys.BRIGHTNESS)) glassBrightness(root)
            "MediaPanelController" -> if (sub(CcGlassKeys.MEDIA)) glassMedia(root)
            "VolumePanelController" -> if (sub(CcGlassKeys.CC_VOLUME)) glassCcVolume(controller, root)
            "DetailPanelController" -> glassDetail(controller, root)
            else -> Unit
        }
        if (debug()) dumpTree(root)
    }

    /**
     * 亮度面板：大亮度条的轨道（`bionics_progress_bg` / `progress_bg`）
     * 与下方圆形按钮本体（`QSTileItemIconView`）；只改组件本体，不包住文字成矩形。
     */
    private fun glassBrightness(root: View?) {
        // 大亮度条轨道。圆形磁贴按钮由 hookTiles 在 updateState 后按状态处理。
        byId(root, setOf("bionics_progress_bg", "progress_bg"))
    }

    /**
     * 播放器面板：只给**设备列表里的设备卡片**套玻璃，绝不 recursion 到控件/文本。
     */
    private fun glassMedia(root: View?) {
        val list = findList(root) as? ViewGroup ?: return
        applyDeviceCards(list)
        if (observed[list] == true) return
        observed[list] = true
        list.viewTreeObserver.addOnGlobalLayoutListener {
            if (master()) runCatching { applyDeviceCards(list) }
        }
    }

    private fun applyDeviceCards(list: ViewGroup) {
        forEachChild(list) { child ->
            if (!child.isClickable) return@forEachChild
            if (child is android.widget.TextView) return@forEachChild
            val active = child.isSelected
            child.background = null
            CcGlassApi.applyStyle(
                child,
                CcGlassApi.colorBlend(if (active) "CC_MIPLAY_PANEL_ACTIVE_BIONICS_COLORS" else "CC_TILE_DEFAULT_BLEND_COLORS"),
                CcGlassApi.bionics(if (active) TOKEN_ACTIVATED else TOKEN_DEFAULT),
            )
        }
    }

    /** 控制中心音量面板：各音量条、静音/勿扰圆形按钮、定时静音/勿扰滑块。 */
    private fun glassCcVolume(controller: Any, root: View?) {
        val delegate = call(controller, "getDelegate")
        // 各音量条（列容器内的每一列）
        val columns = delegate?.let { call(it, "getContentColumns") } as? ViewGroup
        columns?.let {
            forEachChild(it) { column ->
                glass(column)
                clearSliderDarkBg(column)
            }
        }
        byId(root, setOf("bg_blur"))
        byClass(root, setOf("MiuiVolumeTimerSeekBar"))
    }

    /** 删除开启玻璃前的深色背景（`volume_column_view` 等带不透明背景的子视图）。 */
    private fun clearSliderDarkBg(column: View) {
        traverse(column) { v ->
            val name = idName(v)
            if (name == "volume_column_view" || name == "volume_column_slider_bg_blend") {
                v.background = null
            }
        }
    }

    /** 详情面板（移动数据 / WLAN）：逐张卡片套玻璃 + 更多设置按钮。 */
    private fun glassDetail(controller: Any, root: View?) {
        val type = call(controller, "getType")?.toString().orEmpty()
        val wifi = type.contains("WIFI") && sub(CcGlassKeys.WLAN)
        val cell = type.contains("CELL") && sub(CcGlassKeys.MOBILE_DATA)
        if (!wifi && !cell) return
        // 对「附近的 WLAN」列表容器整体套一张圆角玻璃卡片（列表合起来是一张卡片）。
        findList(root)?.let { glass(it) }
        byId(root, setOf("more_button"))
    }

    // ---------------- 侧边音量（主 APK miui.volume） ----------------

    private fun hookSideVolume(pluginCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(SIDE_VOLUME_CONTROLLER, pluginCl)
        if (cls == null) {
            HookHelper.log("$tag: $SIDE_VOLUME_CONTROLLER not found")
            return
        }
        val names = setOf("showVolumePanelH", "initPanelView", "updateExpandedH", "updateVolumeColumnSliderH")
        var hooked = false
        cls.declaredMethods.filter { it.name in names }.forEach { method ->
            method.isAccessible = true
            runCatching {
                HookHelper.hookAfter(method) { param ->
                    applySideVolume(param.thisObject ?: return@hookAfter)
                }
                hooked = true
            }.onFailure { HookHelper.log("$tag: hook side volume ${method.name} failed", it) }
        }
        HookHelper.log("$tag: side volume hooked=$hooked")
    }

    private fun applySideVolume(controller: Any) {
        if (!master() || !sub(CcGlassKeys.SIDE_VOLUME)) return
        // 各音量条
        (call(controller, "getColumns") as? List<*>)?.forEach { column ->
            column ?: return@forEach
            val view = call(column, "getView") as? View
            glass(view)
            view?.let { clearSliderDarkBg(it) }
        }
        // 静音/勿扰圆形按钮 + 定时滑块
        val ringer = call(controller, "getVolumeRingerModeLayout") as? View
        byId(ringer, setOf("bg_blur"))
        byClass(ringer, setOf("MiuiVolumeTimerSeekBar"))
        if (debug()) dumpTree(ringer)
    }

    // ---------------- 视图遍历工具 ----------------

    private fun call(target: Any, getter: String): Any? =
        runCatching { Reflect.callMethod(target, getter) }.getOrNull()

    private fun glass(view: Any?) {
        val v = view as? View ?: return
        CcGlassApi.apply(v, TOKEN_GLASS)
        if (debug()) HookHelper.log("$tag: glass ${v.javaClass.simpleName} #${idName(v)}")
    }

    private fun byId(root: Any?, ids: Set<String>) {
        val v = root as? View ?: return
        traverse(v) { child ->
            if (idName(child) in ids) glass(child)
        }
    }

    private fun byClass(root: Any?, classNames: Set<String>) {
        val v = root as? View ?: return
        traverse(v) { child ->
            if (classNames.any { child.javaClass.simpleName.contains(it) }) glass(child)
        }
    }

    private fun traverse(view: View, action: (View) -> Unit) {
        action(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) traverse(view.getChildAt(i), action)
        }
    }

    private fun forEachChild(parent: ViewGroup, action: (View) -> Unit) {
        for (i in 0 until parent.childCount) action(parent.getChildAt(i))
    }

    private fun findList(root: View?): View? {
        val v = root ?: return null
        var result: View? = null
        traverse(v) { child ->
            if (result == null && isList(child)) result = child
        }
        return result
    }

    private fun isList(view: View): Boolean {
        val name = view.javaClass.name
        return name.contains("RecyclerView") || name.contains("ListView")
    }

    private fun master(): Boolean = HookPrefs.getBoolean(CcGlassKeys.MASTER, false)

    private fun sub(key: String): Boolean = HookPrefs.getBoolean(key, true)

    private fun debug(): Boolean = HookPrefs.getBoolean(CcGlassKeys.DEBUG, false)

    private fun idName(view: View): String =
        runCatching {
            if (view.id == View.NO_ID) "" else view.resources.getResourceEntryName(view.id)
        }.getOrDefault("")

    private fun dumpTree(root: View?) {
        val view = root ?: return
        val sb = StringBuilder("$tag: view tree\n")
        collect(view, 0, sb)
        HookHelper.log(sb.toString())
    }

    private fun collect(view: View, depth: Int, sb: StringBuilder) {
        sb.append("  ".repeat(depth))
            .append(view.javaClass.simpleName)
            .append(" #").append(idName(view))
            .append(if (view.isClickable) " [clickable]" else "")
            .append(if (view.isActivated) " [activated]" else "")
            .append(if (view.isSelected) " [selected]" else "")
            .append('\n')
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collect(view.getChildAt(i), depth + 1, sb)
        }
    }

    companion object {
        const val KEY = "cc_glass"

        private const val TOKEN_GLASS = "DefaultContentBgMaterialToken"
        private const val TOKEN_DEFAULT = "DEFAULT_GLASS_TOKEN"
        private const val TOKEN_ACTIVATED = "ACTIVATED_GLASS_TOKEN"

        private const val SECONDARY_BASE =
            "miui.systemui.controlcenter.panel.secondary.SecondaryPanelControllerBase"
        private const val TILE_ITEM_CLASS =
            "miui.systemui.controlcenter.qs.tileview.QSTileItemView"
        private const val MORE_BUTTON_CLASS =
            "miui.systemui.controlcenter.widget.DetailPanelMoreButtonView"
        private const val SIDE_VOLUME_CONTROLLER =
            "com.android.systemui.miui.volume.VolumePanelViewController"
    }
}
