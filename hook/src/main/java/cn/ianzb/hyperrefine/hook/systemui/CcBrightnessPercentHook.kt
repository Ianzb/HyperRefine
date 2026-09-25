package cn.ianzb.hyperrefine.hook.systemui

import android.view.View
import android.widget.TextView
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import kotlin.math.abs

/**
 * 控制中心亮度条百分比数值显示。
 *
 * 目标（插件内）：
 * - 一级亮度条 `panel.main.brightness.BrightnessSliderController.updateIconProgress`
 * - 二级亮度条 `panel.secondary.brightness.BrightnessPanelSliderDelegate.updateIconProgress`
 *
 * 二级亮度面板展开 / 收起时，`ToggleSliderView` 通过 `setLeftTopRightBottom` 直接改尺寸（不触发布局），
 * 其子 `top_text` 的水平居中不会随宽度更新，导致平移动画中水平位置错误；这里在动画每帧按容器当前宽度
 * 重新水平居中该文本。
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
                            val icon = runCatching { Reflect.callMethod(holder, "getIcon") }.getOrNull() as? View
                            PercentText.show(topText, value, max, PREF, icon, highlightColor = HIGHLIGHT_COLOR)
                        }.onFailure { HookHelper.log("$tag: main update failed", it) }
                    }
                }
        }.onFailure { HookHelper.log("$tag: main hook failed", it) }
    }

    private fun hookSecondary(pluginCl: ClassLoader) {
        val delegate = Reflect.findClassIfExists(SECONDARY_CLASS, pluginCl)
        if (delegate == null) {
            HookHelper.log("$tag: $SECONDARY_CLASS not found (secondary panel unused?)")
        } else {
            runCatching {
                delegate.declaredMethods
                    .filter { it.name == "updateIconProgress" }
                    .forEach { method ->
                        HookHelper.hookAfter(method) { param ->
                            if (!HookPrefs.getBoolean(KEY, false)) return@hookAfter
                            val thiz = param.thisObject ?: return@hookAfter
                            runCatching {
                                val topText = secondaryTopText(thiz) ?: return@runCatching
                                val slider = Reflect.callMethod(thiz, "getVSlider")
                                val (value, max) = PercentText.progressOf(slider) ?: return@runCatching
                                val icon = runCatching { Reflect.callMethod(thiz, "getVIcon") }.getOrNull() as? View
                                PercentText.show(topText, value, max, PREF, icon, highlightColor = HIGHLIGHT_COLOR)
                            }.onFailure { HookHelper.log("$tag: secondary update failed", it) }
                        }
                    }
            }.onFailure { HookHelper.log("$tag: secondary hook failed", it) }
        }

        // 动画每帧重新水平居中 top_text（仅二级亮度面板有该平移动画）。
        val animator = Reflect.findClassIfExists(ANIMATOR_CLASS, pluginCl)
        if (animator == null) {
            HookHelper.log("$tag: $ANIMATOR_CLASS not found")
            return
        }
        runCatching {
            animator.declaredMethods
                .filter { it.name == "frameCallback" }
                .forEach { method ->
                    HookHelper.hookAfter(method) { param ->
                        if (!HookPrefs.getBoolean(KEY, false)) return@hookAfter
                        val anim = param.thisObject ?: return@hookAfter
                        val delegateObj = runCatching { Reflect.getObjectField(anim, "sliderDelegate") }.getOrNull()
                            ?: return@hookAfter
                        recenterTopText(delegateObj)
                    }
                }
        }.onFailure { HookHelper.log("$tag: animator hook failed", it) }
    }

    private fun secondaryTopText(delegate: Any): TextView? {
        val binding = runCatching { Reflect.callMethod(delegate, "getSliderBinding") }.getOrNull()
        (binding?.let { runCatching { Reflect.getObjectField(it, "topText") }.getOrNull() } as? TextView)
            ?.let { return it }
        return (runCatching { Reflect.callMethod(delegate, "getVToggleSliderInner") }.getOrNull() as? View)
            ?.let { PercentText.findViewByName(it, "top_text") }
    }

    /**
     * 让百分比文本相对 `ToggleSliderView` 当前宽度水平居中。
     *
     * `setLeftTopRightBottom` 只改容器尺寸、不触发布局，子文本的水平居中会停留在旧宽度上；
     * 这里用容器当前宽度与文本当前中心手动计算 `translationX` 补偿。
     */
    private fun recenterTopText(delegate: Any) {
        runCatching {
            val toggle = runCatching { Reflect.callMethod(delegate, "getVToggleSlider") }.getOrNull() as? View
                ?: return@runCatching
            val topText = secondaryTopText(delegate) ?: return@runCatching
            val width = toggle.width
            if (width <= 0) return@runCatching
            val textCenter = (topText.left + topText.right) / 2f
            val delta = width / 2f - textCenter
            if (abs(topText.translationX - delta) > 0.5f) topText.translationX = delta
        }
    }

    companion object {
        const val KEY = "cc_brightness_percent"
        const val PREF = "cc_brightness"
        const val MAIN_CLASS = "miui.systemui.controlcenter.panel.main.brightness.BrightnessSliderController"
        const val SECONDARY_CLASS = "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelSliderDelegate"
        const val ANIMATOR_CLASS = "miui.systemui.controlcenter.panel.secondary.brightness.BrightnessPanelAnimator"

        /** 高值太阳图标橙色（对应插件 `color/toggle_slider_brightness_icon_color`）。 */
        val HIGHLIGHT_COLOR: Int = 0xFFFF9F05.toInt()
    }
}
