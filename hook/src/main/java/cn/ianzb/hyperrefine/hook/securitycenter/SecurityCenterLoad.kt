package cn.ianzb.hyperrefine.hook.securitycenter

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/**
 * 安全服务（com.miui.securitycenter）目标 Load：
 * 隐藏 90W 及以上快充机型在快充加速时发布的「快充加速通知」。
 * 进入提醒与退出提醒各有独立开关。
 */
class SecurityCenterLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            FastChargeEnterNotifyHook(),
            HookPrefs.getBoolean(FastChargeEnterNotifyHook.KEY, false),
        )
        initHook(
            FastChargeExitNotifyHook(),
            HookPrefs.getBoolean(FastChargeExitNotifyHook.KEY, false),
        )
    }

    companion object {
        const val TARGET_PACKAGE = "com.miui.securitycenter"
    }
}
