package cn.ianzb.hyperrefine.hook.systemui.radius

import android.content.res.Resources
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.systemui.PluginLoader
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 控制中心「圆角调整」。
 *
 * 设计原则：**端点注入 + 保留官方动画 setter**，既不破坏官方圆角过渡动画，也支持各项自定义。
 *
 * 官方结构：每个组件的“静态圆角”都在其初始化 / 尺寸变化方法里读取 dimen 后设置：
 * - 一级磁贴：`QSCardItemView.updateCornerRadius()` / `QSTileItemIconView.updateSize()`
 * - 播放器：`MediaPlayerController$MediaPlayerViewHolder.updateRadius()`
 * - 一级亮度/音量滑块：`ToggleSliderViewHolder.updateSize()` / `updateResources()`
 * - 亮度二级：`BrightnessPanelSliderDelegate.updateSize()` / `updateResources()`
 * - 二级面板内容背景：`SecondaryPanelControllerBase.updateSize()`（按控制器类型分流）
 * - 设备中心入口：`DeviceCenterEntryViewHolder.onConfigurationChanged/onViewAttachedToWindow`
 * - 音量：`VolumeColumnRes.getRadius` 等读取的 `miui_volume_*` dimen（直接覆写 dimen）
 *
 * 打开二级菜单时，`*PanelAnimator` 会调用各组件的 `setXxxRadius(i)` 做过渡；这些 setter
 * **不 hook**，动画因此保持原生、平滑、无闪烁。
 */
class CcRadiusHook : BaseHook() {

    override val key: String = CcRadiusKeys.MASTER

    private val appliedLogged = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** blur SDK 圆角覆盖的递归保护。 */
    @Volatile
    private var inBlurCorner = false

    /** 当前 `VolumeColumn.setRadius` 期间的展开态：true=展开，false=收起，null=非音量列上下文。 */
    private val volumeColumnIsExpanded = ThreadLocal<Boolean?>()

    /** 当前音量列是否属于控制中心二级音量面板（`isControlCenterPanel`）：true=CC，false/未设置=侧边。 */
    private val volumeColumnIsCCPanel = ThreadLocal<Boolean?>()

    private val dimenItemCache = HashMap<Long, String?>()

    override fun init() {
        val systemCl = target.classLoader ?: return
        PluginLoader.register(key, systemCl) { pluginCl -> hookPlugin(pluginCl) }
        hookResources()
    }

