package cn.ianzb.hyperrefine.hook.base

import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.ApplicationInfo
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * 目标进程信息封装，屏蔽 libxposed 参数细节。
 */
class PackageTarget(
    val packageName: String,
    val processName: String,
    val applicationInfo: ApplicationInfo?,
    val classLoader: ClassLoader?,
    val isSystemServer: Boolean = false,
) {
    /** 目标应用版本名（尽力解析，取不到为空）。 */
    val appVersionName: String get() = appVersion.first

    /** 目标应用 versionCode（尽力解析，取不到为 0）。 */
    val appVersionCode: Long get() = appVersion.second

    private val appVersion: Pair<String, Long> by lazy { resolveAppVersion() }

    @SuppressLint("PrivateApi")
    private fun resolveAppVersion(): Pair<String, Long> {
        val application = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Application
        }.getOrNull()
        val pm = application?.packageManager ?: return "" to 0L
        return runCatching {
            val info = pm.getPackageInfo(packageName, 0)
            (info.versionName ?: "") to info.longVersionCode
        }.getOrDefault("" to 0L)
    }

    companion object {

        fun from(param: PackageReadyParam): PackageTarget = PackageTarget(
            packageName = param.packageName,
            processName = currentProcessName() ?: param.applicationInfo.processName ?: param.packageName,
            applicationInfo = param.applicationInfo,
            classLoader = param.classLoader,
        )

        /**
         * 当前进程名（非 `applicationInfo.processName`）。
         *
         * `applicationInfo.processName` 是该应用声明的**默认**进程名，对所有进程都相同，
         * 无法用于按进程路由（如 `com.milink.service:ui` / `:core` / `com.milink.crossdeviceservice`）。
         */
        private fun currentProcessName(): String? = runCatching {
            Application.getProcessName()
        }.getOrNull()

        @Suppress("unused")
        fun fromSystemServer(param: SystemServerStartingParam): PackageTarget = PackageTarget(
            packageName = "android",
            processName = "system_server",
            applicationInfo = null,
            classLoader = param.classLoader,
            isSystemServer = true,
        )
    }
}
