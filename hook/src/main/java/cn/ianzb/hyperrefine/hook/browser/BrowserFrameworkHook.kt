package cn.ianzb.hyperrefine.hook.browser

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 框架级 startActivity 拦截（独立实现，思路参考 Fxxk-MiBrowser，MIT）。
 *
 * 覆盖 `ContextImpl` / `Activity` / `ContextWrapper` / `Instrumentation` 各类重载，以及 MIUI
 * 的 `miui.util.ContextWrapper` / `android.miui.ActivityStarter`，统一交给 [BrowserIntentInterceptor]。
 */
class BrowserFrameworkHook : BaseHook() {

    override val key: String = BrowserKeys.INTENT_INTERCEPT

    override fun init() {
        val loader = target.classLoader ?: return
        val intent = Intent::class.java
        val bundle = Bundle::class.java
        val i = Int::class.javaPrimitiveType!!

        hook(loader, "android.app.ContextImpl", "startActivity", arrayOf(intent, bundle), 0, -1, 1)
        hook(loader, "android.app.ContextImpl", "startActivity", arrayOf(intent), 0, -1, -1)
        hook(loader, "android.app.Activity", "startActivity", arrayOf(intent, bundle), 0, -1, 1)
        hook(loader, "android.app.Activity", "startActivity", arrayOf(intent), 0, -1, -1)
        hook(loader, "android.app.Activity", "startActivityForResult", arrayOf(intent, i, bundle), 0, -1, 2)
        hook(loader, "android.content.ContextWrapper", "startActivity", arrayOf(intent, bundle), 0, -1, 1)

        hook(loader, "android.app.Instrumentation", "execStartActivity", arrayOf(
            Context::class.java, android.os.IBinder::class.java, android.os.IBinder::class.java,
            Activity::class.java, intent, i, bundle,
        ), 4, 0, 6)
        hook(loader, "android.app.Instrumentation", "execStartActivity", arrayOf(
            Context::class.java, android.os.IBinder::class.java, android.os.IBinder::class.java,
            Activity::class.java, intent, i,
        ), 4, 0, -1)
        hook(loader, "android.app.Instrumentation", "execStartActivity", arrayOf(
            Context::class.java, android.os.IBinder::class.java, android.os.IBinder::class.java,
            intent, i,
        ), 3, 0, -1)

        hook(loader, "miui.util.ContextWrapper", "startActivity", arrayOf(intent, bundle), 0, -1, 1)
        hook(loader, "android.miui.ActivityStarter", "startActivity", arrayOf(intent), 0, -1, -1)
    }

    private fun hook(
        loader: ClassLoader,
        className: String,
        methodName: String,
        params: Array<Class<*>>,
        intentIndex: Int,
        contextIndex: Int,
        optionsIndex: Int,
    ) {
        val clazz = Reflect.findClassIfExists(className, loader) ?: return
        val method = Reflect.findMethodIfExists(clazz, methodName, *params) ?: return
        runCatching {
            HookHelper.intercept(method) { chain ->
                val args = chain.args
                val intent = args.getOrNull(intentIndex) as? Intent
                val context = if (contextIndex == -1) {
                    chain.thisObject as? Context
                } else {
                    args.getOrNull(contextIndex) as? Context
                }
                val options = if (optionsIndex >= 0) args.getOrNull(optionsIndex) as? Bundle else null
                BrowserIntentInterceptor.handle(intent, context, options, chain)
            }
        }.onFailure { HookHelper.log("$tag: hook $className.$methodName failed", it) }
    }
}
