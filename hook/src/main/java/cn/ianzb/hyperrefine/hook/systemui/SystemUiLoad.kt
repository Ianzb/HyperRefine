package cn.ianzb.hyperrefine.hook.systemui

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

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
    }

    companion object {
        const val TARGET_PACKAGE = "com.android.systemui"
    }
}
