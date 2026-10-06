package cn.ianzb.hyperrefine.hook.browser

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri

/**
 * 解析系统默认浏览器（独立实现，思路参考 Fxxk-MiBrowser，MIT）。
 *
 * 优先取 `MATCH_DEFAULT_ONLY` 的默认处理者；若默认恰为小米浏览器则视为未设置，退回到
 * 首个非小米浏览器（`isDefault=false`，用系统选择器）。全为小米浏览器时退回第一个。
 */
object BrowserDefaultResolver {

    data class BrowserInfo(
        val packageName: String,
        val activityName: String?,
        val isDefault: Boolean,
    )

    private fun probeIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://www.example.com")).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addCategory(Intent.CATEGORY_DEFAULT)
        }

    fun resolve(context: Context): BrowserInfo? {
        val pm = context.packageManager
        val intent = probeIntent()

        val resolved: ResolveInfo? = runCatching {
            pm.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
            )
        }.getOrNull()
        if (resolved?.activityInfo != null) {
            val pkg = resolved.activityInfo.packageName
            if (!XiaomiBrowserPackages.isBrowser(pkg)) {
                return BrowserInfo(pkg, resolved.activityInfo.name, true)
            }
        }

        val all: List<ResolveInfo> = runCatching {
            pm.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        }.getOrDefault(emptyList())

        all.firstOrNull { !XiaomiBrowserPackages.isBrowser(it.activityInfo.packageName) }?.let {
            return BrowserInfo(it.activityInfo.packageName, it.activityInfo.name, false)
        }
        val fallback = all.firstOrNull() ?: return null
        return BrowserInfo(fallback.activityInfo.packageName, fallback.activityInfo.name, false)
    }

    fun buildWebIntent(data: Uri, pkg: String?): Intent =
        Intent(Intent.ACTION_VIEW, data).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addCategory(Intent.CATEGORY_DEFAULT)
            if (pkg != null) setPackage(pkg)
        }
}
