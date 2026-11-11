package cn.ianzb.hyperrefine.hook.systemui

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.ImageView
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.misound.AppVolumeKeys
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.status.HookStatusReporter
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 侧边音量条「多应用音量」入口。
 *
 * 直接复用官方静音 / 勿扰按钮的同款实现：inflate 官方 `miui_ringer_mode_layout` 布局，
 * 并用官方 `MiuiRingerModeLayout.RingerButtonHelper` 管理其背景 / 模糊 / 图标 / 展开尺寸，
 * 再把它接进 `VolumeShowHideAnimator` 的 `mRingerBtnLayouts`，使其拥有与原生按钮完全一致
 * 的外观与「自上而下递增延迟」的出现动画。
 *
 * 点击后拉起 MiSound 的 `VolumeUIService` 打开原生多应用音量面板。
 */
class AppVolumeEntryHook : BaseHook() {

    override val key: String = AppVolumeKeys.ENTRY

    /** 每个 ringer 父容器对应的入口视图。 */
    private val entries = WeakHashMap<ViewGroup, View>()
    /** controller → 铃声按钮布局；只在仍 attach 时复用，避免持有已销毁的面板。 */
    private val ringerCache = WeakHashMap<Any, WeakReference<View>>()
    /** parent → 上次写入的官方按钮可见性签名，避免每次音量键都重复 setVisibility。 */
    private val officialVisibilityState = WeakHashMap<ViewGroup, Long>()

    /** 滑条 → 入口；边界拖动时每帧镜像动画，不能每帧递归遍历整棵对话框。 */
    private val mirrorEntryCache = WeakHashMap<View, WeakReference<View>>()
    /** 入口 → 自身 bg_blur；同上，避免每帧 findViewById 递归。 */
    private val mirrorBlurCache = WeakHashMap<View, WeakReference<View>>()

    /** 每个官方 `MiuiRingerModeLayout` 对应的入口 RingerButtonHelper。 */
    private val helpers = WeakHashMap<Any, Any>()

    /** 入口最近一次的显示状态，避免重复播放透明度动画。 */
    private val lastVisible = WeakHashMap<View, Boolean>()

    /** 整体居中平移：面板最近一次的上移目标，避免重复播放动画。 */
    private val lastShift = WeakHashMap<View, Float>()

    /** 官方按钮根是否处于“只隐藏按钮、保留计时器/二级内容”的状态。 */
    private val hiddenButtonRoots = WeakHashMap<View, Boolean>()

    /** 本轮侧边音量条显示期间，用户是否手动操作过入口（手动操作后不再自动展开面板）。 */
    private val userToggled = WeakHashMap<View, Boolean>()

    /** 入口期望的显隐状态（延迟显隐时用于重放）。 */
    private val desiredVisible = WeakHashMap<View, Boolean>()

    /** 延迟显隐的等待中标记，避免重复排队。 */
    private val pendingShow = WeakHashMap<View, Boolean>()

    /** 「自动显示」重试中由本流程刷新入口状态，避免入口变化再次触发自动展开（递归）。 */
    private var autoRefreshing = false

    // 插件资源 / 类（loadPlugin 时解析）
    private var idDnd = 0
    private var idRinger = 0
    private var idBlur = 0
    private var idIcon = 0
    private var layoutRingerButton = 0
    private var outerClass: Class<*>? = null
    private var helperClass: Class<*>? = null

    /** 控制中心插件 ClassLoader（用于系统界面模式反射官方 VolumeColumn）。 */
    private var pluginClassLoader: ClassLoader? = null

    /** 最近的音量面板控制器，供注入后立即刷新显隐。 */
    @Volatile
    private var lastController: Any? = null

    /** `updateExpandedH` 的参数提示；展开动画首帧 `mExpanded` 可能尚未写入。 */
    @Volatile
    private var expandedHint: Boolean? = null

    override fun init() {
        // 本 hook 同时实现「入口常显」，据实上报其生效状态。
        HookStatusReporter.markInstalled(AppVolumeKeys.ENTRY_VISIBILITY)
        HookPrefs.registerListener {
            entries.keys.toList().forEach { parent ->
                parent.post {
                    officialVisibilityState.remove(parent)
                    applyOfficialVisibility(parent, findDndRoot(parent))
                    lastController?.let { controller ->
                        if (controllerOwnsView(controller, parent)) {
                            entryOf(parent)?.let { updateVisibility(controller, it) }
                        }
                    }
                }
            }
        }
        val systemCl = target.classLoader ?: return
        PluginLoader.register(key, systemCl) { pluginCl ->
            loadPlugin(pluginCl)
            hookAnimator(pluginCl)
            hookDialogControlCenter(pluginCl)
            hookRingerLayout(pluginCl)
            hookController(pluginCl)
            hookControllerControlCenter(pluginCl)
            hookSeekBarAnim(pluginCl)
            hookRingerButtonDelay(pluginCl)
        }
    }

    // ---------------- 插件资源 / 类 ----------------

    private fun loadPlugin(pluginCl: ClassLoader) {
        pluginClassLoader = pluginCl
        outerClass = Reflect.findClassIfExists(OUTER_CLASS, pluginCl)
        helperClass = Reflect.findClassIfExists(HELPER_CLASS, pluginCl)
    }

    /** 插件资源 id 通过运行时资源解析（不同 ROM 版本数值会变，不能写死）。 */
    private fun resolveResources(context: Context) {
        if (idDnd != 0) return
        idDnd = resolveId(context, "dnd_layout")
        idRinger = resolveId(context, "ringer_layout")
        idBlur = resolveId(context, "bg_blur")
        idIcon = resolveId(context, "icon")
        layoutRingerButton = resolveRes(context, "miui_ringer_mode_layout", "layout")
        HookHelper.log("$tag: ids dnd=$idDnd ringer=$idRinger blur=$idBlur icon=$idIcon layout=$layoutRingerButton")
    }

    private fun resolveId(context: Context, name: String): Int {
        for (pkg in RES_PACKAGES + context.packageName) {
            val id = runCatching { context.resources.getIdentifier(name, "id", pkg) }.getOrDefault(0)
            if (id != 0) return id
        }
        return 0
    }

    private fun resolveRes(context: Context, name: String, type: String): Int {
        for (pkg in RES_PACKAGES + context.packageName) {
            val id = runCatching { context.resources.getIdentifier(name, type, pkg) }.getOrDefault(0)
            if (id != 0) return id
        }
        return 0
    }

    // ---------------- 注入入口（在其出现动画的 animator 初始化时） ----------------

    private fun hookAnimator(pluginCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(ANIMATOR_CLASS, pluginCl)
        if (cls == null) {
            HookHelper.log("$tag: $ANIMATOR_CLASS not found")
            return
        }
        cls.declaredMethods.filter { it.name == "initView" }.forEach { method ->
            method.isAccessible = true
            runCatching {
                HookHelper.hookAfter(method) { param ->
                    val volumeView = param.args.getOrNull(0) as? View ?: return@hookAfter
                    val animator = param.thisObject ?: return@hookAfter
                    runCatching { injectEntry(volumeView, animator) }
                        .onFailure { HookHelper.log("$tag: inject entry failed", it) }
                }
            }.onFailure { HookHelper.log("$tag: hook initView failed", it) }
        }
    }

