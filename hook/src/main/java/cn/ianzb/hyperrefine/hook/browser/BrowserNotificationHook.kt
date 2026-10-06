package cn.ianzb.hyperrefine.hook.browser

import android.app.Notification
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper

/**
 * 通知图标替换（目标 `com.miui.mishare.connectivity` / `com.xiaomi.aicr`）。
 *
 * - 互传：通知内容含网页 / 浏览器字样，或近期缓存过来源 URL 时，把小米浏览器图标换成默认浏览器图标；
 * - AI 引擎复制直达：`id==111` 且点击会打开小米浏览器时替换。
 *
 * 独立实现（思路参考 Fxxk-MiBrowser，MIT）。
 */
class BrowserNotificationHook : BaseHook() {

    override val key: String = BrowserKeys.NOTIFICATION_ICON

    override fun init() {
        val loader = target.classLoader ?: return
        val nm = cn.ianzb.hyperrefine.hook.xposed.Reflect.findClassIfExists("android.app.NotificationManager", loader)
            ?: return

        cn.ianzb.hyperrefine.hook.xposed.Reflect.findMethodIfExists(
            nm, "notify", Int::class.javaPrimitiveType!!, Notification::class.java
        )?.let { method ->
            runCatching {
                HookHelper.hookBefore(method) { param ->
                    val id = param.args.getOrNull(0) as? Int ?: return@hookBefore
                    val notification = param.args.getOrNull(1) as? Notification ?: return@hookBefore
                    handle(id, notification)
                }
            }
        }
        cn.ianzb.hyperrefine.hook.xposed.Reflect.findMethodIfExists(
            nm, "notify", String::class.java, Int::class.javaPrimitiveType!!, Notification::class.java
        )?.let { method ->
            runCatching {
                HookHelper.hookBefore(method) { param ->
                    val id = param.args.getOrNull(1) as? Int ?: return@hookBefore
                    val notification = param.args.getOrNull(2) as? Notification ?: return@hookBefore
                    handle(id, notification)
                }
            }
        }
    }

    private fun handle(id: Int, notification: Notification) {
        val process = BrowserCompat.currentProcessName()
        val replace = when (process) {
            BrowserKeys.PKG_MISHARE -> shouldReplaceMiShare(notification)
            BrowserKeys.PKG_AI_ENGINE -> id == 111 &&
                notification.extras?.getString("copyText") != null &&
                BrowserCompat.wouldOpenMiBrowser(notification)
            else -> false
        }
        if (!replace) return

        val context = BrowserCompat.currentApplication() ?: return
        val browser = BrowserDefaultResolver.resolve(context) ?: return
        BrowserCompat.replaceNotificationIcon(notification, context, browser.packageName)
    }

    private fun shouldReplaceMiShare(notification: Notification): Boolean {
        if (BrowserUrlRecovery.hasRecent()) return true
        val extras = notification.extras ?: return false
        val title = extras.getCharSequence("android.title")?.toString().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString().orEmpty()
        val bigText = extras.getCharSequence("android.bigText")?.toString().orEmpty()
        val content = "$title\n$text\n$bigText"
        return content.contains("http") ||
            content.contains("浏览器") ||
            content.contains("browser", ignoreCase = true) ||
            content.contains("打开") ||
            content.contains("链接") ||
            content.contains("网页")
    }
}
