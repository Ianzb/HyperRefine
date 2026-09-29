package cn.ianzb.hyperrefine.xposed

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Hook 生效状态的持久化存储（App 侧）。
 *
 * 只接受「当前模块版本 + 当前开机」的目标进程回报：
 * 旧版本或上一次开机的证据会被自动丢弃，避免把历史 Hook 误认为当前生效。
 *
 * 采用 device-protected 存储，保证在直接启动（Direct Boot）阶段也能读取。
 */
object HookStatusStore {

    private val mutableState = MutableStateFlow<Set<String>>(emptySet())

    /** 当前生效的配置键集合（Compose 读取以获得自动刷新）。 */
    val state: StateFlow<Set<String>> = mutableState.asStateFlow()

    private var preferences: SharedPreferences? = null
    private var bootCount: Int = INVALID_BOOT_COUNT
    private var versionCode: Long = 0L

    fun initialize(context: Context) {
        if (preferences != null) return
        val deviceContext = context.createDeviceProtectedStorageContext()
        bootCount = runCatching {
            Settings.Global.getInt(
                deviceContext.contentResolver,
                Settings.Global.BOOT_COUNT,
                INVALID_BOOT_COUNT,
            )
        }.getOrDefault(INVALID_BOOT_COUNT)
        versionCode = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        }.getOrDefault(0L)
        preferences = deviceContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutableState.value = readScopedKeys()
    }

    /** 当前模块 versionCode（初始化后有效，0 表示未知）。 */
    fun currentVersionCode(): Long = versionCode

    fun isApplied(key: String): Boolean = key in mutableState.value

    /** 记录一批已在当前进程安装成功的配置键（与已有集合取并集）。 */
    fun record(context: Context, keys: Collection<String>) {
        initialize(context)
        if (keys.isEmpty()) return
        persist(readScopedKeys() + keys)
    }

    /** 移除若干配置键（例如开关关闭后，待目标进程重启重新回报）。 */
    fun removeKeys(context: Context, keys: Collection<String>) {
        initialize(context)
        if (keys.isEmpty()) return
        persist(readScopedKeys() - keys.toSet())
    }

    /** 清空全部证据（例如显式重启目标进程后）。 */
    fun clear(context: Context) {
        initialize(context)
        preferences?.edit(commit = true) { clear() }
        mutableState.value = emptySet()
    }

    private fun persist(keys: Set<String>) {
        val prefs = preferences ?: return
        prefs.edit(commit = true) {
            putLong(KEY_VERSION_CODE, versionCode)
            putInt(KEY_BOOT_COUNT, bootCount)
            putStringSet(KEY_INSTALLED_KEYS, keys)
        }
        mutableState.value = keys
    }

    private fun readScopedKeys(): Set<String> {
        val prefs = preferences ?: return emptySet()
        if (bootCount == INVALID_BOOT_COUNT) return emptySet()
        if (prefs.getLong(KEY_VERSION_CODE, 0L) != versionCode) return emptySet()
        if (prefs.getInt(KEY_BOOT_COUNT, INVALID_BOOT_COUNT) != bootCount) return emptySet()
        return prefs.getStringSet(KEY_INSTALLED_KEYS, emptySet()) ?: emptySet()
    }

    private const val PREFS_NAME = "hook_runtime_status"
    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_BOOT_COUNT = "boot_count"
    private const val KEY_INSTALLED_KEYS = "installed_keys"
    private const val INVALID_BOOT_COUNT = -1
}
