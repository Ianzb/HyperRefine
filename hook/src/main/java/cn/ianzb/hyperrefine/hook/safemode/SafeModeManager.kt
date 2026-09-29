package cn.ianzb.hyperrefine.hook.safemode

import android.content.SharedPreferences
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import io.github.libxposed.api.XposedInterface

/**
 * Hook 兜底 / 安全模式（hook 侧，**只读**）。
 *
 * libxposed 的远程偏好在 hooked app 内是只读的（`getRemotePreferences` 文档明确），
 * 因此 hook 侧不再写入：崩溃计数与安全模式开关由 App 侧维护并回写远程偏好
 * （见 App 的 `SafeModeReader`）。hook 侧仅读取 `safe_mode_<pkg>`，决定是否跳过该包全部 hook。
 */
object SafeModeManager {

    /** 远程偏好分组名，需与 App 侧 `SafeModeReader.GROUP` 一致。 */
    const val GROUP = "hyperrefine_safe_mode"

    private const val SAFE_PREFIX = "safe_mode_"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(module: XposedInterface) {
        prefs = runCatching { module.getRemotePreferences(GROUP) }.getOrNull()
    }

    /**
     * 进程启动时调用。
     *
     * @return true 表示该包已被 App 置为安全模式，应跳过全部 hook。
     */
    fun handleStart(packageName: String): Boolean {
        val safe = runCatching { prefs?.getBoolean("$SAFE_PREFIX$packageName", false) }
            .getOrNull() ?: false
        if (safe) {
            HookHelper.log("SafeMode: $packageName is in safe mode, skip hooks")
        }
        return safe
    }
}
