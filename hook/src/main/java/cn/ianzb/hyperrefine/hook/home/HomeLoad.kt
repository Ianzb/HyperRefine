package cn.ianzb.hyperrefine.hook.home

import cn.ianzb.hyperrefine.hook.base.BaseLoad

/**
 * 系统桌面（com.miui.home）目标 Load，具体 Hook 待补充。
 */
class HomeLoad : BaseLoad() {

    override val targetPackages: List<String> = listOf(TARGET_PACKAGE)

    companion object {
        const val TARGET_PACKAGE = "com.miui.home"
    }
}
