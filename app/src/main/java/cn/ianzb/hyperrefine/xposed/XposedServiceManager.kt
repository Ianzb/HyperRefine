package cn.ianzb.hyperrefine.xposed

import android.os.Bundle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cn.ianzb.hyperrefine.prefs.PrefsStore
import io.github.libxposed.service.HookedTarget
import io.github.libxposed.service.HotReloadResult
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * LSPosed 服务绑定：作用域查询 / 主动申请、热重载触发、运行中目标查询。
 */
object XposedServiceManager {

    private var service: XposedService? = null

    var isActivated by mutableStateOf(false)
        private set

    /** 是否已获得 Root 权限（异步检测）。 */
    var isRootAvailable by mutableStateOf(false)
        private set

    /** Root 是否已检测完成。 */
    var rootChecked by mutableStateOf(false)
        private set

    var scope by mutableStateOf<List<String>>(emptyList())
        private set

    @Volatile
    private var rootChecking = false

    private val listener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            this@XposedServiceManager.service = service
            isActivated = true
            PrefsStore.attachRemote(service.getRemotePreferences(PrefsStore.REMOTE_GROUP))
            SafeModeReader.attach(service)
            refreshScope()
        }

        override fun onServiceDied(service: XposedService) {
            this@XposedServiceManager.service = null
            isActivated = false
            scope = emptyList()
            PrefsStore.attachRemote(null)
            SafeModeReader.attach(null)
        }
    }

    fun init() {
        XposedServiceHelper.registerListener(listener)
        checkRoot()
    }

    /** 异步检测 Root 权限，避免阻塞主线程；重复调用会被合并。 */
    fun checkRoot() {
        if (rootChecking) return
        rootChecking = true
        Thread {
            val root = RootHelper.isRootAvailable()
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                isRootAvailable = root
                rootChecked = true
                rootChecking = false
            }
        }.apply {
            isDaemon = true
            name = "MiuixRootCheck"
        }.start()
    }

    fun getService(): XposedService? = service

    fun refreshScope() {
        scope = service?.scope ?: emptyList()
    }

    fun isInScope(packageName: String): Boolean = scope.contains(packageName)

    /**
     * 主动申请作用域：仅对尚未授权的包发起请求。
     */
    fun ensureScope(packages: List<String>, onResult: ((Boolean, String?) -> Unit)? = null) {
        val current = service
        if (current == null) {
            onResult?.invoke(false, "service unavailable")
            return
        }
        val missing = packages.filter { it.isNotEmpty() && it !in scope }
        if (missing.isEmpty()) {
            onResult?.invoke(true, null)
            return
        }
        current.requestScope(missing, object : XposedService.OnScopeEventListener {
            override fun onScopeRequestApproved(approved: List<String>) {
                refreshScope()
                onResult?.invoke(true, null)
            }

            override fun onScopeRequestFailed(message: String) {
                onResult?.invoke(false, message)
            }
        })
    }

    fun removeScope(packages: List<String>) {
        service?.removeScope(packages)
        refreshScope()
    }

    /**
     * 触发目标进程热重载。
     */
    fun hotReload(packages: List<String>, onResult: ((String) -> Unit)? = null) {
        val current = service
        if (current == null) {
            onResult?.invoke("service unavailable")
            return
        }
        val targets = current.runningTargets.filter { target ->
            packages.any { pkg -> target.processName == pkg || target.processName.startsWith("$pkg:") }
        }
        if (targets.isEmpty()) {
            onResult?.invoke("no running target")
            return
        }
        targets.forEach { target ->
            current.hotReloadModule(target, Bundle(), object : XposedService.HotReloadCallback {
                override fun onHotReloadResult(target: HookedTarget, result: HotReloadResult) {
                    onResult?.invoke(result.status().name)
                }
            })
        }
    }

    fun runningTargets(): List<HookedTarget> = service?.runningTargets ?: emptyList()
}
