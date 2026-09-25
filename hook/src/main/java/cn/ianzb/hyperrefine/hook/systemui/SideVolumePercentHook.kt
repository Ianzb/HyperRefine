package cn.ianzb.hyperrefine.hook.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.WeakHashMap

/**
 * 侧边音量条（按音量键呼出）百分比数值显示。
 *
 * 目标：`com.android.systemui.miui.volume.VolumePanelViewController` / `VolumeColumn`（插件内）
 * - `updateVolumeColumnSliderH` / `updateSuperVolumeView` 之后写入百分比文本并维护可见性
 * - `showVolumePanelH` / `initPanelView` 之后刷新一次（首次弹出未改音量时也能显示）
 * - 应用「长按展开、隐藏三个点」交互
 *
 * 位置开关（`side_volume_inside`）：
 * - 关闭：收起态在音量区域上方悬浮显示（`miui_super_volume_collapsed`）；
 * - 开启：改为音量条内部上方显示（`miui_super_volume_expanded`）。
 *
 * 交互开关（`side_volume_longpress`，默认关闭）：长按音量条展开音量面板，并隐藏三个点按钮。
 *
 * 注意：`VolumeColumn.initColumn` 会把自身的拖拽监听
 * `VolumeColumn.onTouchListener` 通过 `view.setOnTouchListener(...)` 设在音量栏根视图上。
 * 直接 `setOnTouchListener` 会覆盖它导致拖动进度卡住，因此这里用包装器**保留并转调**原监听。
 */
class SideVolumePercentHook : BaseHook() {

    override val key: String = KEY

    override fun init() {
        val systemCl = target.classLoader ?: return
        PluginLoader.register(key, systemCl) { pluginCl ->
            val controller = Reflect.findClassIfExists(CONTROLLER_CLASS, pluginCl)
            if (controller == null) {
                HookHelper.log("$tag: $CONTROLLER_CLASS not found")
                return@register
            }
            HookHelper.log("$tag: $CONTROLLER_CLASS found (plugin CL)")
            runCatching {
                controller.declaredMethods
                    .filter { it.name == "updateVolumeColumnSliderH" || it.name == "updateSuperVolumeView" }
                    .forEach { method ->
                        HookHelper.hookAfter(method) { param ->
                            val thiz = param.thisObject ?: return@hookAfter
                            update(thiz, param.args.getOrNull(0))
                        }
                    }
                // 首次弹出时系统在展示后才异步补列 / 数值，立即 + 延迟各刷新一次。
                controller.declaredMethods
                    .filter { it.name == "showVolumePanelH" || it.name == "initPanelView" }
                    .forEach { method ->
                        HookHelper.hookAfter(method) { param ->
                            val thiz = param.thisObject ?: return@hookAfter
                            applyLongPress(thiz)
                            update(thiz, null)
                            val view = runCatching { Reflect.getObjectField(thiz, "mVolumeView") }.getOrNull() as? View
                            view?.post { update(thiz, null) }
                            view?.postDelayed({ update(thiz, null) }, 150L)
                            view?.postDelayed({ update(thiz, null) }, 400L)
                        }
                    }
            }.onFailure { HookHelper.log("$tag: hook failed", it) }

            // 三个点按钮在展开状态变化时会被系统重新显示，这里持续保持隐藏。
            runCatching {
                Reflect.findClassIfExists(DIALOG_VIEW_CLASS, pluginCl)
                    ?.declaredMethods
                    ?.filter { it.name == "updateExpandButtonH" }
                    ?.forEach { method ->
                        HookHelper.hookAfter(method) { param ->
                            val view = param.thisObject ?: return@hookAfter
                            if (longPressEnabled()) {
                                (Reflect.getObjectField(view, "mExpandButton") as? View)
                                    ?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
                            }
                        }
                    }
            }.onFailure { HookHelper.log("$tag: dialog hook failed", it) }
        }
    }

