package cn.ianzb.hyperrefine.hook.connect

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

class BluetoothAudioLoad : BaseLoad() {
    override val targetPackages = listOf(TARGET)

    override fun onPackageLoaded(target: PackageTarget) {
        if (target.processName != TARGET) return
        initHook(BluetoothHighestSampleRateHook(), HookPrefs.getBoolean(BluetoothHighestSampleRateHook.KEY, false))
    }

    companion object {
        const val TARGET = "com.android.bluetooth"
    }
}
