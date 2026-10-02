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
 * 以及融合设备中心隐藏末尾「…」省略卡片。
 */
class SystemUiLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(CcVolumePercentHook(), HookPrefs.getBoolean(CcVolumePercentHook.KEY, false))
        initHook(CcBrightnessPercentHook(), HookPrefs.getBoolean(CcBrightnessPercentHook.KEY, false))
        initHook(SideVolumePercentHook(), HookPrefs.getBoolean(SideVolumePercentHook.KEY, false))
        initHook(DeviceCenterMoreHook(), HookPrefs.getBoolean(DeviceCenterMoreHook.KEY, false))
        initHook(
            DeviceCenterLandscapeRightHook(),
            HookPrefs.getBoolean(DeviceCenterLandscapeRightHook.KEY, false),
        )
        initHook(CcGlassHook(), HookPrefs.getBoolean(CcGlassHook.KEY, false))
        initHook(CcMaterialGateHook(), HookPrefs.getBoolean(CcGlassKeys.THEME_MATERIAL, false))
        initHook(CcRadiusHook(), HookPrefs.getBoolean(CcRadiusKeys.MASTER, false))
        initHook(AppVolumeEntryHook(), HookPrefs.getBoolean(AppVolumeKeys.ENTRY, false))
    }

    companion object {
        const val TARGET_PACKAGE = "com.android.systemui"
    }
}
