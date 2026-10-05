package cn.ianzb.hyperrefine.hook.systemui

import android.content.Context
import android.content.res.Configuration
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.status.HookStatusReporter
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 侧边音量条二级（展开）面板的整体竖直位置调节。
 *
 * 展开面板的位置由官方 `MiuiVolumeDialogRes.getMarginTop(context, needDialog, expanded, screenHeight, viewHeight)`
 * 计算（`MiuiVolumeDialogMotion.getExpandedStates` 取它作为 `mVolumeView` 的 topMargin，展开背景也用它）。
 * 本 hook 在 `needDialog=true && expanded=true` 时按百分比覆盖返回值：
 * 100% = 面板上边缘贴屏幕顶部（marginTop 0），0% = 下边缘贴屏幕底部（marginTop = screenHeight - viewHeight）。
 * 竖屏 / 横屏各自一个百分比；收起态由 [SideVolumePositionHook] 单独处理，二者互不影响（同一个方法、条件互斥）。
 */
class SidePanelPositionHook : BaseHook() {

    override val key: String = VolumeBarKeys.L2_POS

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
            HookHelper.log("$tag: hooked expanded getMarginTop")
        }
    }

    private fun applyPosition(param: HookParam) {
        if (param.args.getOrNull(1) != true || param.args.getOrNull(2) != true) return
        val screenHeight = param.args.getOrNull(3) as? Int ?: return
        val viewHeight = param.args.getOrNull(4) as? Int ?: return
        if (screenHeight <= 0 || viewHeight <= 0 || viewHeight >= screenHeight) return
        val landscape = (param.args.getOrNull(0) as? Context)
            ?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
        val key = if (landscape) VolumeBarKeys.L2_POS_LANDSCAPE else VolumeBarKeys.L2_POS_PORTRAIT
        val default = if (landscape) {
            VolumeBarKeys.DEFAULT_L2_POS_LANDSCAPE
        } else {
            VolumeBarKeys.DEFAULT_L2_POS_PORTRAIT
        }
        val percent = HookPrefs.getFloat(key, default).coerceIn(0f, 100f)
        param.setResultValue(((1f - percent / 100f) * (screenHeight - viewHeight)).toInt())
    }

    companion object {
        private const val RES_CLASS = "com.android.systemui.miui.volume.MiuiVolumeDialogRes"
    }
}
