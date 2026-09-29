package cn.ianzb.hyperrefine.hook.connect

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/**
 * 小米互联（`com.xiaomi.mirror`）目标 Load：允许平板竖屏流转应用。
 */
class XiaomiMirrorLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(
            PortraitStreamingHook(),
            HookPrefs.getBoolean(ConnectKeys.PORTRAIT_STREAMING, false),
        )
    }

    companion object {
        const val TARGET_PACKAGE = "com.xiaomi.mirror"
    }
}
