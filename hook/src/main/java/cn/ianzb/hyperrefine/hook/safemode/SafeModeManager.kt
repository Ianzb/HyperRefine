package cn.ianzb.hyperrefine.hook.safemode

import android.content.SharedPreferences
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import io.github.libxposed.api.XposedInterface

/**
 * Hook 兜底 / 安全模式。
 *
 * 原理（经典开机循环保护）：每次目标进程加载 hook 前先写入一个「正在加载」时间戳；
 * 若进程成功存活超过 [SURVIVE_MS]，则清除时间戳并重置崩溃计数。
 * 若进程在存活窗口内崩溃，时间戳会残留，下次启动即判定为「疑似由 hook 导致的崩溃」并累加计数；
 * 计数达到阈值后自动进入安全模式，跳过该包全部 hook，避免系统应用反复崩溃导致无法开机。
 *
 * 数据通过 LSPosed 远程偏好持久化，App 侧读取同一分组即可展示 / 手动重置。
 */
object SafeModeManager {

    /** 远程偏好分组名，需与 App 侧 `SafeModeReader.GROUP` 保持一致。 */
    const val GROUP = "hyperrefine_safe_mode"

    /** 上次加载时间戳在该时间窗口内再次启动，视为一次崩溃。 */
    private const val CRASH_WINDOW_MS = 60_000L

    /** 存活该时长后认为加载成功，重置计数。 */
    private const val SURVIVE_MS = 15_000L

    private const val DEFAULT_THRESHOLD = 3
    private const val CRITICAL_THRESHOLD = 2

    /** 崩溃可能导致无法开机的关键应用，阈值更低。 */
    private val CRITICAL_PACKAGES = setOf(
        "android",
        "system",
        "com.android.systemui",
        "com.android.settings",
        "com.miui.home",
        "com.miui.securitycenter",
    )

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(module: XposedInterface) {
        prefs = runCatching { module.getRemotePreferences(GROUP) }.getOrNull()
    }

    /**
     * 进程启动时调用。
     *
     * @return true 表示该包已处于安全模式，应跳过全部 hook。
     */
    fun handleStart(packageName: String): Boolean {
        val p = prefs ?: return false
        return runCatching { handleStartInternal(p, packageName) }
            .onFailure { HookHelper.log("SafeMode handleStart failed: $packageName", it) }
            .getOrDefault(false)
    }

    private fun handleStartInternal(p: SharedPreferences, packageName: String): Boolean {
        if (p.getBoolean(safeKey(packageName), false)) {
            HookHelper.log("SafeMode: $packageName is in safe mode, skip hooks")
            return true
        }

        val now = System.currentTimeMillis()
        val lastLoading = p.getLong(loadingKey(packageName), 0L)
        val crashedLastRun = lastLoading != 0L && now - lastLoading in 0..CRASH_WINDOW_MS

        if (crashedLastRun) {
            val count = p.getInt(countKey(packageName), 0) + 1
            p.edit().putInt(countKey(packageName), count).commit()
            if (count >= thresholdOf(packageName)) {
                p.edit().putBoolean(safeKey(packageName), true).commit()
                HookHelper.log("SafeMode: $packageName disabled after $count crashes")
                return true
            }
            HookHelper.log("SafeMode: $packageName crash count = $count")
        } else {
            p.edit().putInt(countKey(packageName), 0).commit()
        }

        p.edit().putLong(loadingKey(packageName), now).commit()
        scheduleSurviveReset(packageName, now)
        return false
    }

    private fun scheduleSurviveReset(packageName: String, marker: Long) {
        Thread {
            try {
                Thread.sleep(SURVIVE_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            val p = prefs ?: return@Thread
            runCatching {
                if (p.getLong(loadingKey(packageName), 0L) == marker) {
                    p.edit().remove(loadingKey(packageName)).putInt(countKey(packageName), 0).commit()
                }
            }
        }.apply {
            isDaemon = true
            name = "MiuixSafeMode-$packageName"
        }.start()
    }

    private fun thresholdOf(packageName: String): Int =
        if (packageName in CRITICAL_PACKAGES) CRITICAL_THRESHOLD else DEFAULT_THRESHOLD

    private fun safeKey(pkg: String) = "safe_mode_$pkg"
    private fun countKey(pkg: String) = "crash_count_$pkg"
    private fun loadingKey(pkg: String) = "loading_since_$pkg"
}