    private fun hookPlugin(pluginCl: ClassLoader) {
        HookHelper.log("$tag: hooking plugin classes")

        // 横向磁贴（WLAN / 数据等大卡片）
        hookEndpointAfter(pluginCl, QS_CARD_ITEM_VIEW, listOf("updateCornerRadius"), { CcRadiusKeys.HORIZONTAL_TILE }, ::forceTileCard)
        // 小磁贴（下方 1x1 组件）
        hookEndpointAfter(pluginCl, QS_TILE_ICON_VIEW, listOf("updateSize", "updateCornerRadius"), { CcRadiusKeys.SMALL_TILE }, ::forceTileIcon)
        // 磁贴背景重建时补一次
        hookBackgroundSetters(pluginCl, QS_TILE_ICON_VIEW, CcRadiusKeys.SMALL_TILE)
        // 一级亮度 / 音量滑块
        hookEndpointAfter(pluginCl, TOGGLE_SLIDER_HOLDER, listOf("updateSize", "updateResources"), { CcRadiusKeys.SLIDER_L1 }, ::forceLevel1Slider)
        // 官方在构造函数里于 updateResources/updateSize 之后才设置各 outline provider，
        // 会覆盖上面的强制；构造结束后再套一次。
        hookLevel1SliderCtor(pluginCl)
        // 亮度二级
        hookEndpointAfter(pluginCl, BRIGHTNESS_SLIDER_DELEGATE, listOf("updateSize", "updateResources"), { CcRadiusKeys.BRIGHTNESS_L2 }, ::forceBrightnessL2)
        // 亮度二级动画每帧同步玻璃轮廓（否则玻璃 SDF 仍用原始半径，描边错位）
        hookBrightnessSliderGlass(pluginCl)
        // 二级面板内容背景
        hookEndpointAfter(pluginCl, SECONDARY_PANEL_BASE, listOf("updateSize", "updateContentBg"), { panelBgItem(it) }, ::forceContentBg)
        // 材质模式下背景是玻璃，官方 setContentBgRadius 不生效；把每次调用（含动画插值帧）的参数同步到玻璃轮廓
        hookContentBgRadiusGlass(pluginCl)
        // 融合设备中心入口
        hookEndpointAfter(pluginCl, DEVICE_CENTER_ENTRY_HOLDER, listOf("onConfigurationChanged", "onViewAttachedToWindow"), { CcRadiusKeys.DEVICE_CENTER }, ::forceDeviceCenterEntry)
        // 播放器：updateResources 会重设背景为原生半径，故连同 setCornerRadius/updateRadius 一起补
        hookEndpointAfter(
            pluginCl,
            MEDIA_PLAYER_HOLDER,
            listOf("updateRadius", "updateResources", "setCornerRadius", "updateBlendBlur"),
            { CcRadiusKeys.MEDIA },
            ::forceMedia,
        )
        // 无论谁设置 MediaPlayerPanel 圆角，都在其后把玻璃轮廓强制为自定义
        hookEndpointAfter(pluginCl, MEDIA_PLAYER_PANEL, listOf("setCornerRadius"), { CcRadiusKeys.MEDIA }, ::forceMediaPanel)
        // 音量二级：静音/勿扰按钮、定时组件保持各自自定义（setResultValue，非逐帧）
        hookRadiusProvider(pluginCl, RINGER_BUTTON_RES, "getButtonRadius", CcRadiusKeys.RINGER)
        hookRadiusProvider(pluginCl, TIMER_ITEM_RES, "getRadius", CcRadiusKeys.TIMER)
        // 音量列：getRadius 为可靠端点（setResultValue）→ 自定义；上下文区分 CC / 侧边
        hookVolumeColumnContext(pluginCl)
        hookVolumeColumnOwner(pluginCl)
        hookVolumeColumnRadius(pluginCl)
        // 音量二级模糊容器（blur SDK，非 dimen）
        hookVolumeBlurBackground(pluginCl)
        // blur SDK 的 setCornerRadius 覆盖：任何调用后都用配置值重设（带递归保护）
        hookBlurCorner(pluginCl)
        // 侧边音量展开「整体面板」的模糊圆角来源（与音量列共用 getRadius，需单独按背景值返回）
        hookRadiusProvider(pluginCl, VOLUME_DIALOG_MOTION, "getVolumeRadius", CcRadiusKeys.SIDE_VOLUME_L2_BG)
        // 侧边音量展开背景 mExpandBgView 的圆角来源（仅此处使用），同时驱动 outline/blur 与展开动画端点
        hookRadiusProvider(pluginCl, MIUI_VOLUME_DIALOG_RES, "getBgRadius", CcRadiusKeys.SIDE_VOLUME_L2_BG)
        // 侧边音量二级整体面板背景：展示/展开时强制圆角
        for (m in listOf("showVolumePanelH", "initPanelView", "updateExpandedH")) {
            hookMethodAfter(pluginCl, SIDE_VOLUME_CONTROLLER, m, { CcRadiusKeys.SIDE_VOLUME_L2_BG }) { obj, px ->
                val bg = Reflect.callMethod(obj, "getVolumeContentBg") as? View ?: return@hookMethodAfter
                Reflect.callMethod(bg, "setCornerRadius", px)
                applyGlassOutline(bg, px)
            }
        }

        // 动画端点：官方 `*PanelAnimator` 捕获 from/to 时读取这些 getter，令其返回自定义值，
        // 动画插值仍由官方 setter 完成（setter 不 hook）。
        // 只 hook outline（进度填充半径必须保持原生小值，否则 min() 会被取成大值把填充裁成圆）
        // 详情/收回动画端点读 getCornerRadius()，令其返回自定义，避免收回末尾回到 hook 前圆角
        hookGetterValue(pluginCl, QS_CARD_ITEM_VIEW, listOf("getCornerRadius")) { CcRadiusKeys.HORIZONTAL_TILE }
        hookGetterValue(pluginCl, QS_TILE_ICON_VIEW, listOf("getCornerRadius")) { CcRadiusKeys.SMALL_TILE }
        // DetailPanelAnimator 的 fromView 是 ViewHolder（DetailFromView），直接 hook 其 getCornerRadius
        hookGetterValue(pluginCl, QS_CARD_VIEW_HOLDER, listOf("getCornerRadius")) { CcRadiusKeys.HORIZONTAL_TILE }
        hookGetterValue(pluginCl, QS_ITEM_VIEW_HOLDER, listOf("getCornerRadius")) { CcRadiusKeys.SMALL_TILE }
        hookGetterValue(pluginCl, MEDIA_PLAYER_HOLDER, listOf("getCornerRadius")) { CcRadiusKeys.MEDIA }
        // 各二级动画器会复用缓存的 lastAnimValue（旧半径），计算前清空以强制用自定义 getter 重算
        for (animator in SECONDARY_PANEL_ANIMATORS) {
            hookClearFieldBefore(pluginCl, animator, "calculateViewValues", "lastAnimValue")
        }
        // 收回动画的假卡片在结束帧可能落到非自定义圆角；每帧回调后按卡片类型分别强制圆角
        Reflect.findClassIfExists(DETAIL_PANEL_ANIMATOR, pluginCl)?.let { cls ->
            cls.declaredMethods.firstOrNull { it.name == "frameCallback" }?.let { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        val obj = param.thisObject ?: return@hookAfter
                        val fake = Reflect.callMethod(obj, "getFakeView") as? View ?: return@hookAfter
                        val name = fake.javaClass.name
                        when {
                            name.contains("QSCardItemView") ->
                                pxFor(CcRadiusKeys.HORIZONTAL_TILE)?.let { forceTileCard(fake, it) }
                            name.contains("QSTileItemIconView") ->
                                pxFor(CcRadiusKeys.SMALL_TILE)?.let { forceTileIcon(fake, it) }
                        }
                    }
                }.onFailure { HookHelper.log("$tag: hook DetailPanelAnimator.frameCallback failed", it) }
            }
        }
        hookGetterValue(pluginCl, TOGGLE_SLIDER_HOLDER, listOf("getOutlineRadius")) { CcRadiusKeys.SLIDER_L1 }
        hookGetterValue(pluginCl, BRIGHTNESS_SLIDER_DELEGATE, listOf("getOutlineRadius")) { CcRadiusKeys.BRIGHTNESS_L2 }
        hookGetterValue(pluginCl, SECONDARY_PANEL_BASE, listOf("getContentBgRadius"), ::panelBgItem)
        // 音量二级：只 hook 容器圆角端点，进度填充半径保持原生（否则填充上角会变圆）
        hookGetterValue(pluginCl, VOLUME_PANEL_CONTROLLER, listOf("getCornerRadius")) { CcRadiusKeys.CC_VOLUME_L2_BG }
    }

    /** 在方法执行前把实例某字段清空（用于强制重算缓存）。 */
    private fun hookClearFieldBefore(cl: ClassLoader, className: String, methodName: String, field: String) {
        val cls = Reflect.findClassIfExists(className, cl) ?: run {
            HookHelper.log("$tag: $className not found")
            return
        }
        cls.declaredMethods
            .filter { it.name == methodName }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookBefore(method) { param ->
                        param.thisObject?.let { Reflect.setObjectField(it, field, null) }
                    }
                }.onFailure { HookHelper.log("$tag: hook $className.$methodName failed", it) }
            }
        HookHelper.log("$tag: hooked clear $className.$field before $methodName")
    }

    /** 动画端点 getter：返回配置值，使动画的 from/to 跟随自定义。 */
    private fun hookGetterValue(
        cl: ClassLoader,
        className: String,
        methodNames: List<String>,
        itemFor: (Any?) -> String,
    ) {
        val cls = Reflect.findClassIfExists(className, cl) ?: run {
            HookHelper.log("$tag: $className not found")
            return
        }
        var hooked = 0
        cls.declaredMethods
            .filter { it.name in methodNames && it.parameterCount == 0 && it.returnType == Float::class.javaPrimitiveType }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        val item = itemFor(param.thisObject)
                        pxFor(item)?.let {
                            param.setResultValue(it)
                            logApplied(item, it, "$className.${method.name}(getter)")
                        }
                    }
                    hooked++
                }.onFailure { HookHelper.log("$tag: hook $className.${method.name} failed", it) }
            }
        HookHelper.log("$tag: hooked getter $className $methodNames x$hooked")
    }

    /** 返回 int 圆角的静态工具方法（静音/勿扰按钮、定时组件），setResultValue 覆盖。 */
    private fun hookRadiusProvider(cl: ClassLoader, className: String, methodName: String, item: String) {
        val cls = Reflect.findClassIfExists(className, cl) ?: run {
            HookHelper.log("$tag: $className not found")
            return
        }
        var hooked = 0
        cls.declaredMethods
            .filter { it.name == methodName && it.returnType == Int::class.javaPrimitiveType }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        pxFor(item)?.let {
                            param.setResultValue(it.toInt())
                            logApplied(item, it, "$className.$methodName")
                        }
                    }
                    hooked++
                }.onFailure { HookHelper.log("$tag: hook $className.$methodName failed", it) }
            }
        HookHelper.log("$tag: hooked $className.$methodName x$hooked")
    }

    /**
     * 音量列端点：`VolumeColumnRes.getRadius(context, needDialog, expanded)`。
     *
     * 官方第三个参数并非可靠的展开态（侧边传 `mIsNotifySingle`），故改为在 `VolumeColumn.setRadius`
     * 上下文中读取该列的**真实** `isExpanded()`：收起 → [CcRadiusKeys.SIDE_VOLUME_L1]，展开 → [CcRadiusKeys.SIDE_VOLUME_L2]。
     * 仅在音量列上下文内覆盖，避免影响 `MiuiVolumeDialogMotion` / `VolumePanelAnimator` 的其它调用。
     */
    private fun hookVolumeColumnRadius(cl: ClassLoader) {
        val cls = Reflect.findClassIfExists(VOLUME_COLUMN_RES, cl) ?: return
        var hooked = 0
        cls.declaredMethods
            .filter { it.name == "getRadius" && it.returnType == Int::class.javaPrimitiveType && it.parameterCount == 3 }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        // 控制中心二级音量面板：单独设置（含面板展开动画）。
                        if (volumeColumnIsCCPanel.get() == true) {
                            pxFor(CcRadiusKeys.CC_VOLUME_L2)?.let {
                                param.setResultValue(it.toInt())
                                logApplied(CcRadiusKeys.CC_VOLUME_L2, it, "VolumeColumnRes.getRadius(cc)")
                            }
                            return@hookAfter
                        }
                        // 侧边音量：按该列真实展开态区分一级 / 二级。
                        val expanded = volumeColumnIsExpanded.get() ?: return@hookAfter
                        val item = if (expanded) CcRadiusKeys.SIDE_VOLUME_L2 else CcRadiusKeys.SIDE_VOLUME_L1
                        pxFor(item)?.let {
                            param.setResultValue(it.toInt())
                            logApplied(item, it, "VolumeColumnRes.getRadius")
                        }
                    }
                    hooked++
                }.onFailure { HookHelper.log("$tag: hook VolumeColumnRes.getRadius failed", it) }
            }
        HookHelper.log("$tag: hooked VolumeColumnRes.getRadius x$hooked")
    }

    /** 标记 `VolumeColumn.setRadius` 期间该列的展开态。 */
    private fun hookVolumeColumnContext(cl: ClassLoader) {
        val cls = Reflect.findClassIfExists(VOLUME_COLUMN_CLASS, cl) ?: return
        cls.declaredMethods
            .filter {
                it.name == "setRadius" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Boolean::class.javaPrimitiveType
            }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookBefore(method) { param ->
                        val expanded = Reflect.callMethod(param.thisObject ?: return@hookBefore, "isExpanded") as? Boolean
                        volumeColumnIsExpanded.set(expanded ?: false)
                    }
                    HookHelper.hookAfter(method) { volumeColumnIsExpanded.remove() }
                }.onFailure { HookHelper.log("$tag: hook VolumeColumn.setRadius context failed", it) }
            }
        HookHelper.log("$tag: hooked VolumeColumn.setRadius context")
    }

    /**
     * 标记音量列归属：`VolumePanelViewController` 为控制中心二级音量面板（`isControlCenterPanel`）时，
     * 其音量列路由到 [CcRadiusKeys.CC_VOLUME_L2]；否则为侧边音量。覆盖静态布局（`updateColumnH` /
     * `updateTempColumnH`）与展开动画（`VolumePanelAnimator.frameCallback`）。
     */
    private fun hookVolumeColumnOwner(cl: ClassLoader) {
        Reflect.findClassIfExists(SIDE_VOLUME_CONTROLLER, cl)?.let { cls ->
            for (name in listOf("updateColumnH", "updateTempColumnH")) {
                cls.declaredMethods.filter { it.name == name }.forEach { method ->
                    method.isAccessible = true
                    runCatching {
                        HookHelper.hookBefore(method) { param ->
                            val cc = Reflect.getObjectField(
                                param.thisObject ?: return@hookBefore,
                                "isControlCenterPanel",
                            ) as? Boolean
                            volumeColumnIsCCPanel.set(cc ?: false)
                        }
                        HookHelper.hookAfter(method) { volumeColumnIsCCPanel.remove() }
                    }.onFailure { HookHelper.log("$tag: hook $name owner failed", it) }
                }
            }
        }
        Reflect.findClassIfExists(VOLUME_PANEL_ANIMATOR, cl)?.let { cls ->
            cls.declaredMethods.firstOrNull { it.name == "frameCallback" }?.let { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookBefore(method) { volumeColumnIsCCPanel.set(true) }
                    HookHelper.hookAfter(method) { volumeColumnIsCCPanel.remove() }
                }.onFailure { HookHelper.log("$tag: hook VolumePanelAnimator.frameCallback owner failed", it) }
            }
        }
        HookHelper.log("$tag: hooked volume column owner")
    }

    // ---------------- 端点注入 ----------------

    private fun hookEndpointAfter(
        cl: ClassLoader,
        className: String,
        methodNames: List<String>,
        itemFor: (Any?) -> String,
        force: (Any, Float) -> Unit,
    ) {
        val cls = Reflect.findClassIfExists(className, cl) ?: run {
            HookHelper.log("$tag: $className not found")
            return
        }
        var hooked = 0
        cls.declaredMethods
            .filter { it.name in methodNames && it.parameterCount <= 1 }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        val obj = param.thisObject ?: return@hookAfter
                        val item = itemFor(obj)
                        val px = pxFor(item) ?: return@hookAfter
                        logApplied(item, px, "$className.${method.name}")
                        runCatching { force(obj, px) }
                    }
                    hooked++
                }.onFailure { HookHelper.log("$tag: hook $className.${method.name} failed", it) }
            }
        HookHelper.log("$tag: hooked $className $methodNames x$hooked")
    }

    /** 任意方法之后强制圆角（仅按方法名取第一个同名方法）。 */
    private fun hookMethodAfter(
        cl: ClassLoader,
        className: String,
        methodName: String,
        itemFor: (Any?) -> String,
        force: (Any, Float) -> Unit,
    ) {
        val cls = Reflect.findClassIfExists(className, cl) ?: run {
            HookHelper.log("$tag: $className not found")
            return
        }
        val method = cls.declaredMethods.firstOrNull { it.name == methodName } ?: run {
            HookHelper.log("$tag: $className.$methodName not found")
            return
        }
        method.isAccessible = true
        runCatching {
            HookHelper.hookAfter(method) { param ->
                val obj = param.thisObject ?: return@hookAfter
                val px = pxFor(itemFor(obj)) ?: return@hookAfter
                force(obj, px)
            }
        }.onFailure { HookHelper.log("$tag: hook $className.$methodName failed", it) }
    }

    private fun hookBackgroundSetters(cl: ClassLoader, className: String, item: String) {
        val cls = Reflect.findClassIfExists(className, cl) ?: return
        cls.declaredMethods
            .filter {
                (it.name == "setEnabledBg" || it.name == "setDisabledBg") &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Drawable::class.java
            }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        val d = param.args.getOrNull(0) as? Drawable ?: return@hookAfter
                        pxFor(item)?.let { setGradientCornerRadius(d, it) }
                    }
                }.onFailure { HookHelper.log("$tag: hook ${method.name} failed", it) }
            }
    }

    // ---------------- 各组件强制 ----------------

    private fun forceTileCard(obj: Any, px: Float) {
        forceDrawableField(obj, "enabledBg", px)
        forceDrawableField(obj, "disabledBg", px)
        forceFloatField(obj, "_cornerRadius", px)
        (obj as? View)?.invalidateOutline()
    }

    private fun forceTileIcon(obj: Any, px: Float) {
        forceDrawableField(obj, "enabledBg", px)
        forceDrawableField(obj, "disabledBg", px)
        // 详情动画端点读 getCornerRadius() -> _cornerRadius，必须一起改
        forceFloatField(obj, "_cornerRadius", px)
    }

    private fun forceMedia(obj: Any, px: Float) {
        val binding = Reflect.getObjectField(obj, "binding") ?: return
        val root = Reflect.getObjectField(binding, "root") as? View ?: return
        val bg = runCatching { root.background?.mutate() }.getOrNull() ?: root.background
        setGradientCornerRadius(bg, px)
        // MediaPlayerPanel.setCornerRadius 会 early-return（值相等时），先复位字段强制重套玻璃轮廓。
        forceFloatField(root, "cornerRadius", -1f)
        Reflect.callMethod(root, "setCornerRadius", px)
        if (appliedLogged.add("media_dbg")) {
            val r = (root.background as? android.graphics.drawable.GradientDrawable)?.cornerRadius
            HookHelper.log("$tag: forceMedia px=$px root=${root.javaClass.simpleName} bg=${root.background?.javaClass?.name} bgRadius=$r clip=${root.clipToOutline}")
        }
    }

    private fun forceLevel1Slider(obj: Any, px: Float) {
        val binding = Reflect.getObjectField(obj, "binding") ?: return
        // 轨道背景：让官方 `toggle_slider_inner` 的遮罩半径跟随自定义值。
        for (field in listOf("progressBg", "bionicsProgressBg")) {
            val v = Reflect.getObjectField(binding, field) as? View ?: continue
            setGradientCornerRadius(v.background, px)
            v.invalidateOutline()
        }
        // 遮罩：`toggle_slider_inner`（clipToOutline）以轨道圆角裁剪填充。显式固定其轮廓半径，
        // 不再修改白色条 `progress` 自身的填充轮廓（其官方轮廓负责按进度切顶部，遮罩负责外圈圆角）。
        val inner = Reflect.getObjectField(binding, "toggleSliderInner") as? View
        if (inner != null) {
            inner.clipToOutline = true
            inner.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, px)
                }
            }
            inner.invalidateOutline()
        }
    }

    /**
     * 官方 `ToggleSliderViewHolder` 构造函数在 `updateResources/updateSize`（触发我方强制）**之后**才
     * 设置 `progress` / `toggleSliderInner` 的 outline provider（官方 147/158 行），会覆盖我方自定义轮廓，
     * 导致首次创建时音量条白色部分按原生半径裁剪。这里在构造结束后重套一次。
     */
    private fun hookLevel1SliderCtor(cl: ClassLoader) {
        val cls = Reflect.findClassIfExists(TOGGLE_SLIDER_HOLDER, cl) ?: return
        var hooked = 0
        cls.declaredConstructors.forEach { ctor ->
            ctor.isAccessible = true
            runCatching {
                HookHelper.hookAfter(ctor) { param ->
                    val obj = param.thisObject ?: return@hookAfter
                    val px = pxFor(CcRadiusKeys.SLIDER_L1) ?: return@hookAfter
                    forceLevel1Slider(obj, px)
                }
                hooked++
            }.onFailure { HookHelper.log("$tag: hook ToggleSliderViewHolder ctor failed", it) }
        }
        HookHelper.log("$tag: hooked ToggleSliderViewHolder ctor x$hooked")
    }

    private fun forceBrightnessL2(obj: Any, px: Float) {
        forceFloatField(obj, "outlineRadius", px)
        // 亮度条本体及其过渡玻璃层：显式设置玻璃轮廓，否则 SDF 会沿用背景 drawable 的「原始半径」导致描边错位
        for (getter in listOf("getVProgressBg", "getBionicsProgressBg")) {
            val v = Reflect.callMethod(obj, getter) as? View ?: continue
            setGradientCornerRadius(v.background, px)
            applyGlassOutline(v, px)
            v.invalidateOutline()
        }
        (Reflect.callMethod(obj, "getVToggleSliderInner") as? View)?.invalidateOutline()
    }

    /**
     * 亮度二级动画每帧会调用 `setOutlineRadius`，此时把玻璃轮廓同步为自定义值，
     * 避免动画过程中玻璃 SDF 仍以原始半径渲染（描边错位）。
     */
    private fun hookBrightnessSliderGlass(cl: ClassLoader) {
        val cls = Reflect.findClassIfExists(BRIGHTNESS_SLIDER_DELEGATE, cl) ?: return
        var hooked = 0
        cls.declaredMethods
            .filter {
                it.name == "setOutlineRadius" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Float::class.javaPrimitiveType
            }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        val obj = param.thisObject ?: return@hookAfter
                        val px = pxFor(CcRadiusKeys.BRIGHTNESS_L2) ?: return@hookAfter
                        for (getter in listOf("getVProgressBg", "getBionicsProgressBg")) {
                            val v = Reflect.callMethod(obj, getter) as? View ?: continue
                            setGradientCornerRadius(v.background, px)
                            applyGlassOutline(v, px)
                            v.invalidateOutline()
                        }
                    }
                    hooked++
                }.onFailure { HookHelper.log("$tag: hook setOutlineRadius glass failed", it) }
            }
        HookHelper.log("$tag: hooked BrightnessPanelSliderDelegate.setOutlineRadius(glass) x$hooked")
    }

    /** `MediaPlayerPanel.setCornerRadius` 之后：直接设玻璃轮廓（避免材质覆盖），不递归调用自身。 */
    private fun forceMediaPanel(obj: Any, px: Float) {
        forceFloatField(obj, "cornerRadius", px)
        runCatching {
            val cls = Reflect.findClassIfExists(MI_BLUR_COMPAT, obj.javaClass.classLoader) ?: return@runCatching
            val m = cls.declaredMethods.firstOrNull {
                it.name == "setBlurOutlineRoundRect" && it.parameterCount >= 2
            } ?: return@runCatching
            m.isAccessible = true
            if (m.parameterCount == 2) m.invoke(null, obj, px) else m.invoke(null, obj, px, true)
        }.onFailure { HookHelper.log("$tag: MediaPlayerPanel glass outline failed", it) }
    }

    private fun forceContentBg(obj: Any, px: Float) {
        forceFloatField(obj, "contentBgRadius", px)
        val contentBg = Reflect.callMethod(obj, "getContentBg") as? View ?: return
        setGradientCornerRadius(contentBg.background, px)
        // 材质模式下背景为玻璃，需直接设置玻璃轮廓圆角
        applyGlassOutline(contentBg, px)
    }

    /**
     * 材质模式下二级面板背景是玻璃：官方 `setContentBgRadius` 只处理 `GradientDrawable`（无效），
     * 这里把**方法实际参数**（含动画插值的每一帧）同步到玻璃轮廓，保证过渡动画跟随。
     */
    private fun hookContentBgRadiusGlass(cl: ClassLoader) {
        val cls = Reflect.findClassIfExists(SECONDARY_PANEL_BASE, cl) ?: return
        var hooked = 0
        cls.declaredMethods
            .filter {
                it.name == "setContentBgRadius" &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Float::class.javaPrimitiveType
            }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        val radius = param.args.getOrNull(0) as? Float ?: return@hookAfter
                        val contentBg = Reflect.callMethod(param.thisObject ?: return@hookAfter, "getContentBg") as? View
                            ?: return@hookAfter
                        applyGlassOutline(contentBg, radius)
                    }
                    hooked++
                }.onFailure { HookHelper.log("$tag: hook setContentBgRadius failed", it) }
            }
        HookHelper.log("$tag: hooked setContentBgRadius(glass) x$hooked")
    }

    private fun applyGlassOutline(view: View, px: Float) {
        runCatching {
            val cls = Reflect.findClassIfExists(MI_BLUR_COMPAT, view.javaClass.classLoader) ?: return@runCatching
            val m = cls.declaredMethods.firstOrNull {
                it.name == "setBlurOutlineRoundRect" && it.parameterCount == 2
            } ?: return@runCatching
            m.isAccessible = true
            m.invoke(null, view, px)
        }.onFailure { HookHelper.log("$tag: setBlurOutlineRoundRect failed", it) }
    }

    private fun forceDeviceCenterEntry(obj: Any, px: Float) {
        val itemView = Reflect.getObjectField(obj, "itemView") as? View ?: return
        setGradientCornerRadius(itemView.background, px)
        itemView.invalidateOutline()
    }

    /** blur SDK（`com.miui.blur.sdk.backdrop.a`）圆角：调用后以配置值重设（递归保护）。 */
    private fun hookBlurCorner(cl: ClassLoader) {
        val cls = Reflect.findClassIfExists(BLUR_BASE, cl) ?: run {
            HookHelper.log("$tag: $BLUR_BASE not found")
            return
        }
        var hooked = 0
        cls.declaredMethods.filter { it.name == "setCornerRadius" }.forEach { method ->
            method.isAccessible = true
            runCatching {
                HookHelper.hookAfter(method) { param ->
                    if (inBlurCorner) return@hookAfter
                    val obj = param.thisObject ?: return@hookAfter
                    val n = obj.javaClass.name
                    if (!n.endsWith("ExpandBlurFrameLayout") && !n.endsWith("VolumeBlurFrameLayout")) {
                        return@hookAfter
                    }
                    val px = pxFor(CcRadiusKeys.SIDE_VOLUME_L2_BG) ?: return@hookAfter
                    inBlurCorner = true
                    try {
                        Reflect.callMethod(obj, "setCornerRadius", px)
                    } finally {
                        inBlurCorner = false
                    }
                    logApplied(CcRadiusKeys.SIDE_VOLUME_L2_BG, px, "blurSDK.setCornerRadius")
                }
                hooked++
            }.onFailure { HookHelper.log("$tag: hook blurSDK.setCornerRadius failed", it) }
        }
        HookHelper.log("$tag: hooked blurSDK.setCornerRadius x$hooked")
    }

    private fun hookVolumeBlurBackground(cl: ClassLoader) {
        for (className in VOLUME_BLUR_VIEWS) {
            val cls = Reflect.findClassIfExists(className, cl) ?: continue
            cls.declaredConstructors.forEach { ctor ->
                ctor.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(ctor) { param ->
                        val px = pxFor(CcRadiusKeys.SIDE_VOLUME_L2_BG) ?: return@hookAfter
                        val view = param.thisObject ?: return@hookAfter
                        logApplied(CcRadiusKeys.SIDE_VOLUME_L2_BG, px, "${className.substringAfterLast('.')}.ctor")
                        Reflect.callMethod(view, "setCornerRadius", px)
                    }
                }.onFailure { HookHelper.log("$tag: hook $className ctor failed", it) }
            }
        }
    }

    // ---------------- dimen 覆写（音量等无独立端点者） ----------------

    private fun hookResources() {
        Resources::class.java.declaredMethods
            .filter {
                (it.name == "getDimension" || it.name == "getDimensionPixelSize") &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == Int::class.javaPrimitiveType
            }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookBefore(method) { param ->
                        val res = param.thisObject as? Resources ?: return@hookBefore
                        val id = param.args.getOrNull(0) as? Int ?: return@hookBefore
                        val item = mappedItem(res, id) ?: return@hookBefore
                        val px = pxFor(item) ?: return@hookBefore
                        logApplied(item, px, "Resources.${method.name}")
                        param.setResultValue(
                            if (method.returnType == Int::class.javaPrimitiveType) px.toInt() else px
                        )
                    }
                }.onFailure { HookHelper.log("$tag: hook Resources.${method.name} failed", it) }
            }
        HookHelper.log("$tag: resources dimen override installed")
    }

    private fun mappedItem(res: Resources, id: Int): String? {
        val key = (System.identityHashCode(res).toLong() shl 32) or (id.toLong() and 0xFFFFFFFFL)
        synchronized(dimenItemCache) {
            if (dimenItemCache.containsKey(key)) return dimenItemCache[key]
        }
        val name = runCatching { res.getResourceEntryName(id) }.getOrNull()
        val item = when (name) {
            // 共用 dimen：一级磁贴 / 播放器 / 一级滑块 / 设备中心入口的父级裁剪与轨道
            "control_center_universal_corner_radius" -> SHARED
            // 音量列半径由 VolumeColumn 上下文 hook 分流（CC / 侧边），不在此覆写 dimen
            "miui_volume_timer_corner_radius" -> CcRadiusKeys.TIMER
            "o3_miui_ringer_btn_radius" -> CcRadiusKeys.RINGER
            "o3_miui_ringer_btn_radius_cc" -> CcRadiusKeys.RINGER
            "o3_miui_ringer_btn_radius_expended" -> CcRadiusKeys.RINGER
            else -> null
        }
        synchronized(dimenItemCache) { dimenItemCache[key] = item }
        return item
    }

    // ---------------- 配置 / 工具 ----------------

    private fun pxFor(item: String): Float? {
        val custom = HookPrefs.getBoolean(
            CcRadiusKeys.customKey(item),
            CcRadiusKeys.itemCustomDefault(item),
        )
        // 单项自定义优先：即使总开关关闭也生效。
        if (custom) {
            val dp = HookPrefs.getFloat(CcRadiusKeys.valueKey(item), CcRadiusKeys.itemValueDefault(item))
            return dp * Resources.getSystem().displayMetrics.density
        }
        // 未自定义：仅在总开关开启时套用统一值，否则保持系统默认（不被模块修改）。
        if (!HookPrefs.getBoolean(CcRadiusKeys.MASTER, false)) return null
        val background = CcRadiusKeys.isBackground(item)
        val unifiedKey = if (background) CcRadiusKeys.BACKGROUND else CcRadiusKeys.COMPONENT
        val unifiedDefault = if (background) CcRadiusKeys.DEFAULT_BACKGROUND else CcRadiusKeys.DEFAULT_COMPONENT
        return HookPrefs.getFloat(unifiedKey, unifiedDefault) * Resources.getSystem().displayMetrics.density
    }

    private fun panelBgItem(controller: Any?): String {
        val name = controller?.javaClass?.simpleName ?: return CcRadiusKeys.DETAIL_BG
        return when {
            name.contains("Brightness") -> CcRadiusKeys.BRIGHTNESS_L2_BG
            name.contains("Media") -> CcRadiusKeys.MEDIA_L2_BG
            name.contains("Volume") -> CcRadiusKeys.CC_VOLUME_L2_BG
            else -> CcRadiusKeys.DETAIL_BG
        }
    }

    private fun logApplied(item: String, px: Float, where: String) {
        if (appliedLogged.add("${item}_$where")) {
            HookHelper.log("$tag: apply $item = $px px ($where)")
        }
    }

    private fun forceDrawableField(obj: Any, field: String, px: Float) {
        (Reflect.getObjectField(obj, field) as? Drawable)?.let { setGradientCornerRadius(it, px) }
    }

    private fun forceFloatField(obj: Any, field: String, px: Float) {
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            val f = runCatching { cls.getDeclaredField(field) }.getOrNull()
            if (f != null) {
                f.isAccessible = true
                f.setFloat(obj, px)
                return
            }
            cls = cls.superclass
        }
    }

    private fun setGradientCornerRadius(drawable: Drawable?, px: Float) {
        when (drawable) {
            is android.graphics.drawable.GradientDrawable -> {
                drawable.setCornerRadius(px)
                drawable.invalidateSelf()
            }
            is android.graphics.drawable.LayerDrawable -> {
                for (i in 0 until drawable.numberOfLayers) setGradientCornerRadius(drawable.getDrawable(i), px)
            }
            else -> Unit
        }
    }

    companion object {
        /** 共用 dimen（control_center_universal_corner_radius）哨兵：解析为统一圆角。 */
        private const val SHARED = "__shared__"

        private const val QS_CARD_ITEM_VIEW =
            "miui.systemui.controlcenter.qs.tileview.QSCardItemView"
        private const val QS_TILE_ICON_VIEW =
            "miui.systemui.controlcenter.qs.tileview.QSTileItemIconView"
        private const val QS_CARD_VIEW_HOLDER =
            "miui.systemui.controlcenter.panel.main.qs.QSCardViewHolder"
        private const val QS_ITEM_VIEW_HOLDER =
            "miui.systemui.controlcenter.panel.main.qs.QSItemViewHolder"
        private const val MEDIA_PLAYER_HOLDER =
            "miui.systemui.controlcenter.panel.main.media.MediaPlayerController\$MediaPlayerViewHolder"
        private const val MEDIA_PLAYER_PANEL =
            "miui.systemui.controlcenter.panel.main.media.MediaPlayerPanel"
        private const val DETAIL_PANEL_ANIMATOR =
            "miui.systemui.controlcenter.panel.secondary.detail.DetailPanelAnimator"

        private val SECONDARY_PANEL_ANIMATORS = listOf(
            DETAIL_PANEL_ANIMATOR,
            "miui.systemui.controlcenter.panel.secondary.media.MediaPanelAnimator",
            "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelAnimator",
            "miui.systemui.controlcenter.panel.secondary.volume.VolumePanelAnimator",
        )
        private const val TOGGLE_SLIDER_HOLDER =
            "miui.systemui.controlcenter.panel.main.recyclerview.ToggleSliderViewHolder"
        private const val BRIGHTNESS_SLIDER_DELEGATE =
            "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate"
        private const val SECONDARY_PANEL_BASE =
            "miui.systemui.controlcenter.panel.secondary.SecondaryPanelControllerBase"
        private const val DEVICE_CENTER_ENTRY_HOLDER =
            "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryViewHolder"
        private const val VOLUME_PANEL_CONTROLLER =
            "miui.systemui.controlcenter.panel.secondary.volume.VolumePanelController"
        private const val VOLUME_PANEL_ANIMATOR =
            "miui.systemui.controlcenter.panel.secondary.volume.VolumePanelAnimator"
        private const val RINGER_BUTTON_RES =
            "com.android.systemui.miui.volume.RingerButtonRes"
        private const val TIMER_ITEM_RES =
            "com.android.systemui.miui.volume.TimerItemRes"
        private const val VOLUME_COLUMN_RES =
            "com.android.systemui.miui.volume.VolumeColumnRes"
        private const val VOLUME_COLUMN_CLASS =
            "com.android.systemui.miui.volume.VolumeColumn"
        private const val SIDE_VOLUME_CONTROLLER =
            "com.android.systemui.miui.volume.VolumePanelViewController"
        private const val BLUR_BASE = "com.miui.blur.sdk.backdrop.a"
        private const val MIUI_VOLUME_DIALOG_RES =
            "com.android.systemui.miui.volume.MiuiVolumeDialogRes"
        private const val VOLUME_DIALOG_MOTION =
            "com.android.systemui.miui.volume.MiuiVolumeDialogMotion"

        private const val MI_BLUR_COMPAT = "miui.systemui.util.MiBlurCompat"

        private val VOLUME_BLUR_VIEWS = listOf(
            "com.android.systemui.miui.volume.widget.ExpandBlurFrameLayout",
            "com.android.systemui.miui.volume.widget.VolumeBlurFrameLayout",
        )
    }
}
