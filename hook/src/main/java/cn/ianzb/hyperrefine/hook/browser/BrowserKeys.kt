package cn.ianzb.hyperrefine.hook.browser

/**
 * 「浏览器」功能配置键。
 *
 * 功能思路参考 Fxxk-MiBrowser（MIT），本项目为独立实现、未复用其代码。
 */
object BrowserKeys {

    /** 改用系统默认浏览器总开关。 */
    const val MASTER = "browser_master"

    /** 框架级跳转拦截（startActivity / execStartActivity 等）。 */
    const val INTENT_INTERCEPT = "browser_intent_intercept"

    /** 伪装小米浏览器「已安装」，避免 http 链接被转成应用商店下载页。 */
    const val FAKE_INSTALLED = "browser_fake_installed"

    /** PendingIntent 创建时拦截 market:// 下载页。 */
    const val PENDING_INTENT = "browser_pending_intent"

    /** 通知图标替换（互传 / 复制直达）。 */
    const val NOTIFICATION_ICON = "browser_notification_icon"

    /** 小米互传链接。 */
    const val MISHARE = "browser_mishare"

    /** 小爱识屏 / 超级小爱。 */
    const val VOICE_ASSIST = "browser_voice_assist"

    /** HyperAI 复制直达。 */
    const val COPY_DIRECT = "browser_copy_direct"

    /** 设置内「管理小米路由器」。 */
    const val ROUTER_SETTINGS = "browser_router_settings"

    // ── 目标包 ──────────────────────────────────────────────────────────
    const val PKG_MISHARE = "com.miui.mishare.connectivity"
    const val PKG_AI_ENGINE = "com.xiaomi.aicr"
    const val PKG_VOICE_ASSIST = "com.miui.voiceassist"
    const val PKG_AI_VISION = "com.xiaomi.aiasst.vision"
    const val PKG_CONTENT_CATCHER = "com.miui.contentcatcher"
    const val PKG_MARKET = "com.xiaomi.market"
    const val PKG_SETTINGS = "com.android.settings"
    const val PKG_BROWSER = "com.android.browser"
}
