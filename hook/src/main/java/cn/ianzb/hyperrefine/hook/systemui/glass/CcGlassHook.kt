package cn.ianzb.hyperrefine.hook.systemui.glass

import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.widget.SeekBar
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
 * - 播放器：设备卡片、横向总音量条
 *
 * 所有材质都调用系统自身接口（`MaterialBackgroundExt` / `MiBackgroundStyle` /
 * `MiBlurCompat` / `QSTileItemIconView` 自带方法），不复制任何系统代码。
 */
class CcGlassHook : BaseHook() {

    override val key: String = KEY

    /** 已监听布局的列表容器（避免重复添加 `OnGlobalLayoutListener`）。 */
    private val observedMediaLists = java.util.WeakHashMap<ViewGroup, Boolean>()
    private val mediaVolumeSliders = java.util.WeakHashMap<View, java.lang.ref.WeakReference<SeekBar>>()
    private var mediaContentClass: Class<*>? = null

    /** 已设置圆形轮廓的设备中心卡片。 */
    private val outlined = java.util.WeakHashMap<View, Boolean>()
    private val pendingGlassDraws = java.util.WeakHashMap<View, Boolean>()
    private val ringerRadii = java.util.WeakHashMap<View, Int>()
    private val bionicsDetailRows = java.util.WeakHashMap<View, Boolean>()
    private val detailTopMargins = java.util.WeakHashMap<View, Pair<Int, Int>>()
    private val detailRows = java.util.WeakHashMap<View, java.lang.ref.WeakReference<Any>>()
    private val detailGlassParams = CcDetailGlassParams()
    private val defaultGlassParams by lazy { bionicsParams(TOKEN_DEFAULT) }
    private val activatedGlassParams by lazy { bionicsParams(TOKEN_ACTIVATED) }
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
        detailGlassParams.install()
        PluginLoader.register(key, systemCl) { pluginCl ->
            CcGlassApi.init(pluginCl)
            ringerResources = Reflect.findClassIfExists(RINGER_RES_CLASS, pluginCl)
            hookSecondaryPanels(pluginCl)
            runCatching { hookMediaVolumeSlider(pluginCl) }
                .onFailure { HookHelper.log("$tag: hook media volume slider failed", it) }
            runCatching { hookNotificationMedia(pluginCl) }
                .onFailure { HookHelper.log("$tag: hook notification media failed", it) }
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
            "BrightnessPanelController" -> if (CcGlassKeys.isEnabled(CcGlassKeys.BRIGHTNESS)) glassBrightness(root)
            "MediaPanelController" -> if (CcGlassKeys.isEnabled(CcGlassKeys.MEDIA)) glassMedia(root)
            "VolumePanelController" -> if (CcGlassKeys.isEnabled(CcGlassKeys.CC_VOLUME)) glassCcVolume(controller, root)
            "DetailPanelController" -> if (CcGlassKeys.isEnabled(CcGlassKeys.NETWORK)) glassDetail(root)
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
                if (CcGlassKeys.isEnabled(CcGlassKeys.BRIGHTNESS) && param.args.getOrNull(3) == true) param.setArg(3, false)
            }
        }.onFailure { HookHelper.log("$tag: hook brightness tile ctor failed", it) }
    }

    private fun glassBrightness(root: View?) {
        // 大亮度条轨道；主题 / 配置切换后系统会重绘，稍后再补一次。
        applyBrightnessSlider(root)
        root?.postDelayed({ if (CcGlassKeys.isEnabled(CcGlassKeys.BRIGHTNESS)) applyBrightnessSlider(root) }, 300L)
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
                detailRows.remove(view)
                if (CcGlassKeys.isEnabled(CcGlassKeys.NETWORK)) applyDetailGlass(view, index, adapter) else applyDetailTopGap(view, 0)
            }
        }.onFailure { HookHelper.log("$tag: hook detail items failed", it) }
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
                    if (!CcGlassKeys.isEnabled(CcGlassKeys.NETWORK)) return@hookAfter
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
                    view.post { if (CcGlassKeys.isEnabled(CcGlassKeys.NETWORK)) runCatching { applyToggleGlass(view) } }
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
                applyBeforeGlassDraw(view, CcGlassKeys.NETWORK) {
                    detailRows[view]?.get()?.let { applyDetailMaterial(view, it) }
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
            val selected = view.isSelected
            val suffix = call(content, "getSuffix")
            val applied = if (!selected && (suffix == "Wifi" || suffix == "Bluetooth")) {
                detailGlassParams.applyToTarget(view) { CcGlassApi.applyDetailBionicsStyle(view, false) }
            } else {
                CcGlassApi.applyDetailBionicsStyle(view, selected)
            }
            if (applied) {
                bionicsDetailRows[view] = true
            } else {
                if (bionicsDetailRows.remove(view) == true) CcGlassApi.clearBionicsStyle(view)
                applyDetailBlend(view, content)
            }
        }.onFailure { HookHelper.log("$tag: detail glass failed", it) }
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
        if (root == null) return
        val defaults = defaultGlassParams
        traverse(root) { child ->
            if (idName(child) == "more_button") {
                if (defaults == null) glass(child)
                else detailGlassParams.capture(root, child, defaults) { glass(child) }
            }
        }
    }

    // ---------------- 播放器 ----------------

    private fun hookNotificationMedia(pluginCl: ClassLoader) {
        val content = Reflect.findClassIfExists(MEDIA_CONTENT_CLASS, pluginCl) ?: return
        mediaContentClass = content
        HookHelper.hookAfter(content.getDeclaredMethod("onAttachedToWindow")) { param ->
            val root = param.thisObject as? View ?: return@hookAfter
            if (mediaScene(root) == CcGlassKeys.NOTIFICATION_MEDIA && CcGlassKeys.isEnabled(CcGlassKeys.NOTIFICATION_MEDIA)) {
                glassMedia(root)
            }
        }
        HookHelper.hookAfter(content.getDeclaredMethod("onDetachedFromWindow")) { param ->
            val root = param.thisObject as? View ?: return@hookAfter
            mediaVolumeSliders.remove(root)?.get()?.let(detailGlassParams::clearVolumeMaterial)
            detailGlassParams.clear(root)
        }
    }

    private fun mediaScene(view: View): String =
        if (generateSequence(view) { it.parent as? View }.any {
            it.javaClass.name == NOTIFICATION_MEDIA_HOST_CLASS
        }) CcGlassKeys.NOTIFICATION_MEDIA else CcGlassKeys.MEDIA

    private fun mediaContent(view: View): View? {
        val contentClass = mediaContentClass ?: return null
        return generateSequence(view) { it.parent as? View }.firstOrNull(contentClass::isInstance)
    }

    private fun bionicsParams(token: String): FloatArray? =
        CcGlassApi.bionics(token)?.let { (call(it, "getToBionicsParams") as? FloatArray)?.copyOf() }

    private fun hookMediaVolumeSlider(pluginCl: ClassLoader) {
        val header = Reflect.findClassIfExists(MEDIA_HEADER_CLASS, pluginCl) ?: return
        val volumeBar = header.declaredFields.singleOrNull {
            it.name == "volumeBar" && SeekBar::class.java.isAssignableFrom(it.type)
        }?.apply { isAccessible = true } ?: return
        val applyMaterial = header.getDeclaredMethod("applyMaterialBackground")
        HookHelper.hookAfter(applyMaterial) { param ->
            if (!CcGlassKeys.isEnabled(CcGlassKeys.MEDIA) &&
                !CcGlassKeys.isEnabled(CcGlassKeys.NOTIFICATION_MEDIA)
            ) return@hookAfter
            val slider = volumeBar.get(param.thisObject) as? SeekBar ?: return@hookAfter
            // Resolve the host after attachment and wait for the track's own layout.
            scheduleMediaVolumeSlider(slider)
        }
        // The notification host can refresh native mix colors independently of the initial bind.
        Reflect.findMethodIfExists(header, "updateSeekBarBlur")?.let { refresh ->
            HookHelper.hookAfter(refresh) { param ->
                val slider = volumeBar.get(param.thisObject) as? SeekBar ?: return@hookAfter
                if (usesNotificationVolumeGlass(slider)) scheduleMediaVolumeSlider(slider)
            }
        }
        Reflect.findMethodIfExists(android.widget.ProgressBar::class.java, "setProgressDrawable", Drawable::class.java)
            ?.let { refresh ->
                HookHelper.intercept(refresh) { chain ->
                    val result = chain.proceed()
                    val slider = chain.thisObject as? SeekBar
                    if (slider != null && volumeBar.type.isInstance(slider) && usesNotificationVolumeGlass(slider)) {
                        scheduleMediaVolumeSlider(slider)
                    }
                    result
                }
            }
        val seekBarBase = volumeBar.type.superclass
        if (seekBarBase != null) {
            Reflect.findMethodIfExists(seekBarBase, "drawableStateChanged")?.let { stateChanged ->
                HookHelper.intercept(stateChanged) { chain ->
                    val result = chain.proceed()
                    val slider = chain.thisObject as? SeekBar
                    if (slider != null && volumeBar.type.isInstance(slider) &&
                        detailGlassParams.hasVolumeMaterial(slider) && usesNotificationVolumeGlass(slider)
                    ) {
                        // Miuix resets alpha on the whole LayerDrawable during drawable state updates.
                        val track = mediaVolumeTrack(slider)
                        if (track != null && track.alpha != 0) track.alpha = 0
                    }
                    result
                }
            }
        }
        Reflect.findMethodIfExists(volumeBar.type, "onTouchEvent", android.view.MotionEvent::class.java)?.let { touch ->
            HookHelper.hookAfter(touch) { param ->
                val slider = param.thisObject as? SeekBar ?: return@hookAfter
                val event = param.args.firstOrNull() as? android.view.MotionEvent ?: return@hookAfter
                if (event.actionMasked == android.view.MotionEvent.ACTION_MOVE) return@hookAfter
                val scene = mediaScene(slider)
                if (CcGlassKeys.isEnabled(scene)) scheduleMediaVolumeSlider(slider)
            }
        }
    }

    private fun usesNotificationVolumeGlass(slider: SeekBar): Boolean =
        slider.isAttachedToWindow && CcGlassKeys.isEnabled(CcGlassKeys.NOTIFICATION_MEDIA) &&
            mediaScene(slider) == CcGlassKeys.NOTIFICATION_MEDIA

    private fun scheduleMediaVolumeSlider(slider: SeekBar) {
        applyBeforeGlassDraw(
            slider,
            scene = { mediaScene(slider) },
            ready = {
                mediaVolumeTrack(slider)?.bounds?.isEmpty == false &&
                    notificationMediaSourceReady(slider)
            },
            applyOnLayout = false,
        ) {
            mediaContent(slider)?.let { panel ->
                mediaVolumeSliders[panel] = java.lang.ref.WeakReference(slider)
                if (mediaScene(slider) == CcGlassKeys.NOTIFICATION_MEDIA) {
                    val list = (call(panel, "getMRecycler") ?: findList(panel)) as? ViewGroup
                    val source = list?.let { mediaGlassSource(mediaDeviceCards(it)) }
                    if (source != null) captureMediaGlass(panel, source)
                }
            }
            applyMediaVolumeSlider(slider)
        }
    }

    private fun notificationMediaSourceReady(slider: SeekBar): Boolean {
        if (mediaScene(slider) != CcGlassKeys.NOTIFICATION_MEDIA) return true
        val panel = mediaContent(slider) ?: return true
        val list = (call(panel, "getMRecycler") ?: findList(panel)) as? ViewGroup ?: return true
        val cards = mediaDeviceCards(list)
        return cards.isEmpty() || mediaGlassSource(cards) != null
    }

    private fun mediaVolumeTrack(slider: SeekBar) =
        (slider.progressDrawable as? LayerDrawable)?.findDrawableByLayerId(android.R.id.background)

    private fun applyMediaVolumeSlider(slider: SeekBar) {
        val layers = slider.progressDrawable as? LayerDrawable ?: return
        val track = mediaVolumeTrack(slider) ?: return
        if (track.bounds.isEmpty) return
        if (!CcGlassApi.canApplyDetailBionicsStyle()) return
        val token = CcGlassApi.bionics(TOKEN_DEFAULT) ?: return
        val originalOutline = slider.outlineProvider
        slider.outlineProvider = MEDIA_VOLUME_TRACK_OUTLINE
        CcGlassApi.setSdfSize(slider, track.bounds.width(), track.bounds.height())
        if (!detailGlassParams.applyToTarget(slider, retainVolumeMaterial = usesNotificationVolumeGlass(slider)) {
                CcGlassApi.applyStyle(slider, token)
            }
        ) {
            slider.outlineProvider = originalOutline
            return
        }
        // The native SeekBar refreshes its colors after binding. Alpha is separate from
        // that color state, so only the inactive drawable stays hidden across refreshes.
        layers.mutate()
        layers.findDrawableByLayerId(android.R.id.background)?.alpha = 0
        slider.invalidateOutline()
    }

    private fun glassMedia(root: View?) {
        if (root == null) return
        val list = (call(root, "getMRecycler") ?: findList(root)) as? ViewGroup ?: return
        applyDeviceCards(list)
        if (observedMediaLists.put(list, true) == true) return
        val watcher = object : View.OnAttachStateChangeListener, ViewTreeObserver.OnGlobalLayoutListener {
            private var observer: ViewTreeObserver? = null

            override fun onViewAttachedToWindow(view: View) {
                observer = view.viewTreeObserver.also { it.addOnGlobalLayoutListener(this) }
            }

            override fun onViewDetachedFromWindow(view: View) {
                observer?.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
                observer = null
            }

            override fun onGlobalLayout() {
                runCatching { applyDeviceCards(list) }
                    .onFailure { HookHelper.log("$tag: media glass layout update failed", it) }
            }
        }
        list.addOnAttachStateChangeListener(watcher)
        if (list.isAttachedToWindow) watcher.onViewAttachedToWindow(list)
    }

    private fun applyDeviceCards(list: ViewGroup) {
        if (!CcGlassKeys.isEnabled(mediaScene(list))) return
        val cards = mediaDeviceCards(list)
        val panel = mediaContent(list)
        val source = mediaGlassSource(cards)
        if (panel != null && source != null) {
            val changed = captureMediaGlass(panel, source)
            if (changed) {
                mediaVolumeSliders[panel]?.get()?.takeIf { mediaContent(it) === panel }
                    ?.let(::scheduleMediaVolumeSlider)
            }
            cards.forEach { if (it !== source) applyDeviceCard(it) }
        } else {
            cards.forEach(::applyDeviceCard)
        }
    }

    private fun mediaDeviceCards(list: ViewGroup): List<View> =
        (0 until list.childCount).mapNotNull(list::getChildAt)
            .filter { it.isClickable && it !is android.widget.TextView }

    private fun mediaGlassSource(cards: List<View>): View? {
        val ready = cards.asSequence().filter { it.width > 0 && it.height > 0 }
        return ready.firstOrNull { !it.isSelected } ?: ready.firstOrNull()
    }

    private fun captureMediaGlass(panel: View, source: View): Boolean {
        val defaults = defaultGlassParams
        val sourceDefaults = if (source.isSelected) activatedGlassParams else defaults
        if (defaults == null || sourceDefaults == null) {
            applyDeviceCard(source)
            return false
        }
        return detailGlassParams.capture(
            panel, source, sourceDefaults, defaults,
            copyCustomizedValues = mediaScene(panel) == CcGlassKeys.NOTIFICATION_MEDIA,
        ) {
            applyDeviceCard(source)
        }
    }

    private fun applyDeviceCard(child: View) {
        // 尺寸为 0 时等下一帧布局完成后再套，避免按 0 尺寸算出错误参数（过饱和 / 不透明）。
        if (child.width == 0 || child.height == 0) {
            applyBeforeGlassDraw(child, scene = { mediaScene(child) }) {
                if (child.parent is ViewGroup && child.isClickable) applyDeviceCard(child)
            }
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
        if (!CcGlassKeys.isEnabled(CcGlassKeys.SIDE_VOLUME)) return
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
                val helper = param.thisObject ?: return@hookAfter
                val layout = runCatching { Reflect.getObjectField(helper, "this\$0") }.getOrNull() ?: return@hookAfter
                val side = runCatching { Reflect.getObjectField(layout, "mNeedShowDialog") as? Boolean }
                    .getOrNull() ?: return@hookAfter
                if (!CcGlassKeys.isEnabled(if (side) CcGlassKeys.SIDE_VOLUME else CcGlassKeys.CC_VOLUME)) return@hookAfter
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
                    if (!CcGlassKeys.isEnabled(CcGlassKeys.DEVICE_CENTER)) return@hookAfter
                    val container = deviceContainer(param.thisObject) ?: return@hookAfter
                    applyBeforeGlassDraw(container, CcGlassKeys.DEVICE_CENTER) {
                        styleDeviceItemGlass(container)
                    }
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

    /** Apply after layout and before drawing, when the target and its host are ready. */
    private fun applyBeforeGlassDraw(
        view: View,
        scene: String,
        apply: () -> Unit,
    ) = applyBeforeGlassDraw(view, { scene }, apply = apply)

    private fun applyBeforeGlassDraw(
        view: View,
        scene: () -> String,
        ready: () -> Boolean = { true },
        applyOnLayout: Boolean = true,
        apply: () -> Unit,
    ) {
        if (pendingGlassDraws.put(view, true) == true) return
        val listener = object : ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener, View.OnLayoutChangeListener {
            private var observer: ViewTreeObserver? = null

            override fun onViewAttachedToWindow(v: View) {
                observer = v.viewTreeObserver.also { it.addOnPreDrawListener(this) }
                if (applyOnLayout) applyIfReady()
            }

            override fun onViewDetachedFromWindow(v: View) = remove()

            override fun onLayoutChange(
                v: View,
                left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) = applyIfReady(fromLayout = true)

            private fun remove() {
                observer?.takeIf { it.isAlive }?.removeOnPreDrawListener(this)
                view.removeOnAttachStateChangeListener(this)
                if (applyOnLayout) view.removeOnLayoutChangeListener(this)
                pendingGlassDraws.remove(view)
            }

            private fun applyIfReady(fromLayout: Boolean = false) {
                if (!view.isAttachedToWindow || view.width <= 0 || view.height <= 0) return
                if (!fromLayout && (!view.isLaidOut || view.isLayoutRequested)) return
                val key = scene()
                if (!CcGlassKeys.isEnabled(key)) {
                    remove()
                    return
                }
                if (!ready()) return
                remove()
                runCatching(apply).onFailure { HookHelper.log("$tag: initialize $key glass failed", it) }
            }

            override fun onPreDraw(): Boolean {
                applyIfReady()
                return true
            }
        }
        if (applyOnLayout) view.addOnLayoutChangeListener(listener)
        view.addOnAttachStateChangeListener(listener)
        if (view.isAttachedToWindow) listener.onViewAttachedToWindow(view)
    }

    private fun styleDeviceItemGlass(container: View) {
        if (outlined[container] != true) {
            outlined[container] = true
            makeCircular(container)
        }
        if (container.background != null) container.background = null
        CcGlassApi.setSdfSize(container)
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
                if (!CcGlassKeys.isEnabled(CcGlassKeys.NETWORK)) return@hookAfter
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
        private const val MEDIA_CONTENT_CLASS = "com.android.systemui.QSControlMiPlayDetailContent"
        private const val MEDIA_HEADER_CLASS = "com.android.systemui.QSControlMiPlayDetailHeader"
        private const val NOTIFICATION_MEDIA_HOST_CLASS =
            "com.android.systemui.statusbar.notification.modal.ModalQSControlDetail"
        private val MEDIA_VOLUME_TRACK_OUTLINE = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val track = ((view as? SeekBar)?.progressDrawable as? LayerDrawable)
                    ?.findDrawableByLayerId(android.R.id.background) ?: return
                val bounds = track.bounds
                if (bounds.isEmpty) return
                outline.setRoundRect(
                    bounds.left + view.paddingLeft, bounds.top + view.paddingTop,
                    bounds.right + view.paddingLeft, bounds.bottom + view.paddingTop,
                    bounds.height() / 2f,
                )
            }
        }
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
        private const val DETAIL_ADAPTER_CLASS = "com.android.systemui.qs.QSDetailContent\$Adapter"
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
