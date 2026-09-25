package cn.ianzb.hyperrefine.hook.xposed

import cn.ianzb.hyperrefine.hook.base.HookEntryRegistry
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.safemode.SafeModeManager
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * libxposed API 102 模块入口。
 *
 * 负责：初始化封装层、按包分发 Load。
 */
class XposedEntry : XposedModule() {

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
}
