package cn.ianzb.hyperrefine.hook.misound

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/**
 * MiSound（`com.miui.misound`）目标 Load：多应用音量相关。
 *
 * - 隐藏系统左侧蓝色悬浮球
 * - 接收广播展开原生多应用音量面板
 * - 切换面板应用范围（当前播放 / 全部记录）
 */
class MiSoundLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(AppVolumeKeys.TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            MiSoundAppVolumeHook(),
            HookPrefs.getBoolean(AppVolumeKeys.ENTRY, false),
        )
    }
}
