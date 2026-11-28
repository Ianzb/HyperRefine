package cn.ianzb.hyperrefine.xposed

import cn.ianzb.hyperrefine.hook.connect.BluetoothAudioLoad

/**
 * 应用重启 / 系统重启工具。
 *
 * - 普通应用：仅当应用在运行时，Root 下结束其进程，不主动拉起；未运行则不更改。
 * - 系统界面（`com.android.systemui`）：结束进程由系统自动拉起（`restartSystemUi`）。
 * - system_server（`system` / `android`）：只能通过重启系统恢复，执行 `reboot`。
 */
object AppRestarter {

    private val SYSTEM_PACKAGES = setOf("system", "android", "system_server")

    private const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    private const val SECURITY_CENTER_PACKAGE = "com.miui.securitycenter"

    fun isSystemPackage(packageName: String): Boolean = packageName in SYSTEM_PACKAGES

    /**
     * 重启指定应用：仅结束正在运行的应用进程，不重新打开应用。
     *
     * @return true 表示操作已完成或无需操作；false 表示缺少 Root 权限。
     */
    fun restart(packageName: String): Boolean {
        if (!XposedServiceManager.isRootAvailable) return false
        if (packageName == SYSTEM_UI_PACKAGE) return restartSystemUi()
        if (isSystemPackage(packageName)) {
            return RootHelper.exec("reboot")
        }
        if (!isRunning(packageName)) return true
        SafeModeReader.noteUserRestart(packageName)
        // BluetoothManagerService rebinds after the process exits. Force-stop can prevent that.
        if (packageName == BluetoothAudioLoad.TARGET) return RootHelper.exec("killall ${BluetoothAudioLoad.TARGET}")
        return RootHelper.exec("am force-stop $packageName")
    }

    /** 安全服务的工具箱可以在主进程退出后继续留在 :ui 进程。 */
    private fun isRunning(packageName: String): Boolean {
        if (packageName == SECURITY_CENTER_PACKAGE) {
            return RootHelper.exec("pidof $packageName $packageName:ui $packageName.remote $packageName:cache")
        }
        return RootHelper.exec("pidof $packageName")
    }

    /** 重启系统（用于 system_server 目标）。 */
    @Suppress("unused")
    fun reboot(): Boolean =
        XposedServiceManager.isRootAvailable && RootHelper.exec("reboot")

    /**
     * 重启系统界面（`com.android.systemui`）。
     *
     * 结束 SystemUI 进程触发系统自动重新拉起；`force-stop` 仅作兜底
     * （部分机型 force-stop 后不会自动恢复，故放在最后）。
     *
     * @return true 表示已执行重启命令；false 表示缺少 Root 权限。
     */
    fun restartSystemUi(processName: String = SYSTEM_UI_PACKAGE): Boolean {
        if (!XposedServiceManager.isRootAvailable) return false
        SafeModeReader.noteUserRestart(SYSTEM_UI_PACKAGE)
        if (RootHelper.exec("pkill -f $processName")) return true
        if (RootHelper.exec("killall $processName")) return true
        return RootHelper.exec("am force-stop $processName")
    }
}
