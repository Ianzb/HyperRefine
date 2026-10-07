package cn.ianzb.hyperrefine.hook.systemui

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 从锁屏相机 / 手电筒返回息屏时，仍显示「全屏息屏」（压暗锁屏内容），而不是只显示时间的纯黑息屏。
 *
 * 目标进程：`com.android.systemui`。
 *
 * 小米把息屏分为「全屏息屏 AOD」（压暗后的锁屏内容，`MiuiFullAodManager`）与「纯黑息屏」。
 * 进入息屏时是否走全屏样式由 `DozeServiceHostInjector#updateScreenOffNeedFullAodAnimStateInternal()`
 * 计算：
 * `mScreenOffNeedFullAodAnim = (!mFullAodEnable || mKeyguardOccluded || ...) ? false : true;`
 * 从锁屏相机 / 手电筒（`KeyguardBottomArea` 快捷入口）打开会置位 `mKeyguardOccluded`，
 * 于是回到息屏时 `mScreenOffNeedFullAodAnim=false`，错误地进入纯黑息屏；且
 * `MiuiFullAodManager#onKeyguardOccludedChanged(true)` 还会调用
 * `DozeServiceHostInjector#changeAodStyleShown()` 把样式切成纯黑。
 *
 * 处理方式：开关开启时，只要支持全屏息屏（`mFullAodEnable`），就让
 * `mScreenOffNeedFullAodAnim` 保持 true，并让 `changeAodStyleShown()` 不再切换样式，
 * 从而相机 / 手电筒后仍进入全屏息屏。
 */
class AodFullScreenKeepHook : BaseHook() {

    override val key: String = KEY

    override fun init() {
        val loader = target.classLoader ?: return
        val clazz = Reflect.findClassIfExists(INJECTOR, loader) ?: run {
            HookHelper.log("$tag: $INJECTOR not found")
            return
        }
        val fullAodField = runCatching { Reflect.findField(clazz, "mFullAodEnable") }.getOrNull()
        val animField = runCatching { Reflect.findField(clazz, "mScreenOffNeedFullAodAnim") }.getOrNull()
        val stateMethod = Reflect.findMethodIfExists(clazz, "updateScreenOffNeedFullAodAnimStateInternal")
        if (stateMethod == null) HookHelper.log("$tag: updateScreenOffNeedFullAodAnimStateInternal not found")
        if (stateMethod != null && animField != null) {
            HookHelper.hookAfter(stateMethod) { param ->
                if (HookPrefs.getBoolean(KEY, false) && fullAodField?.getBoolean(param.thisObject) == true) {
                    runCatching { animField.setBoolean(param.thisObject, true) }
                }
            }
        }
        Reflect.findMethodIfExists(clazz, "changeAodStyleShown")?.let { method ->
            HookHelper.hookBefore(method) { param ->
                if (HookPrefs.getBoolean(KEY, false)) param.setResultValue(null)
            }
        }
        HookHelper.log("$tag: init fullAodField=${fullAodField != null} animField=${animField != null}")
    }

    companion object {
        const val KEY = "aod_full_screen_keep"

        private const val INJECTOR = "com.android.keyguard.injector.DozeServiceHostInjector"
    }
}
