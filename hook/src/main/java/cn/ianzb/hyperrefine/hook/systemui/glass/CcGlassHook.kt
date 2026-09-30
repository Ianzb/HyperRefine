package cn.ianzb.hyperrefine.hook.systemui.glass

import android.view.View
import android.view.ViewGroup
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.systemui.PluginLoader
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 控制中心「柔光玻璃」：把二级面板的按钮 / 卡片接入系统同款柔光玻璃。
 *
 * 统一由总开关 [CcGlassKeys.MASTER] 控制。涉及：
 * - 亮度二级：大亮度条 + 三个圆形按钮（开启白色遮罩 / 关闭默认玻璃）
 * - WLAN / 移动数据 / 蓝牙详情：列表项（顶部已连接卡片、下方列表组）与「更多设置」按钮
 * - 控制中心音量 / 侧边音量：音量条、静音 / 勿扰圆按钮、定时滑块
 * - 播放器：设备卡片
 *
 * 所有材质都调用系统自身接口（`MaterialBackgroundExt` / `MiBackgroundStyle` /
 * `MiBlurCompat` / `QSTileItemIconView` 自带方法），不复制任何系统代码。
 */
class CcGlassHook : BaseHook() {

    override val key: String = KEY

    /** 已监听布局的列表容器（避免重复添加 `OnGlobalLayoutListener`）。 */
    private val observed = java.util.WeakHashMap<ViewGroup, Boolean>()

    /** 已设置圆形轮廓的静音 / 勿扰按钮。 */
    private val outlined = java.util.WeakHashMap<View, Boolean>()

    /** 已注册「动画结束重套」的图标动画对象。 */
    private val animEndHooked = java.util.WeakHashMap<Any, Boolean>()

    override fun init() {
        val systemCl = target.classLoader ?: return
        CcGlassApi.initSystem(systemCl)
        PluginLoader.register(key, systemCl) { pluginCl ->
            CcGlassApi.init(pluginCl)
            hookSecondaryPanels(pluginCl)
            hookTileIcons(pluginCl)
            hookMoreButton(pluginCl)
            hookDetailItems(systemCl)
            hookRingerButtons(pluginCl)
            hookSideVolume(pluginCl)
        }
    }

    // ---------------- 二级面板（控制中心插件） ----------------

