package cn.ianzb.hyperrefine.hook.systemui.glass

import android.view.View
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Method

/**
 * 控制中心「柔光玻璃 / 玻璃材质」接口的反射门面。
 *
 * 两类接口：
 * 1. 材质 token：`ControlCenterMaterialTokens` +
 *    `MaterialBackgroundExt.setMaterialBackground(view, token, animate)`。
 * 2. 底层玻璃（一级界面卡片 / 图标同款）：
 *    `MiBackgroundStyle.setMiBackgroundStyle(view, blend, bionicsToken)` +
 *    `MiBlurCompat.setMiViewBlurModeCompat(view, 1)`；按状态选用
 *    `DEFAULT_GLASS_TOKEN` / `ACTIVATED_GLASS_TOKEN`。
 * 3. 系统详情项同款：`com.miui.systemui.util.MiBlurCompat` 的模糊模式 + blend 色。
 *
 * 本类只调用系统自身接口，不复制任何系统代码；类 / 方法查找结果均缓存。
 */
object CcGlassApi {

    private const val TOKENS_CLASS =
        "miui.systemui.controlcenter.material.ControlCenterMaterialTokens"
    private const val MATERIAL_EXT_CLASS =
        "miui.systemui.ui.material.MaterialBackgroundExt"
    private const val MI_STYLE_CLASS = "miui.systemui.util.MiBackgroundStyle"
    private const val BLUR_COMPAT_CLASS = "miui.systemui.util.MiBlurCompat"
    private const val SYSTEM_BLUR_CLASS = "com.miui.systemui.util.MiBlurCompat"

    private var tokensInstance: Any? = null
    private var applyMethod: Method? = null

    private var styleInstance: Any? = null
    private var setBackgroundStyleMethod: Method? = null
    private var setViewBlurModeMethod: Method? = null

    private var systemSetViewBlurMode: Method? = null
    private var systemSetBlendColorsNew: Method? = null

    private val tokenCache = HashMap<String, Any?>()
    private val bionicsCache = HashMap<String, Any?>()

    @Volatile
    private var initialized = false

    /** 插件 ClassLoader 就绪后初始化；只需成功一次。 */
    @Synchronized
    fun init(pluginCl: ClassLoader) {
        if (initialized) return
        initialized = true

        runCatching {
            Reflect.findClassIfExists(TOKENS_CLASS, pluginCl)?.let {
                tokensInstance = Reflect.getStaticObjectField(it, "INSTANCE")
            }
            Reflect.findClassIfExists(MATERIAL_EXT_CLASS, pluginCl)?.let { extCl ->
                applyMethod = extCl.declaredMethods
                    .firstOrNull { it.name == "setMaterialBackground" && it.parameterCount == 3 }
                    ?.also { it.isAccessible = true }
            }
            Reflect.findClassIfExists(MI_STYLE_CLASS, pluginCl)?.let { styleCl ->
                styleInstance = Reflect.getStaticObjectField(styleCl, "INSTANCE")
                setBackgroundStyleMethod = styleCl.declaredMethods
                    .firstOrNull { it.name == "setMiBackgroundStyle" && it.parameterCount == 3 }
                    ?.also { it.isAccessible = true }
            }
            Reflect.findClassIfExists(BLUR_COMPAT_CLASS, pluginCl)?.let { blurCl ->
                setViewBlurModeMethod = blurCl.declaredMethods
                    .firstOrNull { it.name == "setMiViewBlurModeCompat" && it.parameterCount == 2 }
                    ?.also { it.isAccessible = true }
            }
        }.onFailure { HookHelper.log("CcGlass: init failed", it) }
    }

    /** 初始化主 APK（systemui）里的 `com.miui.systemui.util.MiBlurCompat`（供详情项玻璃使用）。 */
    @Synchronized
    fun initSystem(systemCl: ClassLoader) {
        if (systemSetViewBlurMode != null) return
        runCatching {
            val cls = Reflect.findClassIfExists(SYSTEM_BLUR_CLASS, systemCl) ?: return@runCatching
            systemSetViewBlurMode = cls.declaredMethods
                .firstOrNull { it.name == "setMiViewBlurModeCompat" && it.parameterCount == 2 }
                ?.also { it.isAccessible = true }
            systemSetBlendColorsNew = cls.declaredMethods
                .firstOrNull { it.name == "setMiBackgroundBlendColorsNew\$default" && it.parameterCount == 2 }
                ?.also { it.isAccessible = true }
                ?: cls.declaredMethods
                    .firstOrNull { it.name == "setMiBackgroundBlendColors" && it.parameterCount == 3 }
                    ?.also { it.isAccessible = true }
        }.onFailure { HookHelper.log("CcGlass: system blur init failed", it) }
    }

    /**
     * 系统详情项同款玻璃：模糊模式 1 + 透明背景 + 系统 blend 色。
     * 不改动视图的 outline，从而保留系统的分组圆角（整组共享一张圆角卡片）。
     */
    fun forceBlurGlass(view: View, blendColors: IntArray?): Boolean {
        val blur = systemSetViewBlurMode ?: return false
        val blend = systemSetBlendColorsNew ?: return false
        return runCatching {
            blur.invoke(null, 1, view)
            view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            val colors = blendColors ?: IntArray(0)
            if (blend.parameterCount == 2) blend.invoke(null, view, colors)
            else blend.invoke(null, view, colors, 1.0f)
            true
        }.getOrElse {
            HookHelper.log("CcGlass: forceBlurGlass failed", it)
            false
        }
    }

    /** 对视图应用指定材质 token（如 `DefaultContentBgMaterialToken`）。 */
    fun apply(view: View, getterSuffix: String): Boolean {
        val token = token(getterSuffix) ?: return false
        applyMethod?.let { method ->
            return runCatching {
                method.invoke(null, view, token, false)
                true
            }.getOrElse {
                HookHelper.log("CcGlass: apply ${view.javaClass.simpleName} failed", it)
                false
            }
        }
        return applyStyle(view, bionics("DEFAULT_GLASS_TOKEN"))
    }

    /** 一级界面同款：模糊模式 1 + SDF 柔光玻璃（强调色由 [bionicsToken] 决定）。 */
    fun applyStyle(view: View, bionicsToken: Any?): Boolean {
        val blur = setViewBlurModeMethod ?: return false
        val style = setBackgroundStyleMethod ?: return false
        return runCatching {
            blur.invoke(null, view, 1)
            style.invoke(null, view, null, bionicsToken)
            true
        }.getOrElse {
            HookHelper.log("CcGlass: applyStyle ${view.javaClass.simpleName} failed", it)
            false
        }
    }

    /** 按 getter 名取材质 token（带缓存）。 */
    fun token(getterSuffix: String): Any? = cached(tokenCache, tokensInstance, "get$getterSuffix")

    /** 取底层玻璃 token，如 `DEFAULT_GLASS_TOKEN` / `ACTIVATED_GLASS_TOKEN`（带缓存）。 */
    fun bionics(getterSuffix: String): Any? = cached(bionicsCache, styleInstance, "get$getterSuffix")

    private fun cached(cache: HashMap<String, Any?>, instance: Any?, getter: String): Any? {
        synchronized(cache) {
            if (cache.containsKey(getter)) return cache[getter]
        }
        if (instance == null) return null
        val value = runCatching { Reflect.callMethod(instance, getter) }.getOrNull()
        if (value != null) synchronized(cache) { cache[getter] = value }
        return value
    }
}
