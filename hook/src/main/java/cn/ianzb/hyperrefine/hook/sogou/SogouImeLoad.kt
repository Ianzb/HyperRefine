package cn.ianzb.hyperrefine.hook.sogou

import cn.ianzb.hyperrefine.hook.base.BaseLoad
import cn.ianzb.hyperrefine.hook.base.PackageTarget

/**
 * 搜狗输入法（`com.sohu.inputmethod.sogou.xiaomi`）目标 Load。
 *
 * Hook 始终安装，回调内实时读取开关；用户切换设置后重新唤起键盘即可生效。
 */
class SogouImeLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(SogouImeKeys.PKG_SOGOU_IME)

    override fun onPackageLoaded(target: PackageTarget) {
        initHook(SogouImeGlassHook(), true)
    }
}