    private fun hookSecondaryPanels(pluginCl: ClassLoader) {
        val base = Reflect.findClassIfExists(SECONDARY_BASE, pluginCl) ?: return

        base.declaredMethods
            .firstOrNull { it.name == "onSecondaryVisible" && it.parameterCount == 2 }
            ?.let { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        if ((param.args.getOrNull(0) as? Boolean) != true) return@hookAfter
                        val controller = param.thisObject ?: return@hookAfter
                        // 立即套一次（展开前）；delegate 建好内容、动画开始前再补一次。
                        applySecondary(controller)
                        (call(controller, "getSecondaryPanelContainer") as? View)
                            ?.post { if (master()) runCatching { applySecondary(controller) } }
                    }
                }.onFailure { HookHelper.log("$tag: hook secondary failed", it) }
            }

        // 深浅色 / 尺寸等配置变化后重套，避免切换主题后玻璃丢失。
        base.declaredMethods
            .firstOrNull { it.name == "onConfigurationChanged" && it.parameterCount == 1 }
            ?.let { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        if (master()) param.thisObject?.let { applySecondary(it) }
                    }
                }.onFailure { HookHelper.log("$tag: hook config change failed", it) }
            }
    }

    private fun applySecondary(controller: Any) {
        if (!master()) return
        val root = call(controller, "getSecondaryPanelContainer") as? View
            ?: call(controller, "getView") as? View
        when (controller.javaClass.simpleName) {
            "BrightnessPanelController" -> glassBrightness(root)
            "MediaPanelController" -> glassMedia(root)
            "VolumePanelController" -> glassCcVolume(controller, root)
            "DetailPanelController" -> glassDetail(root)
        }
    }

    // ---------------- 亮度二级 ----------------

    /**
     * 亮度面板圆形按钮（`QSTileItemIconView`）。挂在 `updateIconInternal` 之后：
     * 黑夜模式磁贴的 `updateIcon` 会把 `updateIconInternal` post 到下一帧，挂后者才能保证玻璃最后设置。
     */
    private fun hookTileIcons(pluginCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(TILE_ICON_CLASS, pluginCl) ?: return
        val method = cls.declaredMethods.firstOrNull {
            it.name == "updateIconInternal" && it.parameterCount == 5
        } ?: cls.declaredMethods.firstOrNull {
            it.name == "updateIcon" && it.parameterCount == 5
        } ?: return
        method.isAccessible = true
        runCatching {
            HookHelper.hookAfter(method) { param ->
                if (!master()) return@hookAfter
                val iconView = param.thisObject as? View ?: return@hookAfter
                if (!inBrightnessPanel(iconView)) return@hookAfter
                applyTileGlass(iconView, param.args.getOrNull(0))
            }
        }.onFailure { HookHelper.log("$tag: hook tile icons failed", it) }
    }

    private fun inBrightnessPanel(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent is View && idName(parent) == "brightness_panel") return true
            parent = parent.parent
        }
        return false
    }

    private fun glassBrightness(root: View?) {
        // 大亮度条轨道；主题 / 配置切换后系统会重绘，稍后再补一次。
        applyBrightnessSlider(root)
        root?.postDelayed({ if (master()) applyBrightnessSlider(root) }, 300L)
        // 圆形磁贴按钮：按当前开关状态处理。
        val view = root ?: return
        traverse(view) { child ->
            if (child is ViewGroup && child.javaClass.simpleName == TILE_ICON_NAME) {
                applyTileGlass(child, stateOf(child))
            }
        }
    }

    private fun applyBrightnessSlider(root: View?) {
        byId(root, SLIDER_IDS)
    }

    /**
     * 与系统 `QSTileItemIconView` 一致的两种状态：
     * - 开启：取系统开启背景 drawable（白色圆形遮罩）与图标 combine；
     * - 关闭：去掉深色底，套默认柔光玻璃（图标本体带圆形 outline）。
     */
    private fun applyTileGlass(iconView: View, state: Any?) {
        val target = call(iconView, "getIcon") as? android.widget.ImageView ?: return

        // 图标正在播放动画（如深色模式切换）时不要覆盖它的 drawable，否则动画会卡在一半；
        // 注册动画结束回调，等播完再套。
        val anim = findAnimatable2(target.drawable)
        if ((anim as? android.graphics.drawable.Animatable)?.isRunning == true) {
            registerTileAnimationEnd(iconView, target)
            return
        }

        val on = (state?.let { getIntField(it, "state") }) == 2

        // 系统把「底图 + 图标」合成成 LayerDrawable：去掉底图层，只保留图标本体。
        (target.drawable as? android.graphics.drawable.LayerDrawable)?.let { layer ->
            if (layer.numberOfLayers >= 1) {
                runCatching { target.setImageDrawable(layer.getDrawable(layer.numberOfLayers - 1)) }
            }
        }

        iconView.background = null
        target.clipToOutline = false
        target.background = null
        if (on) {
            val bg = call(iconView, "getActiveBackgroundDrawable", state) as? android.graphics.drawable.Drawable
            val icon = target.drawable
            if (icon != null && bg != null) {
                val combined = android.graphics.drawable.LayerDrawable(
                    arrayOf<android.graphics.drawable.Drawable>(bg, icon),
                )
                combined.setLayerGravity(1, android.view.Gravity.CENTER)
                (call(iconView, "getProperIconSize", icon) as? Int)
                    ?.takeIf { it > 0 }
                    ?.let { combined.setLayerSize(1, it, it) }
                target.setImageDrawable(combined)
            }
        } else {
            CcGlassApi.applyStyle(target, null, CcGlassApi.bionics(TOKEN_DEFAULT))
        }
        registerTileAnimationEnd(iconView, target)
    }

    /** 在图标动画结束时重套一次玻璃（每个动画对象只注册一次）。 */
    private fun registerTileAnimationEnd(iconView: View, target: android.widget.ImageView) {
        val anim = findAnimatable2(target.drawable) ?: return
        if (animEndHooked[anim] == true) return
        animEndHooked[anim] = true
        runCatching {
            anim.registerAnimationCallback(object : android.graphics.drawable.Animatable2.AnimationCallback() {
                override fun onAnimationEnd(drawable: android.graphics.drawable.Drawable?) {
                    if (!master()) return
                    runCatching { applyTileGlass(iconView, stateOf(iconView)) }
                }
            })
        }.onFailure { HookHelper.log("$tag: register anim end failed", it) }
    }

    private fun findAnimatable2(drawable: android.graphics.drawable.Drawable?): android.graphics.drawable.Animatable2? {
        when (drawable) {
            is android.graphics.drawable.Animatable2 -> return drawable
            is android.graphics.drawable.LayerDrawable -> {
                for (i in 0 until drawable.numberOfLayers) {
                    findAnimatable2(drawable.getDrawable(i))?.let { return it }
                }
            }
        }
        return null
    }

    // ---------------- 详情面板（WLAN / 移动数据 / 蓝牙） ----------------

    private fun hookDetailItems(systemCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(DETAIL_ADAPTER_CLASS, systemCl) ?: return
        val method = cls.declaredMethods.firstOrNull {
            it.name == "updateSelectableItemBackground" && it.parameterCount == 2
        } ?: return
        method.isAccessible = true
        runCatching {
            HookHelper.hookAfter(method) { param ->
                if (!master()) return@hookAfter
                val view = param.args.getOrNull(1) as? View ?: return@hookAfter
                val index = param.args.getOrNull(0) as? Int
                val adapter = param.thisObject ?: return@hookAfter
                // 立即套一次（覆盖系统同一轮的实心色）；布局完成后再补一次（修正按 0 尺寸算出的参数）。
                applyDetailGlass(view, index, adapter)
                view.post { if (master()) runCatching { applyDetailGlass(view, index, adapter) } }
            }
        }.onFailure { HookHelper.log("$tag: hook detail items failed", it) }
    }

    private fun applyDetailGlass(view: View, index: Int?, adapter: Any) {
        runCatching {
            view.background = null
            if (view.isSelected) {
                // 顶部已连接卡片：激活柔光玻璃（白色玻璃 + 描边）。
                CcGlassApi.applyStyle(view, null, CcGlassApi.bionics(TOKEN_ACTIVATED))
            } else {
                // 下方列表组：系统 blend 玻璃（由系统 `getBlendColorsArrayId` 提供色板）。
                val content = Reflect.getObjectField(adapter, "this\$0") ?: return
                val arrayId = Reflect.callMethod(content, "getBlendColorsArrayId", false) as? Int ?: return
                CcGlassApi.forceBlurGlass(view, view.resources.getIntArray(arrayId))
            }
            if (index != null) applyGroupOutline(adapter, index, view)
        }.onFailure { HookHelper.log("$tag: detail glass failed", it) }
    }

    /** 复刻系统按「组内位置」设置 outline：首项上圆角、尾项下圆角、中间直角 → 整组一张圆角卡片。 */
    private fun applyGroupOutline(adapter: Any, index: Int, view: View) {
        runCatching {
            val content = Reflect.getObjectField(adapter, "this\$0") ?: return
            val map = Reflect.getObjectField(content, "groupTypeMap") ?: return
            val type = Reflect.callMethod(map, "get", index, 0) as? Int ?: 0
            if (type == 0) return
            val radius = Reflect.getObjectField(content, "universalCornerRadius") as? Float ?: return
            val top = if (type == GROUP_SINGLE || type == GROUP_FIRST) radius else 0f
            val bottom = if (type == GROUP_SINGLE || type == GROUP_LAST) radius else 0f
            val cls = Reflect.findClass(OUTLINE_PROVIDER_CLASS, view.context.classLoader)
            val provider = Reflect.newInstance(cls, top, top, bottom, bottom) as? android.view.ViewOutlineProvider
                ?: return
            view.outlineProvider = provider
            view.clipToOutline = true
        }.onFailure { HookHelper.log("$tag: group outline failed", it) }
    }

    /** 详情面板补「更多设置」按钮玻璃（列表项由 [hookDetailItems] 处理）。 */
    private fun glassDetail(root: View?) {
        byId(root, setOf("more_button"))
    }

    // ---------------- 播放器 ----------------

    private fun glassMedia(root: View?) {
        val list = findList(root) as? ViewGroup ?: return
        applyDeviceCards(list)
        list.post { if (master()) runCatching { applyDeviceCards(list) } }
        if (observed[list] == true) return
        observed[list] = true
        list.viewTreeObserver.addOnGlobalLayoutListener {
            if (master()) runCatching { applyDeviceCards(list) }
        }
    }

    private fun applyDeviceCards(list: ViewGroup) {
        forEachChild(list) { child ->
            if (child.isClickable && child !is android.widget.TextView) applyDeviceCard(child)
        }
    }

    private fun applyDeviceCard(child: View) {
        // 尺寸为 0 时等下一帧布局完成后再套，避免按 0 尺寸算出错误参数（过饱和 / 不透明）。
        if (child.width == 0 || child.height == 0) {
            child.post { if (master()) runCatching { applyDeviceCard(child) } }
            return
        }
        val active = child.isSelected
        child.background = null
        CcGlassApi.applyStyle(
            child,
            CcGlassApi.colorBlend(if (active) "CC_MIPLAY_PANEL_ACTIVE_BIONICS_COLORS" else "CC_TILE_DEFAULT_BLEND_COLORS"),
            CcGlassApi.bionics(if (active) TOKEN_ACTIVATED else TOKEN_DEFAULT),
        )
    }

    // ---------------- 音量（控制中心 + 侧边） ----------------

    private fun glassCcVolume(controller: Any, root: View?) {
        val columns = call(controller, "getDelegate")?.let { call(it, "getContentColumns") } as? ViewGroup
        columns?.let {
            forEachChild(it) { column ->
                glass(column)
                clearSliderDarkBg(column)
            }
        }
        glassRinger(root)
        byClass(root, setOf(TIMER_SEEK_BAR_NAME))
    }

    private fun hookSideVolume(pluginCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(SIDE_VOLUME_CONTROLLER, pluginCl) ?: return
        cls.declaredMethods.filter { it.name in SIDE_VOLUME_METHODS }.forEach { method ->
            method.isAccessible = true
            runCatching {
                HookHelper.hookAfter(method) { param ->
                    applySideVolume(param.thisObject ?: return@hookAfter)
                }
            }.onFailure { HookHelper.log("$tag: hook side volume ${method.name} failed", it) }
        }
    }

    private fun applySideVolume(controller: Any) {
        if (!master()) return
        // 侧边二级菜单的**整体面板**背景：清掉系统兜底色后套柔光玻璃（与滑条同样的做法）。
        (call(controller, "getVolumeContentBg") as? View)?.let { v ->
            v.background = null
            CcGlassApi.applyStyle(v, null, CcGlassApi.bionics(TOKEN_DEFAULT))
        }
        glassVolumeColumns(controller)
        val ringer = call(controller, "getVolumeRingerModeLayout") as? View
        glassRinger(ringer)
        byClass(ringer, setOf(TIMER_SEEK_BAR_NAME))
    }

    /** 对音量控制器的所有音量列（含收起态使用的临时列 `mTempColumn`）套玻璃并清除深色底。 */
    private fun glassVolumeColumns(controller: Any) {
        val applyColumn: (Any?) -> Unit = { column ->
            val columnView = column?.let { call(it, "getView") as? View }
            if (columnView != null) {
                glass(columnView)
                // 一次遍历同时清除深色底与滑条兜底色（侧边音量的滑条被系统铺了一层偏深的 flat 兜底色）。
                traverse(columnView) { v ->
                    when (idName(v)) {
                        "volume_column_view", "volume_column_slider_bg_blend" -> v.background = null
                        "volume_column_slider" -> {
                            v.background = null
                            CcGlassApi.applyStyle(v, null, CcGlassApi.bionics(TOKEN_DEFAULT))
                        }
                    }
                }
            }
        }
        (call(controller, "getColumns") as? List<*>)?.forEach(applyColumn)
        runCatching { Reflect.getObjectField(controller, "mTempColumn") }.getOrNull()?.let(applyColumn)
    }

    /**
     * 静音 / 勿扰圆按钮：清除禁用时的深色底并套玻璃。状态更新后系统会重新铺深色底，
     * 故在其 `updateState` 之后再次清除，保证圆形按钮不带深色背景。
     */
    private fun hookRingerButtons(pluginCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(RINGER_HELPER_CLASS, pluginCl) ?: return
        val method = cls.declaredMethods.firstOrNull {
            it.name == "updateState" && it.parameterCount == 0
        } ?: return
        method.isAccessible = true
        runCatching {
            HookHelper.hookAfter(method) { param ->
                if (!master()) return@hookAfter
                val helper = param.thisObject ?: return@hookAfter
                val standard = Reflect.getObjectField(helper, "mStandardView") as? View ?: return@hookAfter
                if (standard.background != null) standard.background = null
            }
        }.onFailure { HookHelper.log("$tag: hook ringer buttons failed", it) }
    }

    private fun glassRinger(root: View?) {
        val view = root ?: return
        traverse(view) { child ->
            if (idName(child) != "bg_blur") return@traverse
            traverse(child) { inner ->
                if (idName(inner) == "miui_standard_btn" && inner.background != null) {
                    inner.background = null
                }
            }
            if (outlined[child] != true) {
                outlined[child] = true
                makeCircular(child)
            }
            if (child.background != null) child.background = null
            glass(child)
        }
    }

    /** 静音 / 勿扰按钮必须是圆形：设置圆形轮廓，否则玻璃会画成方形。 */
    private fun makeCircular(view: View) {
        view.clipToOutline = true
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, minOf(v.width, v.height) / 2f)
            }
        }
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

    // ---------------- 「更多设置」按钮 ----------------

    /** 点击时系统会重新套用实心 token，导致玻璃消失；这里在状态变化后重套默认玻璃。 */
    private fun hookMoreButton(pluginCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(MORE_BUTTON_CLASS, pluginCl) ?: return
        val method = cls.declaredMethods.firstOrNull {
            it.name == "dispatchSetPressed" && it.parameterCount == 1
        } ?: return
        method.isAccessible = true
        runCatching {
            HookHelper.hookAfter(method) { param ->
                if (!master()) return@hookAfter
                (param.thisObject as? View)?.let { CcGlassApi.apply(it, TOKEN_GLASS) }
            }
        }.onFailure { HookHelper.log("$tag: hook more button failed", it) }
    }

    // ---------------- 工具 ----------------

    private fun call(target: Any, getter: String, vararg args: Any?): Any? =
        runCatching { Reflect.callMethod(target, getter, *args) }.getOrNull()

    private fun stateOf(iconView: View): Any? = runCatching { Reflect.getObjectField(iconView, "state") }.getOrNull()

    private fun getIntField(target: Any, name: String): Int? =
        runCatching { Reflect.getObjectField(target, name) as? Int }.getOrNull()

    private fun glass(view: Any?) {
        (view as? View)?.let { CcGlassApi.apply(it, TOKEN_GLASS) }
    }

    private fun byId(root: Any?, ids: Set<String>) {
        val view = root as? View ?: return
        traverse(view) { child -> if (idName(child) in ids) glass(child) }
    }

    private fun byClass(root: Any?, classNames: Set<String>) {
        val view = root as? View ?: return
        traverse(view) { child ->
            if (classNames.any { child.javaClass.simpleName == it }) glass(child)
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
        val view = root ?: return null
        var result: View? = null
        traverse(view) { child ->
            if (result == null && child.javaClass.name.contains("RecyclerView")) result = child
        }
        return result
    }

    private fun master(): Boolean = HookPrefs.getBoolean(CcGlassKeys.MASTER, false)

    private fun idName(view: View): String =
        runCatching {
            if (view.id == View.NO_ID) "" else view.resources.getResourceEntryName(view.id)
        }.getOrDefault("")

    companion object {
        const val KEY = "cc_glass"

        private const val TOKEN_GLASS = "DefaultContentBgMaterialToken"
        private const val TOKEN_DEFAULT = "DEFAULT_GLASS_TOKEN"
        private const val TOKEN_ACTIVATED = "ACTIVATED_GLASS_TOKEN"

        private const val SECONDARY_BASE =
            "miui.systemui.controlcenter.panel.secondary.SecondaryPanelControllerBase"
        private const val TILE_ICON_CLASS =
            "miui.systemui.controlcenter.qs.tileview.QSTileItemIconView"
        private const val TILE_ICON_NAME = "QSTileItemIconView"
        private const val TIMER_SEEK_BAR_NAME = "MiuiVolumeTimerSeekBar"
        private const val DETAIL_ADAPTER_CLASS =
            "com.android.systemui.qs.QSDetailContent\$Adapter"
        private const val RINGER_HELPER_CLASS =
            "com.android.systemui.miui.volume.MiuiRingerModeLayout\$RingerButtonHelper"
        private const val MORE_BUTTON_CLASS =
            "miui.systemui.controlcenter.widget.DetailPanelMoreButtonView"
        private const val SIDE_VOLUME_CONTROLLER =
            "com.android.systemui.miui.volume.VolumePanelViewController"
        private const val OUTLINE_PROVIDER_CLASS =
            "com.android.systemui.util.ViewUtils\$setCustomOutline\$1"

        private const val GROUP_SINGLE = 1
        private const val GROUP_FIRST = 2
        private const val GROUP_LAST = 3

        private val SLIDER_IDS = setOf("bionics_progress_bg", "progress_bg")
        private val SIDE_VOLUME_METHODS =
            setOf("showVolumePanelH", "initPanelView", "updateExpandedH", "updateVolumeColumnSliderH")
    }
}