    private fun update(controller: Any, columnArg: Any?) {
        // 交互监听只在面板展示 / 初始化时安装一次，避免拖动逐帧重复反射。
        if (!HookPrefs.getBoolean(KEY, false)) return
        runCatching {
            val expanded = runCatching { Reflect.getObjectField(controller, "mExpanded") }.getOrNull() as? Boolean ?: false
            val inside = HookPrefs.getBoolean(INSIDE_KEY, false)
            if (lastInside != inside) {
                lastInside = inside
                HookHelper.log("$tag: inside=$inside, expanded=$expanded")
            }

            // 收集相关音量栏：传入栏 + mColumns + temp 栏，逐个用各自的真实数值写入，避免动画中途停住。
            val columns = buildList {
                columnArg?.let { add(it) }
                (runCatching { Reflect.getObjectField(controller, "mColumns") }.getOrNull() as? List<*>)
                    ?.forEach { c -> c?.let { add(it) } }
                if (!expanded) {
                    runCatching { Reflect.getObjectField(controller, "mTempColumn") }.getOrNull()?.let { add(it) }
                }
            }.distinctBy { System.identityHashCode(it) }
            if (columns.isEmpty()) return@runCatching

            val showColumn = expanded || inside
            columns.forEach { c ->
                val text = runCatching { Reflect.callMethod(c, "getSuperVolume") }.getOrNull() as? TextView
                    ?: return@forEach
                text.setVisible(showColumn)
                if (showColumn) {
                    val (value, max) = valueOf(c) ?: return@forEach
                    val icon = runCatching { Reflect.callMethod(c, "getIcon") }.getOrNull() as? View
                    val iconColorRes = runCatching { Reflect.callMethod(c, "getIconColorRes") }.getOrNull() as? Int
                    PercentText.show(text, value, max, PREF, icon, iconColorRes, HIGHLIGHT_COLOR)
                }
            }

            // 悬浮文本（`miui_super_volume_collapsed`）：收起态且未开启「条内显示」时显示（仅当前激活流）。
            val activeStream = runCatching { Reflect.getObjectField(controller, "mActiveStream") }.getOrNull() as? Int ?: -2
            val activeColumn = columnArg
                ?: columns.firstOrNull { runCatching { Reflect.callMethod(it, "getStream") }.getOrNull() == activeStream }
            val panelText = runCatching { Reflect.getObjectField(controller, "mSuperVolume") }.getOrNull() as? TextView
            val panelBg = runCatching { Reflect.getObjectField(controller, "mSuperVolumeBg") }.getOrNull() as? View
            val pair = if (!inside && !expanded) activeColumn?.let { valueOf(it) } else null
            panelBg?.setVisible(pair != null)
            if (panelText != null) {
                if (pair != null) {
                    PercentText.show(panelText, pair.first, pair.second, PREF, null, null, HIGHLIGHT_COLOR)
                } else {
                    panelText.setVisible(false)
                }
            }
        }.onFailure { HookHelper.log("$tag: update failed", it) }
    }

    /**
     * 取某音量栏的当前数值。优先取真实音量等级（`ss.level` / `ss.levelMax`，随音量实时更新、不会停在动画中间），
     * 其次取滑条当前进度。
     */
    private fun valueOf(column: Any): Pair<Int, Int>? {
        PercentText.levelOf(runCatching { Reflect.callMethod(column, "getSs") }.getOrNull())?.let { return it }
        return PercentText.progressOf(runCatching { Reflect.callMethod(column, "getSlider") }.getOrNull())
    }

