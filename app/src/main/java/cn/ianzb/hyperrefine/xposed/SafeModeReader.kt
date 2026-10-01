package cn.ianzb.hyperrefine.xposed

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import io.github.libxposed.service.XposedService

/**
 * App 侧维护 hook 兜底（安全模式）状态。
 *
 * libxposed 远程偏好在 hooked app 内**只读**，hook 侧无法写入，因此改为：
 * - 目标进程每次成功装载 hook 后会广播回报（见 [HookStatusReceiver]），App 借此记录一次「进程启动」；
 * - 启动发生在窗口内的重复启动视为一次疑似 hook 崩溃，累计到阈值后把 `safe_mode_<pkg>` 写入远程偏好；
 * - hook 侧下次启动读到该标记即跳过全部 hook。
 *
 * 计数与开关以 App 本地（普通偏好）为准，再把安全模式开关单向同步到远程偏好 `GROUP`，
 * 供 hook 侧读取；手动开关同样生效。
 */
object SafeModeReader {

    /** 远程偏好分组名，需与 hook 侧 `SafeModeManager.GROUP` 一致。 */
    const val GROUP = "hyperrefine_safe_mode"

    private const val PREFS_NAME = "safe_mode_store"
    private const val SAFE_PREFIX = "safe_mode_"
    private const val COUNT_PREFIX = "crash_count_"
    private const val LAST_PREFIX = "last_start_"

    /** 存活窗口：启动后在该时长内再次启动，视为上一次未成功加载（疑似崩溃）。 */
    private const val SURVIVE_MS = 15_000L

    private const val DEFAULT_THRESHOLD = 3
    private const val CRITICAL_THRESHOLD = 2

    /**
     * 安全模式白名单：仅对系统界面、桌面、系统进程启用自动安全模式。
     *
     * 其他进程（应用 / 第三方 / 部分系统服务）本身就会按自身或系统策略重启，
     * 若按其「进程重启」计数触发安全模式会误判，故不计入。
     */
    private val SAFE_MODE_WHITELIST = setOf(
        "android",
        "system",
        "com.android.systemui",
        "com.miui.home",
    )

    @Volatile
    private var service: XposedService? = null

    private var prefs: SharedPreferences? = null

    /** 当前处于安全模式（hook 被自动禁用）的包名集合。 */
    var safeModePackages by mutableStateOf<Set<String>>(emptySet())
        private set

    fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        refresh()
    }

    fun attach(service: XposedService?) {
        this.service = service
        refresh()
        syncToRemote()
    }

    /** 重新读取本地安全模式状态。 */
    fun refresh() {
        safeModePackages = readSafeSet()
    }

    fun isInSafeMode(packageName: String): Boolean = packageName in safeModePackages

    /** 最近一次记录的崩溃次数。 */
    fun crashCount(packageName: String): Int =
        prefs?.getInt("$COUNT_PREFIX$packageName", 0) ?: 0

    /**
     * 记录一次目标进程启动。窗口内的重复启动累计为疑似崩溃，达到阈值即置安全模式并同步到远程偏好。
     */
    fun recordProcessStart(packageName: String) {
        // 只对白名单（系统界面 / 桌面 / 系统进程）做自动安全模式判定。
        if (packageName !in SAFE_MODE_WHITELIST) return
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        val last = p.getLong("$LAST_PREFIX$packageName", 0L)
        val count = if (last != 0L && now - last in 0..SURVIVE_MS) {
            p.getInt("$COUNT_PREFIX$packageName", 0) + 1
        } else {
            1
        }
        p.edit {
            putLong("$LAST_PREFIX$packageName", now)
            putInt("$COUNT_PREFIX$packageName", count)
            if (count >= thresholdOf(packageName)) {
                putBoolean("$SAFE_PREFIX$packageName", true)
            }
        }
        refresh()
        syncToRemote()
    }

    /** 退出安全模式并清空该包的崩溃计数。 */
    fun reset(packageName: String) = setSafeMode(packageName, false)

    /**
     * 手动启用 / 关闭某应用的安全模式。
     *
     * 启用后 hook 进程会在下次启动时跳过该包全部 hook；关闭时同时清空崩溃计数。
     */
    fun setSafeMode(packageName: String, enabled: Boolean) {
        val p = prefs ?: return
        p.edit {
            if (enabled) {
                putBoolean("$SAFE_PREFIX$packageName", true)
            } else {
                remove("$SAFE_PREFIX$packageName")
                remove("$COUNT_PREFIX$packageName")
                remove("$LAST_PREFIX$packageName")
            }
        }
        refresh()
        syncToRemote()
    }

    /** 退出全部安全模式。 */
    fun resetAll() {
        prefs?.edit { clear() }
        refresh()
        syncToRemote()
    }

    private fun thresholdOf(packageName: String): Int =
        if (packageName in SAFE_MODE_WHITELIST) CRITICAL_THRESHOLD else DEFAULT_THRESHOLD

    private fun readSafeSet(): Set<String> =
        prefs?.all
            ?.filter { it.key.startsWith(SAFE_PREFIX) && it.value == true }
            ?.keys
            ?.map { it.removePrefix(SAFE_PREFIX) }
            ?.toSet()
            ?: emptySet()

    /** 把本地安全模式开关同步到远程偏好，供 hook 侧读取。 */
    private fun syncToRemote() {
        val remote = runCatching { service?.getRemotePreferences(GROUP) }.getOrNull() ?: return
        val localSafe = readSafeSet()
        runCatching {
            remote.edit {
                remote.all.keys
                    .filter { it.startsWith(SAFE_PREFIX) }
                    .forEach { key ->
                        if (key.removePrefix(SAFE_PREFIX) !in localSafe) remove(key)
                    }
                localSafe.forEach { putBoolean("$SAFE_PREFIX$it", true) }
            }
        }
    }
}
