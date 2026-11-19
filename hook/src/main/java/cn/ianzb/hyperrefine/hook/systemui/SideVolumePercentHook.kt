package cn.ianzb.hyperrefine.hook.systemui

import android.view.View
import android.widget.TextView
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 侧边音量条（按音量键呼出）百分比数值显示。
 *
 * 目标：`com.android.systemui.miui.volume.VolumePanelViewController` / `VolumeColumn`（插件内）
 * - `updateVolumeColumnSliderH` / `updateSuperVolumeView` 之后写入百分比文本并维护可见性
 * - 打开、初始化及切换展开状态时刷新，拖动期间每帧最多刷新一次
 *
 * 位置开关（`side_volume_inside`）：
 * - 关闭：收起态在音量区域上方悬浮显示（`miui_super_volume_collapsed`）；
 * - 开启：改为音量条内部上方显示（`miui_super_volume_expanded`）。
 *
 */
class SideVolumePercentHook : BaseHook() {

    override val key: String = KEY
    private val pendingUpdates = java.util.WeakHashMap<View, Boolean>()

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
                    .filter { it.name in UPDATE_METHODS }
                    .forEach { method ->
                        HookHelper.hookAfter(method) { param ->
                            val thiz = param.thisObject ?: return@hookAfter
                            scheduleUpdate(thiz)
                        }
                    }
            }.onFailure { HookHelper.log("$tag: hook failed", it) }
        }
    }

    private fun scheduleUpdate(controller: Any) {
        if (!HookPrefs.getBoolean(KEY, false)) return
        val view = runCatching { Reflect.getObjectField(controller, "mVolumeView") }.getOrNull() as? View ?: return
        if (pendingUpdates.put(view, true) == true) return
        // 原生拖动可在同一帧触发两个回调，合并后再读取最终状态。
        view.postOnAnimation {
            pendingUpdates.remove(view)
            if (view.isAttachedToWindow && HookPrefs.getBoolean(KEY, false)) update(controller)
        }
    }

    private fun update(controller: Any) {
        runCatching {
            val expanded = runCatching { Reflect.getObjectField(controller, "mExpanded") }.getOrNull() as? Boolean ?: false
            val inside = HookPrefs.getBoolean(INSIDE_KEY, false)
            if (lastInside != inside) {
                lastInside = inside
                HookHelper.log("$tag: inside=$inside, expanded=$expanded")
            }

            val columns = (Reflect.getObjectField(controller, "mColumns") as? List<*>).orEmpty()
            val temp = if (expanded) null else Reflect.getObjectField(controller, "mTempColumn")

            val showColumn = expanded || inside
            fun updateColumn(c: Any) {
                val text = runCatching { Reflect.callMethod(c, "getSuperVolume") }.getOrNull() as? TextView
                    ?: return
                val columnView = Reflect.callMethod(c, "getView") as? View ?: return
                if (!showColumn || columnView.visibility != View.VISIBLE) {
                    text.setVisible(false)
                    return
                }
                val (value, max) = valueOf(c) ?: return
                val icon = runCatching { Reflect.callMethod(c, "getIcon") }.getOrNull() as? View
                val iconColorRes = runCatching { Reflect.callMethod(c, "getIconColorRes") }.getOrNull() as? Int
                PercentText.show(text, value, max, PREF, icon, iconColorRes, HIGHLIGHT_COLOR)
                PercentText.applyVerticalPosition(text, PREF)
            }
            columns.forEach { if (it != null) updateColumn(it) }
            if (temp != null && columns.none { it === temp }) updateColumn(temp)

            // 悬浮文本（`miui_super_volume_collapsed`）：收起态且未开启「条内显示」时显示（仅当前激活流）。
            val panelText = runCatching { Reflect.getObjectField(controller, "mSuperVolume") }.getOrNull() as? TextView
            val panelBg = runCatching { Reflect.getObjectField(controller, "mSuperVolumeBg") }.getOrNull() as? View
            val pair = if (!inside && !expanded) {
                val tempVisible = Reflect.getObjectField(controller, "mShouldTempBeVisible") == true &&
                    temp != null && (Reflect.callMethod(temp, "getView") as? View)?.visibility == View.VISIBLE
                val active = if (tempVisible) temp else Reflect.callMethod(controller, "getActiveColumn")
                active?.let(::valueOf)
            } else null
            panelBg?.setVisible(pair != null)
            if (panelText != null) {
                if (pair != null) {
                    PercentText.show(
                        panelText, pair.first, pair.second, PREF, null, null, HIGHLIGHT_COLOR,
                        lowVolumeColor = 0xFF344054.toInt(),
                    )
                    PercentText.applyVerticalPosition(panelText, PREF)
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

    private fun View.setVisible(visible: Boolean) {
        val target = if (visible) View.VISIBLE else View.GONE
        if (visibility != target) visibility = target
    }

    companion object {
        const val KEY = "side_volume_percent"
        const val PREF = "side_volume"
        const val INSIDE_KEY = "side_volume_inside"
        const val CONTROLLER_CLASS = "com.android.systemui.miui.volume.VolumePanelViewController"

        /** 兜底高值喇叭图标蓝色。 */
        val HIGHLIGHT_COLOR: Int = 0xFF3482FF.toInt()
        private val UPDATE_METHODS = setOf(
            "updateVolumeColumnSliderH", "updateSuperVolumeView", "showVolumePanelH", "initPanelView", "updateExpandedH",
        )

        private var lastInside: Boolean? = null
    }
}
