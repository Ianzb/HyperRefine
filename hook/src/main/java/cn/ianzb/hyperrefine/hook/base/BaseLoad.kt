package cn.ianzb.hyperrefine.hook.base

import cn.ianzb.hyperrefine.hook.dexkit.DexKitCacheManager
import cn.ianzb.hyperrefine.hook.nativehook.BaseNativeHook
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.rule.VersionContext
import cn.ianzb.hyperrefine.hook.status.HookStatusReporter
import cn.ianzb.hyperrefine.hook.xposed.HookHelper

/**
 * 按目标包组织的一组 hook。
 *
 * 子类在 [onPackageLoaded] 中通过 [initHook] 声明本包需要安装的 Java hook，
 * 通过 [initNativeHook] 声明需要载入的原生 hook 库及其开关。
 */
abstract class BaseLoad {

    /** 本 Load 负责的目标包名。 */
    abstract val targetPackages: List<String>

    private val pendingHooks = mutableListOf<Pair<BaseHook, Boolean>>()

    private val pendingNativeHooks = mutableListOf<Pair<BaseNativeHook, Boolean>>()

    @Volatile
    private var currentTarget: PackageTarget? = null

    /** 目标包就绪时回调，子类在此注册 hook。 */
    protected open fun onPackageLoaded(target: PackageTarget) {}

    /** 注册一条 hook 及其启用状态（通常来自配置键）。 */
    protected fun initHook(hook: BaseHook, enabled: Boolean) {
        pendingHooks.add(hook to enabled)
    }

    /** 注册一个原生 hook 库及其启用状态（通常来自配置键）。 */
    protected fun initNativeHook(hook: BaseNativeHook, enabled: Boolean) {
        pendingNativeHooks.add(hook to enabled)
    }

    fun onPackageReady(target: PackageTarget) {
        currentTarget = target
        pendingHooks.clear()
        pendingNativeHooks.clear()
        runCatching { onPackageLoaded(target) }
            .onFailure { HookHelper.log("onPackageLoaded failed: ${target.packageName}", it) }

        val needsDexKit = pendingHooks.any { it.second && it.first.useDexKit() }
        if (needsDexKit && !target.isSystemServer && target.applicationInfo != null) {
            runCatching { DexKitCacheManager.init(target, target.packageName) }
                .onFailure { HookHelper.log("DexKit init failed: ${target.packageName}", it) }
        }

        pendingHooks.forEach { (hook, enabled) ->
            if (enabled) install(hook)
        }

        pendingNativeHooks.forEach { (hook, enabled) ->
            if (enabled) installNative(hook)
        }

        // 生效状态由 XposedEntry 在本包全部 Load 完成后统一上报：
        // 每个进程只发一次广播（作为一次进程启动证据），多 Load 时不会互相顶掉。
        if (needsDexKit) runCatching { DexKitCacheManager.releaseBridge() }
    }

    private fun install(hook: BaseHook) {
        val target = currentTarget ?: return
        val keys = hook.keys
        keys.forEach { HookStatusReporter.markEvaluated(it) }
        try {
            if (hook.useDexKit()) {
                hook.dexKitInitInProgress = true
                val ok = try {
                    hook.initDexKit()
                } finally {
                    hook.dexKitInitInProgress = false
                }
                if (!ok) {
                    HookHelper.log("${hook.tag} skipped: initDexKit returned false")
                    keys.forEach { HookStatusReporter.markFailed(it) }
                    return
                }
            }
            val variant = hook.apply(target)
            HookHelper.log("${hook.tag} hook success${variant?.let { " [$it]" } ?: ""} @ ${target.packageName}")
            keys.forEach { HookStatusReporter.markInstalled(it) }
        } catch (t: HookSkippedException) {
            // 版本 / 设备筛选未命中属于主动跳过。
            HookHelper.log("${hook.tag} skipped @ ${target.packageName}: ${t.message}")
            keys.forEach { HookStatusReporter.markFailed(it) }
        } catch (t: Throwable) {
            HookHelper.log("${hook.tag} hook failed @ ${target.packageName}", t)
            keys.forEach { HookStatusReporter.markFailed(it) }
        }
    }

    private fun installNative(hook: BaseNativeHook) {
        val target = currentTarget ?: return
        HookStatusReporter.markEvaluated(hook.key)
        if (hook.versionGate != null || !hook.deviceScope.isNullOrEmpty()) {
            val context = VersionContext.of(target.packageName, target.appVersionName, target.appVersionCode)
            if (!hook.appliesTo(context)) {
                HookHelper.log("${hook.key} skipped @ ${target.packageName}: gate not matched | $context")
                HookStatusReporter.markFailed(hook.key)
                return
            }
        }
        val ok = try {
            hook.install()
        } catch (t: Throwable) {
            HookHelper.log("native hook load failed: ${hook.libraryName} @ ${target.packageName}", t)
            false
        }
        if (ok) {
            HookHelper.log("native hook loaded: ${hook.libraryName} @ ${target.packageName}")
            HookStatusReporter.markInstalled(hook.key)
        } else {
            if (hook.required) {
                HookHelper.log("native hook required but not loaded: ${hook.libraryName}")
            }
            HookStatusReporter.markFailed(hook.key)
        }
    }
}
