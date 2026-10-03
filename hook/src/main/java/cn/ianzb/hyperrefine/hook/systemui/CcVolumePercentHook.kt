package cn.ianzb.hyperrefine.hook.systemui

import android.view.View
import android.widget.TextView
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
                // 深 / 浅色切换后系统会对 top_text 调用 `setTextAppearance` 重置字号、字重与颜色
                // （VolumeSliderController.onConfigurationChanged），此时上述方法不一定回调，
                // 故在配置变更回调之后再套一次百分比样式。
                controller.declaredMethods
                    .firstOrNull { it.name == "onConfigurationChanged" && it.parameterCount == 1 }
                    ?.let { method ->
                        method.isAccessible = true
                        HookHelper.hookAfter(method) { param ->
                            param.thisObject?.let { update(it) }
                        }
                    }
            }.onFailure { HookHelper.log("$tag: hook failed", it) }

            // 打开二级菜单（音量面板展开）时，一级音量条的 `top_text` 父容器高度在动画中不断变化，
            // 而百分比位置只在若干回调里重算，导致高度跳变。这里在二级面板的逐帧回调里持续重算，
            // 让百分比位置跟随动画 / 配置高度连续变化。
            runCatching {
                val animatorCl = Reflect.findClassIfExists(VOLUME_PANEL_ANIMATOR, pluginCl) ?: return@runCatching
                val frameCallback = animatorCl.declaredMethods
                    .firstOrNull { it.name == "frameCallback" && it.parameterCount == 0 } ?: return@runCatching
                frameCallback.isAccessible = true
                HookHelper.hookAfter(frameCallback) { param ->
                    if (HookPrefs.getBoolean(KEY, false)) param.thisObject?.let { followAnim(it) }
                }
            }.onFailure { HookHelper.log("$tag: hook frameCallback failed", it) }
        }
    }

    /**
     * 二级音量面板逐帧回调：展开时百分比由一级 `top_text` 变形成二级音量列的 `superVolume`，
     * 而系统动画只按 `superVolume` 的布局坐标插值（不含我们给 `top_text` 加的 `translationY`），
     * 因此动画中竖直高度会跳变。这里在每帧动画后把配置的高度重新套到二级 `superVolume` 上，
     * 使其与侧边音量条百分比用的是同一套「父容器高度 - 文本高度」算法、并连续跟随动画。
     */
    private fun followAnim(animator: Any) {
        runCatching {
            // 不限制在 isExpanding：动画首帧的 animator 状态可能还不是 expanding，
            // 若跳过会导致「起始位置」用系统默认（不含我们加的 translationY），
            // 从而与一级页面的百分比不一致。逐帧无条件按当前父容器高度重算即可。
            //
            // 注意要遍历**所有**可见列：主音量条之外的其它条（铃声 / 闹钟等）也各自有一个
            // `superVolume`，若只处理第一列，它们的百分比会保留系统默认位置、终止高度出错。
            val columns = Reflect.getObjectField(animator, "volumeColumnList") as? List<*> ?: return
            columns.forEach { column ->
                if (column == null) return@forEach
                val superVolume = runCatching { Reflect.callMethod(column, "getSuperVolume") }.getOrNull()
                    as? TextView ?: return@forEach
                val (value, max) = valueOfColumn(column) ?: return@forEach
                val icon = runCatching { Reflect.callMethod(column, "getIcon") }.getOrNull() as? View
                PercentText.show(superVolume, value, max, PREF, icon, null, HIGHLIGHT_COLOR)
                PercentText.applyVerticalPosition(superVolume, PREF)
            }
        }.onFailure { HookHelper.log("$tag: followAnim failed", it) }
    }

    private fun valueOfColumn(column: Any): Pair<Int, Int>? {
        PercentText.levelOf(runCatching { Reflect.callMethod(column, "getSs") }.getOrNull())?.let { return it }
        return PercentText.progressOf(runCatching { Reflect.callMethod(column, "getSlider") }.getOrNull())
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
            PercentText.applyVerticalPosition(topText, PREF)
        }.onFailure { HookHelper.log("$tag: update failed", it) }
    }

    companion object {
        const val KEY = "cc_volume_percent"
        const val PREF = "cc_volume"
        const val CONTROLLER_CLASS = "miui.systemui.controlcenter.panel.main.volume.VolumeSliderController"
        const val VOLUME_PANEL_ANIMATOR =
            "miui.systemui.controlcenter.panel.secondary.volume.VolumePanelAnimator"

        /** 高值喇叭图标蓝色（对应插件 `color/toggle_slider_volume_icon_color`）。 */
        val HIGHLIGHT_COLOR: Int = 0xFF3482FF.toInt()
    }
}
