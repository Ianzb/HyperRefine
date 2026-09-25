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
 * - `updateVolumeColumnSliderH` 之后写入各栏 `superVolume` 文本（展开态）
 * - `updateSuperVolumeView` 之后维护收起态 `mSuperVolume` 文本与可见性
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
            runCatching {
                controller.declaredMethods
                    .filter { it.name == "updateVolumeColumnSliderH" || it.name == "updateSuperVolumeView" }
                    .forEach { method ->
                        HookHelper.hookAfter(method) { param ->
                            val thiz = param.thisObject ?: return@hookAfter
                            update(thiz, param.args.getOrNull(0))
                        }
                    }
            }.onFailure { HookHelper.log("$tag: hook failed", it) }
        }
    }

    private fun update(controller: Any, columnArg: Any?) {
        if (!HookPrefs.getBoolean(KEY, false)) return
        runCatching {
            val column = columnArg ?: runCatching { Reflect.callMethod(controller, "getActiveColumn") }.getOrNull()
                ?: return
            val (value, max) = PercentText.progressOf(runCatching { Reflect.callMethod(column, "getSlider") }.getOrNull())
                ?: PercentText.levelOf(runCatching { Reflect.callMethod(column, "getSs") }.getOrNull())
                ?: return

            val icon = runCatching { Reflect.callMethod(column, "getIcon") }.getOrNull() as? View
            val iconColorRes = runCatching { Reflect.callMethod(column, "getIconColorRes") }.getOrNull() as? Int
            val expanded = runCatching { Reflect.getObjectField(controller, "mExpanded") }.getOrNull() as? Boolean ?: false

            // 展开态：各栏自带的 superVolume 文本
            val columnText = runCatching { Reflect.callMethod(column, "getSuperVolume") }.getOrNull() as? TextView
            if (columnText != null) {
                if (columnText.visibility != (if (expanded) View.VISIBLE else View.GONE)) {
                    columnText.visibility = if (expanded) View.VISIBLE else View.GONE
                }
                if (expanded) {
                    PercentText.show(columnText, value, max, PREF, icon, iconColorRes, FALLBACK_COLOR)
                }
            }

            // 收起态：面板顶部的超级音量文本（仅当前激活流）
            val stream = runCatching { Reflect.callMethod(column, "getStream") }.getOrNull() as? Int ?: -1
            val activeStream = runCatching { Reflect.getObjectField(controller, "mActiveStream") }.getOrNull() as? Int ?: -2
            val panelText = runCatching { Reflect.getObjectField(controller, "mSuperVolume") }.getOrNull() as? TextView
            val panelBg = runCatching { Reflect.getObjectField(controller, "mSuperVolumeBg") }.getOrNull() as? View
            if (panelBg != null && panelBg.visibility != (if (expanded) View.GONE else View.VISIBLE)) {
                panelBg.visibility = if (expanded) View.GONE else View.VISIBLE
            }
            if (panelText != null && stream == activeStream) {
                PercentText.show(panelText, value, max, PREF, icon, iconColorRes, FALLBACK_COLOR)
            }
        }.onFailure { HookHelper.log("$tag: update failed", it) }
    }

    companion object {
        const val KEY = "side_volume_percent"
        const val PREF = "side_volume"
        const val CONTROLLER_CLASS = "com.android.systemui.miui.volume.VolumePanelViewController"

        /** 喇叭图标蓝色（跟随取不到时的兜底）。 */
        val FALLBACK_COLOR: Int = 0xFF3482FF.toInt()
    }
}
