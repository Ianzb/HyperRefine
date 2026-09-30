package cn.ianzb.hyperrefine.hook.systemui.glass

import android.view.View
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Method

/**
 * 控制中心「柔光玻璃 / 玻璃材质」接口的反射门面。
 *
 * 两类接口：
 * 1. 材质 token 接口：`ControlCenterMaterialTokens` +
 *    `MaterialBackgroundExt.setMaterialBackground(view, token, animate)`。
 * 2. 底层玻璃接口（一级界面卡片/图标同款）：
 *    `MiBackgroundStyle.setMiBackgroundStyle(view, blendToken, bionicsToken)` +
 *    `MiBlurCompat.setMiViewBlurModeCompat(view, 1)`；
 *    按状态选用 `DEFAULT_GLASS_TOKEN` / `ACTIVATED_GLASS_TOKEN` 实现强调色。
 *
 * 本类只调用系统自身接口，不复制任何系统代码。
 */
object CcGlassApi {

    private const val TOKENS_CLASS =
        "miui.systemui.controlcenter.material.ControlCenterMaterialTokens"
    private const val MATERIAL_EXT_CLASS =
        "miui.systemui.ui.material.MaterialBackgroundExt"
    private const val MI_STYLE_CLASS = "miui.systemui.util.MiBackgroundStyle"
    private const val COLOR_BLEND_CLASS = "miui.systemui.util.MiuiColorBlendToken"
    private const val BLUR_COMPAT_CLASS = "miui.systemui.util.MiBlurCompat"

    private var tokensInstance: Any? = null
    private var applyMethod: Method? = null

    private var styleInstance: Any? = null
    private var colorBlendInstance: Any? = null
    private var setBackgroundStyleMethod: Method? = null
    private var setViewBlurModeMethod: Method? = null

    private val tokenCache = HashMap<String, Any?>()
    private val bionicsCache = HashMap<String, Any?>()
    private val blendCache = HashMap<String, Any?>()

    @Volatile
    private var initialized = false

    /** 插件 ClassLoader 就绪后初始化；只需成功一次。 */
    @Synchronized
    fun init(pluginCl: ClassLoader) {
        if (initialized) return
        initialized = true

        runCatching {
            val tokensCl = Reflect.findClassIfExists(TOKENS_CLASS, pluginCl)
            if (tokensCl != null) tokensInstance = Reflect.getStaticObjectField(tokensCl, "INSTANCE")
        }.onFailure { HookHelper.log("CcGlass: tokens init failed", it) }

        runCatching {
            val extCl = Reflect.findClassIfExists(MATERIAL_EXT_CLASS, pluginCl)
            applyMethod = extCl?.declaredMethods
                ?.firstOrNull { it.name == "setMaterialBackground" && it.parameterCount == 3 }
                ?.also { it.isAccessible = true }
        }.onFailure { HookHelper.log("CcGlass: material ext init failed", it) }

        runCatching {
            val styleCl = Reflect.findClassIfExists(MI_STYLE_CLASS, pluginCl)
            styleInstance = styleCl?.let { Reflect.getStaticObjectField(it, "INSTANCE") }
            setBackgroundStyleMethod = styleCl?.declaredMethods
                ?.firstOrNull { it.name == "setMiBackgroundStyle" && it.parameterCount == 3 }
                ?.also { it.isAccessible = true }
        }.onFailure { HookHelper.log("CcGlass: mi style init failed", it) }

        runCatching {
            val blendCl = Reflect.findClassIfExists(COLOR_BLEND_CLASS, pluginCl)
            colorBlendInstance = blendCl?.let { Reflect.getStaticObjectField(it, "INSTANCE") }
        }.onFailure { HookHelper.log("CcGlass: color blend init failed", it) }

        runCatching {
            val blurCl = Reflect.findClassIfExists(BLUR_COMPAT_CLASS, pluginCl)
            setViewBlurModeMethod = blurCl?.declaredMethods
                ?.firstOrNull { it.name == "setMiViewBlurModeCompat" && it.parameterCount == 2 }
                ?.also { it.isAccessible = true }
        }.onFailure { HookHelper.log("CcGlass: blur compat init failed", it) }

        HookHelper.log(
            "CcGlass: init tokens=${tokensInstance != null} apply=${applyMethod != null} " +
                "style=${setBackgroundStyleMethod != null} blur=${setViewBlurModeMethod != null}"
        )
    }

    /** 按 getter 名取材质 token，如 `DefaultContentBgMaterialToken`。 */
    fun token(getterSuffix: String): Any? {
        synchronized(tokenCache) {
            if (tokenCache.containsKey(getterSuffix)) return tokenCache[getterSuffix]
        }
        val instance = tokensInstance ?: return null
        val value = runCatching { Reflect.callMethod(instance, "get$getterSuffix") }.getOrNull()
        if (value != null) synchronized(tokenCache) { tokenCache[getterSuffix] = value }
        return value
    }

    /** 对视图应用指定材质 token。 */
    fun apply(view: View, getterSuffix: String): Boolean {
        val token = token(getterSuffix) ?: return false
        return apply(view, token)
    }

    fun apply(view: View, token: Any): Boolean {
        applyMethod?.let { method ->
            return runCatching {
                method.invoke(null, view, token, false)
                true
            }.getOrElse {
                HookHelper.log("CcGlass: apply ${view.javaClass.simpleName} failed", it)
                false
            }
        }
        return applyStyle(view, null, bionics("DEFAULT_GLASS_TOKEN"))
    }

    /** 取底层玻璃 token，如 `DEFAULT_GLASS_TOKEN` / `ACTIVATED_GLASS_TOKEN`。 */
    fun bionics(getterSuffix: String): Any? {
        synchronized(bionicsCache) {
            if (bionicsCache.containsKey(getterSuffix)) return bionicsCache[getterSuffix]
        }
        val instance = styleInstance ?: return null
        val value = runCatching { Reflect.callMethod(instance, "get$getterSuffix") }.getOrNull()
        if (value != null) synchronized(bionicsCache) { bionicsCache[getterSuffix] = value }
        return value
    }

    /** 取混合色 token，如 `CC_DETAIL_PANEL_LIST_BLEND_COLORS`。 */
    fun colorBlend(getterSuffix: String): Any? {
        synchronized(blendCache) {
            if (blendCache.containsKey(getterSuffix)) return blendCache[getterSuffix]
        }
        val instance = colorBlendInstance ?: return null
        val value = runCatching { Reflect.callMethod(instance, "get$getterSuffix") }.getOrNull()
        if (value != null) synchronized(blendCache) { blendCache[getterSuffix] = value }
        return value
    }

    /**
     * 一级界面同款：设置模糊模式并套用玻璃（可带强调色 blend）。
     * 强调色在 Bionics 模式下由 [bionicsToken] 决定。
     */
    fun applyStyle(view: View, blendToken: Any?, bionicsToken: Any?): Boolean {
        val blurMethod = setViewBlurModeMethod ?: return false
        val styleMethod = setBackgroundStyleMethod ?: return false
        return runCatching {
            blurMethod.invoke(null, view, 1)
            styleMethod.invoke(null, view, blendToken, bionicsToken)
            true
        }.getOrElse {
            HookHelper.log("CcGlass: applyStyle ${view.javaClass.simpleName} failed", it)
            false
        }
    }
}
