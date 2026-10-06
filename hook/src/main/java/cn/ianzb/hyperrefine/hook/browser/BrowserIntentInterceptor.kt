package cn.ianzb.hyperrefine.hook.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import io.github.libxposed.api.XposedInterface

/**
 * 框架级网页跳转拦截核心（独立实现，思路参考 Fxxk-MiBrowser，MIT）。
 *
 * 识别被强制指向小米浏览器 / 应用商店的网页 Intent，清洗后改投系统默认浏览器（或选择器）。
 * 仅处理 `ACTION_VIEW`(或 `ACTION_MAIN`) + `http/https/market/mi/intent`，其余原样放行。
 */
object BrowserIntentInterceptor {

    private val reentrant = ThreadLocal.withInitial { false }

    /** 在 `startActivity` / `execStartActivity` 的 hook 中调用；返回值为原方法结果。 */
    fun handle(
        intent: Intent?,
        context: Context?,
        options: Bundle?,
        chain: XposedInterface.Chain,
    ): Any? {
        if (intent == null || context == null) return chain.proceed()
        if (reentrant.get() == true) return chain.proceed()
        if (!shouldIntercept(intent, context.packageName)) return chain.proceed()

        reentrant.set(true)
        try {
            return if (redirect(intent, context, options)) null else chain.proceed()
        } finally {
            reentrant.set(false)
        }
    }

    private fun redirect(original: Intent, context: Context, options: Bundle?): Boolean {
        val cleaned = clean(original)
        val browser = BrowserDefaultResolver.resolve(context) ?: return false

        val data = cleaned.data ?: return false
        if (isXiaomiScheme(data)) {
            // 无法恢复真实 URL：仅当它是浏览器下载页时取消，否则保留原 Intent。
            return isBrowserDownloadUri(original.data)
        }

        val replacement = if (browser.isDefault) {
            BrowserDefaultResolver.buildWebIntent(data, browser.packageName)
        } else {
            Intent.createChooser(BrowserDefaultResolver.buildWebIntent(data, null), "Open with")
        }.apply {
            flags = cleaned.flags
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtras(cleaned)
        }

        return runCatching { context.startActivity(replacement, options); true }.getOrDefault(false)
    }

    private fun shouldIntercept(intent: Intent, caller: String): Boolean {
        val action = intent.action
        if (action != Intent.ACTION_VIEW && action != Intent.ACTION_MAIN) return false

        val data: Uri = intent.data ?: BrowserUrlRecovery.extractWebUri(intent) ?: return false
        val scheme = data.scheme ?: return false

        val targetPkg = intent.`package`
        val targetComponent = intent.component
        val targetsBrowser = XiaomiBrowserPackages.isBrowser(targetPkg) ||
            (targetComponent != null && XiaomiBrowserPackages.isBrowser(targetComponent.packageName))
        val targetsMarket = XiaomiBrowserPackages.isMarket(targetPkg) ||
            (targetComponent != null && XiaomiBrowserPackages.isMarket(targetComponent.packageName))

        if ((scheme == "http" || scheme == "https") && targetsBrowser) return true
        if (scheme == "market" && targetsMarket) return true
        if ((scheme == "http" || scheme == "https") && targetsMarket) return true

        if (XiaomiBrowserPackages.isMarket(caller)) return false

        if ((scheme == "http" || scheme == "https") && targetPkg == null && targetComponent == null &&
            XiaomiBrowserPackages.isXiaomiSystemApp(caller)
        ) {
            return true
        }
        if ((scheme == "http" || scheme == "https") && caller == BrowserKeys.PKG_SETTINGS &&
            BrowserUrlRecovery.isRouterAdminUrl(data) &&
            cn.ianzb.hyperrefine.hook.prefs.HookPrefs.getBoolean(BrowserKeys.ROUTER_SETTINGS, true)
        ) {
            return true
        }
        if (scheme.startsWith("mi") &&
            (isBrowserDownloadUri(data) || BrowserUrlRecovery.recoverForRedirect(intent) != null)
        ) {
            return true
        }
        if (scheme == "intent" && targetsBrowser) return true
        if (scheme == "market" && targetPkg == null && targetComponent == null && isBrowserDownloadUri(data)) {
            return true
        }
        return false
    }

    private fun clean(intent: Intent): Intent {
        val cleaned = Intent(intent)
        if (cleaned.data == null) {
            BrowserUrlRecovery.extractWebUri(cleaned)?.let { cleaned.data = it }
        }
        if (isXiaomiBrowserOrMarket(cleaned.`package`)) cleaned.`package` = null
        cleaned.component?.let { comp ->
            if (isXiaomiBrowserOrMarket(comp.packageName)) cleaned.component = null
        }
        val scheme = cleaned.data?.scheme?.lowercase()
        if (scheme == "market" || scheme == "intent" || scheme?.startsWith("mi") == true) {
            BrowserUrlRecovery.recoverForRedirect(cleaned)?.let { cleaned.data = it }
        }
        return cleaned
    }

    private fun isXiaomiBrowserOrMarket(pkg: String?): Boolean =
        XiaomiBrowserPackages.isBrowser(pkg) || XiaomiBrowserPackages.isMarket(pkg)

    private fun isXiaomiScheme(uri: Uri): Boolean {
        val scheme = uri.scheme ?: return false
        return scheme == "market" || scheme.startsWith("mi")
    }

    private fun isBrowserDownloadUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme ?: return false
        if (scheme != "market" && !scheme.startsWith("mi")) return false
        return XiaomiBrowserPackages.isBrowser(runCatching { uri.getQueryParameter("id") }.getOrNull())
    }
}
