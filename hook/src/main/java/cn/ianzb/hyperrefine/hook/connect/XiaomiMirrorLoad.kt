package cn.ianzb.hyperrefine.hook.connect

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.connect.mirror.MirrorFloatingWindowHook
import cn.ianzb.hyperrefine.hook.connect.mirror.MirrorRefreshRateHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/**
 * 小米互联（`com.xiaomi.mirror`）目标 Load：
 * 允许平板竖屏流转应用、妙享桌面增强（自由浮窗 / 下拉最小化 / 投屏刷新率请求）。
 */
class XiaomiMirrorLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            PortraitStreamingHook(),
            HookPrefs.getBoolean(ConnectKeys.PORTRAIT_STREAMING, false),
        )
        initHook(
            MirrorFloatingWindowHook(),
            HookPrefs.getBoolean(MirrorKeys.FLOATING_WINDOW, false),
        )
        initHook(
            MirrorRefreshRateHook(),
            HookPrefs.getBoolean(MirrorKeys.REFRESH_RATE, false),
        )
    }

    companion object {
        const val TARGET_PACKAGE = "com.xiaomi.mirror"
    }
}
