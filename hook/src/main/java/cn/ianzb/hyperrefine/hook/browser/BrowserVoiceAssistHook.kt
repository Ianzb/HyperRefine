package cn.ianzb.hyperrefine.hook.browser

import android.content.Context
import android.content.Intent
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 小爱识屏 / 超级小爱（目标 `com.miui.voiceassist`）。
 *
 * 超级小爱在调用 `startActivity` 前会通过 `com.xiaomi.voiceassistant.utils.t2.isIntentAvailable(Intent, Context)`
 * 检查指向小米浏览器的 Intent；这里在该阶段恢复原始 http(s) 链接并改投系统默认浏览器，返回 true。
 * 同时缓存 `openInBrowser` 收到的 URL。
 *
 * 独立实现（思路参考 Fxxk-MiBrowser，MIT）。
 */
class BrowserVoiceAssistHook : BaseHook() {

    override val key: String = BrowserKeys.VOICE_ASSIST

    override fun init() {
        val loader = target.classLoader ?: return
        hookIsIntentAvailable(loader)
        hookOpenInBrowser(loader)
    }

    private fun hookIsIntentAvailable(loader: ClassLoader) {
        val cls = Reflect.findClassIfExists("com.xiaomi.voiceassistant.utils.t2", loader) ?: return
        val method = Reflect.findMethodIfExists(cls, "isIntentAvailable", Intent::class.java, Context::class.java)
            ?: return
        HookHelper.hookBefore(method) { param ->
            val intent = param.args.getOrNull(0) as? Intent ?: return@hookBefore
            val context = param.args.getOrNull(1) as? Context ?: return@hookBefore
            if (rewriteToDefault(intent, context)) param.setResultValue(true)
        }
    }

    private fun hookOpenInBrowser(loader: ClassLoader) {
        val cls = Reflect.findClassIfExists("com.xiaomi.voiceassistant.utils.t2", loader) ?: return
        val method = Reflect.findMethodIfExists(cls, "openInBrowser", String::class.java) ?: return
        HookHelper.hookBefore(method) { param ->
            BrowserUrlRecovery.rememberFromValue(param.args.getOrNull(0))
        }
    }

    /** 若 Intent 指向小米浏览器，改写为指向默认浏览器的 ACTION_VIEW。 */
    private fun rewriteToDefault(intent: Intent, context: Context): Boolean {
        val targetsBrowser = XiaomiBrowserPackages.isBrowser(intent.`package`) ||
            XiaomiBrowserPackages.isBrowser(intent.component?.packageName)
        if (!targetsBrowser) return false

        val recovered = BrowserUrlRecovery.recoverForRedirect(intent) ?: return false
        intent.action = Intent.ACTION_VIEW
        intent.data = recovered
        intent.component = null
        intent.`package` = null
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.addCategory(Intent.CATEGORY_DEFAULT)
        BrowserDefaultResolver.resolve(context)?.let { if (it.isDefault) intent.setPackage(it.packageName) }
        return true
    }
}
