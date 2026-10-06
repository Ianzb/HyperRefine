package cn.ianzb.hyperrefine.hook.browser

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 浏览器功能的通用辅助（独立实现，思路参考 Fxxk-MiBrowser，MIT）。
 */
object BrowserCompat {

    fun currentApplication(): Application? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()

    fun currentProcessName(): String? = runCatching { Application.getProcessName() }.getOrNull()

    fun defaultBrowserLabel(context: Context): String? {
        val info = BrowserDefaultResolver.resolve(context) ?: return null
        if (!info.isDefault) return null
        return runCatching {
            val ai = context.packageManager.getApplicationInfo(
                info.packageName, PackageManager.ApplicationInfoFlags.of(0L)
            )
            context.packageManager.getApplicationLabel(ai).toString()
        }.getOrNull()
    }

    fun appIcon(context: Context, pkgName: String): Icon? {
        val drawable = runCatching { context.packageManager.getApplicationIcon(pkgName) }.getOrNull() ?: return null
        val bitmap = if (drawable is BitmapDrawable && drawable.bitmap != null) {
            drawable.bitmap
        } else {
            val w = drawable.intrinsicWidth.coerceAtLeast(1)
            val h = drawable.intrinsicHeight.coerceAtLeast(1)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            bmp
        }
        return Icon.createWithBitmap(bitmap)
    }

    /** 通知点击是否会打开小米浏览器（按其 PendingIntent 的目标判断）。 */
    fun wouldOpenMiBrowser(notification: Notification): Boolean {
        val pi = notification.contentIntent ?: return false
        val intent = extractIntentFromPendingIntent(pi)
        if (intent != null) {
            if (XiaomiBrowserPackages.isBrowser(intent.`package`)) return true
            if (XiaomiBrowserPackages.isBrowser(intent.component?.packageName)) return true
            return false
        }
        val iconPkg = notificationIconPackage(notification)
        return XiaomiBrowserPackages.isBrowser(iconPkg)
    }

    fun extractIntentFromPendingIntent(pi: PendingIntent): Intent? {
        runCatching { Reflect.callMethod(pi, "getIntent") as? Intent }.getOrNull()?.let { return it }
        for (name in arrayOf("mIntent", "mTargetIntent")) {
            runCatching { Reflect.getObjectField(pi, name) as? Intent }.getOrNull()?.let { return it }
        }
        for (field in pi.javaClass.declaredFields) {
            if (!Intent::class.java.isAssignableFrom(field.type)) continue
            runCatching { field.isAccessible = true; field.get(pi) as? Intent }.getOrNull()?.let { return it }
        }
        return null
    }

    fun notificationIconPackage(notification: Notification): String? = runCatching {
        val icon = Reflect.getObjectField(notification, "mSmallIcon")
        Reflect.callMethod(icon!!, "getResPackage") as? String
    }.getOrNull()

    /** 替换小米浏览器图标为默认浏览器图标（small/large/小米自定义位图）。 */
    fun replaceNotificationIcon(notification: Notification, context: Context, defaultPkg: String): Boolean {
        val icon = appIcon(context, defaultPkg) ?: return false
        var ok = false
        runCatching { Reflect.getObjectField(notification, "mSmallIcon") }.getOrNull()?.let {
            setField(notification, "mSmallIcon", icon); ok = true
        }
        runCatching { Reflect.getObjectField(notification, "mLargeIcon") }.getOrNull()?.let {
            setField(notification, "mLargeIcon", icon); ok = true
        }
        notification.extras?.let { extras ->
            runCatching { extras.putParcelable("miui.appIcon", icon) }
            runCatching {
                extras.getBundle("miui.focus.pics")?.let { focus ->
                    focus.putParcelable("miui.focus.pic_image", icon)
                    focus.putParcelable("miui.land.pic_image", icon)
                }
            }
        }
        return ok
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = runCatching { Reflect.findField(target.javaClass, name) }.getOrNull() ?: return
        if (!runCatching { field.isAccessible = true; field.set(target, value) }.isSuccess) {
            cn.ianzb.hyperrefine.hook.passkey.UnsafeField.setObject(field, target, value)
        }
    }
}
