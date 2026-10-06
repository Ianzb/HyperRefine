package cn.ianzb.hyperrefine.hook.browser

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/**
 * 「浏览器」目标 Load。
 *
 * 框架级拦截需覆盖所有进程，故以通配 [cn.ianzb.hyperrefine.hook.base.HookEntryRegistry.GLOBAL]
 * （配合 `android` 框架作用域）安装；应用专属 hook 按包路由。所有子功能均受总开关约束。
 */
class BrowserLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(
        cn.ianzb.hyperrefine.hook.base.HookEntryRegistry.GLOBAL,
        BrowserKeys.PKG_MISHARE,
        BrowserKeys.PKG_AI_ENGINE,
        BrowserKeys.PKG_VOICE_ASSIST,
        BrowserKeys.PKG_SETTINGS,
        BrowserKeys.PKG_MARKET,
        BrowserKeys.PKG_AI_VISION,
        BrowserKeys.PKG_CONTENT_CATCHER,
    )

    override fun onPackageLoaded(target: PackageTarget) {
        if (!HookPrefs.getBoolean(BrowserKeys.MASTER, false)) return
        val pkg = target.packageName
        val isXiaomiApp = XiaomiBrowserPackages.isXiaomiSystemApp(pkg)

        if (HookPrefs.getBoolean(BrowserKeys.INTENT_INTERCEPT, true)) {
            initHook(BrowserFrameworkHook(), true)
        }
        if (HookPrefs.getBoolean(BrowserKeys.FAKE_INSTALLED, true) && isXiaomiApp) {
            initHook(BrowserPackageHook(), true)
        }
        if (HookPrefs.getBoolean(BrowserKeys.PENDING_INTENT, true) &&
            (pkg == BrowserKeys.PKG_MISHARE || isXiaomiApp)
        ) {
            initHook(BrowserPendingIntentHook(), true)
        }
        if (HookPrefs.getBoolean(BrowserKeys.NOTIFICATION_ICON, true) &&
            (pkg == BrowserKeys.PKG_MISHARE || pkg == BrowserKeys.PKG_AI_ENGINE)
        ) {
            initHook(BrowserNotificationHook(), true)
        }
        if (HookPrefs.getBoolean(BrowserKeys.MISHARE, true) && pkg == BrowserKeys.PKG_MISHARE) {
            initHook(BrowserMiShareHook(), true)
        }
        if (HookPrefs.getBoolean(BrowserKeys.VOICE_ASSIST, true) && pkg == BrowserKeys.PKG_VOICE_ASSIST) {
            initHook(BrowserVoiceAssistHook(), true)
        }
        if (HookPrefs.getBoolean(BrowserKeys.COPY_DIRECT, true) && pkg == BrowserKeys.PKG_AI_ENGINE) {
            initHook(BrowserCopyDirectHook(), true)
        }
    }
}
