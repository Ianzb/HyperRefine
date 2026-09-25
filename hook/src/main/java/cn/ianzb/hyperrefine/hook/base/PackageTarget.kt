package cn.ianzb.hyperrefine.hook.base

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
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

    private fun resolveAppVersion(): Pair<String, Long> {
        val application = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Application
        }.getOrNull()
        val pm = application?.packageManager ?: return "" to 0L
        return runCatching {
            val info = pm.getPackageInfo(packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            (info.versionName ?: "") to code
        }.getOrDefault("" to 0L)
    }

    companion object {

        fun from(param: PackageReadyParam): PackageTarget = PackageTarget(
            packageName = param.packageName,
            processName = param.applicationInfo.processName ?: param.packageName,
            applicationInfo = param.applicationInfo,
            classLoader = param.classLoader,
        )

        fun fromSystemServer(param: SystemServerStartingParam): PackageTarget = PackageTarget(
            packageName = "android",
            processName = "system_server",
            applicationInfo = null,
            classLoader = param.classLoader,
            isSystemServer = true,
        )

        /**
         * 热重载后重建目标信息（新代码代次拿不到 PackageReadyParam）。
         */
        fun restored(
            packageName: String,
            processName: String,
            applicationInfo: ApplicationInfo?,
        ): PackageTarget = PackageTarget(
            packageName = packageName,
            processName = processName,
            applicationInfo = applicationInfo,
            classLoader = currentClassLoader(),
        )

        /**
         * 尽力获取当前进程的 Application ClassLoader。
         */
        fun currentClassLoader(): ClassLoader? = runCatching {
            val thread = Class.forName("android.app.ActivityThread")
            val application = thread.getMethod("currentApplication").invoke(null) as? Application
            application?.classLoader
        }.getOrNull() ?: Thread.currentThread().contextClassLoader
    }
}
