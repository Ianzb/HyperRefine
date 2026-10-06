package cn.ianzb.hyperrefine.hook.browser

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 伪装小米浏览器「已安装」，并把互传 / 复制直达里的小米浏览器图标、名称替换为默认浏览器
 * （独立实现，思路参考 Fxxk-MiBrowser，MIT）。
 *
 * 系统组件在检测到小米浏览器未安装时，会把 http 链接改写成 `market://details?id=com.android.browser`
 * 下载页；这里让 `PackageManager` 认为它存在，从源头避免改写。含自查询放行，避免浏览器进程读自身信息被拦截。
 */
class BrowserPackageHook : BaseHook() {

    override val key: String = BrowserKeys.FAKE_INSTALLED

    override fun init() {
        val loader = target.classLoader ?: return
        val pmClass = Reflect.findClassIfExists("android.app.ApplicationPackageManager", loader)
            ?: Reflect.findClassIfExists("android.app.ApplicationPackageManager")
            ?: return

        val intType = Int::class.javaPrimitiveType!!
        val packageInfoFlags = runCatching { PackageManager.PackageInfoFlags::class.java }.getOrNull()
        val appInfoFlags = runCatching { PackageManager.ApplicationInfoFlags::class.java }.getOrNull()

        hookFake(loader, pmClass, "getPackageInfo", arrayOf(String::class.java, intType)) { buildFakePackageInfo(it) }
        packageInfoFlags?.let {
            hookFake(loader, pmClass, "getPackageInfo", arrayOf(String::class.java, it)) { p -> buildFakePackageInfo(p) }
        }
        hookFake(loader, pmClass, "getApplicationInfo", arrayOf(String::class.java, intType)) { buildFakeApplicationInfo(it) }
        appInfoFlags?.let {
            hookFake(loader, pmClass, "getApplicationInfo", arrayOf(String::class.java, it)) { p -> buildFakeApplicationInfo(p) }
        }

        hookLaunchIntent(loader, pmClass)
        hookIconsAndLabel(loader, pmClass)
    }

    private fun hookFake(
        loader: ClassLoader,
        pmClass: Class<*>,
        name: String,
        params: Array<Class<*>>,
        builder: (String) -> Any,
    ) {
        val method = Reflect.findMethodIfExists(pmClass, name, *params) ?: return
        runCatching {
            HookHelper.hookBefore(method) { param ->
                val pkg = param.args.getOrNull(0) as? String ?: return@hookBefore
                if (!XiaomiBrowserPackages.isBrowser(pkg)) return@hookBefore
                if (currentProcessName() == pkg) return@hookBefore
                param.setResultValue(builder(pkg))
            }
        }.onFailure { HookHelper.log("$tag: hook $name failed", it) }
    }

    private fun hookLaunchIntent(loader: ClassLoader, pmClass: Class<*>) {
        val method = Reflect.findMethodIfExists(pmClass, "getLaunchIntentForPackage", String::class.java) ?: return
        runCatching {
            HookHelper.hookAfter(method) { param ->
                val pkg = param.args.getOrNull(0) as? String ?: return@hookAfter
                if (XiaomiBrowserPackages.isBrowser(pkg) && param.result == null) {
                    param.result = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_LAUNCHER)
                        setPackage(pkg)
                    }
                }
            }
        }.onFailure { HookHelper.log("$tag: hook getLaunchIntentForPackage failed", it) }
    }

    private fun hookIconsAndLabel(loader: ClassLoader, pmClass: Class<*>) {
        Reflect.findMethodIfExists(pmClass, "getApplicationIcon", String::class.java)?.let { method ->
            runCatching {
                HookHelper.hookBefore(method) { param ->
                    val pkg = param.args.getOrNull(0) as? String ?: return@hookBefore
                    if (!shouldSwapIcon(pkg)) return@hookBefore
                    currentApplication()?.let { ctx ->
                        runCatching { ctx.packageManager.getApplicationIcon(defaultBrowserPkg(ctx) ?: return@hookBefore) }
                            .onSuccess { param.setResultValue(it) }
                    }
                }
            }
        }
        Reflect.findMethodIfExists(pmClass, "getApplicationIcon", ApplicationInfo::class.java)?.let { method ->
            runCatching {
                HookHelper.hookBefore(method) { param ->
                    val info = param.args.getOrNull(0) as? ApplicationInfo ?: return@hookBefore
                    if (!shouldSwapIcon(info.packageName)) return@hookBefore
                    currentApplication()?.let { ctx ->
                        runCatching { ctx.packageManager.getApplicationIcon(defaultBrowserPkg(ctx) ?: return@hookBefore) }
                            .onSuccess { param.setResultValue(it) }
                    }
                }
            }
        }
        Reflect.findMethodIfExists(pmClass, "getApplicationLabel", ApplicationInfo::class.java)?.let { method ->
            runCatching {
                HookHelper.hookBefore(method) { param ->
                    val info = param.args.getOrNull(0) as? ApplicationInfo ?: return@hookBefore
                    if (!shouldSwapHyperAiLabel(info.packageName)) return@hookBefore
                    val ctx = currentApplication() ?: return@hookBefore
                    val pkg = defaultBrowserPkg(ctx) ?: return@hookBefore
                    runCatching {
                        val ai = ctx.packageManager.getApplicationInfo(
                            pkg, PackageManager.ApplicationInfoFlags.of(0L)
                        )
                        param.setResultValue(ctx.packageManager.getApplicationLabel(ai))
                    }
                }
            }
        }
    }

    private fun shouldSwapIcon(pkg: String?): Boolean {
        if (!XiaomiBrowserPackages.isBrowser(pkg)) return false
        val proc = currentProcessName()
        return proc == BrowserKeys.PKG_MISHARE || proc == BrowserKeys.PKG_AI_ENGINE
    }

    private fun shouldSwapHyperAiLabel(pkg: String?): Boolean {
        if (!XiaomiBrowserPackages.isBrowser(pkg)) return false
        return currentProcessName() == BrowserKeys.PKG_AI_ENGINE
    }

    private fun defaultBrowserPkg(ctx: Context): String? {
        val info = BrowserDefaultResolver.resolve(ctx) ?: return null
        return if (info.isDefault) info.packageName else null
    }

    private fun buildFakePackageInfo(packageName: String): PackageInfo = PackageInfo().apply {
        this.packageName = packageName
        versionName = "1.0"
        @Suppress("DEPRECATION")
        versionCode = 1
        applicationInfo = buildFakeApplicationInfo(packageName)
    }

    private fun buildFakeApplicationInfo(packageName: String): ApplicationInfo = ApplicationInfo().apply {
        this.packageName = packageName
        flags = ApplicationInfo.FLAG_SYSTEM
        val stub = "/system/app/$packageName/$packageName.apk"
        sourceDir = stub
        publicSourceDir = stub
    }

    private fun currentApplication(): Application? = runCatching {
        Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? Application
    }.getOrNull()

    private fun currentProcessName(): String? = runCatching { Application.getProcessName() }.getOrNull()
}
