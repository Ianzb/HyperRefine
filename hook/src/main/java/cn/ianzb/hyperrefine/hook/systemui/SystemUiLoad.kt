package cn.ianzb.hyperrefine.hook.systemui

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/**
 * 系统界面（com.android.systemui）目标 Load：
 * 控制中心音量条 / 控制中心亮度条 / 侧边音量条的百分比数值显示。
 */
class SystemUiLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        // 无条件安装插件 ClassLoader 入口 hook 并尝试恢复已加载插件，
        // 保证「插件先加载、开关后开启 + 热重载」时也能补装成员 hook。
        target.classLoader?.let { PluginLoader.bootstrap(it) }
        initHook(CcVolumePercentHook(), HookPrefs.getBoolean(CcVolumePercentHook.KEY, false))
        initHook(CcBrightnessPercentHook(), HookPrefs.getBoolean(CcBrightnessPercentHook.KEY, false))
        initHook(SideVolumePercentHook(), HookPrefs.getBoolean(SideVolumePercentHook.KEY, false))
    }

    companion object {
        const val TARGET_PACKAGE = "com.android.systemui"
    }
}
