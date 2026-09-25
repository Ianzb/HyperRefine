package cn.ianzb.hyperrefine.hook.prefs

import android.content.SharedPreferences

/**
 * Hook 进程侧的配置读取（只读）。
 *
 * 数据由 App 侧写入 LSPosed 远程偏好，hook 进程通过
 * [io.github.libxposed.api.XposedInterface.getRemotePreferences] 读取。
 *
 * 键名需与 App 侧 [PrefsStore] 保持一致（统一加 `prefs_key_` 前缀）。
 */
object HookPrefs {

    /** 远程偏好分组名，需与 App 侧保持一致。 */
    const val GROUP = "hyperrefine_remote"

    private const val KEY_PREFIX = "prefs_key_"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(remote: SharedPreferences) {
        prefs = remote
    }

    private fun key(key: String): String =
        if (key.startsWith(KEY_PREFIX)) key else KEY_PREFIX + key

    fun getBoolean(key: String, defaultValue: Boolean = false): Boolean =
        prefs?.getBoolean(key(key), defaultValue) ?: defaultValue

    fun getString(key: String, defaultValue: String? = null): String? =
        prefs?.getString(key(key), defaultValue) ?: defaultValue

    fun getInt(key: String, defaultValue: Int = 0): Int =
        prefs?.getInt(key(key), defaultValue) ?: defaultValue

    fun getLong(key: String, defaultValue: Long = 0L): Long =
        prefs?.getLong(key(key), defaultValue) ?: defaultValue

    fun getFloat(key: String, defaultValue: Float = 0f): Float =
        prefs?.getFloat(key(key), defaultValue) ?: defaultValue

    fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): Set<String> =
        prefs?.getStringSet(key(key), defaultValue) ?: defaultValue

    fun getAll(): Map<String, *> = prefs?.all ?: emptyMap<String, Any>()
}
