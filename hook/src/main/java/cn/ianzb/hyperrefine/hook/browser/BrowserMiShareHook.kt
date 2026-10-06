package cn.ianzb.hyperrefine.hook.browser

import android.content.Intent
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 小米互传链接缓存（目标 `com.miui.mishare.connectivity`）。
 *
 * 互传接收链接时会先把原始 URL 交给 `LyraShareListenerService`，随后才改写成下载页；
 * 这里在 `onStartCommand` 阶段缓存原始网页地址，供后续恢复与通知图标替换。
 */
class BrowserMiShareHook : BaseHook() {

    override val key: String = BrowserKeys.MISHARE

    override fun init() {
        val loader = target.classLoader ?: return
        val cls = Reflect.findClassIfExists(
            "com.miui.mishare.connectivity.refactor.lyra.LyraShareListenerService", loader
        ) ?: return
        val method = Reflect.findMethodIfExists(
            cls, "onStartCommand", Intent::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!
        ) ?: return
        HookHelper.hookBefore(method) { param ->
            (param.args.getOrNull(0) as? Intent)?.let { BrowserUrlRecovery.rememberFromIntent(it) }
        }
    }
}
