package cn.ianzb.hyperrefine.hook.browser

/**
 * 与「强制小米浏览器」相关的包名清单。
 *
 * 独立实现（思路参考 Fxxk-MiBrowser，MIT）。
 */
object XiaomiBrowserPackages {

    /** 小米浏览器可能使用的包名。 */
    val BROWSER: Set<String> = setOf(
        "com.android.browser",
        "com.miui.browser",
        "com.mi.globalbrowser",
        "com.xiaomi.browser",
    )

    /** 小米应用商店可能使用的包名。 */
    val MARKET: Set<String> = setOf(
        "com.xiaomi.market",
        "com.xiaomi.mi.global.appstore",
        "com.mi.india.appstore",
    )

    /** 会隐式转发网页链接的小米系统应用。 */
    val XIAOMI_SYSTEM_APPS: Set<String> = setOf(
        BrowserKeys.PKG_MISHARE,
        BrowserKeys.PKG_AI_ENGINE,
        BrowserKeys.PKG_VOICE_ASSIST,
        BrowserKeys.PKG_AI_VISION,
        BrowserKeys.PKG_CONTENT_CATCHER,
        "com.xiaomi.mirror",
        "com.miui.securitycenter",
        "com.android.systemui",
        "com.android.settings",
        "com.miui.home",
        "com.miui.notes",
        "com.milink.service",
    )

    fun isBrowser(pkg: String?): Boolean = pkg != null && pkg in BROWSER

    fun isMarket(pkg: String?): Boolean = pkg != null && pkg in MARKET

    fun isXiaomiSystemApp(pkg: String?): Boolean {
        if (pkg == null) return false
        if (pkg in XIAOMI_SYSTEM_APPS) return true
        return pkg.startsWith("com.miui.") || pkg.startsWith("com.xiaomi.") || pkg.startsWith("com.milink.")
    }
}
