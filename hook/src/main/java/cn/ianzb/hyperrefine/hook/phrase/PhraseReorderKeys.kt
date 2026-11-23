package cn.ianzb.hyperrefine.hook.phrase

import cn.ianzb.hyperrefine.hook.prefs.HookPrefs

/**
 * 「剪贴板与常用语」常用语排序。
 *
 * 目标应用是 HyperOS 系统组件 `com.miui.phrase`，这里只保存本地显示顺序，
 * 不修改官方数据库结构，避免与账号同步冲突。
 */
object PhraseReorderKeys {

    const val PKG_SOGOU_IME = "com.sohu.inputmethod.sogou.xiaomi"

    const val ENABLED = "phrase_reorder_enabled"

    fun isEnabled(): Boolean = HookPrefs.getBoolean(ENABLED, false)
}
