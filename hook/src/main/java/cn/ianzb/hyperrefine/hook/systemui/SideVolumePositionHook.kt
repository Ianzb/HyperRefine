package cn.ianzb.hyperrefine.hook.systemui

import android.content.Context
import android.content.res.Configuration
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.status.HookStatusReporter
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 侧边音量条（收起态）竖直位置调节。
 *
 * 官方位置由 `MiuiVolumeDialogRes.getMarginTop(context, needDialog, expanded, screenHeight, viewHeight)`
 * 计算：竖屏用固定 dimen、横屏用居中 `(screenHeight - viewHeight) / 2`。开启「位置调节」后按高度百分比
 * 覆盖返回值：
 * 100% = 音量条上边缘贴屏幕顶部（marginTop 0），0% = 下边缘贴屏幕底部（marginTop = screenHeight - viewHeight）。
 * 竖屏 / 横屏各自一个百分比（不同 ROM / 方向的官方基准不同，分开更直观）。
 *
 * 只覆盖「收起态侧边音量条」（`needDialog=true && expanded=false` 且真实传入屏幕 / 视图高度）；
 * 展开面板、控制中心面板、以及 `getMarginTop` 的少参重载（内部 `screenHeight=viewHeight=0`）均不改变，
 * 避免影响官方其它布局（悬浮百分比文本 / 阴影与面板同源，会自然跟随）。
 *
 * 多应用音量入口带来的额外高度由 `AppVolumeEntryHook.shiftPanel` 继续做整体平移（保持平均高度不变），
 * 与本 hook 的基准位置叠加，互不冲突。
 */
class SideVolumePositionHook : BaseHook() {

    override val key: String = VolumeBarKeys.SIDE_POS

    override fun init() {
        val systemCl = target.classLoader ?: return
        PluginLoader.register(key, systemCl) { pluginCl ->
            val cls = Reflect.findClassIfExists(RES_CLASS, pluginCl)
            if (cls == null) {
                HookHelper.log("$tag: $RES_CLASS not found")
                return@register
            }
            val booleanType = Boolean::class.javaPrimitiveType!!
            val intType = Int::class.javaPrimitiveType!!
            val methods = cls.declaredMethods.filter {
                it.name == "getMarginTop" &&
                    it.returnType == intType &&
                    it.parameterTypes.contentEquals(
                        arrayOf(Context::class.java, booleanType, booleanType, intType, intType),
                    )
            }
            if (methods.isEmpty()) {
                HookHelper.log("$tag: getMarginTop(Context,boolean,boolean,int,int) not found")
                return@register
            }
            methods.forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param -> applyPosition(param) }
                }.onFailure { HookHelper.log("$tag: hook getMarginTop failed", it) }
            }
            HookStatusReporter.markInstalled(key)
            HookHelper.log("$tag: hooked MiuiVolumeDialogRes.getMarginTop")
        }
    }

    private fun applyPosition(param: cn.ianzb.hyperrefine.hook.xposed.HookParam) {
        val needDialog = param.args.getOrNull(1) as? Boolean ?: false
        val expanded = param.args.getOrNull(2) as? Boolean ?: false
        if (!needDialog || expanded) return
        val screenHeight = param.args.getOrNull(3) as? Int ?: return
        val viewHeight = param.args.getOrNull(4) as? Int ?: return
        if (screenHeight <= 0 || viewHeight <= 0 || viewHeight >= screenHeight) return
        val landscape = (param.args.getOrNull(0) as? Context)
            ?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
        val key = if (landscape) VolumeBarKeys.SIDE_POS_LANDSCAPE else VolumeBarKeys.SIDE_POS_PORTRAIT
        val default = if (landscape) {
            VolumeBarKeys.DEFAULT_SIDE_POS_LANDSCAPE
        } else {
            VolumeBarKeys.DEFAULT_SIDE_POS_PORTRAIT
        }
        val percent = HookPrefs.getFloat(key, default).coerceIn(0f, 100f)
        param.setResultValue(((1f - percent / 100f) * (screenHeight - viewHeight)).toInt())
    }

    companion object {
        private const val RES_CLASS = "com.android.systemui.miui.volume.MiuiVolumeDialogRes"
    }
}
