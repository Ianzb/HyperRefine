package cn.ianzb.hyperrefine.hook.systemui

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 控制中心亮度条百分比数值显示。
 *
 * 目标（插件内）：
 * - 一级亮度条 `panel.main.brightness.BrightnessSliderController.updateIconProgress`
 * - 二级亮度条 `panel.secondary.brightness.BrightnessPanelSliderDelegate.updateIconProgress`
 */
class CcBrightnessPercentHook : BaseHook() {

    override val key: String = KEY

    override fun init() {
        val systemCl = target.classLoader ?: return
        PluginLoader.register(key, systemCl) { pluginCl ->
            hookMain(pluginCl)
            hookSecondary(pluginCl)
        }
    }

    private fun hookMain(pluginCl: ClassLoader) {
        val controller = Reflect.findClassIfExists(MAIN_CLASS, pluginCl)
        if (controller == null) {
            HookHelper.log("$tag: $MAIN_CLASS not found")
            return
        }
        runCatching {
            controller.declaredMethods
                .filter { it.name == "updateIconProgress" }
                .forEach { method ->
                    HookHelper.hookAfter(method) { param ->
                        if (!HookPrefs.getBoolean(KEY, false)) return@hookAfter
                        val thiz = param.thisObject ?: return@hookAfter
                        runCatching {
                            val holder = Reflect.callMethod(thiz, "getSliderHolder") ?: return@runCatching
                            val topText = PercentText.findTopText(holder) ?: return@runCatching
                            val slider = Reflect.callMethod(thiz, "getSlider")
                            val (value, max) = PercentText.progressOf(slider) ?: return@runCatching
                            val icon = runCatching { Reflect.callMethod(holder, "getIcon") }.getOrNull() as? android.view.View
                            PercentText.show(topText, value, max, PREF, icon, fallbackColor = FALLBACK_COLOR)
                        }.onFailure { HookHelper.log("$tag: main update failed", it) }
                    }
                }
        }.onFailure { HookHelper.log("$tag: main hook failed", it) }
    }

    private fun hookSecondary(pluginCl: ClassLoader) {
        val delegate = Reflect.findClassIfExists(SECONDARY_CLASS, pluginCl)
        if (delegate == null) {
            HookHelper.log("$tag: $SECONDARY_CLASS not found (secondary panel unused?)")
            return
        }
        runCatching {
            delegate.declaredMethods
                .filter { it.name == "updateIconProgress" }
                .forEach { method ->
                    HookHelper.hookAfter(method) { param ->
                        if (!HookPrefs.getBoolean(KEY, false)) return@hookAfter
                        val thiz = param.thisObject ?: return@hookAfter
                        runCatching {
                            val inner = Reflect.callMethod(thiz, "getVToggleSliderInner") ?: return@runCatching
                            val topText = (inner as? android.view.View)?.let { PercentText.findViewByName(it, "top_text") }
                                ?: PercentText.findTopText(runCatching { Reflect.callMethod(thiz, "getSliderBinding") }.getOrNull())
                                ?: return@runCatching
                            val slider = Reflect.callMethod(thiz, "getVSlider")
                            val (value, max) = PercentText.progressOf(slider) ?: return@runCatching
                            val icon = runCatching { Reflect.callMethod(thiz, "getVIcon") }.getOrNull() as? android.view.View
                            PercentText.show(topText, value, max, PREF, icon, fallbackColor = FALLBACK_COLOR)
                        }.onFailure { HookHelper.log("$tag: secondary update failed", it) }
                    }
                }
        }.onFailure { HookHelper.log("$tag: secondary hook failed", it) }
    }

    companion object {
        const val KEY = "cc_brightness_percent"
        const val PREF = "cc_brightness"
        const val MAIN_CLASS = "miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController"
        const val SECONDARY_CLASS = "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate"

        /** 太阳图标金色（跟随取不到时的兜底）。 */
        val FALLBACK_COLOR: Int = 0xFFFFC531.toInt()
    }
}
