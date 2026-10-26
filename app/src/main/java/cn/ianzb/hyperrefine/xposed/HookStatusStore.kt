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
 *
 * 状态不是单一布尔值：同时保存「成功键」「待回报键」和「已回报的目标包」。
 * 某个开启中的键没有出现在成功记录里，但它的目标包已经回报过，说明进程已启动
 * 却没有装上该 Hook，应显示「未生效」；目标包完全没有回报时才显示「等待启动」。
 */
object HookStatusStore {

    private val mutableSnapshot = MutableStateFlow(HookStatusSnapshot())

    /** 当前状态快照（Compose 读取以获得自动刷新）。 */
    val snapshot: StateFlow<HookStatusSnapshot> = mutableSnapshot.asStateFlow()

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
        mutableSnapshot.value = readScopedSnapshot()
    }

    /** 当前模块 versionCode（初始化后有效，0 表示未知）。 */
    fun currentVersionCode(): Long = versionCode

    /** 某键在指定目标包集合下的当前状态。 */
    fun statusFor(key: String, targetPackages: Collection<String>): HookStatus {
        val snapshot = mutableSnapshot.value
        return when {
            key in snapshot.applied -> HookStatus.APPLIED
            key in snapshot.pending -> HookStatus.WAITING
            snapshot.reportedPackages.any { it in targetPackages } -> HookStatus.NOT_APPLIED
            else -> HookStatus.WAITING
        }
    }

    /**
     * 记录一次目标进程回报。
     *
     * @param applied 明确安装成功的键
     * @param failed 明确安装失败 / 被跳过的键；会从成功集合中移除，后续按「未生效」展示
     * @param evaluated 本进程实际尝试安装的键（用于区分进程是否已经回报）
     * @param sourcePackage 回报来源包
     */
    fun record(
        context: Context,
        applied: Collection<String>,
        failed: Collection<String>,
        evaluated: Collection<String>,
        sourcePackage: String,
    ) {
        initialize(context)
        if (evaluated.isEmpty()) return
        val old = mutableSnapshot.value
        // 同一轮里失败优先：避免“先报成功、后报失败”的 Hook 残留绿色。
        val nextApplied = (old.applied + applied) - failed.toSet()
        val nextPending = old.pending - evaluated
        val nextReported = old.reportedPackages + sourcePackage
        persist(
            HookStatusSnapshot(
                applied = nextApplied,
                pending = nextPending,
                reportedPackages = nextReported,
            )
        )
    }

    /** 移除若干配置键（例如开关关闭后，待目标进程重启重新回报）。 */
    fun removeKeys(context: Context, keys: Collection<String>) {
        initialize(context)
        if (keys.isEmpty()) return
        val old = mutableSnapshot.value
        val removed = keys.toSet()
        persist(
            old.copy(
                applied = old.applied - removed,
                pending = old.pending + removed,
            )
        )
    }

    /** 清空全部证据（例如显式重启目标进程后）。 */
    fun clear(context: Context) {
        initialize(context)
        preferences?.edit(commit = true) { clear() }
        mutableSnapshot.value = HookStatusSnapshot()
    }

    private fun persist(snapshot: HookStatusSnapshot) {
        val prefs = preferences ?: return
        prefs.edit(commit = true) {
            putLong(KEY_VERSION_CODE, versionCode)
            putInt(KEY_BOOT_COUNT, bootCount)
            putStringSet(KEY_INSTALLED_KEYS, snapshot.applied)
            putStringSet(KEY_PENDING_KEYS, snapshot.pending)
            putStringSet(KEY_REPORTED_PACKAGES, snapshot.reportedPackages)
        }
        mutableSnapshot.value = snapshot
    }

    private fun readScopedSnapshot(): HookStatusSnapshot {
        val prefs = preferences ?: return HookStatusSnapshot()
        if (bootCount == INVALID_BOOT_COUNT) return HookStatusSnapshot()
        if (prefs.getLong(KEY_VERSION_CODE, 0L) != versionCode) return HookStatusSnapshot()
        if (prefs.getInt(KEY_BOOT_COUNT, INVALID_BOOT_COUNT) != bootCount) return HookStatusSnapshot()
        return HookStatusSnapshot(
            applied = prefs.getStringSet(KEY_INSTALLED_KEYS, emptySet()).orEmpty(),
            pending = prefs.getStringSet(KEY_PENDING_KEYS, emptySet()).orEmpty(),
            reportedPackages = prefs.getStringSet(KEY_REPORTED_PACKAGES, emptySet()).orEmpty(),
        )
    }

    private const val PREFS_NAME = "hook_runtime_status"
    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_BOOT_COUNT = "boot_count"
    private const val KEY_INSTALLED_KEYS = "installed_keys"
    private const val KEY_PENDING_KEYS = "pending_keys"
    private const val KEY_REPORTED_PACKAGES = "reported_packages"
    private const val INVALID_BOOT_COUNT = -1
}
