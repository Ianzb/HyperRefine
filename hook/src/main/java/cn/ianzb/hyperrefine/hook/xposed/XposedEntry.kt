package cn.ianzb.hyperrefine.hook.xposed

import android.content.pm.ApplicationInfo
import android.os.Bundle
import cn.ianzb.hyperrefine.hook.base.HookEntryRegistry
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.nativehook.NativeHookHelper
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.safemode.SafeModeManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.HotReloadedParam
import io.github.libxposed.api.XposedModuleInterface.HotReloadingParam
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * libxposed API 102 模块入口。
 *
 * 负责：初始化封装层、按包分发 Load、热重载前后处理。
 */
class XposedEntry : XposedModule() {

    @Volatile
    private var lastTarget: PackageTarget? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        HookHelper.init(this)
        HookPrefs.init(getRemotePreferences(HookPrefs.GROUP))
        SafeModeManager.init(this)
        HookStatusWriter.init(this)
        HookHelper.log("module loaded in ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage) return
        // 保证开机 / 冷启动时即使 onModuleLoaded 顺序异常也能读到最新配置。
        HookPrefs.init(getRemotePreferences(HookPrefs.GROUP))
        SafeModeManager.init(this)
        // 兜底：重复崩溃时自动跳过该包全部 hook，避免系统应用反复崩溃导致无法开机。
        if (SafeModeManager.handleStart(param.packageName)) return
        val target = PackageTarget.from(param)
        lastTarget = target
        HookEntryRegistry.loadsFor(target.packageName).forEach { load ->
            runCatching { load.onPackageReady(target) }
                .onFailure { HookHelper.log("load failed for ${target.packageName}", it) }
        }
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        // 模板默认不处理 system_server；如需支持可在此注册对应 Load。
        // 同样接入兜底机制，防止 system_server 崩溃导致无法开机。
        SafeModeManager.init(this)
        if (SafeModeManager.handleStart("system")) {
            HookHelper.log("SafeMode: system_server is in safe mode, skip hooks")
        }
    }

    override fun onHotReloading(param: HotReloadingParam): Boolean {
        HookStatusWriter.flush()
        val bundle = Bundle().apply {
            lastTarget?.let {
                putString(KEY_PACKAGE, it.packageName)
                putString(KEY_PROCESS, it.processName)
                putParcelable(KEY_APP_INFO, it.applicationInfo)
            }
        }
        param.setSavedInstanceState(bundle)
        return true
    }

    override fun onHotReloaded(param: HotReloadedParam) {
        // 新代码代次：先卸载旧 hook，再重新初始化并安装。
        HookRegistry.unhookAll()
        // 原生库无法真正卸载，仅清空跟踪记录，让新代次重新走一遍加载流程（原生 hook 需自行保持幂等）。
        NativeHookHelper.reset()
        HookHelper.init(this)
        HookPrefs.init(getRemotePreferences(HookPrefs.GROUP))
        SafeModeManager.init(this)
        HookStatusWriter.init(this)

        val saved = (param.savedInstanceState as? Bundle) ?: param.extras
        val packageName = saved?.getString(KEY_PACKAGE)
        if (packageName != null) {
            val processName = saved.getString(KEY_PROCESS) ?: packageName
            @Suppress("DEPRECATION")
            val appInfo = saved.getParcelable(KEY_APP_INFO) as? ApplicationInfo
            val target = PackageTarget.restored(packageName, processName, appInfo)
            lastTarget = target
            HookEntryRegistry.loadsFor(packageName).forEach { load ->
                runCatching { load.onPackageReady(target) }
                    .onFailure { HookHelper.log("hot reload load failed for $packageName", it) }
            }
        }
    }

    private companion object {
        const val KEY_PACKAGE = "target_package"
        const val KEY_PROCESS = "target_process"
        const val KEY_APP_INFO = "target_app_info"
    }
}
