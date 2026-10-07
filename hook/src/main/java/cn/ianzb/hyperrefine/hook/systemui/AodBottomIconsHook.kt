package cn.ianzb.hyperrefine.hook.systemui

import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 息屏（AOD）时保留锁屏左下角 / 右下角的快捷功能图标。
 *
 * 目标进程：`com.android.systemui`。
 *
 * 进入（全屏）息屏时，`KeyguardPanelViewController` 会通过息屏联动动画
 * `doHideKeyguardViewAnim(...)` → `translationAlpha`（Folme 值）把锁屏元素整体压暗/淡出。
 * 该过渡的监听器 `KeyguardPanelViewController$doSignatureColorAnim$config$1#onUpdate`
 * 会把 `keyguardBottomAreaInjector.mKeyguardBottomAreaView`（左下 / 右下快捷功能图标所在视图）
 * 的 `transitionAlpha` 从 1 动画到 0，于是息屏时两个功能图标被隐藏。
 *
 * 处理方式：开关开启时，在该监听器回调之后，把底部功能图标视图的 `transitionAlpha` 重新置为 1。
 * 该监听器仅在息屏联动（进入 / 退出息屏）时触发，因此：
 * - 进入息屏时图标不被淡出，息屏界面依然可见；
 * - 解锁（退出息屏）时图标也不再重新淡入（没有「出现动画」）。
 * 「遮挡（相机 / 手电筒）」等场景走的是另一条路径，不受影响。
 */
class AodBottomIconsHook : BaseHook() {

    override val key: String = KEY

    override fun init() {
        val loader = target.classLoader ?: return
        val listener = Reflect.findClassIfExists(LISTENER, loader) ?: run {
            HookHelper.log("$tag: $LISTENER not found")
            return
        }
        val method = listener.declaredMethods.firstOrNull {
            it.name == "onUpdate" && it.parameterCount == 2
        } ?: run {
            HookHelper.log("$tag: onUpdate not found")
            return
        }
        method.isAccessible = true
        runCatching {
            HookHelper.hookAfter(method) { param ->
                if (!HookPrefs.getBoolean(KEY, false)) return@hookAfter
                val listener = param.thisObject ?: return@hookAfter
                val controller = Reflect.getObjectField(listener, "this\$0") ?: return@hookAfter
                val injector = Reflect.getObjectField(controller, "keyguardBottomAreaInjector")
                    ?: return@hookAfter
                val bottom = Reflect.getObjectField(injector, "mKeyguardBottomAreaView") as? View
                    ?: return@hookAfter
                runCatching { bottom.transitionAlpha = 1f }
            }
        }.onFailure { HookHelper.log("$tag: hook failed", it) }
    }

    companion object {
        const val KEY = "aod_keep_bottom_icons"

        private const val LISTENER =
            "com.android.keyguard.panel.KeyguardPanelViewController\$doSignatureColorAnim\$config\$1"
    }
}
