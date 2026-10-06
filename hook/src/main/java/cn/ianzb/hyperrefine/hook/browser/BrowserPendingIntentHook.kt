package cn.ianzb.hyperrefine.hook.browser

import android.content.Context
import android.content.Intent
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * `PendingIntent.getActivity` 创建时拦截（独立实现，思路参考 Fxxk-MiBrowser，MIT）。
 *
 * 小米互传等在创建 PendingIntent 前就把 http 链接转成了 `market://details?id=com.android.browser`，
 * 此时 startActivity 只能看到下载页。这里在创建阶段把 data 换回恢复的原始 URL。
 */
class BrowserPendingIntentHook : BaseHook() {

    override val key: String = BrowserKeys.PENDING_INTENT

    override fun init() {
        val loader = target.classLoader ?: return
        val cls = Reflect.findClassIfExists("android.app.PendingIntent", loader) ?: return
        val method = Reflect.findMethodIfExists(
            cls, "getActivity", Context::class.java, Int::class.javaPrimitiveType!!,
            Intent::class.java, Int::class.javaPrimitiveType!!,
        ) ?: return

        HookHelper.intercept(method) { chain ->
            val original = chain.getArg(2) as? Intent
            val context = chain.getArg(0) as? Context
            if (original == null || context == null) return@intercept chain.proceed()

            val data = original.data
            if (data?.scheme != "market" ||
                !XiaomiBrowserPackages.isBrowser(runCatching { data.getQueryParameter("id") }.getOrNull())
            ) {
                return@intercept chain.proceed()
            }
            val browser = BrowserDefaultResolver.resolve(context) ?: return@intercept chain.proceed()
            val recovered = BrowserUrlRecovery.recoverForRedirect(original) ?: return@intercept chain.proceed()

            val replacement = Intent(Intent.ACTION_VIEW, recovered).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                addCategory(Intent.CATEGORY_DEFAULT)
                if (browser.isDefault) setPackage(browser.packageName)
                flags = original.flags
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val args = chain.args.toMutableList()
            args[2] = replacement
            chain.proceed(args.toTypedArray())
        }
    }
}
