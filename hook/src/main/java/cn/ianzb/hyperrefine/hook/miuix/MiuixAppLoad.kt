package cn.ianzb.hyperrefine.hook.miuix

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * MIUIX 应用目标 Load：顶栏渐变模糊。
 *
 * 参考 HyperBackground 的作用域，覆盖带 `miuix.appcompat.internal.app.widget.ActionBarContainer`
 * 的系统应用（设置 / 短信 / 联系人 / 电话 / 时钟 / 文件管理 / 主题 / 笔记 / 计算器等）。
 * 只有确实存在该顶栏类的进程才安装 hook，避免无谓的 DexKit 开销。
 */
class MiuixAppLoad : BaseLoad() {

    override val targetPackages: List<String> = MIUIX_PACKAGES

    override fun onPackageLoaded(target: PackageTarget) {
        val loader = target.classLoader ?: return
        val enabled = HookPrefs.getBoolean(TopBarKeys.KEY, false)
        if (Reflect.findClassIfExists(BAR_CLASS, loader) != null) {
            initHook(TopBarGradientHook(), enabled)
        }
        // MIUIX NestedHeaderLayout 自带的滚动渐变遮罩（如笔记标题下方那条）由顶栏渐变接管后多余，隐藏之。
        initHook(NestedHeaderMaskHook(), enabled)
    }

    companion object {
        const val BAR_CLASS = "miuix.appcompat.internal.app.widget.ActionBarContainer"

        val MIUIX_PACKAGES: List<String> = listOf(
            // 设置 / 通信
            "com.android.settings",
            "com.android.mms",
            "com.android.contacts",
            "com.android.phone",
            // 常用系统应用
            "com.android.deskclock",
            "com.android.fileexplorer",
            "com.android.providers.downloads.ui",
            "com.android.quicksearchbox",
            "com.android.soundrecorder",
            "com.android.thememanager",
            "com.android.updater",
            "com.miui.aod",
            "com.miui.audiomonitor",
            "com.miui.backup",
            "com.miui.calculator",
            "com.miui.cloudbackup",
            "com.miui.cloudservice",
            "com.miui.huanji",
            "com.miui.notes",
            "com.miui.powerkeeper",
            "com.miui.securitycenter",
            "com.miui.securitycore",
            "com.xiaomi.account",
            "com.xiaomi.misettings",
        )
    }
}
