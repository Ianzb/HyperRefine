package cn.ianzb.hyperrefine.hook.systemui

import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 息屏（AOD）时保持电池图标样式不变，并让电池的息屏过渡动画保持连续（不先消失再出现）。
 *
 * 目标进程：`com.android.systemui`。
 *
 * 一、保持样式：状态栏电池视图 `MiuiBatteryMeterView` 进入息屏时由 `toggleAodMode(true)` 置位 `mToAod`，
 * 随后 `onBatteryStyleChanged(int)` 里 `i3 = (!mToAod || i == 3) ? i : 3;` 会把样式强制切成 3。
 * 处理：仅在计算样式时临时把 `mToAod` 置 false（执行原逻辑后还原），使息屏电池保持与状态栏一致。
 *
 * 二、连续过渡：息屏联动时 `KeyguardStatusBarViewControllerInject.animateFullAod(z, animated)`
 * 依据 `mStoreRealStyle != 3` 选择动画：
 * - 等于 3：走连续分支——只做图标容器 / 左侧容器动画，电池**始终可见**（淡入淡出连续、无中断）；
 * - 不等于 3：先把电池淡出到 0，动画结束后再淡入到 1——观感是「先消失一会再出现」。
 * 本功能已让电池样式与状态栏一致（通常不为 3），故把该分支改为连续分支：跳过电池淡出 / 淡入，
 * 仅做图标容器 / 左侧容器动画并保持电池可见。
 */
class AodBatteryStyleHook : BaseHook() {

    override val key: String = KEY

    override fun init() {
        hookStyleKeep()
        hookContinuousAnim()
    }

    /** 保持息屏电池样式与状态栏一致。 */
    private fun hookStyleKeep() {
        val loader = target.classLoader ?: return
        val clazz = Reflect.findClassIfExists(BATTERY_VIEW, loader) ?: run {
            HookHelper.log("$tag: $BATTERY_VIEW not found")
            return
        }
        val method = Reflect.findMethodIfExists(clazz, "onBatteryStyleChanged", Integer.TYPE) ?: run {
            HookHelper.log("$tag: onBatteryStyleChanged not found")
            return
        }
        val toAod = runCatching { Reflect.findField(clazz, "mToAod") }.getOrNull()
        HookHelper.intercept(method) { chain ->
            val view = chain.thisObject
            if (HookPrefs.getBoolean(KEY, false) && view != null && toAod != null && toAod.getBoolean(view)) {
                toAod.setBoolean(view, false)
                try {
                    chain.proceed()
                } finally {
                    toAod.setBoolean(view, true)
                }
            } else {
                chain.proceed()
            }
        }
    }

    /** 息屏联动动画：走连续分支，电池保持可见（样式已与状态栏一致）。 */
    private fun hookContinuousAnim() {
        val loader = target.classLoader ?: return
        val clazz = Reflect.findClassIfExists(INJECTOR, loader) ?: run {
            HookHelper.log("$tag: $INJECTOR not found")
            return
        }
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "animateFullAod" && it.parameterCount == 2
        } ?: run {
            HookHelper.log("$tag: animateFullAod not found")
            return
        }
        method.isAccessible = true
        runCatching {
            HookHelper.hookBefore(method) { param ->
                if (!HookPrefs.getBoolean(KEY, false)) return@hookBefore
                val toLock = param.args.getOrNull(0) as? Boolean ?: return@hookBefore
                val animated = param.args.getOrNull(1) as? Boolean ?: return@hookBefore
                if (!animated) return@hookBefore
                val self = param.thisObject ?: return@hookBefore
                val barView = Reflect.getObjectField(self, "keyguardStatusBarView") ?: return@hookBefore
                val battery = Reflect.getObjectField(barView, "mBatteryView") ?: return@hookBefore
                // 官方样式为 3 时本就是连续分支，无需干预。
                if (Reflect.getObjectField(battery, "mStoreRealStyle") == 3) return@hookBefore
                // 连续分支：仅联动图标容器 / 左侧容器，电池保持可见。
                runCatching { Reflect.callMethod(battery, "animateColorIfNeed", toLock) }
                runCatching { Reflect.callMethod(barView, "animateIconContainer", toLock) }
                runCatching { Reflect.callMethod(barView, "animateKeyguardLeftSideContainer", toLock) }
                runCatching { Reflect.setObjectField(barView, "mToLockScreen", toLock) }
                (battery as? View)?.let {
                    it.alpha = 1.0f
                    it.visibility = View.VISIBLE
                }
                param.setResultValue(null)
            }
        }.onFailure { HookHelper.log("$tag: hook animateFullAod failed", it) }
    }

    companion object {
        const val KEY = "aod_battery_style_keep"

        private const val BATTERY_VIEW = "com.android.systemui.statusbar.views.MiuiBatteryMeterView"
        private const val INJECTOR =
            "com.android.systemui.statusbar.phone.KeyguardStatusBarViewControllerInject"
    }
}
