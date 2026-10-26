package cn.ianzb.hyperrefine.hook.status

import android.annotation.SuppressLint
import android.app.Application
import android.app.BroadcastOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import cn.ianzb.hyperrefine.hook.xposed.HookHelper

/**
 * Hook 生效状态上报器（hook 进程侧）。
 *
 * 目标进程在每条 hook 安装成功后调用 [markInstalled] 记录已安装的配置键；
 * [flush] 由 `XposedEntry` 在本包**全部 Load 完成后**调用一次，合并上报（每个进程一次广播，
 * 作为一次进程启动证据；多 Load 各自 flush 时先到者会把后到者的键顶掉，故不能各自 flush）。
 *
 * 由于 libxposed 的 `onPackageReady` 早于 `Application` 创建（API 文档：「is ready to create
 * Application」），此时通常拿不到进程上下文。因此 [flush] 会挂载一次
 * `Application.attach(Context)` 生命周期钩子，在上下文就绪后补发；若已就绪则直接上报。
 */
object HookStatusReporter {

    private val installedKeys = LinkedHashSet<String>()
    private val evaluatedKeys = LinkedHashSet<String>()
    private val failedKeys = LinkedHashSet<String>()

    @Volatile
    private var pendingSource: Pair<String, String>? = null

    @Volatile
    private var reported = false

    @Volatile
    private var attachHookInstalled = false

    @Volatile
    private var deferredRetryScheduled = false

    /** 记录一个本进程实际尝试安装的配置键（无论成功或失败）。 */
    @Synchronized
    fun markEvaluated(key: String) {
        if (key.isNotBlank()) evaluatedKeys.add(key)
    }

    /** 记录一个已成功安装的配置键。 */
    @Synchronized
    fun markInstalled(key: String) {
        if (key.isBlank()) return
        evaluatedKeys.add(key)
        failedKeys.remove(key)
        installedKeys.add(key)
    }

    /** 记录一个安装失败的配置键，用于 App 侧显示「不可用」。 */
    @Synchronized
    fun markFailed(key: String) {
        if (key.isBlank()) return
        evaluatedKeys.add(key)
        if (key !in installedKeys) failedKeys.add(key)
    }

    /**
     * 合并上报当前进程尝试安装的配置键；无任何尝试时静默跳过。
     */
    @Synchronized
    fun flush(sourcePackage: String, sourceProcess: String) {
        pendingSource = sourcePackage to sourceProcess
        if (evaluatedKeys.isEmpty()) return
        val context = currentApplication() ?: systemContext()
        if (context != null) {
            reportIfPending(context)
        } else {
            ensureAttachHook()
            scheduleDeferredRetry()
        }
    }

    @Synchronized
    fun reset() {
        installedKeys.clear()
        evaluatedKeys.clear()
        failedKeys.clear()
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
        if (reported || evaluatedKeys.isEmpty()) return
        report(
            context = context,
            sourcePackage = source.first,
            sourceProcess = source.second,
            installed = ArrayList(installedKeys),
            evaluated = ArrayList(evaluatedKeys),
            failed = ArrayList(failedKeys),
        )
        reported = true
    }

    private fun report(
        context: Context,
        sourcePackage: String,
        sourceProcess: String,
        installed: ArrayList<String>,
        evaluated: ArrayList<String>,
        failed: ArrayList<String>,
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
                .putStringArrayListExtra(HookStatusContract.EXTRA_INSTALLED_KEYS, installed)
                .putStringArrayListExtra(HookStatusContract.EXTRA_EVALUATED_KEYS, evaluated)
                .putStringArrayListExtra(HookStatusContract.EXTRA_FAILED_KEYS, failed)
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES or Intent.FLAG_RECEIVER_FOREGROUND)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                val options = BroadcastOptions.makeBasic().apply {
                    setShareIdentityEnabled(true)
                }
                context.sendBroadcast(intent, null, options.toBundle())
            } else {
                context.sendBroadcast(intent)
            }
            HookHelper.log(
                "hook status reported: installed=$installed failed=$failed @ $sourceProcess"
            )
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

    /**
     * system_server 等没有 Application 的进程：退回系统上下文上报。
     * `flush` 调用时机可能早于系统上下文创建，拿不到时由 [scheduleDeferredRetry] 稍后补发。
     */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun systemContext(): Context? = runCatching {
        val thread = Class.forName("android.app.ActivityThread")
            .getMethod("currentActivityThread")
            .invoke(null) ?: return@runCatching null
        var current: Class<*>? = thread.javaClass
        while (current != null) {
            val method = runCatching { current.getDeclaredMethod("getSystemContext") }.getOrNull()
            if (method != null) {
                method.isAccessible = true
                return@runCatching method.invoke(thread) as? Context
            }
            current = current.superclass
        }
        null
    }.getOrNull()

    /** 延迟补发一次（系统上下文通常会在 system_server 启动稍后创建）。 */
    @Synchronized
    private fun scheduleDeferredRetry() {
        if (deferredRetryScheduled) return
        deferredRetryScheduled = true
        runCatching {
            Handler(Looper.getMainLooper()).postDelayed({
                val context = currentApplication() ?: systemContext() ?: return@postDelayed
                reportIfPending(context)
            }, DEFERRED_RETRY_MS)
        }.onFailure { HookHelper.log("hook status: deferred retry unavailable", it) }
    }

    private fun moduleVersionCode(context: Context): Long = runCatching {
        context.packageManager
            .getPackageInfo(HookStatusContract.MODULE_PACKAGE, 0)
            .longVersionCode
    }.getOrDefault(0L)

    private const val DEFERRED_RETRY_MS = 5_000L
}
