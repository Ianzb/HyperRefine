package cn.ianzb.hyperrefine.hook.status

import android.annotation.SuppressLint
import android.app.Application
import android.app.BroadcastOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.ianzb.hyperrefine.hook.xposed.HookHelper

/**
 * Hook 生效状态上报器（hook 进程侧）。
 *
 * 目标进程在完成一组 hook 注册后调用 [markInstalled] 记录已安装的配置键，并在
 * `onPackageReady` 结束时调用 [flush] 合并上报一次。
 *
 * 由于 libxposed 的 `onPackageReady` 早于 `Application` 创建（API 文档：「is ready to create
 * Application」），此时通常拿不到进程上下文。因此 [flush] 会挂载一次
 * `Application.attach(Context)` 生命周期钩子，在上下文就绪后补发；若已就绪则直接上报。
 */
object HookStatusReporter {

    private val installedKeys = LinkedHashSet<String>()

    @Volatile
    private var pendingSource: Pair<String, String>? = null

    @Volatile
    private var reported = false

    @Volatile
    private var attachHookInstalled = false

    /** 记录一个已成功安装的配置键。 */
    @Synchronized
    fun markInstalled(key: String) {
        if (key.isNotBlank()) installedKeys.add(key)
    }

    /**
     * 合并上报当前进程已安装的配置键；无已安装键时静默跳过。
     */
    @Synchronized
    fun flush(sourcePackage: String, sourceProcess: String) {
        pendingSource = sourcePackage to sourceProcess
        if (installedKeys.isEmpty()) return
        val context = currentApplication()
        if (context != null) {
            reportIfPending(context)
        } else {
            ensureAttachHook()
        }
    }

    @Synchronized
    fun reset() {
        installedKeys.clear()
        reported = false
    }

    /** 挂载 `Application.attach(Context)`，在进程上下文就绪后补发一次状态。 */
    @SuppressLint("DiscouragedPrivateApi")
    private fun ensureAttachHook() {
        if (attachHookInstalled) return
        attachHookInstalled = true
        try {
            val attach = Application::class.java
                .getDeclaredMethod("attach", Context::class.java)
                .apply { isAccessible = true }
            HookHelper.intercept(attach) { chain ->
                val result = chain.proceed()
                val context = (chain.getArg(0) as? Context)?.let { it.applicationContext ?: it }
                if (context != null) reportIfPending(context)
                result
            }
        } catch (t: Throwable) {
            attachHookInstalled = false
            HookHelper.log("hook status: attach hook unavailable", t)
        }
    }

    @Synchronized
    private fun reportIfPending(context: Context) {
        val source = pendingSource ?: return
        if (reported || installedKeys.isEmpty()) return
        report(context, source.first, source.second, ArrayList(installedKeys))
        reported = true
    }

    private fun report(
        context: Context,
        sourcePackage: String,
        sourceProcess: String,
        keys: ArrayList<String>,
    ) {
        try {
            val intent = Intent(HookStatusContract.ACTION_HOOKS_ACTIVE)
                .setComponent(
                    ComponentName(
                        HookStatusContract.MODULE_PACKAGE,
                        HookStatusContract.RECEIVER_CLASS,
                    )
                )
                .putExtra(HookStatusContract.EXTRA_SOURCE_PACKAGE, sourcePackage)
                .putExtra(HookStatusContract.EXTRA_SOURCE_PROCESS, sourceProcess)
                .putExtra(HookStatusContract.EXTRA_MODULE_VERSION, moduleVersionCode(context))
                .putStringArrayListExtra(HookStatusContract.EXTRA_INSTALLED_KEYS, keys)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                val options = BroadcastOptions.makeBasic().apply {
                    setShareIdentityEnabled(true)
                }
                context.sendBroadcast(intent, null, options.toBundle())
            } else {
                context.sendBroadcast(intent)
            }
            HookHelper.log("hook status reported: keys=$keys @ $sourceProcess")
        } catch (t: Throwable) {
            HookHelper.log("hook status report failed @ $sourceProcess", t)
        }
    }

    @SuppressLint("PrivateApi")
    private fun currentApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication")
            .invoke(null) as? Context
    }.getOrNull()

    private fun moduleVersionCode(context: Context): Long = runCatching {
        context.packageManager
            .getPackageInfo(HookStatusContract.MODULE_PACKAGE, 0)
            .longVersionCode
    }.getOrDefault(0L)
}
