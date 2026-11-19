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
 * - 亮度二级：大亮度条；三个圆形按钮改为非详情磁贴，走系统自带 SDF 玻璃（状态与动画由系统处理）
 * - WLAN / 移动数据 / 蓝牙详情：独立卡片和分组列表项
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

    /** 已设置圆形轮廓的设备中心卡片。 */
    private val outlined = java.util.WeakHashMap<View, Boolean>()
    private val ringerRadii = java.util.WeakHashMap<View, Int>()
    private val bionicsDetailRows = java.util.WeakHashMap<View, Boolean>()
    private val detailTopMargins = java.util.WeakHashMap<View, Pair<Int, Int>>()
    private val pendingDetailLayouts = java.util.WeakHashMap<View, View.OnLayoutChangeListener>()
    private val detailRows = java.util.WeakHashMap<View, java.lang.ref.WeakReference<Any>>()
    private val refreshedDetailVisibility = java.util.WeakHashMap<View, Boolean>()
    private var ringerResources: Class<*>? = null

    /**
     * 侧边音量已套玻璃的视图及其状态签名。
     *
     * 音量按键 / 拖动会高频触发 `updateVolumeColumnSliderH`，而每次都对全部音量列
     * 反射套用模糊玻璃（`setMiViewBlurModeCompat` + `setMiBackgroundStyle`）代价极高，
     * 会造成调整音量时明显卡顿。此处记录每个视图最近一次套用的签名，仅在
     * 「面板重新展示」或「展开态变化」导致签名变化时重套，其余调用直接跳过。
     */
    private val sideVolumeApplied = java.util.WeakHashMap<View, Long>()

    /** 侧边音量玻璃状态代次：每次面板重新展示 / 初始化时自增，强制重套一次。 */
    private var sideVolumeGeneration = 0L

    /** 最近一次已套玻璃的状态签名；仅音量值变化（签名不变）时跳过整轮遍历。 */
    @Volatile
    private var lastSideSignature = Long.MIN_VALUE

    /** 视图 id 资源名缓存：`getResourceEntryName` 较慢，高频遍历中复用。 */
    private val idNameCache = java.util.WeakHashMap<View, String>()

    override fun init() {
        val systemCl = target.classLoader ?: return
        CcGlassApi.initSystem(systemCl)
        PluginLoader.register(key, systemCl) { pluginCl ->
            CcGlassApi.init(pluginCl)
            ringerResources = Reflect.findClassIfExists(RINGER_RES_CLASS, pluginCl)
            hookSecondaryPanels(pluginCl)
            hookBrightnessTileDetailFlag(pluginCl)
            hookMoreButton(pluginCl)
            hookDetailItems(systemCl)
            hookToggleItems(systemCl)
            hookRingerButtons(pluginCl)
            hookSideVolume(pluginCl)
            hookDeviceCenterGlass(pluginCl)
        }
    }

    // ---------------- 二级面板（控制中心插件） ----------------

    private fun hookSecondaryPanels(pluginCl: ClassLoader) {
        val base = Reflect.findClassIfExists(SECONDARY_BASE, pluginCl) ?: return
        base.declaredMethods.firstOrNull { it.name == "onAnimComplete" && it.parameterCount == 0 }?.let { method ->
            runCatching {
                HookHelper.hookAfter(method) { param ->
                    val controller = param.thisObject ?: return@hookAfter
                    if (!master() || controller.javaClass.simpleName != "DetailPanelController") return@hookAfter
                    val root = call(controller, "getContent") as? View ?: return@hookAfter
                    if (!root.isShown) return@hookAfter
                    traverse(root) { row ->
                        detailRows[row]?.get()?.let { applyDetailMaterial(row, it) }
                    }
                }
            }.onFailure { HookHelper.log("$tag: hook detail animation completion failed", it) }
        }
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
     * 亮度面板的三个圆形磁贴按钮官方按「详情磁贴」处理时只套 blend 颜色、不套 SDF 柔光玻璃；
     * 只有非详情磁贴才会走 `MiBackgroundStyle.setMiBackgroundStyle(... DEFAULT/ACTIVATED_GLASS_TOKEN ...)`。
     *
     * 全项目只有 `BrightnessPanelTilesDelegate` 以 `isDetailTile = true` 构造该视图，故仅需在构造前把
     * 该参数改为 `false`，玻璃、开关态与动画即由系统按非详情分支自行完成，无需再手工拼 drawable。
     */
    private fun hookBrightnessTileDetailFlag(pluginCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(TILE_ICON_CLASS, pluginCl) ?: return
        val ctor = cls.declaredConstructors.firstOrNull { c ->
            c.parameterCount == 4 &&
                c.parameterTypes[0] == android.content.Context::class.java &&
                c.parameterTypes[1] == android.content.Context::class.java &&
                c.parameterTypes[2] == java.lang.Boolean.TYPE &&
                c.parameterTypes[3] == java.lang.Boolean.TYPE
        } ?: return
        ctor.isAccessible = true
        runCatching {
            // (pluginContext, sysUIContext, card, isDetailTile) -> isDetailTile = false
            HookHelper.hookBefore(ctor) { param ->
                if (param.args.getOrNull(3) == true) param.setArg(3, false)
            }
        }.onFailure { HookHelper.log("$tag: hook brightness tile ctor failed", it) }
    }

    private fun glassBrightness(root: View?) {
        // 大亮度条轨道；主题 / 配置切换后系统会重绘，稍后再补一次。
        applyBrightnessSlider(root)
        root?.postDelayed({ if (master()) applyBrightnessSlider(root) }, 300L)
    }

    private fun applyBrightnessSlider(root: View?) {
        byId(root, SLIDER_IDS)
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
                val view = param.args.getOrNull(1) as? View ?: return@hookAfter
                val index = param.args.getOrNull(0) as? Int
                val adapter = param.thisObject ?: return@hookAfter
                pendingDetailLayouts.remove(view)?.let(view::removeOnLayoutChangeListener)
                detailRows.remove(view)
                if (master()) applyDetailGlass(view, index, adapter) else applyDetailTopGap(view, 0)
            }
        }.onFailure { HookHelper.log("$tag: hook detail items failed", it) }
        cls.declaredMethods.filter { it.name == "onBindViewHolder" && it.parameterCount == 3 }
            .forEach { bind ->
                runCatching {
                    HookHelper.hookAfter(bind) { param ->
                        if (!master()) return@hookAfter
                        val holder = param.args.getOrNull(0) ?: return@hookAfter
                        val row = runCatching { Reflect.getObjectField(holder, "itemView") }.getOrNull() as? View
                            ?: return@hookAfter
                        val binding = detailRows[row] ?: return@hookAfter
                        row.post {
                            if (detailRows[row] !== binding || !row.isAttachedToWindow || !master()) return@post
                            binding.get()?.let { applyDetailMaterial(row, it) }
                        }
                    }
                }.onFailure { HookHelper.log("$tag: hook detail bind completion failed", it) }
            }
    }

    private fun isConnectionDetail(content: Any): Boolean {
        val suffix = Reflect.callMethod(content, "getSuffix") as? String ?: return false
        return suffix == "Wifi" || suffix == "Cellular" || suffix == "Bluetooth"
    }

    /**
     * 移动网络（SIM）详情里的「5G 网络」开关卡片是一个 `ToggleItem`：系统的
     * [updateSelectableItemBackground] 对非 `SelectableItem` 会直接 `return`，因此该卡片
     * 不会走玻璃分支。这里在该卡片绑定完成后，套用与详情页「更多设置」按钮一致的
     * 柔光玻璃材质（`DefaultContentBgMaterialToken`），并按分组位置设置圆角
     * （`ToggleItem` 恒为单项，四角皆圆）。
     */
    private fun hookToggleItems(systemCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(DETAIL_ADAPTER_CLASS, systemCl)
        if (cls == null) {
            HookHelper.log("$tag: hookToggleItems $DETAIL_ADAPTER_CLASS not found")
            return
        }
        val methods = cls.declaredMethods.filter {
            it.name == "onBindViewHolder" && it.parameterCount == 2
        }
        methods.forEach { method ->
            method.isAccessible = true
            runCatching {
                HookHelper.hookAfter(method) { param ->
                    if (!master()) return@hookAfter
                    val holder = param.args.getOrNull(0) ?: return@hookAfter
                    val index = param.args.getOrNull(1) as? Int ?: return@hookAfter
                    val adapter = param.thisObject ?: return@hookAfter
                    val content = Reflect.getObjectField(adapter, "this\$0") ?: return@hookAfter
                    // 仅处理移动网络详情里的开关项（本模块注入的「5G 网络」）。
                    if (Reflect.getObjectField(content, "suffix") != "Cellular") return@hookAfter
                    val items = Reflect.getObjectField(content, "items") as? Array<*> ?: return@hookAfter
                    val item = items.getOrNull(index) ?: return@hookAfter
                    if (!item.javaClass.name.endsWith("ToggleItem")) return@hookAfter
                    val view = Reflect.getObjectField(holder, "itemView") as? View ?: return@hookAfter
                    applyToggleGlass(view)
                    view.post { if (master()) runCatching { applyToggleGlass(view) } }
                }
            }.onFailure { HookHelper.log("$tag: hook toggle items failed", it) }
        }
    }

    /** 5G 开关卡片：与「更多设置」按钮同款柔光玻璃材质（`DefaultContentBgMaterialToken`）。 */
    private fun applyToggleGlass(view: View): Boolean {
        // 系统在绑定 ToggleItem 时已按 `universalCornerRadius` 设置好圆角轮廓，这里只需换材质背景。
        view.background = null
        return CcGlassApi.apply(view, TOKEN_GLASS)
    }

    private fun applyDetailGlass(view: View, index: Int?, adapter: Any) {
        runCatching {
            val content = Reflect.getObjectField(adapter, "this\$0") ?: return
            val groups = Reflect.getObjectField(adapter, "groupTypeMap") as? android.util.SparseIntArray ?: return
            val groupType = index?.let { groups.get(it) } ?: return
            val connectionDetail = isConnectionDetail(content)
            if (connectionDetail && groupType in 1..4) {
                prepareDetailCard(view, groupType, content)
                detailRows[view] = java.lang.ref.WeakReference(content)
                if (view.width > 0 && view.height > 0) {
                    applyDetailMaterial(view, content)
                } else {
                    val listener = View.OnLayoutChangeListener { row, left, top, right, bottom, _, _, _, _ ->
                        if (right > left && bottom > top) {
                            pendingDetailLayouts.remove(row)?.let(row::removeOnLayoutChangeListener)
                            if (master()) applyDetailMaterial(row, content)
                        }
                    }
                    pendingDetailLayouts[view] = listener
                    view.addOnLayoutChangeListener(listener)
                }
            } else {
                applyDetailTopGap(view, 0)
                if (bionicsDetailRows.remove(view) == true) CcGlassApi.clearBionicsStyle(view)
                if (groupType in 1..4 && !view.isSelected) applyDetailBlend(view, content)
            }
        }.onFailure { HookHelper.log("$tag: detail glass failed", it) }
    }

    private fun prepareDetailCard(view: View, groupType: Int, content: Any) {
        // 绑定阶段设置间距，让 RecyclerView 在第一次测量时就预留空间。
        val gap = if (groupType == 3 || groupType == 4) {
            (4f * view.resources.displayMetrics.density + 0.5f).toInt()
        } else 0
        applyDetailTopGap(view, gap)
        if (groupType == 1) return
        val radius = Reflect.getObjectField(content, "universalCornerRadius") as? Float ?: return
        view.outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, outline: android.graphics.Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radius)
            }
        }
        view.clipToOutline = true
    }

    private fun applyDetailMaterial(view: View, content: Any) {
        if (view.width <= 0 || view.height <= 0) return
        runCatching {
            if (CcGlassApi.applyDetailBionicsStyle(view, view.isSelected)) {
                bionicsDetailRows[view] = true
                refreshDetailVisibilityOnce(view)
            } else {
                if (bionicsDetailRows.remove(view) == true) CcGlassApi.clearBionicsStyle(view)
                applyDetailBlend(view, content)
            }
        }.onFailure { HookHelper.log("$tag: detail glass failed", it) }
    }

    private fun refreshDetailVisibilityOnce(view: View) {
        if (view.isSelected || !view.isAttachedToWindow || view.visibility != View.VISIBLE ||
            refreshedDetailVisibility.put(view, true) == true
        ) return
        view.visibility = View.INVISIBLE
        view.postOnAnimation {
            view.postOnAnimation {
                if (view.visibility == View.INVISIBLE) view.visibility = View.VISIBLE
            }
        }
    }

    private fun applyDetailBlend(view: View, content: Any) {
        val arrayId = Reflect.callMethod(content, "getBlendColorsArrayId", view.isSelected) as? Int ?: return
        CcGlassApi.forceBlurGlass(view, view.resources.getIntArray(arrayId))
    }

    private fun applyDetailTopGap(view: View, gap: Int) {
        val params = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val previous = detailTopMargins[view]
        val base = if (previous?.second == params.topMargin) previous.first else params.topMargin
        val target = base + gap
        if (gap == 0) detailTopMargins.remove(view) else detailTopMargins[view] = base to target
        if (params.topMargin == target) return
        params.topMargin = target
        view.layoutParams = params
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
        child.background = null
        if (child.isSelected) {
            // 当前设备：激活柔光玻璃（白色）。
            CcGlassApi.applyStyle(child, CcGlassApi.bionics(TOKEN_ACTIVATED))
        } else {
            // 其它设备：与控制中心一级媒体卡片同款材质 token。
            CcGlassApi.apply(child, "MediaItemToken")
        }
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
        glassRinger(root, expanded = true, needDialog = false)
        byClass(root, setOf(TIMER_SEEK_BAR_NAME))
    }

    private fun hookSideVolume(pluginCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(SIDE_VOLUME_CONTROLLER, pluginCl) ?: return
        cls.declaredMethods.filter { it.name in SIDE_VOLUME_METHODS }.forEach { method ->
            method.isAccessible = true
            // 面板重新展示 / 初始化属于低频结构事件，此时强制重套一次（覆盖主题 / 配置变化）。
            val structural = method.name == "showVolumePanelH" || method.name == "initPanelView"
            runCatching {
                HookHelper.hookAfter(method) { param ->
                    val controller = param.thisObject ?: return@hookAfter
                    if (structural) sideVolumeGeneration++
                    applySideVolume(controller)
                }
            }.onFailure { HookHelper.log("$tag: hook side volume ${method.name} failed", it) }
        }
    }

    private fun applySideVolume(controller: Any) {
        if (!master()) return
        val expanded = runCatching { Reflect.getObjectField(controller, "mExpanded") }.getOrNull() as? Boolean ?: false
        val signature = (sideVolumeGeneration shl 1) or (if (expanded) 1L else 0L)
        // 仅音量值变化时签名不变：跳过本轮遍历（调音量不再重复套玻璃 / 清底），消除卡顿。
        if (signature == lastSideSignature) return
        lastSideSignature = signature
        // 侧边二级菜单的**整体面板**背景：清掉系统兜底色后套柔光玻璃（与滑条同样的做法）。
        (call(controller, "getVolumeContentBg") as? View)?.let { v ->
            v.background = null
            applyStyleOnce(v, signature)
        }
        glassVolumeColumns(controller, signature)
        val ringer = call(controller, "getVolumeRingerModeLayout") as? View
        val needDialog = runCatching { Reflect.getObjectField(controller, "mNeedShowDialog") }.getOrNull() as? Boolean
            ?: return
        glassRinger(ringer, expanded, needDialog, signature)
        byClass(ringer, setOf(TIMER_SEEK_BAR_NAME), signature)
    }

    /** 对音量控制器的所有音量列（含收起态使用的临时列 `mTempColumn`）套玻璃并清除深色底。 */
    private fun glassVolumeColumns(controller: Any, signature: Long) {
        val applyColumn: (Any?) -> Unit = { column ->
            val columnView = column?.let { call(it, "getView") as? View }
            if (columnView != null) {
                // 与多应用音量面板共用同一套列玻璃处理，保证两者完全一致。
                VolumeColumnGlass.apply(
                    columnView,
                    styleRoot = { v ->
                        applyOnce(v, signature) { CcGlassApi.apply(it, VolumeColumnGlass.COLUMN_TOKEN) }
                    },
                    styleSlider = { v -> applyStyleOnce(v, signature) },
                )
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

    private fun glassRinger(root: View?, expanded: Boolean, needDialog: Boolean, signature: Long? = null) {
        val view = root ?: return
        val resources = ringerResources ?: return
        traverse(view) { child ->
            if (idName(child) != "bg_blur") return@traverse
            val radius = runCatching {
                Reflect.callStaticMethod(resources, "getButtonRadius", child.context, expanded, needDialog) as? Int
            }.getOrNull() ?: return@traverse
            traverse(child) { inner ->
                if (idName(inner) == "miui_standard_btn" && inner.background != null) {
                    inner.background = null
                }
            }
            if (ringerRadii[child] != radius) {
                ringerRadii[child] = radius
                child.clipToOutline = true
                child.outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(v: View, outline: android.graphics.Outline) {
                        val maxRadius = minOf(v.width, v.height) / 2f
                        outline.setRoundRect(0, 0, v.width, v.height, radius.toFloat().coerceIn(0f, maxRadius))
                    }
                }
            }
            if (child.background != null) child.background = null
            glass(child, signature)
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

    /**
     * 融合设备中心：圆形设备组件（每个设备 / 末尾「设备中心」入口）的柔光玻璃。
     *
     * 官方对这些圆形按钮套用的材质是 `ControlCenterMaterialTokens.deviceControlItemToken`，
     * 它的 bionics 分支只有一层纯色（`SolidColorStyle(ic_device_center_item_bionic_bg_color)`），
     * 因此开启「柔光玻璃」后这些按钮仍是实心圆、没有描边玻璃。
     *
     * 处理方式：在官方 `DeviceViewHolder#updateMaterialStyle` 之后，改为套用官方融合设备中心
     * 卡片同款材质 token（`deviceItemToken`，含柔光玻璃与一级磁贴色板）。
     * 与播放器页设备卡片一致：直接拼底层 SDF 玻璃在首次展示时会发灰 / 过糊，走官方 token
     * 才会一次成型。该方法在绑定、配置变化、材质模式变化时都会被调用，覆盖所有重绘时机。
     */
    private fun hookDeviceCenterGlass(pluginCl: ClassLoader) {
        DEVICE_HOLDER_CLASSES.forEach { className ->
            val cls = Reflect.findClassIfExists(className, pluginCl) ?: return@forEach
            val method = cls.declaredMethods.firstOrNull {
                it.name == "updateMaterialStyle" && it.parameterCount == 1
            } ?: return@forEach
            method.isAccessible = true
            runCatching {
                HookHelper.hookAfter(method) { param ->
                    if (!master()) return@hookAfter
                    val container = deviceContainer(param.thisObject) ?: return@hookAfter
                    applyDeviceItemGlass(container)
                }
            }.onFailure { HookHelper.log("$tag: hook $className#updateMaterialStyle failed", it) }
        }
    }

    /** 从 `DeviceViewHolder` 取圆形按钮视图（`binding.container`）。 */
    private fun deviceContainer(holder: Any?): View? {
        val viewHolder = holder ?: return null
        val binding = runCatching { Reflect.getObjectField(viewHolder, "binding") }.getOrNull() ?: return null
        return runCatching { Reflect.getObjectField(binding, "container") as? View }.getOrNull()
    }

    /**
     * 圆形设备按钮套玻璃。
     *
     * 清掉纯色背景后系统自带的 oval 轮廓也会消失，玻璃会被画成方块，故这里显式设置圆形轮廓；
     * 尺寸为 0（尚未布局）时等下一帧再套，避免按 0 尺寸算出错误参数；首次套用时面板材质 /
     * 背景可能尚未就绪，故布局完成后再补套一次（与播放器页设备卡片同样的处理）。
     */
    private fun applyDeviceItemGlass(container: View) {
        if (container.width == 0 || container.height == 0) {
            container.post { if (master()) runCatching { applyDeviceItemGlass(container) } }
            return
        }
        styleDeviceItemGlass(container)
        container.post { if (master()) runCatching { styleDeviceItemGlass(container) } }
    }

    private fun styleDeviceItemGlass(container: View) {
        if (outlined[container] != true) {
            outlined[container] = true
            makeCircular(container)
        }
        if (container.background != null) container.background = null
        CcGlassApi.apply(container, TOKEN_DEVICE_ITEM)
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

    private fun glass(view: Any?, signature: Long? = null) {
        val v = view as? View ?: return
        applyOnce(v, signature) { CcGlassApi.apply(it, TOKEN_GLASS) }
    }

    /** 与 [glass] 相同，但使用一级界面同款 SDF 玻璃（`DEFAULT_GLASS_TOKEN`）。 */
    private fun applyStyleOnce(view: View, signature: Long?) {
        applyOnce(view, signature) { CcGlassApi.applyStyle(it, CcGlassApi.bionics(TOKEN_DEFAULT)) }
    }

    /**
     * 仅在状态签名变化时执行 [apply]；[signature] 为 null 表示不缓存（低频路径）。
     * 只有套用成功才记录，避免失败后永久跳过。
     */
    private fun applyOnce(view: View, signature: Long?, apply: (View) -> Boolean): Boolean {
        if (signature != null && sideVolumeApplied[view] == signature) return false
        val applied = apply(view)
        if (applied && signature != null) sideVolumeApplied[view] = signature
        return applied
    }

    private fun byId(root: Any?, ids: Set<String>, signature: Long? = null) {
        val view = root as? View ?: return
        traverse(view) { child -> if (idName(child) in ids) glass(child, signature) }
    }

    private fun byClass(root: Any?, classNames: Set<String>, signature: Long? = null) {
        val view = root as? View ?: return
        traverse(view) { child ->
            if (classNames.any { child.javaClass.simpleName == it }) glass(child, signature)
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

    private fun idName(view: View): String {
        if (view.id == View.NO_ID) return ""
        idNameCache[view]?.let { return it }
        val name = runCatching { view.resources.getResourceEntryName(view.id) }.getOrDefault("")
        idNameCache[view] = name
        return name
    }

    companion object {
        const val KEY = "cc_glass"

        private const val TOKEN_GLASS = "DefaultContentBgMaterialToken"
        private const val TOKEN_DEFAULT = "DEFAULT_GLASS_TOKEN"
        private const val TOKEN_ACTIVATED = "ACTIVATED_GLASS_TOKEN"

        /** 融合设备中心卡片同款材质（含柔光玻璃）；圆形设备组件复用它。 */
        private const val TOKEN_DEVICE_ITEM = "DeviceItemToken"

        private const val SECONDARY_BASE =
            "miui.systemui.controlcenter.panel.secondary.SecondaryPanelControllerBase"
        private const val TILE_ICON_CLASS =
            "miui.systemui.controlcenter.qs.tileview.QSTileItemIconView"
        private const val TIMER_SEEK_BAR_NAME = "MiuiVolumeTimerSeekBar"
        private const val DETAIL_ADAPTER_CLASS =
            "com.android.systemui.qs.QSDetailContent\$Adapter"
        private const val RINGER_HELPER_CLASS =
            "com.android.systemui.miui.volume.MiuiRingerModeLayout\$RingerButtonHelper"
        private const val MORE_BUTTON_CLASS =
            "miui.systemui.controlcenter.widget.DetailPanelMoreButtonView"
        private const val SIDE_VOLUME_CONTROLLER =
            "com.android.systemui.miui.volume.VolumePanelViewController"
        private const val RINGER_RES_CLASS =
            "com.android.systemui.miui.volume.RingerButtonRes"

        /** 融合设备中心的圆形组件：每个设备 + 末尾「融合设备中心」入口。 */
        private val DEVICE_HOLDER_CLASSES = listOf(
            "miui.systemui.controlcenter.panel.main.devicecenter.devices.DeviceItemViewHolder",
            "miui.systemui.controlcenter.panel.main.devicecenter.devices.DetailViewHolder",
        )

        private val SLIDER_IDS = setOf("bionics_progress_bg", "progress_bg")
        private val SIDE_VOLUME_METHODS =
            setOf("showVolumePanelH", "initPanelView", "updateExpandedH", "updateVolumeColumnSliderH")
    }
}