    /**
     * 长按音量条展开音量面板，并隐藏三个点按钮（避免与百分比数值重叠）。
     *
     * MIUI 的拖拽逻辑位于 `VolumeColumn.onTouchListener`，被设在音量栏根视图上；这里用
     * [LongPressTouchListener] 包装原监听（保留其拖拽行为），并用 [GestureDetector] 识别长按。
     */
    private fun applyLongPress(controller: Any) {
        runCatching {
            val volumeView = Reflect.getObjectField(controller, "mVolumeView") as? View ?: return
            if (longPressEnabled()) {
                findExpandButton(volumeView)?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }
            }

            val targets = buildList {
                add(volumeView)
                (runCatching { Reflect.getObjectField(controller, "mColumns") }.getOrNull() as? List<*>)?.forEach { c ->
                    c ?: return@forEach
                    (runCatching { Reflect.getObjectField(c, "view") }.getOrNull() as? View)?.let { add(it) }
                    (runCatching { Reflect.getObjectField(c, "slider") }.getOrNull() as? View)?.let { add(it) }
                }
                val tempColumn = runCatching { Reflect.getObjectField(controller, "mTempColumn") }.getOrNull()
                (tempColumn?.let { runCatching { Reflect.getObjectField(it, "view") }.getOrNull() } as? View)?.let { add(it) }
                (tempColumn?.let { runCatching { Reflect.getObjectField(it, "slider") }.getOrNull() } as? View)?.let { add(it) }
            }.distinctBy { System.identityHashCode(it) }

            targets.forEach { it.installLongPress(controller) }
        }.onFailure { HookHelper.log("$tag: long-press setup failed", it) }
    }

    /** 按资源名查找三个点按钮（避免使用已弃用的 `Resources.getIdentifier`）。 */
    private fun findExpandButton(root: View): View? {
        if (root.id != View.NO_ID &&
            runCatching { root.resources.getResourceEntryName(root.id) }.getOrNull() == "volume_expand_button"
        ) {
            return root
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findExpandButton(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** 每个视图只安装一次；若被系统重新覆盖（非本包装器）则重新包装当前监听。 */
    private fun View.installLongPress(controller: Any) {
        // `View.getOnTouchListener()` 为隐藏 API，需反射获取，以保留 MIUI 原有的拖拽监听。
        val current = runCatching { Reflect.callMethod(this, "getOnTouchListener") }.getOrNull() as? View.OnTouchListener
        if (current is LongPressTouchListener) return
        val detector = detectors.getOrPut(this) { createDetector(context, controller) }
        setOnTouchListener(LongPressTouchListener(detector, current))
        HookHelper.log("$tag: long-press installed on ${javaClass.simpleName} (original=${current != null})")
    }

    private fun createDetector(context: Context, controller: Any): GestureDetector =
        GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (!longPressEnabled()) return
                HookHelper.log("$tag: long-press -> expand")
                runCatching { Reflect.callMethod(controller, "onExpandClicked") }
                    .onFailure { HookHelper.log("$tag: onExpandClicked failed", it) }
            }
        })

    private fun longPressEnabled(): Boolean = HookPrefs.getBoolean(LONGPRESS_KEY, false)

    private fun View.setVisible(visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (visibility != target) visibility = target
    }

    /** 保留并转调 MIUI 原有的触控监听（拖拽），同时把事件喂给长按检测。 */
    @SuppressLint("ClickableViewAccessibility")
    private class LongPressTouchListener(
        private val detector: GestureDetector,
        private val original: View.OnTouchListener?,
    ) : View.OnTouchListener {
        override fun onTouch(v: View, event: MotionEvent): Boolean {
            detector.onTouchEvent(event)
            return original?.onTouch(v, event) ?: false
        }
    }

    companion object {
        const val KEY = "side_volume_percent"
        const val PREF = "side_volume"
        const val INSIDE_KEY = "side_volume_inside"
        const val LONGPRESS_KEY = "side_volume_longpress"
        const val CONTROLLER_CLASS = "com.android.systemui.miui.volume.VolumePanelViewController"
        const val DIALOG_VIEW_CLASS = "com.android.systemui.miui.volume.MiuiVolumeDialogView"

        /** 兜底高值喇叭图标蓝色。 */
        val HIGHLIGHT_COLOR: Int = 0xFF3482FF.toInt()

        private val detectors = WeakHashMap<View, GestureDetector>()

        private var lastInside: Boolean? = null
    }
}
