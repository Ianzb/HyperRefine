package cn.ianzb.hyperrefine.hook.weather

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/** 天气（`com.miui.weather2`）目标 Load：解锁高级外观。 */
class WeatherLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(WeatherKeys.TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initNativeHook(
            WeatherNativeHook(),
            HookPrefs.getBoolean(WeatherKeys.ADVANCED, false),
        )
    }
}
