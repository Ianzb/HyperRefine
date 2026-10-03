package cn.ianzb.hyperrefine.hook.systemui.glass

import android.content.Context
import android.graphics.drawable.Drawable
import android.view.View
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 系统音量面板「展开态」官方材质链路（反射控制中心插件类）。
 *
 * 复刻官方 `MiuiVolumeDialogMotion.updateExpandBgState` 的判定树：
 * - OS4（plugin 18.x）：`Util.isAdvancedMaterialEffective` 为真 → advanced material
 *   （`MiBackgroundStyle` 玻璃 token + `setMiBgBlur`）；否则 S 版 blur；否则静态背景。
 * - OS3（plugin 17.x）：`MiBlurCompat.getBackgroundBlurOpenedInDefaultTheme` 为真 →
 *   `Util.setMiViewBlurAndBlendColor`；否则 S 版 blur；否则静态背景。
 *
 * 两代插件各自缺失对方首查 API，软探测恒 false，同一棵树在两代上命中各自官方分支。
 *
 * 思路参考 SoundMan（GPL-3.0），按 HyperOS 4 插件签名自主实现。
 */
class OfficialExpandedMaterial(
    private val classLoader: ClassLoader,
    private val context: Context,
) {

    enum class Mode { ADVANCED, THEME_BLUR, BLUR_FOR_S, STATIC }

    /** 应用官方展开材质（需视图已 attach）。 */
    fun apply(view: View): Mode {
        view.background = null
        val advanced = isAdvancedMaterialEffective()
        val themeBlur = themeBlurOpened()
        val lowEnd = isLowEndDevice()
        val defaultPluginTheme = isDefaultPluginTheme()
        return when {
            advanced -> {
                if (applyAdvanced(view)) Mode.ADVANCED else fallback(view)
            }
            themeBlur -> {
                if (applyThemeBlur(view)) Mode.THEME_BLUR else fallback(view)
            }
            !lowEnd && defaultPluginTheme -> {
                if (applyBlurForS(view)) Mode.BLUR_FOR_S else fallback(view)
            }
            else -> fallback(view)
        }
    }

    private fun fallback(view: View): Mode {
        val bg = expandedBackground()
        if (bg != null) {
            view.background = bg
            return Mode.STATIC
        }
        return Mode.STATIC
    }

    /** 官方展开态圆角轮廓；返回官方半径（px），失败返回 0。 */
    fun applyOutline(view: View): Int {
        val radius = invoke(RES, null, "getBgRadius", context, quiet = true) as? Int ?: 0
        val advanced = isAdvancedMaterialEffective()
        val ok = invoke(MI_BLUR_COMPAT, null, "setOutlineRoundRect", view, radius.toFloat(), advanced, quiet = true) !== FAILED ||
            invoke(UTIL, null, "setRoundRect", view, radius.toFloat(), quiet = true) !== FAILED
        if (ok) view.clipToOutline = true
        return radius
    }

    /** 关闭材质。 */
    fun clear(view: View) {
        invoke(UTIL, null, "setMiBgBlur", view, 0, false, quiet = true)
        invoke(UTIL, null, "setMiBgBlur", view, 0, quiet = true)
        invoke(MI_BLUR_COMPAT, null, "setMiViewBlurModeCompat", view, 0, quiet = true)
        invoke(UTIL, null, "setViewBlurForS", view, 0, quiet = true)
        view.background = null
    }

    private fun isAdvancedMaterialEffective(): Boolean =
        invoke(UTIL, null, "isAdvancedMaterialEffective", context, quiet = true) as? Boolean ?: false

    private fun themeBlurOpened(): Boolean =
        invoke(MI_BLUR_COMPAT, null, "getBackgroundBlurOpenedInDefaultTheme", context, quiet = true) as? Boolean
            ?: false

    private fun isLowEndDevice(): Boolean =
        invoke(BLUR_UTILS, null, "isLowEndDevice", quiet = true) as? Boolean ?: false

    private fun isDefaultPluginTheme(): Boolean {
        val themeClass = loadClass(THEME_UTILS) ?: return true
        val instance = runCatching { themeClass.getField("INSTANCE").get(null) }.getOrNull() ?: return true
        return invoke(THEME_UTILS, instance, "getDefaultPluginTheme", quiet = true) as? Boolean ?: true
    }

    private fun applyAdvanced(view: View): Boolean {
        val blandColor = invoke(RES, null, "getBgBlandColor", true, quiet = true).takeUnless { it === FAILED }
            ?: return false
        val styleClass = loadClass(STYLE) ?: return false
        val instance = runCatching { styleClass.getField("INSTANCE").get(null) }.getOrNull() ?: return false
        val glassToken = invoke(STYLE, instance, "getVOLUMPANEL_EXPAND_GLASS_TOKEN", quiet = true)
            .takeUnless { it === FAILED } ?: return false
        if (invoke(UTIL, null, "setMiViewBackgroundStyle", view, 1, blandColor, glassToken, quiet = true) === FAILED) {
            return false
        }
        val radius = invoke(RES, null, "getBlandBlurRadius", context, quiet = true) as? Int ?: return false
        return invoke(UTIL, null, "setMiBgBlur", view, radius, true, quiet = true) !== FAILED
    }

    private fun applyThemeBlur(view: View): Boolean {
        val blandColor = invoke(RES, null, "getBgBlandColor", true, quiet = true).takeUnless { it === FAILED }
            ?: return false
        return invoke(UTIL, null, "setMiViewBlurAndBlendColor", view, 1, blandColor, quiet = true) !== FAILED
    }

    private fun applyBlurForS(view: View): Boolean {
        val radius = invoke(RES, null, "getBgRadius", context, quiet = true) as? Int ?: return false
        return invoke(UTIL, null, "setViewBlurForS", view, radius, quiet = true) !== FAILED
    }

    private fun expandedBackground(): Drawable? {
        val resId = invoke(RES, null, "getBgRes", true, quiet = true) as? Int ?: return null
        if (resId == 0) return null
        return runCatching { context.resources.getDrawable(resId, context.theme) }.getOrNull()
    }

    private fun invoke(
        className: String,
        target: Any?,
        methodName: String,
        vararg args: Any?,
        quiet: Boolean = false,
    ): Any? {
        val clazz = loadClass(className) ?: return FAILED
        val method = (clazz.methods.asSequence() + clazz.declaredMethods.asSequence()).firstOrNull {
            it.name == methodName && parametersMatch(it.parameterTypes, args)
        } ?: run {
            if (!quiet) HookHelper.log("ExpandedMaterial: missing $className.$methodName")
            return FAILED
        }
        method.isAccessible = true
        return runCatching { method.invoke(target, *args) }
            .getOrElse {
                HookHelper.log("ExpandedMaterial: $className.$methodName failed", it)
                FAILED
            }
    }

    private fun loadClass(name: String): Class<*>? = Reflect.findClassIfExists(name, classLoader)

    private fun parametersMatch(types: Array<Class<*>>, args: Array<out Any?>): Boolean {
        if (types.size != args.size) return false
        return types.indices.all { index ->
            val value = args[index] ?: return@all !types[index].isPrimitive
            types[index].isInstance(value) || when (types[index]) {
                java.lang.Boolean.TYPE -> value is Boolean
                java.lang.Integer.TYPE -> value is Int
                java.lang.Float.TYPE -> value is Float
                else -> false
            }
        }
    }

    private companion object {
        const val UTIL = "com.android.systemui.miui.volume.Util"
        const val RES = "com.android.systemui.miui.volume.MiuiVolumeDialogRes"
        const val STYLE = "miui.systemui.util.MiBackgroundStyle"
        const val MI_BLUR_COMPAT = "miui.systemui.util.MiBlurCompat"
        const val BLUR_UTILS = "miui.systemui.util.BlurUtils"
        const val THEME_UTILS = "miui.systemui.util.ThemeUtils"
        val FAILED = Any()
    }
}
