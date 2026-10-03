package cn.ianzb.hyperrefine.hook.systemui

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.misound.AppVolumeKeys
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.systemui.glass.CcGlassHook
import cn.ianzb.hyperrefine.hook.systemui.glass.CcGlassKeys
import cn.ianzb.hyperrefine.hook.systemui.glass.CcMaterialGateHook
import cn.ianzb.hyperrefine.hook.systemui.radius.CcRadiusHook
import cn.ianzb.hyperrefine.hook.systemui.radius.CcRadiusKeys

/**
 * 系统界面（com.android.systemui）目标 Load：
 * 控制中心音量条 / 控制中心亮度条 / 侧边音量条的百分比数值显示，
 * 以及融合设备中心隐藏末尾「…」省略卡片、缩小设备点击判定范围。
 */
class SystemUiLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(CcVolumePercentHook(), HookPrefs.getBoolean(CcVolumePercentHook.KEY, false))
        initHook(CcBrightnessPercentHook(), HookPrefs.getBoolean(CcBrightnessPercentHook.KEY, false))
        initHook(SideVolumePercentHook(), HookPrefs.getBoolean(SideVolumePercentHook.KEY, false))
        initHook(DeviceCenterMoreHook(), HookPrefs.getBoolean(DeviceCenterMoreHook.KEY, false))
        initHook(
            DeviceCenterHitAreaHook(),
            HookPrefs.getBoolean(DeviceCenterHitAreaHook.KEY, false),
        )
        initHook(
            DeviceCenterLandscapeRightHook(),
            HookPrefs.getBoolean(DeviceCenterLandscapeRightHook.KEY, false),
        )
        initHook(CcGlassHook(), HookPrefs.getBoolean(CcGlassHook.KEY, false))
        initHook(CcMaterialGateHook(), HookPrefs.getBoolean(CcGlassKeys.THEME_MATERIAL, false))
        // 总开关开启，或任一组件的单项自定义开启，即挂载圆角 hook
        // （总开关关闭时单项自定义仍生效，未自定义的组件保持系统默认）。
        val radiusEnabled = HookPrefs.getBoolean(CcRadiusKeys.MASTER, false) ||
            CcRadiusKeys.ITEMS.any {
                HookPrefs.getBoolean(CcRadiusKeys.customKey(it), CcRadiusKeys.itemCustomDefault(it))
            }
        initHook(CcRadiusHook(), radiusEnabled)
        initHook(AppVolumeEntryHook(), HookPrefs.getBoolean(AppVolumeKeys.ENTRY, false))
    }

    companion object {
        const val TARGET_PACKAGE = "com.android.systemui"
    }
}
