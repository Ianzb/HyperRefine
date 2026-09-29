package cn.ianzb.hyperrefine.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import cn.ianzb.hyperrefine.hook.status.HookStatusContract
import cn.ianzb.hyperrefine.prefs.OptionRegistry

/**
 * 接收目标进程的 Hook 生效回报（App 侧）。
 *
 * 安全校验（防止任意应用伪造「已生效」证据）：
 * 1. 动作匹配；
 * 2. 发送者校验：优先用 `sentFromUid` + `getPackagesForUid` 确认真实来源包；
 *    若某些 ROM/框架未透传发送者 UID，则退回「来源包必须是 App 已声明的目标包」；
 * 3. 模块 versionCode 必须与当前一致（0 视为未知、放行）；
 * 4. 上报的键必须是 App 已声明的配置键子集。
 */
class HookStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != HookStatusContract.ACTION_HOOKS_ACTIVE) return

        val sourcePackage = intent.getStringExtra(HookStatusContract.EXTRA_SOURCE_PACKAGE)
        val specs = OptionRegistry.all()
        val knownKeys = specs.mapTo(HashSet()) { it.key }
        val declaredTargets = specs.flatMapTo(HashSet()) { it.targetPackages }
        val senderUid = runCatching { sentFromUid }.getOrDefault(-1)
        val pakOk = if (senderUid >= 0) {
            val senderPackages = context.packageManager
                .getPackagesForUid(senderUid)
                .orEmpty()
                .toList()
            sourcePackage != null && sourcePackage in senderPackages
        } else {
            // 身份不可用时的兜底：来源包必须是已声明的目标包（仍能挡住无关应用伪造）。
            sourcePackage != null && sourcePackage in declaredTargets
        }
        if (!pakOk) {
            Log.w(TAG, "rejected hook report: identity/package mismatch uid=$senderUid pkg=$sourcePackage")
            return
        }

        // 目标进程成功装载 hook 即视为一次进程启动，用于安全模式的崩溃循环计数。
        SafeModeReader.initialize(context)
        SafeModeReader.recordProcessStart(sourcePackage!!)

        val reportedVersion = intent.getLongExtra(HookStatusContract.EXTRA_MODULE_VERSION, 0L)
        HookStatusStore.initialize(context)
        val currentVersion = HookStatusStore.currentVersionCode()
        if (reportedVersion != 0L && currentVersion != 0L && reportedVersion != currentVersion) {
            Log.w(TAG, "rejected hook report: module version mismatch ($reportedVersion != $currentVersion)")
            return
        }

        val applied = intent
            .getStringArrayListExtra(HookStatusContract.EXTRA_INSTALLED_KEYS)
            .orEmpty()
            .filter { it in knownKeys }
        if (applied.isEmpty()) {
            Log.w(
                TAG,
                "rejected hook report: no known keys from $sourcePackage " +
                    "(reported=${intent.getStringArrayListExtra(HookStatusContract.EXTRA_INSTALLED_KEYS)}, known=${knownKeys.size})"
            )
            return
        }

        HookStatusStore.record(context, applied)
        Log.i(TAG, "recorded ${applied.size} applied hook keys from $sourcePackage: $applied")
    }

    private companion object {
        const val TAG = "HookStatus"
    }
}
