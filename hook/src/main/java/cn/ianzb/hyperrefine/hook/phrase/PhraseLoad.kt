package cn.ianzb.hyperrefine.hook.phrase

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget

/**
 * 「剪贴板与常用语」（`com.miui.phrase`）目标 Load。
 *
 * Hook 始终安装，回调内实时读取开关；用户切换设置后重新打开常用语页面即可生效。
 */
class PhraseLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(
        PhraseReorderKeys.PKG_PHRASE,
        // 输入法弹出的常用语窗口实际运行在输入法进程内，窗口 tag 仍是 com.miui.phrase。
        PhraseReorderKeys.PKG_SOGOU_IME,
    )

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(PhraseReorderHook(), true)
    }
}
