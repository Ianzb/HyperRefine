package cn.ianzb.hyperrefine.hook.systemui

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.misound.AppVolumeKeys
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.status.HookStatusReporter
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * 侧边音量条三个点按钮的交互模式。
 *
 * 三态：正常显示、仅隐藏、隐藏并长按。后两者都会隐藏三个点；只有「隐藏并长按」
 * 才把长按手势装到音量条上。旧配置里的布尔值在迁移前也兼容读取。
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
                            if (hidesExpandButton()) applyLongPress(thiz)
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
                            if (hidesExpandButton()) {
                                (Reflect.getObjectField(view, "mExpandButton") as? View)
                                    ?.let { applyExpandButtonState(it) }
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
            applyExpandButtonState(findExpandButton(volumeView))
            if (!usesLongPress()) return

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

            targets.forEach { it.installLongPress(controller, volumeView) }
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

    /**
     * 仅隐藏模式保留按钮的可点击区域（alpha=0），长按模式才真正移除按钮。
     */
    private fun applyExpandButtonState(button: View?) {
        button ?: return
        when (sideMode()) {
            AppVolumeKeys.SIDE_MODE_LONG_PRESS -> {
                if (button.visibility != View.GONE) button.visibility = View.GONE
            }
            AppVolumeKeys.SIDE_MODE_HIDE -> {
                if (button.visibility != View.VISIBLE) button.visibility = View.VISIBLE
                if (button.alpha != 0f) button.alpha = 0f
            }
            else -> {
                if (button.visibility != View.VISIBLE) button.visibility = View.VISIBLE
                if (button.alpha != 1f) button.alpha = 1f
            }
        }
    }

    /** 每个视图只安装一次；若被系统重新覆盖（非本包装器）则重新包装当前监听。 */
    private fun View.installLongPress(controller: Any, pressView: View) {
        // `View.getOnTouchListener()` 为隐藏 API，需反射获取，以保留 MIUI 原有的拖拽监听。
        val current = runCatching { Reflect.callMethod(this, "getOnTouchListener") }.getOrNull() as? View.OnTouchListener
        if (current is LongPressTouchListener) return
        val detector = detectors.getOrPut(this) { createDetector(context, controller, pressView) }
        setOnTouchListener(LongPressTouchListener(detector, current))
        HookHelper.log("$tag: long-press installed on ${javaClass.simpleName} (original=${current != null})")
    }

    private fun createDetector(context: Context, controller: Any, pressView: View): GestureDetector {
        // 用弱引用保存音量条根视图，避免 GestureDetector 反向持有 View 导致 WeakHashMap 的 key 无法回收。
        val pressRef = WeakReference(pressView)
        return GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onLongPress(e: MotionEvent) {
                if (!usesLongPress()) return
                // 已展开时不再处理：`onExpandClicked` 是开关式的，重复调用会把面板又收回。
                val expanded = runCatching { Reflect.getObjectField(controller, "mExpanded") as? Boolean }
                    .getOrNull() ?: false
                if (expanded) return

                val target = pressRef.get()
                if (target == null || !target.isAttachedToWindow) {
                    expand(controller)
                    return
                }
                playPressFeedback(target) { expand(controller) }
            }
        })
    }

    /**
     * 长按确认时先给按压反馈，再打开二级面板。
     * 动画只作用于音量条根视图，不影响滑动条自身的拖拽与布局测量。
     */
    private fun playPressFeedback(target: View, onPressed: () -> Unit) {
        // 忽略音量条自身可能设置的 haptic 开关，但仍遵循系统全局触感设置。
        target.performHapticFeedback(
            HapticFeedbackConstants.LONG_PRESS,
            HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
        )
        if (!target.isAttachedToWindow) {
            onPressed()
            return
        }
        // 必须先完全回弹再触发官方展开动画：官方展开本身也会缩放/平移音量列，
        // 两个缩放同时进行会让整条音量条在展开瞬间抽搐。
        target.animate().cancel()
        target.animate()
            .scaleX(PRESS_SCALE)
            .scaleY(PRESS_SCALE)
            .setDuration(PRESS_DOWN_MS)
            .withEndAction {
                if (!target.isAttachedToWindow) {
                    onPressed()
                    return@withEndAction
                }
                target.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(PRESS_UP_MS)
                    .withEndAction {
                        if (target.isAttachedToWindow) onPressed()
                    }
                    .start()
            }
            .start()
    }

    private fun expand(controller: Any) {
        HookHelper.log("$tag: long-press -> expand")
        runCatching { Reflect.callMethod(controller, "onExpandClicked") }
            .onFailure { HookHelper.log("$tag: onExpandClicked failed", it) }
    }

    private fun sideMode(): String {
        // 默认 null：`getString` 在旧 Boolean 配置上会返回默认值而非抛异常，
        // 若默认 "off" 会让下面的旧布尔兼容分支永远不执行。
        val stored = HookPrefs.getString(KEY, null)
        if (stored == AppVolumeKeys.SIDE_MODE_OFF ||
            stored == AppVolumeKeys.SIDE_MODE_HIDE ||
            stored == AppVolumeKeys.SIDE_MODE_LONG_PRESS
        ) {
            return stored
        }
        // 迁移前旧配置是 Boolean：true 等同原来的「隐藏 + 长按」。
        val legacy = runCatching { HookPrefs.getBoolean(KEY, false) }.getOrDefault(false)
        return if (legacy) AppVolumeKeys.SIDE_MODE_LONG_PRESS else AppVolumeKeys.SIDE_MODE_OFF
    }

    private fun hidesExpandButton(): Boolean = sideMode() != AppVolumeKeys.SIDE_MODE_OFF

    private fun usesLongPress(): Boolean = sideMode() == AppVolumeKeys.SIDE_MODE_LONG_PRESS

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
        /** 供 `SystemUiLoad` 判断是否需要挂载；该键已从 Boolean 改为三态字符串。 */
        fun isEnabled(): Boolean {
            val stored = HookPrefs.getString(KEY, null)
            if (stored == AppVolumeKeys.SIDE_MODE_HIDE ||
                stored == AppVolumeKeys.SIDE_MODE_LONG_PRESS
            ) {
                return true
            }
            if (stored == AppVolumeKeys.SIDE_MODE_OFF) return false
            return HookPrefs.getBoolean(KEY, false)
        }

        const val KEY = "side_volume_longpress"
        const val CONTROLLER_CLASS = "com.android.systemui.miui.volume.VolumePanelViewController"
        const val DIALOG_VIEW_CLASS = "com.android.systemui.miui.volume.MiuiVolumeDialogView"
        private const val PRESS_SCALE = 0.94f
        private const val PRESS_DOWN_MS = 55L
        private const val PRESS_UP_MS = 85L

        private val detectors = WeakHashMap<View, GestureDetector>()
    }
}
