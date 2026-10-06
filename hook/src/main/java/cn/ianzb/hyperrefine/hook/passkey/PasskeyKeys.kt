package cn.ianzb.hyperrefine.hook.passkey

/**
 * 通行密钥（Passkey / Credential Manager）修复功能的配置键。
 *
 * 功能思路参考 HyperPasskey（GPL-3.0），本项目为独立实现、未复用其代码。
 * Hook 侧 [cn.ianzb.hyperrefine.hook.base.BaseHook.key] 与 App 侧 `OptionSpec.key` 共用本常量。
 */
object PasskeyKeys {

    /** 通行密钥修复总开关。 */
    const val MASTER = "passkey_master"

    /** 系统服务：把 Credential Manager 路由到 Google 凭据界面 / 混合服务。 */
    const val SYSTEM_SERVER = "passkey_system_server"

    /** 设置：让「通行密钥 / 凭据」设置项显示并可用。 */
    const val SETTINGS = "passkey_settings_entry"

    /** 安全中心：阻止其覆盖默认自动填充 / 凭据服务。 */
    const val BLOCK_SECURITY_CENTER = "passkey_block_security_center"

    /** 扫描器：阻止扫码劫持通行密钥。 */
    const val SCANNER = "passkey_block_scanner"

    const val TARGET_SETTINGS = "com.android.settings"
    const val TARGET_SECURITY_CENTER = "com.miui.securitycenter"
    const val TARGET_SCANNER = "com.xiaomi.scanner"

    /** system_server 伪包名（见 PackageTarget.fromSystemServer）。 */
    const val TARGET_SYSTEM = "system"

    /** GMS 凭据提供方 UI 组件。 */
    const val GMS_CRED_CHOOSER = "com.google.android.gms/.identitycredentials.ui.CredentialChooserActivity"

    /** GMS 凭据混合服务组件。 */
    const val GMS_REMOTE_SERVICE = "com.google.android.gms/.auth.api.credentials.credman.service.RemoteService"
}
