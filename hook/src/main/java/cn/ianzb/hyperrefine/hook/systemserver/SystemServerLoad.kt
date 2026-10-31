package cn.ianzb.hyperrefine.hook.systemserver

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/** system_server 侧功能：定时深色模式立即生效、无极小窗密度、桌面 / 锁屏壁纸分别压暗。 */
class SystemServerLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(DarkModeScheduleKeys.TARGET)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            DarkModeScheduleHook(),
            HookPrefs.getBoolean(DarkModeScheduleKeys.IMMEDIATE, false),
        )
        initHook(
            FreeformDensityHook(),
            HookPrefs.getBoolean(FreeformDensityKeys.DENSITY, true),
        )
        // 压暗值可以在应用内随时调整，hook 必须常驻，由回调读取最新配置。
        initHook(WallpaperDimLevelHook(), true)
    }
}
