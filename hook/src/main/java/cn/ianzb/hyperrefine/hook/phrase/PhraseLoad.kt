package cn.ianzb.hyperrefine.hook.phrase

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget

/**
 * 系统将常用语浮层加载到输入法进程。
 *
 * Hook 始终安装，回调内实时读取开关；用户切换设置后重新打开常用语页面即可生效。
 */
class PhraseLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(PhraseReorderKeys.PKG_SOGOU_IME)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(PhraseReorderHook(), true)
    }
}
