package cn.ianzb.hyperrefine.hook.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.status.HookStatusReporter
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.WeakHashMap

/**
 * 侧边音量条「长按打开音量面板」交互。
 *
 * 独立成一条 hook，**不再依赖「侧边音量条百分比」开关**：
 * 只要开启本功能即可长按音量条展开音量面板，并隐藏音量条上的三个点按钮。
 *
 * MIUI 的拖拽逻辑位于 `VolumeColumn.onTouchListener`，被设在音量栏根视图上；这里用
 * [LongPressTouchListener] 包装原监听（保留其拖拽行为），并用 [GestureDetector] 识别长按。
 */
class SideVolumeLongPressHook : BaseHook() {

    override val key: String = KEY

    override fun init() {
        val systemCl = target.classLoader ?: return
        PluginLoader.register(key, systemCl) { pluginCl ->
            val controller = Reflect.findClassIfExists(CONTROLLER_CLASS, pluginCl)
            if (controller == null) {
                HookHelper.log("$tag: $CONTROLLER_CLASS not found")
                return@register
            }
            runCatching {
                controller.declaredMethods
                    .filter { it.name == "showVolumePanelH" || it.name == "initPanelView" }
                    .forEach { method ->
                        HookHelper.hookAfter(method) { param ->
                            val thiz = param.thisObject ?: return@hookAfter
                            if (longPressEnabled()) applyLongPress(thiz)
                        }
                    }
            }.onFailure { HookHelper.log("$tag: hook controller failed", it) }

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

            HookStatusReporter.markInstalled(key)
            HookHelper.log("$tag: hooked long-press")
        }
    }

    private fun applyLongPress(controller: Any) {
        runCatching {
            val volumeView = Reflect.getObjectField(controller, "mVolumeView") as? View ?: return
            findExpandButton(volumeView)?.let { if (it.visibility != View.GONE) it.visibility = View.GONE }

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
        if (root is android.view.ViewGroup) {
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
                // 已展开时不再处理：`onExpandClicked` 是开关式的，重复调用会把面板又收回。
                val expanded = runCatching { Reflect.getObjectField(controller, "mExpanded") as? Boolean }
                    .getOrNull() ?: false
                if (expanded) return
                HookHelper.log("$tag: long-press -> expand")
                runCatching { Reflect.callMethod(controller, "onExpandClicked") }
                    .onFailure { HookHelper.log("$tag: onExpandClicked failed", it) }
            }
        })

    private fun longPressEnabled(): Boolean = HookPrefs.getBoolean(KEY, false)

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
        const val KEY = "side_volume_longpress"
        const val CONTROLLER_CLASS = "com.android.systemui.miui.volume.VolumePanelViewController"
        const val DIALOG_VIEW_CLASS = "com.android.systemui.miui.volume.MiuiVolumeDialogView"

        private val detectors = WeakHashMap<View, GestureDetector>()
    }
}