    /**
     * 控制中心二级音量面板的标记由 `VolumePanelViewController.setControlCenterPanel(true)`
     * 提前写入对话框；仅靠 initView 时的静态判断会受复用/初始化顺序影响。
     * 标记切到 true 的瞬间直接把入口移除，侧边面板仍走正常注入。
     */
    private fun hookDialogControlCenter(pluginCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(DIALOG_CLASS, pluginCl) ?: return
        cls.declaredMethods
            .filter { it.name == "setControlCenterPanel" && it.parameterCount == 1 }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        if (param.args.getOrNull(0) as? Boolean != true) return@hookAfter
                        val dialog = param.thisObject as? View ?: return@hookAfter
                        removeEntry(dialog)
                        restoreOfficialButtons(dialog)
                    }
                }.onFailure { HookHelper.log("$tag: hook setControlCenterPanel failed", it) }
            }
    }

    private fun removeEntry(root: View) {
        // 控制中心二级面板与侧边面板复用同一套官方组件；切到二级面板时模块入口
        // 和已经展开的多应用面板都必须收起，不能只隐藏入口按钮。
        runCatching { AppVolumePanel.hide() }
        val entry = findEntry(root) ?: return
        val parent = entry.parent as? ViewGroup ?: return
        // 不能从官方音量对话框的 ViewGroup 里 removeView：对话框收起动画结束时会
        // 对子 View 逐个 dispatchDetachedFromWindow，期间删子 View 可能留下 null
        // 槽位并触发系统界面 NPE。这里只隐藏，下一次重复注入时再恢复可见。
        entry.animate().cancel()
        entry.alpha = 1f
        entry.visibility = View.GONE
        lastVisible[entry] = false
        desiredVisible[entry] = false
        pendingShow.remove(entry)
        userToggled.remove(entry)
        findTaggedChild(parent, TAG_DIVIDER)?.visibility = View.GONE
    }

    private fun injectEntry(volumeView: View, animator: Any) {
        // 控制中心二级音量面板复用同一套 MiuiVolumeDialogView；它不应出现多应用入口。
        // 控制器上的标记才是权威场景（对话框标记在 CC 初始化时可能尚未转发）。
        if (isControlCenterController(lastController) && controllerOwnsView(lastController, volumeView)) return
        val dialog = findVolumeDialog(volumeView)
        if (dialog != null && isControlCenterDialog(dialog)) {
            return
        }
        resolveResources(volumeView.context)
        if (layoutRingerButton == 0) {
            HookHelper.log("$tag: ringer button layout not resolved")
            return
        }
        val dnd = (if (idDnd != 0) volumeView.findViewById<View>(idDnd) else null)
            ?: findDndRoot(volumeView)
            ?: return
        val parent = dnd.parent as? ViewGroup ?: return
        val existing = entryOf(parent) ?: findEntry(parent)?.also { entries[parent] = it }
        val entry = existing ?: run {
            val dndIndex = parent.indexOfChild(dnd)
            var insertAt = dndIndex + 1
            // 官方两个按钮之间有一个分隔 View（间距）。新增入口前补一个同样的分隔，
            // 保证三个按钮间距一致（否则会少一段间距、排版错位）。
            val divider = siblingDivider(parent, dnd)
            if (divider != null && findTaggedChild(parent, TAG_DIVIDER) == null) {
                val div = View(parent.context)
                div.layoutParams = copyLayoutParams(divider)
                div.tag = TAG_DIVIDER
                parent.addView(div, insertAt.coerceIn(0, parent.childCount))
                insertAt++
            }
            val inflated = runCatching {
                LayoutInflater.from(volumeView.context).inflate(layoutRingerButton, parent, false)
            }.getOrNull() ?: return
            inflated.tag = TAG_ENTRY
            inflated.isClickable = true
            inflated.isFocusable = true
            parent.addView(inflated, insertAt.coerceIn(0, parent.childCount))
            entries[parent] = inflated
            setupEntryBehavior(inflated)
            HookHelper.log("$tag: entry injected into ${parent.javaClass.simpleName}")
            attachDividerSync(parent)
            inflated
        }
        entry.visibility = View.VISIBLE
        findTaggedChild(parent, TAG_DIVIDER)?.visibility = View.VISIBLE
        joinAnimator(animator, entry)
        officialVisibilityState.remove(parent)
        applyOfficialVisibility(parent, dnd)
        // `lastController` 可能仍指向已关闭的控制中心二级面板；只允许当前视图所属的
        // controller 决定入口显隐，否则侧边音量条刚注入的入口会被 CC 分支立刻收起。
        lastController?.takeIf { controllerOwnsView(it, volumeView) }
            ?.let { updateVisibility(it, entry) }
    }

    /** 仅当入口尚未加入动画数组时，把入口与对应的 X 记录一起追加。 */
    private fun joinAnimator(animator: Any, entry: View) {
        val already = runCatching {
            val field = animator.javaClass.getDeclaredField("mRingerBtnLayouts")
            field.isAccessible = true
            val current = field.get(animator) as? Array<*> ?: return@runCatching false
            current.any { it === entry }
        }.getOrDefault(false)
        if (already) return
        extendAnimator(animator, "mRingerBtnLayouts", entry)
        extendAnimator(animator, "ringerBtnLayoutsX", 0f)
    }

    /** 用官方 RingerButtonHelper 接管入口外观，并替换图标与点击回调。 */
    private fun setupEntryBehavior(entry: View) {
        val outer = ancestorOfType(entry, outerClass) ?: run {
            HookHelper.log("$tag: outer MiuiRingerModeLayout not found")
            return
        }
        runCatching {
            val ctor = helperClass?.declaredConstructors?.firstOrNull { it.parameterCount == 4 }
                ?: return@runCatching
            ctor.isAccessible = true
            val helper = ctor.newInstance(outer, entry, false, false)
            helpers[outer] = helper
            helperUpdateState(helper)
        }.onFailure { HookHelper.log("$tag: create RingerButtonHelper failed", it) }

        // 覆盖为模块图标。
        runCatching {
            val icon = entry.findViewById<ImageView>(idIcon)
            loadModuleIcon(entry.context)?.let { icon?.setImageDrawable(it) }
            icon?.imageTintList = ColorStateList.valueOf(Color.WHITE)
        }.onFailure { HookHelper.log("$tag: set entry icon failed", it) }

        // 覆盖点击（官方 helper 会把点击接到静音 / 勿扰切换，这里改为打开多应用音量）。
        entry.setOnClickListener { onEntryClick(entry) }
        runCatching {
            val blur = entry.findViewById<View>(idBlur)
            blur?.setOnClickListener { onEntryClick(entry) }
        }
    }

    /** 把入口追加进原生动画数组，使其拥有与原生按钮一致的延迟出现动画。 */
    private fun extendAnimator(animator: Any, fieldName: String, extra: Any) {
        runCatching {
            val field = animator.javaClass.getDeclaredField(fieldName)
            field.isAccessible = true
            val current = field.get(animator) ?: return
            val length = java.lang.reflect.Array.getLength(current)
            if ((0 until length).any { java.lang.reflect.Array.get(current, it) === extra }) return
            val component = current.javaClass.componentType ?: return
            val grown = java.lang.reflect.Array.newInstance(component, length + 1)
            for (i in 0 until length) java.lang.reflect.Array.set(grown, i, java.lang.reflect.Array.get(current, i))
            java.lang.reflect.Array.set(grown, length, extra)
            field.set(animator, grown)
        }.onFailure { HookHelper.log("$tag: extend $fieldName failed", it) }
    }

    // ---------------- 跟随官方展开 / 收起状态 ----------------

    private fun hookRingerLayout(pluginCl: ClassLoader) {
        val cls = outerClass ?: return
        val methods = setOf(
            "init",
            "updateExpandedH",
            "updateExpandedStateH",
            "updateResources",
            "prepareCollapsedBlurForShowAnimation",
        )
        cls.declaredMethods.filter { it.name in methods }.forEach { method ->
            method.isAccessible = true
            runCatching {
                HookHelper.hookAfter(method) { param ->
                    val outer = param.thisObject ?: return@hookAfter
                    val helper = helpers[outer] ?: return@hookAfter
                    runCatching {
                        when (method.name) {
                            "updateExpandedH" -> helperOnExpanded(
                                helper,
                                param.args.getOrNull(0) as? Boolean ?: false,
                                false,
                            )
                            "prepareCollapsedBlurForShowAnimation" -> helperOnExpanded(helper, false, true)
                        }
                        helperUpdateState(helper)
                    }.onFailure { HookHelper.log("$tag: update entry helper failed", it) }
                }
            }.onFailure { HookHelper.log("$tag: hook ${method.name} failed", it) }
        }
    }

    private fun helperOnExpanded(helper: Any, expanded: Boolean, force: Boolean) {
        helper.javaClass
            .getDeclaredMethod("onExpanded", Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(helper, expanded, force)
    }

    private fun helperUpdateState(helper: Any) {
        helper.javaClass
            .getDeclaredMethod("updateState")
            .apply { isAccessible = true }
            .invoke(helper)
    }

    // ---------------- 边界（最大/最小继续调整）动画 ----------------

    /**
     * 音量到达最大/最小继续调整时，原生通过 `MiuiVolumeSeekBar` 的
     * `SeekBarAnimListener`（`SlideContainerAnim.AnimListener`）对静音 / 勿扰按钮的
     * `bg_blur` 做上下平移 + 缩放。这里包裹该监听器，使入口的 `bg_blur` 同步同一变换，
     * 从而拥有完全一致的边界动画。
     */
    private fun hookSeekBarAnim(pluginCl: ClassLoader) {
        val sliderClass = Reflect.findClassIfExists(SEEK_BAR_CLASS, pluginCl) ?: return
        val iface = Reflect.findClassIfExists(ANIM_LISTENER_IFACE, pluginCl) ?: return
        sliderClass.declaredMethods
            .filter { it.name == "setSeekBarAnimListener" && it.parameterCount == 1 }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookBefore(method) { param ->
                        val slider = param.thisObject as? View ?: return@hookBefore
                        val delegate = param.args.getOrNull(0) ?: return@hookBefore
                        val proxy = runCatching { wrapAnimListener(iface, delegate, slider) }.getOrNull()
                            ?: return@hookBefore
                        param.setArg(0, proxy)
                    }
                }.onFailure { HookHelper.log("$tag: hook setSeekBarAnimListener failed", it) }
            }
    }

    private fun wrapAnimListener(iface: Class<*>, delegate: Any, slider: View): Any =
        java.lang.reflect.Proxy.newProxyInstance(iface.classLoader, arrayOf(iface)) { _, method, args ->
            val result = runCatching { method.invoke(delegate, *(args ?: emptyArray<Any?>())) }.getOrNull()
            runCatching { mirrorAnim(slider, method.name, args) }
            result
        }

    /** 把原生对按钮 `bg_blur` 的平移 / 缩放增量同步到入口的 `bg_blur`。 */
    private fun mirrorAnim(slider: View, name: String, args: Array<out Any?>?) {
        if (idBlur == 0) return
        // 多应用音量面板的滑条不参与镜像，否则其边缘动画会带着入口 / 勿扰按钮一起动。
        if (slider.tag == OfficialVolumeColumnFactory.SLIDER_TAG) return
        val cached = mirrorEntryCache[slider]?.get()?.takeIf { it.parent != null }
        val entry = cached ?: findEntry(slider.rootView)?.also {
            mirrorEntryCache[slider] = WeakReference(it)
        } ?: return
        if (entry.visibility != View.VISIBLE) return
        val blur = mirrorBlurCache[entry]?.get()?.takeIf { it.parent != null }
            ?: entry.findViewById<View>(idBlur)?.also {
                mirrorBlurCache[entry] = WeakReference(it)
            } ?: return
        when (name) {
            // 只跟随勿扰按钮（紧邻入口）的平移，避免 setRingerY + setDndY 叠加导致幅度翻倍。
            "setDndY" -> {
                val before = args?.getOrNull(0) as? Float ?: return
                val current = args.getOrNull(1) as? Float ?: return
                blur.translationY = (current - before) + blur.translationY
            }
            "setScale" -> {
                val before = args?.getOrNull(0) as? Float ?: return
                val current = args.getOrNull(1) as? Float ?: return
                val delta = current - before
                blur.scaleX += delta
                blur.scaleY += delta
            }
            "resetView" -> {
                blur.translationY = 0f
                blur.scaleX = 1f
                blur.scaleY = 1f
            }
        }
    }

    /**
     * 追加的入口按钮在官方「静音 / 勿扰」之后出现。
     *
     * 官方 `VolumeShowHideAnimatorKt.createRingerButtonArgs` 只区分 index==0（30ms）与其余（50ms），
     * 因此入口（index≥2）与勿扰（index==1）延迟相同、几乎同步。这里对 index≥2 继续递增延迟，
     * 使其保持「自上而下递增」的出现节奏。
     */
    private fun hookRingerButtonDelay(pluginCl: ClassLoader) {
        val cls = Reflect.findClassIfExists(RINGER_BUTTON_ARGS_CLASS, pluginCl) ?: return
        val method = cls.declaredMethods.firstOrNull {
            it.name == "createRingerButtonArgs" && it.parameterCount == 3
        } ?: return
        method.isAccessible = true
        runCatching {
            HookHelper.hookAfter(method) { param ->
                // 第 1 个参数为「展开 / 出现」标志；关闭动画本无延迟，不能动。
                if (param.args.getOrNull(0) as? Boolean != true) return@hookAfter
                val index = param.args.getOrNull(1) as? Int ?: return@hookAfter
                if (index < 2) return@hookAfter
                val args = param.result ?: return@hookAfter
                // index 1 为 50ms；index ≥2 每级 +20ms。
                val delayMs = (0.05 + 0.02 * (index - 1)) * 1000.0
                runCatching { Reflect.callMethod(args, "setDelayX", delayMs.toLong()) }
            }
        }.onFailure { HookHelper.log("$tag: hook ringer button delay failed", it) }
    }

    // ---------------- 控制器：显隐 / 上移 ----------------

    private fun hookController(pluginCl: ClassLoader) {
        val controller = Reflect.findClassIfExists(CONTROLLER_CLASS, pluginCl)
        if (controller == null) {
            HookHelper.log("$tag: $CONTROLLER_CLASS not found")
            return
        }
        controller.declaredMethods
            .filter { it.name in CONTROLLER_METHODS }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        param.thisObject?.let {
                            lastController = it
                            if (method.name == "updateExpandedH") {
                                expandedHint = param.args.getOrNull(0) as? Boolean
                            } else if (method.name == "showVolumePanelH" || method.name == "showH") {
                                expandedHint = false
                            } else if (method.name == "dismissH") {
                                expandedHint = null
                            }
                            onController(it, method.name)
                        }
                    }
                }.onFailure { HookHelper.log("$tag: hook ${method.name} failed", it) }
            }
    }

    /**
     * 控制中心二级音量面板的权威标记在 [CONTROLLER_CLASS] 上。
     *
     * 官方在 `initController` 之前就调用 `setControlCenterPanel(true)`，此时
     * `mVolumeView` 还不存在，setter 不会把标记转发到对话框；`reInit()` 创建新
     * 对话框时也不会补写。因此只能读控制器的字段，不能信任对话框上的同名标记。
     */
    private fun hookControllerControlCenter(pluginCl: ClassLoader) {
        val controller = Reflect.findClassIfExists(CONTROLLER_CLASS, pluginCl) ?: return
        controller.declaredMethods
            .filter { it.name == "setControlCenterPanel" && it.parameterCount == 1 }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        if (param.args.getOrNull(0) as? Boolean != true) return@hookAfter
                        val instance = param.thisObject ?: return@hookAfter
                        lastController = instance
                        expandedHint = false
                        hideControlCenterVolume(instance)
                    }
                }.onFailure { HookHelper.log("$tag: hook controller CC failed", it) }
            }
    }

    private fun isControlCenterController(controller: Any?): Boolean {
        if (controller == null) return false
        val field = runCatching {
            Reflect.getObjectField(controller, "isControlCenterPanel")
        }.getOrNull()
        if (field is Boolean) return field
        return runCatching {
            Reflect.callMethod(controller, "isControlCenterPanel") as? Boolean
        }.getOrNull() == true
    }

    /**
     * `lastController` 是跨面板共享的全局状态，控制中心关闭后可能仍指向旧 controller。
     * 只有 controller 的 `mVolumeView` 确实是当前视图的祖先时，才允许使用它的场景标记。
     */
    private fun controllerOwnsView(controller: Any?, view: View): Boolean {
        if (controller == null) return false
        val root = runCatching {
            Reflect.getObjectField(controller, "mVolumeView")
        }.getOrNull() as? View ?: return false
        var current: View? = view
        while (current != null) {
            if (current === root) return true
            current = current.parent as? View
        }
        return false
    }

    /** CC 二级面板只显示官方内容：收起模块入口、模块面板，并恢复官方按钮。 */
    private fun hideControlCenterVolume(controller: Any) {
        runCatching { AppVolumePanel.hide() }
        val root = runCatching {
            Reflect.getObjectField(controller, "mVolumeView")
        }.getOrNull() as? View ?: return
        removeEntry(root)
        restoreOfficialButtons(root)
    }

    /** 恢复被一级面板隐藏的静音 / 勿扰按钮本体和分隔线。 */
    private fun restoreOfficialButtons(root: View) {
        val dnd = (if (idDnd != 0) {
            runCatching { root.findViewById<View>(idDnd) }.getOrNull()
        } else {
            null
        }) ?: findDndRoot(root) ?: return
        val parent = dnd.parent as? ViewGroup ?: return
        officialVisibilityState.remove(parent)
        val ringer = findRingerSibling(parent)
        applyOfficialButtonVisibility(ringer, false)
        applyOfficialButtonVisibility(dnd, false)
        officialDivider(dnd, ringer)?.visibility = View.VISIBLE
        // 已经展开，只恢复按钮本体；不要按收起态重设容器高度。
        applyBothHiddenSpacing(parent, false, true)
    }

    private fun onController(controller: Any, methodName: String) {
        // 控制中心二级面板与一级共用官方 controller / RingerModeLayout：
        // 在它上面必须完全隐藏模块入口和面板，也不能套用一级的静音/勿扰隐藏。
        if (isControlCenterController(controller)) {
            hideControlCenterVolume(controller)
            return
        }
        // 侧边音量条关闭时同步关闭系统界面模式面板，避免再次打开时残留 / 位置错乱。
        if (methodName == "dismissH") runCatching { AppVolumePanel.hide() }
        runCatching {
            val ringer = findRinger(controller) ?: return
            val parent = ringer.parent as? ViewGroup ?: return
            // 即使模块入口未注入，也要按展开状态刷新官方按钮；二级面板与一级共用
            // 同一组静音 / 勿扰按钮，只有收起的一级面板才允许隐藏。
            applyOfficialVisibility(parent, findDndRoot(parent))
            val entry = entryOf(parent) ?: return
            if (!HookPrefs.getBoolean(key, false)) {
                applyEntryState(entry, false)
                shiftPanel(entry, false)
                return
            }
            updateVisibility(controller, entry)
            // 侧边音量条真正显示时，按需自动展开多应用音量面板。
            if (methodName == "showVolumePanelH") maybeAutoShow(controller, entry)
        }.onFailure { HookHelper.log("$tag: onController failed", it) }
    }

    private fun findRinger(controller: Any): View? {
        val cached = ringerCache[controller]?.get()
        if (cached != null && cached.isAttachedToWindow) return cached
        ringerCache.remove(controller)
        val found = callAny(controller, listOf("getRingerModeLayout", "getVolumeRingerModeLayout"))
            ?.let { it as? View }
            ?: runCatching { Reflect.callMethod(controller, "getDelegate") }.getOrNull()?.let { delegate ->
                callAny(delegate, listOf("getRingerModeLayout", "getVolumeRingerModeLayout"))
                    ?.let { it as? View }
            }
        if (found != null && found.isAttachedToWindow) {
            ringerCache[controller] = WeakReference(found)
        }
        return found
    }

    private fun callAny(target: Any, names: List<String>): Any? {
        for (name in names) {
            runCatching { Reflect.callMethod(target, name) }.getOrNull()?.let { return it }
        }
        return null
    }

    private fun findEntry(root: View): View? {
        if (root.tag == TAG_ENTRY) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findEntry(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** 优先用注入时记录的映射，避免每次音量键都递归遍历整个面板。 */
    private fun entryOf(parent: ViewGroup): View? {
        val entry = entries[parent]
        if (entry != null && entry.parent === parent) return entry
        entries.remove(parent)
        return null
    }

    /** 找到紧邻按钮之前的官方「分隔 View」（用于复制间距）。 */
    private fun siblingDivider(parent: ViewGroup, button: View): View? {
        val index = parent.indexOfChild(button)
        for (i in index - 1 downTo 0) {
            val child = parent.getChildAt(i)
            if (child.javaClass.name == "android.view.View") return child
            if (child is ViewGroup) break
        }
        return null
    }

    /**
     * 找到按钮所在层级里的官方分隔 View，跳过模块自己插入的 `TAG_DIVIDER`。
     *
     * `dnd_layout` 有时不是 `parent` 的直接子项，因此先沿父链回到按钮的实际容器，
     * 再找紧邻按钮之前的官方分隔；这样展开二级面板时能把一级隐藏掉的分隔恢复回来。
     */
    private fun officialDivider(dnd: View?, ringer: View?): View? {
        val button = dnd ?: ringer ?: return null
        val container = button.parent as? ViewGroup ?: return null
        val index = container.indexOfChild(button)
        for (i in index - 1 downTo 0) {
            val child = container.getChildAt(i)
            if (child.tag == TAG_DIVIDER) continue
            if (child.javaClass.name == "android.view.View") return child
            if (child is ViewGroup) break
        }
        return null
    }

    /**
     * 让入口前的分隔 View 与官方分隔完全同步。
     *
     * 官方分隔的高度在布局完成后才会被最终确定（展开 / 收起、双列等会变），
     * 注入时拷贝到的还是初始值，故需在布局后及状态回调里再次同步尺寸。
     */
    private val dividerSyncAttached = WeakHashMap<ViewGroup, Boolean>()

    /** 一次性布局后同步即可，避免每帧递归查找、在快速调音量时造成卡顿。 */
    private val dividerSyncScheduled = WeakHashMap<ViewGroup, Boolean>()

    /** 两个官方按钮都隐藏时临时收紧外层上边距；记住原值以便恢复。 */
    private val originalOuterTopMargins = WeakHashMap<View, Int>()

    /** 布局变化后合并到下一帧同步一次，保证与官方分隔高度一致。 */
    private fun attachDividerSync(parent: ViewGroup) {
        if (dividerSyncAttached[parent] == true) return
        dividerSyncAttached[parent] = true
        parent.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            scheduleDividerSync(parent)
        }
        scheduleDividerSync(parent)
    }

    private fun scheduleDividerSync(parent: ViewGroup) {
        if (dividerSyncScheduled[parent] == true) return
        dividerSyncScheduled[parent] = true
        parent.postOnAnimation {
            dividerSyncScheduled[parent] = false
            syncDivider(parent)
        }
    }

    private fun syncDivider(parent: ViewGroup) {
        runCatching {
            val clone = findTaggedChild(parent, TAG_DIVIDER) ?: return@runCatching
            val cloneIndex = parent.indexOfChild(clone)
            if (cloneIndex <= 0) return@runCatching
            val dnd = parent.getChildAt(cloneIndex - 1)
            val official = siblingDivider(parent, dnd) ?: return@runCatching
            if (official === clone) return@runCatching
            val src = official.layoutParams ?: return@runCatching
            val lp = clone.layoutParams ?: return@runCatching
            if (lp.width != src.width || lp.height != src.height) {
                lp.width = src.width
                lp.height = src.height
                clone.layoutParams = lp
                // 只有分隔尺寸真的变化时才重算平移。官方展开/收起动画期间每帧都会
                // 触发 layout，若每次无条件重启动画，会让整条音量条持续抖动。
                entryOf(parent)?.let { entry ->
                    shiftPanel(entry, lastVisible[entry] ?: true)
                }
            }
        }.onFailure { HookHelper.log("$tag: syncDivider failed", it) }
    }

    private fun copyLayoutParams(src: View): ViewGroup.LayoutParams {
        val lp = src.layoutParams ?: return ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return if (lp is ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams(lp.width, lp.height).apply {
                leftMargin = lp.leftMargin
                topMargin = lp.topMargin
                rightMargin = lp.rightMargin
                bottomMargin = lp.bottomMargin
                marginStart = lp.marginStart
                marginEnd = lp.marginEnd
            }
        } else {
            ViewGroup.LayoutParams(lp.width, lp.height)
        }
    }

    private fun findTaggedChild(parent: ViewGroup, tag: String): View? {
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.tag == tag) return child
        }
        return null
    }

    /** 结构兜底：返回最后一个「音量按钮根」（即勿扰 dnd_layout 的根视图）。 */
    private fun findDndRoot(root: View): View? {
        if (idDnd != 0) {
            root.findViewById<View>(idDnd)?.let { return it }
        }
        var lastParent: View? = null
        fun visit(v: View) {
            if (v.tag == TAG_ENTRY) return
            if (v.javaClass.name.contains("VolumeBlurFrameLayout")) {
                lastParent = v.parent as? View
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) visit(v.getChildAt(i))
        }
        visit(root)
        return lastParent
    }

    /** 侧边条里的官方静音 / 铃声按钮根（模块入口复用同一个布局，必须排除）。 */
    private fun findRingerSibling(parent: ViewGroup): View? {
        if (idRinger != 0) {
            val byId = parent.findViewById<View>(idRinger)
            if (byId != null && byId.tag != TAG_ENTRY) return byId
        }
        for (i in 0 until parent.childCount) {
            val child = parent.getChildAt(i)
            if (child.tag == TAG_ENTRY) continue
            if (child.javaClass.name.contains("MiuiRingerModeLayout")) return child
        }
        return null
    }

    /**
     * 隐藏官方静音 / 勿扰按钮，并移除两个按钮之间的分隔占位。
     *
     * 用 `GONE` 而不是 `INVISIBLE`：用户要求隐藏后上方图标整体下移、
     * 不留下空槽位；同时隐藏分隔 View，避免剩余按钮之间多出一段间距。
     */
    private fun applyOfficialVisibility(parent: ViewGroup, dnd: View?) {
        // 控制中心二级面板有自己的 controller 场景，不能套用一级的隐藏规则。
        // 只有当前 parent 确实属于该 CC controller 时才恢复，避免旧 controller 影响侧边面板。
        if (isControlCenterController(lastController) && controllerOwnsView(lastController, parent)) {
            restoreOfficialButtons(parent)
            return
        }
        // 一级与二级复用同一组静音 / 勿扰按钮；隐藏只在收起的一级面板生效，
        // 展开后恢复按钮，避免二级菜单里的两个按钮一起消失。
        val controller = lastController?.takeIf { controllerOwnsView(it, parent) }
        val expanded = expandedHint ?: if (controller == null) {
            false
        } else {
            (runCatching { Reflect.getObjectField(controller, "mExpanded") }
                .getOrNull() as? Boolean) ?: false
        }
        val hideRinger = !expanded && HookPrefs.getBoolean(AppVolumeKeys.HIDE_RINGER, false)
        val hideDnd = !expanded && HookPrefs.getBoolean(AppVolumeKeys.HIDE_DND, false)
        // 同一 parent 上重复调用时，场景未变就不再重写官方按钮可见性 / 间距；
        // 音量键连续触发 showVolumePanelH 时这里是主要热路径。
        var signature = if (expanded) 1L else 0L
        if (hideRinger) signature = signature or 2L
        if (hideDnd) signature = signature or 4L
        if (officialVisibilityState[parent] == signature) return
        val ringer = findRingerSibling(parent)
        applyOfficialButtonVisibility(ringer, hideRinger)
        applyOfficialButtonVisibility(dnd, hideDnd)
        officialDivider(dnd, ringer)?.visibility =
            if (hideRinger || hideDnd) View.GONE else View.VISIBLE
        applyBothHiddenSpacing(parent, hideRinger && hideDnd, expanded)
        officialVisibilityState[parent] = signature
    }

    /**
     * 只隐藏官方按钮本体，保留它的根布局与计时器子项。
     *
     * `dnd_layout` / `ringer_layout` 内部除了 `bg_blur` 按钮，还有 `timer_layout`
     * 等二级内容；直接 `GONE` 整个根布局会把这些内容一起带走。
     */
    private fun applyOfficialButtonVisibility(root: View?, hide: Boolean) {
        if (root == null) return
        val blur = findBlurView(root)
        if (blur == null) {
            // 找不到按钮本体时绝不能退回 GONE 整个根布局：一级 / 二级复用同一个
            // 组件，GONE 根布局会把二级面板的计时器、静音/勿扰按钮一起带走。
            HookHelper.log("$tag: bg_blur not found under ${root.javaClass.simpleName}; skip hiding")
            hiddenButtonRoots.remove(root)
            root.visibility = View.VISIBLE
            return
        }
        root.visibility = View.VISIBLE
        blur.visibility = if (hide) View.GONE else View.VISIBLE
        if (hide) hiddenButtonRoots[root] = true else hiddenButtonRoots.remove(root)
    }

    /** 先按资源 id 找，id 解析失败时按资源名递归兜底。 */
    private fun findBlurView(root: View): View? {
        if (idBlur != 0) {
            runCatching { root.findViewById<View>(idBlur) }.getOrNull()?.let { return it }
        }
        if (root.id != View.NO_ID &&
            runCatching { root.resources.getResourceEntryName(root.id) }.getOrNull() == "bg_blur"
        ) {
            return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findBlurView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun isOfficialButtonVisible(root: View?): Boolean =
        root != null && root.visibility != View.GONE && hiddenButtonRoots[root] != true

    /**
     * 隐藏按钮时分隔线与外层间距的同步。
     *
     * 这里**不再**把外层 `topMargin` 收紧到 0：侧边音量条保持展开时，官方 motion 不会重跑，
     * 只改 margin 会让入口与音量条之间立即贴死；重新打开面板时官方重新布局才恢复间距，
     * 表现为「隐藏静音和勿扰后间距消失，重开才有效果」。保留官方上边距，让实时切换与重开一致。
     *
     * 仍恢复旧版本可能留下的 0 margin，避免升级后已有面板继续贴死。
     */
    private fun applyBothHiddenSpacing(parent: ViewGroup, bothHidden: Boolean, expanded: Boolean) {
        runCatching {
            val entryVisible = entryOf(parent)?.visibility == View.VISIBLE
            findTaggedChild(parent, TAG_DIVIDER)?.visibility =
                if (entryVisible && !bothHidden) View.VISIBLE else View.GONE

            val state = parent.parent as? View
            val outer = state?.parent as? View
            outer?.let { view ->
                val original = originalOuterTopMargins.remove(view) ?: return@let
                val lp = view.layoutParams as? ViewGroup.MarginLayoutParams ?: return@let
                if (lp.topMargin != original) {
                    lp.topMargin = original
                    view.layoutParams = lp
                }
            }
            // 入口可见时，让展开态容器按实际子项高度自适应；隐藏任意数量都成立。
            // 二级面板展开时入口会被收起，不能再改容器高度，否则会把二级布局撑开。
            if (!expanded && entryVisible) {
                ensureExpandedRingerHeight(parent)
            }
        }.onFailure { HookHelper.log("$tag: applyBothHiddenSpacing failed", it) }
    }

    /**
     * 展开态下入口可能和 0~2 个官方按钮同时存在，固定 36dp 装不下。
     * 只把高度改成 `WRAP_CONTENT`；二级展开时不再恢复成收起态的固定高度，
     * 否则会把静音 / 勿扰两个按钮压到一起。
     */
    private fun ensureExpandedRingerHeight(parent: ViewGroup) {
        val state = parent.parent as? View ?: return
        val lp = state.layoutParams ?: return
        if (lp.height == ViewGroup.LayoutParams.WRAP_CONTENT) return
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
        state.layoutParams = lp
    }

    private fun ancestorOfType(view: View, cls: Class<*>?): Any? {
        val type = cls ?: return null
        var parent: ViewParent? = view.parent
        while (parent != null) {
            if (type.isInstance(parent)) return parent
            parent = (parent as? View)?.parent
        }
        return null
    }

    // ---------------- 显示 ----------------

    /**
     * 入口出现 / 消失时，把整条侧边音量条（含音量列）向上平移「多出高度的一半」，
     * 使整体视觉中心（平均高度）不变；隐藏时平移回原位。
     *
     * 「是否启用自动平衡高度」（竖屏 / 横屏分别控制）关闭时不平移，保持原高度 / 位置。
     */
    private fun shiftPanel(entry: View, visible: Boolean) {
        val parent = entry.parent as? ViewGroup ?: return
        val panel = findVolumeDialog(entry) ?: return
        val ringer = findRingerSibling(parent)
        val dnd = findDndRoot(parent)
        val ringerVisible = isOfficialButtonVisible(ringer)
        val dndVisible = isOfficialButtonVisible(dnd)
        val anyOfficialVisible = ringerVisible || dndVisible
        val clone = findTaggedChild(parent, TAG_DIVIDER)
        // 入口消失或官方按钮全部隐藏时，入口前的分隔线没有下文，必须一并收起；
        // 否则「隐藏入口」会留下一条悬空分隔线，也会污染高度差。
        clone?.visibility = if (visible && anyOfficialVisible) View.VISIBLE else View.GONE

        // 以原生「两个官方按钮 + 一条分隔线 + 上边距」为基准，计算当前可见内容的净高度差。
        // 不能只加「入口高度」：隐藏一个官方按钮会正好抵消入口增高，隐藏两个则会缩短面板；
        // 只有净差才能同时覆盖隐藏 0/1/2 个官方按钮、以及入口自身显示/隐藏的组合。
        val delta = runCatching {
            val rowHeight = sequenceOf(ringer, dnd)
                .mapNotNull { view -> view?.takeIf { it.height > 0 }?.height }
                .firstOrNull()
                ?: resolveDimen(
                    entry.resources,
                    entry.context.packageName,
                    "o3_miui_ringer_btn_height",
                    entry.context.dp(48),
                )
            // 入口可能比官方按钮高（含描边/内边距），必须用自身高度，不能用官方行高替代。
            val entryHeight = if (visible) {
                entry.takeIf { it.height > 0 }?.height ?: rowHeight
            } else {
                0
            }
            val cloneDividerHeight = clone
                ?.takeIf { it.visibility != View.GONE }
                ?.height ?: 0
            val officialDivider = (dnd ?: ringer)?.let { siblingDivider(parent, it) }
                ?.takeIf { it !== clone && it.visibility != View.GONE }
            val dividerHeight = sequenceOf(cloneDividerHeight, officialDivider?.height ?: 0)
                .firstOrNull { it > 0 }
                ?: resolveDimen(
                    entry.resources,
                    entry.context.packageName,
                    "miui_volume_ringer_divider_height",
                    entry.context.dp(14),
                )
            val outer = (parent.parent as? View)?.parent as? View
            val currentMargin = (outer?.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin ?: 0
            val baselineMargin = originalOuterTopMargins[outer]
                ?: currentMargin.takeIf { it > 0 }
                ?: resolveDimen(
                    entry.resources,
                    entry.context.packageName,
                    "miui_volume_footer_margin_top",
                    entry.context.dp(10),
                )
            val officialRows = (if (ringerVisible) 1 else 0) + (if (dndVisible) 1 else 0)
            val dividers = (if (ringerVisible && dndVisible) dividerHeight else 0) +
                (if (visible && anyOfficialVisible) dividerHeight else 0)
            val currentHeight = officialRows * rowHeight + entryHeight + dividers + currentMargin
            // 原生基准含两个官方按钮、官方分隔线和上边距。
            val nativeHeight = 2 * rowHeight + dividerHeight + baselineMargin
            currentHeight - nativeHeight
        }.getOrDefault(0)
        val landscape = entry.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val balanceKey = if (landscape) {
            VolumeBarKeys.AUTO_BALANCE_LANDSCAPE
        } else {
            VolumeBarKeys.AUTO_BALANCE_PORTRAIT
        }
        // 自动平衡：按净高度差的一半平移，保持整条音量条平均高度不变；
        // 隐藏按钮使面板变矮时向下补，显示入口使面板变高时向上让。
        val autoBalance = HookPrefs.getBoolean(balanceKey, true)
        // 平移落点取整到物理像素：整条内容做非整数像素平移时会被 GPU 重采样，
        // 观感即「整条渲染分辨率下降 / 发虚」，因此半高度为奇数时向上取整。
        // 三个按钮全隐藏时没有任何可平衡的内容，保持音量条原位，避免整条下坠。
        val hasVisibleButton = visible || anyOfficialVisible
        val half = if (hasVisibleButton) {
            if (delta >= 0) (delta + 1) / 2 else -((-delta + 1) / 2)
        } else {
            0
        }
        val target = if (autoBalance && half != 0) -half.toFloat() else 0f
        // 平移**整个窗口内容**（`mVolumePanelView`：对话框 `MiuiVolumeDialogView` 的父容器）：
        // 它与音量条对话框、悬浮百分比（`miui_super_volume_*`）并列包含在同一父容器下，
        // 只有平移该父容器才能让二者一起移动，避免只移动对话框导致悬浮百分比错位。
        val shiftRoot = panel.parent as? View ?: panel
        if (lastShift[shiftRoot] == target) return
        lastShift[shiftRoot] = target
        shiftRoot.animate()
            .translationY(target)
            .setDuration(SHIFT_DURATION_MS)
            .setInterpolator(android.view.animation.AccelerateDecelerateInterpolator())
            .start()
    }

    /** 找到整条侧边音量条容器（`MiuiVolumeDialogView`）。 */
    private fun findVolumeDialog(view: View): View? {
        var current: View? = view
        while (current != null) {
            if (current.javaClass.name.contains("MiuiVolumeDialogView")) return current
            current = current.parent as? View
        }
        return null
    }

    /** 兼容字段名被混淆/改名的 ROM：优先读字段，再退回同名访问器。 */
    private fun isControlCenterDialog(dialog: View): Boolean {
        val field = runCatching {
            Reflect.getObjectField(dialog, "isControlCenterPanel") as? Boolean
        }.getOrNull()
        if (field != null) return field
        return runCatching {
            Reflect.callMethod(dialog, "isControlCenterPanel") as? Boolean
        }.getOrNull() ?: false
    }

    private fun applyEntryState(entry: View, visible: Boolean, dodge: Boolean = false, deferCount: Int = 0) {
        desiredVisible[entry] = visible
        // 入口隐藏（展开态 / 面板收起）时同步关闭系统界面模式的面板，避免残留。
        // `dodge=true`（侧边二级面板展开）时用「避让」消失动画，其余情况原地淡出。
        if (!visible) {
            pendingShow[entry] = false
            runCatching { if (dodge) AppVolumePanel.hideByExpand() else AppVolumePanel.hide() }
        }
        if (lastVisible[entry] == visible) return
        if (visible) {
            // 官方出现 / 收起形变动画会缩放整条内容并锁定几何参数；此时改变窗口高度（入口显隐）
            // 会打断动画状态机，可能让整条内容停留在异常渲染状态。等动画结束再做显隐切换。
            if (panelAnimating(entry) && deferCount < MAX_DEFER_FRAMES) {
                if (pendingShow[entry] != true) {
                    pendingShow[entry] = true
                    entry.postDelayed({
                        pendingShow[entry] = false
                        applyEntryState(entry, desiredVisible[entry] ?: true, deferCount = deferCount + 1)
                    }, DEFER_WAIT_MS)
                }
                return
            }
            pendingShow[entry] = false
        }
        lastVisible[entry] = visible
        if (visible) {
            // 先让入口以完全透明参与布局，等父容器 / 窗口按新高度完成一次测量后再播出现动画：
            // 官方音量条窗口的高度跟随内容，淡入期间窗口尚未长高时入口会被裁掉一截（表现为「截断」）。
            entry.visibility = View.VISIBLE
            entry.alpha = 0f
            whenLaidOut(entry) { showEntry(entry) }
        } else {
            // 新一轮显示周期：清除上一轮的用户手动操作标记。
            userToggled.remove(entry)
            // 整条音量条先平移回原位（此时入口仍在布局中，窗口高度未收缩），淡出结束后再收起入口，
            // 避免淡出过程中窗口已经变矮、入口被裁掉一截。
            shiftPanel(entry, false)
            entry.animate().alpha(0f).setDuration(FADE_DURATION_MS).withEndAction {
                if (lastVisible[entry] == false) {
                    entry.visibility = View.GONE
                    entry.alpha = 1f
                }
            }.start()
        }
        HookHelper.log("$tag: entry visible=$visible")
    }

    /** 官方出现 / 收起动画是否仍在播放（`MiuiVolumeDialogMotion.mShowHideAnimator.isRunning()`）。 */
    private fun panelAnimating(entry: View): Boolean = runCatching {
        val panel = findVolumeDialog(entry) ?: return false
        val motion = Reflect.getObjectField(panel, "mMotion") ?: return false
        val animator = Reflect.getObjectField(motion, "mShowHideAnimator") ?: return false
        (Reflect.callMethod(animator, "isRunning") as? Boolean) ?: false
    }.getOrDefault(false)

    /** 入口完成一次测量后再执行 [action]（带上限，取不到尺寸时也会放行）。 */
    private fun whenLaidOut(entry: View, frames: Int = 0, action: (View) -> Unit) {
        if (entry.height > 0 || frames >= MAX_LAYOUT_WAIT_FRAMES || !entry.isAttachedToWindow) {
            action(entry)
            return
        }
        entry.post { whenLaidOut(entry, frames + 1, action) }
    }

    /** 播放入口出现动画；入口在本次显示周期内出现时同样按「自动显示」展开多应用音量面板。 */
    private fun showEntry(entry: View) {
        if (lastVisible[entry] != true) return
        // 清掉上一次淡出挂的结束动作，避免复用时在本次动画结束时误收起入口。
        entry.animate().alpha(1f).withEndAction(null).setDuration(FADE_DURATION_MS).start()
        (entry.parent as? ViewGroup)?.let { ensureExpandedRingerHeight(it) }
        shiftPanel(entry, true)
        if (!autoRefreshing) lastController?.let { maybeAutoShow(it, entry) }
    }

    private fun updateVisibility(controller: Any, entry: View) {
        if (isControlCenterController(controller)) {
            hideControlCenterVolume(controller)
            return
        }
        val expanded = expandedHint
            ?: (runCatching { Reflect.getObjectField(controller, "mExpanded") }.getOrNull() as? Boolean
                ?: false)
        // 读后台探测快照：官方判定内部会同步调用音频服务，通话等场景单次可阻塞数秒，主线程不能直接调。
        val visibility = entryVisibility()
        val visible = !expanded &&
            visibility != AppVolumeKeys.VISIBILITY_NEVER &&
            (visibility == AppVolumeKeys.VISIBILITY_ALWAYS ||
                AppVolumePlayback.snapshot().hasActive)
        applyEntryState(entry, visible, dodge = expanded)
        // 快照过期时请一次后台刷新；结果回来（播放状态可能已变化）再重算一次入口状态。
        // 回调也必须校验 controller 仍属于该入口，避免期间切到 CC 后被旧 controller 收起。
        AppVolumePlayback.refresh(entry.context) {
            if (controllerOwnsView(controller, entry)) updateVisibility(controller, entry)
        }
    }

    private fun entryVisibility(): String = when (HookPrefs.getString(AppVolumeKeys.ENTRY_VISIBILITY, AppVolumeKeys.VISIBILITY_AUTO)) {
        AppVolumeKeys.VISIBILITY_ALWAYS -> AppVolumeKeys.VISIBILITY_ALWAYS
        AppVolumeKeys.VISIBILITY_NEVER -> AppVolumeKeys.VISIBILITY_NEVER
        else -> AppVolumeKeys.VISIBILITY_AUTO
    }

    /**
     * 「自动显示」：侧边音量条显示时，若开关开启，自动展开面板。
     * 入口是否可见由「入口显示方式」单独控制；即使入口始终隐藏，自动展开仍可生效。
     * 已展开时、或用户本轮手动操作过入口后，不再自动展开。
     */
    private fun maybeAutoShow(controller: Any, entry: View) {
        // CC 二级面板不是侧边音量条，不能触发模块的自动展开。
        if (isControlCenterController(controller)) {
            hideControlCenterVolume(controller)
            return
        }
        if (!canAutoShow(entry)) return
        if (AppVolumePanel.isShowing()) return
        val root = entry.rootView as? ViewGroup ?: return
        val dialog = findVolumeDialog(entry) ?: return
        // 延后到本轮布局之后，确保侧边音量条已就位再取坐标 / 展开；再补一次延迟重试，
        // 覆盖「官方的正在播放应用列表在音量条展开后才刷新出来」的情形。
        entry.post { tryAutoShow(controller, entry, root, dialog) }
        entry.postDelayed({ tryAutoShow(controller, entry, root, dialog) }, AUTO_SHOW_RETRY_MS)
    }

    private fun tryAutoShow(controller: Any, entry: View, root: ViewGroup, dialog: View) {
        if (isControlCenterController(controller)) return
        if (!canAutoShow(entry)) return
        if (AppVolumePanel.isShowing()) return
        // 入口自己会按「入口显示方式」隐藏；自动展开是独立开关，不能因入口隐藏而失效。
        if (lastVisible[entry] != true && entryVisibility() != AppVolumeKeys.VISIBILITY_NEVER) {
            // 入口还没出现：按最新的「正在播放的应用」重算一次入口状态
            // （应用可能刚刚才开始播放 / 官方列表刚刷新出来）。
            autoRefreshing = true
            try {
                runCatching { updateVisibility(controller, entry) }
            } finally {
                autoRefreshing = false
            }
            if (lastVisible[entry] != true) return
        }
        // 两次延迟重试之间侧边条可能已经收回，避免在隐藏的 dialog 上创建面板。
        if (!dialog.isAttachedToWindow || !dialog.isShown) return
        runCatching { AppVolumePanel.show(root, dialog, entry.context, pluginClassLoader, entry) }
            .onFailure { HookHelper.log("$tag: auto show panel failed", it) }
    }

    /** 是否允许自动展开面板：开关开启，且用户本轮显示期间没有手动操作过入口。 */
    private fun canAutoShow(entry: View): Boolean =
        HookPrefs.getBoolean(AppVolumeKeys.AUTO_SHOW, false) &&
            userToggled[entry] != true

    private fun onEntryClick(entry: View) {
        HookHelper.log("$tag: entry clicked")
        // 用户手动操作过入口（展开 / 关闭）后，本轮显示周期内不再自动展开。
        userToggled[entry] = true
        // 仅系统界面模式：由系统界面自行渲染面板（数值转发到「音质音效」生效 / 同步）。
        val root = entry.rootView as? ViewGroup
        val dialog = findVolumeDialog(entry)
        if (root != null && dialog != null) {
            runCatching { AppVolumePanel.toggle(root, dialog, entry.context, pluginClassLoader, entry) }
                .onFailure { HookHelper.log("$tag: toggle systemui panel failed", it) }
        }
    }

    // ---------------- 资源 ----------------

    private fun loadModuleIcon(context: Context): android.graphics.drawable.Drawable? = runCatching {
        val module = context.createPackageContext(
            AppVolumeKeys.MODULE_PACKAGE,
            Context.CONTEXT_IGNORE_SECURITY,
        )
        val id = module.resources.getIdentifier(
            AppVolumeKeys.ICON_NAME,
            "drawable",
            AppVolumeKeys.MODULE_PACKAGE,
        )
        if (id != 0) module.getDrawable(id) else null
    }.getOrNull()

    private fun resolveDimen(
        res: android.content.res.Resources,
        pkg: String,
        name: String,
        fallback: Int,
    ): Int {
        val id = runCatching { res.getIdentifier(name, "dimen", pkg) }.getOrDefault(0)
        return if (id != 0) runCatching { res.getDimensionPixelSize(id) }.getOrDefault(fallback) else fallback
    }

    private fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CONTROLLER_CLASS =
            "com.android.systemui.miui.volume.VolumePanelViewController"
        private const val ANIMATOR_CLASS =
            "com.android.systemui.miui.volume.VolumeShowHideAnimator"
        private const val DIALOG_CLASS =
            "com.android.systemui.miui.volume.MiuiVolumeDialogView"
        private const val OUTER_CLASS =
            "com.android.systemui.miui.volume.MiuiRingerModeLayout"
        private const val SEEK_BAR_CLASS =
            "com.android.systemui.miui.volume.MiuiVolumeSeekBar"
        private const val ANIM_LISTENER_IFACE =
            "com.android.systemui.miui.volume.MiuiVolumeSeekBar\$SeekBarAnimListener"
        private const val RINGER_BUTTON_ARGS_CLASS =
            "com.android.systemui.miui.volume.VolumeShowHideAnimatorKt"
        private const val HELPER_CLASS =
            "com.android.systemui.miui.volume.MiuiRingerModeLayout\$RingerButtonHelper"

        /** 资源解析候选包名（插件资源可能挂在其中任一包下）。 */
        private val RES_PACKAGES = listOf("miui.systemui.plugin", "com.android.systemui")

        private const val TAG_ENTRY = "tag_app_volume_entry_root"
        private const val TAG_DIVIDER = "tag_app_volume_entry_divider"

        /** 面板上移 / 复位动画时长。 */
        private const val SHIFT_DURATION_MS = 250L

        /** 入口出现 / 消失的透明度动画时长。 */
        private const val FADE_DURATION_MS = 250L

        /** 「自动显示」的延迟重试间隔：等官方的正在播放应用列表刷新后再判定一次。 */
        private const val AUTO_SHOW_RETRY_MS = 300L

        /** 入口出现动画前，等待其完成布局的最大帧数。 */
        private const val MAX_LAYOUT_WAIT_FRAMES = 5

        /** 官方形变动画播放期间，延迟入口显隐的轮询间隔。 */
        private const val DEFER_WAIT_MS = 120L

        /** 延迟入口显隐的最大轮询次数（约 4.8s，超时后照常切换）。 */
        private const val MAX_DEFER_FRAMES = 40

        private val CONTROLLER_METHODS =
            setOf("showH", "showVolumePanelH", "prepareShow", "updateExpandedH", "dismissH")
    }
}
