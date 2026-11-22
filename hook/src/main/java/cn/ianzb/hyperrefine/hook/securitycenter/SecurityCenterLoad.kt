package cn.ianzb.hyperrefine.hook.securitycenter

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/** 安全服务（com.miui.securitycenter）功能入口。 */
class SecurityCenterLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            ToolboxSidebarGlassHook(),
            HookPrefs.getBoolean(ToolboxSidebarGlassHook.KEY, false),
        )
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
