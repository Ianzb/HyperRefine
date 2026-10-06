package cn.ianzb.hyperrefine.hook.passkey

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/**
 * 通行密钥修复目标 Load。
 *
 * 分四处安装（均受总开关约束）：
 * - `system`（system_server）：Credential Manager 路由；
 * - `com.android.settings`：设置项显示 / 可用；
 * - `com.miui.securitycenter`：阻止覆盖默认凭据设置；
 * - `com.xiaomi.scanner`：阻止扫码劫持。
 */
class PasskeyLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(
        PasskeyKeys.TARGET_SYSTEM,
        PasskeyKeys.TARGET_SETTINGS,
        PasskeyKeys.TARGET_SECURITY_CENTER,
        PasskeyKeys.TARGET_SCANNER,
    )

    override fun onPackageLoaded(target: PackageTarget) {
        val master = HookPrefs.getBoolean(PasskeyKeys.MASTER, false)
        if (!master) return
        when {
            target.isSystemServer ->
                initHook(PasskeySystemServerHook(), HookPrefs.getBoolean(PasskeyKeys.SYSTEM_SERVER, true))

            target.packageName == PasskeyKeys.TARGET_SETTINGS ->
                initHook(PasskeySettingsHook(), HookPrefs.getBoolean(PasskeyKeys.SETTINGS, true))

            target.packageName == PasskeyKeys.TARGET_SECURITY_CENTER ->
                initHook(PasskeySecurityCenterHook(), HookPrefs.getBoolean(PasskeyKeys.BLOCK_SECURITY_CENTER, true))

            target.packageName == PasskeyKeys.TARGET_SCANNER ->
                initHook(PasskeyScannerHook(), HookPrefs.getBoolean(PasskeyKeys.SCANNER, true))
        }
    }
}
