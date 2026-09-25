package cn.ianzb.hyperrefine.hook.systemui

import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 控制中心音量条百分比数值显示。
 *
 * 目标：`miui.systemui.controlcenter.panel.main.volume.VolumeSliderController`（插件内）
 * 在 `updateIconProgress` / `updateSuperVolume` 之后，把当前音量百分比写入滑块的 `top_text`。
 */
class CcVolumePercentHook : BaseHook() {

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
                    .filter { it.name == "updateIconProgress" || it.name == "updateSuperVolume" }
                    .forEach { method ->
                        HookHelper.hookAfter(method) { param -> param.thisObject?.let { update(it) } }
                    }
            }.onFailure { HookHelper.log("$tag: hook failed", it) }
        }
    }

    private fun update(controller: Any) {
        if (!HookPrefs.getBoolean(KEY, false)) return
        runCatching {
            val holder = Reflect.callMethod(controller, "getSliderHolder")
                ?: Reflect.callMethod(controller, "getHolder")
                ?: return
            val topText = PercentText.findTopText(holder) ?: return
            val (value, max) = PercentText.progressOf(runCatching { Reflect.callMethod(controller, "getSlider") }.getOrNull())
                ?: run {
                    val v = Reflect.callMethod(controller, "getTargetValue") as? Int ?: return
                    val m = runCatching { Reflect.getObjectField(controller, "sliderMaxValue") }.getOrNull() as? Int ?: return
                    v to m
                }
            val icon = runCatching { Reflect.callMethod(holder, "getIcon") }.getOrNull() as? View
            PercentText.show(
                tv = topText,
                value = value,
                max = max,
                pref = PREF,
                icon = icon,
                highlightColor = HIGHLIGHT_COLOR,
            )
        }.onFailure { HookHelper.log("$tag: update failed", it) }
    }

    companion object {
        const val KEY = "cc_volume_percent"
        const val PREF = "cc_volume"
        const val CONTROLLER_CLASS = "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController"

        /** 高值喇叭图标蓝色（对应插件 `color/toggle_slider_volume_icon_color`）。 */
        val HIGHLIGHT_COLOR: Int = 0xFF3482FF.toInt()
    }
}
