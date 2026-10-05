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
            // 计算器自带新版 MIUIX ActionBarContainer（遮罩由 Paint + Path 渐变绘制），单独适配；
            // 关闭「应用专属适配」或「计算器」后回退为通用实现。
            val special = target.packageName in NEW_ACTIONBAR_PACKAGES &&
                TopBarKeys.calculatorAdvancedEnabled()
            val hook = if (special) CalculatorTopBarGradientHook() else TopBarGradientHook()
            initHook(hook, enabled)
        }
        // MIUIX NestedHeaderLayout 自带的滚动渐变遮罩（如笔记标题下方那条）由顶栏渐变接管后多余，隐藏之。
        initHook(NestedHeaderMaskHook(), enabled)
    }

    companion object {
        const val BAR_CLASS = "miuix.appcompat.internal.app.widget.ActionBarContainer"

        /** 自带新版 MIUIX（遮罩由 `Paint` + `Path` 渐变绘制）的应用，需用专用适配。 */
        val NEW_ACTIONBAR_PACKAGES: Set<String> = setOf("com.miui.calculator")

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
