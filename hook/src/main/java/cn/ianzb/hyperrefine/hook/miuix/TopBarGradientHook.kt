package cn.ianzb.hyperrefine.hook.miuix

import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * MIUIX 顶栏渐变模糊。
 *
 * 移植自 HyperBackground（MIT）的 `dynamic/topbar/DynamicActionBarHook`：
 * 把 MIUIX 二级页面顶栏（`miuix.appcompat.internal.app.widget.ActionBarContainer`）随滚动
 * 淡入淡出的原生遮罩，替换为「顶部模糊 → 底部透明」的渐变模糊层。
 *
 * - 通过 MIUI 隐藏方法 `View.setMiBackgroundBlurMode/setMiViewBlurMode/setMiBackgroundBlurType/
 *   setBackgroundGradientBlurParams` 配置自加的透明子 View；
 * - 用 DexKit 反混淆定位遮罩绘制方法与其驱动的 `float` 透明度字段，使模糊层跟随原生动画；
 * - 移除原生填充背景（`getPrimaryBackground` / `setPrimaryBackground`）。
 *
 * 目标：设置 / 短信 / 联系人等 MIUIX 应用。
 */
open class TopBarGradientHook : BaseHook() {

    override val key: String = TopBarKeys.KEY

    override fun useDexKit(): Boolean = true

    /**
     * 顶栏模糊是否**常驻**（不依赖原生滚动遮罩 alpha）。
     *
     * 与本模块页面 `TopBarBlurConfig.ScrollFadeDistance = 0`（常驻完整模糊）一致的逻辑。
     * 通用版跟随原生遮罩（滚动才出现）；计算器自带的新版 MIUIX 顶栏标题常驻收缩、
     * 原生遮罩 alpha 恒为 0，需常驻模糊。见 [CalculatorTopBarGradientHook]。
     */
    protected open val alwaysBlur: Boolean = false

    private var maskPainter: Method? = null
    private var maskAlpha: Field? = null

    private class State(val blur: View) {
        var original: Drawable? = null
        var cleared: Boolean = false
        var lastRadius: Float = -1f
        var lastHeight: Int = -1
        var lastAlpha: Float = 0f
    }

    private val states: MutableMap<ViewGroup, State> =
        Collections.synchronizedMap(WeakHashMap())

    /** DexKit：只解析遮罩绘制方法与遮罩 alpha 字段（此阶段不能用 [target]）。 */
    override fun initDexKit(): Boolean {
        maskPainter = optionalMember<Method>("mask_painter") { bridge ->
            val classData = bridge.getClassData(BAR) ?: throw IllegalStateException("no class data for $BAR")
            classData.methods.firstOrNull(::isMaskPainter)
                ?: throw IllegalStateException("mask painter not found")
        }
        maskAlpha = optionalMember<Field>("mask_alpha") { bridge ->
            val classData = bridge.getClassData(BAR) ?: throw IllegalStateException("no class data for $BAR")
            val painter = classData.methods.firstOrNull(::isMaskPainter)
                ?: throw IllegalStateException("mask painter not found")
            val fields = painter.usingFields.map { it.field }.filter { field ->
                field.className == BAR && field.typeName == "float" &&
                    field.writers.any { writer ->
                        writer.invokes.any {
                            it.className == VALUE_ANIMATOR && it.name == "getAnimatedValue"
                        }
                    }
            }.distinctBy { it.descriptor }
            if (fields.size != 1) throw IllegalStateException("ambiguous mask alpha: ${fields.size}")
            fields.single()
        }
        return maskPainter != null && maskAlpha != null
    }

    /**
     * 遮罩绘制方法特征（通用版 / 旧版 MIUIX）：
     * `void (Canvas)`，调用 `Canvas.drawPath`，并读取 `ActionBarContainer.getCollapsedHeight`。
     *
     * 计算器等自带新版 MIUIX 的应用特征不同，见 [CalculatorTopBarGradientHook]。
     */
    protected open fun isMaskPainter(method: org.luckypray.dexkit.result.MethodData): Boolean =
        method.returnTypeName == "void" &&
            method.paramTypeNames == listOf(CANVAS) &&
            method.invokes.any { it.className == CANVAS && it.name == "drawPath" } &&
            method.invokes.any { it.className == BAR && it.name == "getCollapsedHeight" }

    override fun init() {
        val alphaField = maskAlpha ?: return
        val painter = maskPainter ?: return
        val loader = target.classLoader ?: return
        val barClass = Reflect.findClassIfExists(BAR, loader) ?: return

        val setType = Reflect.findMethodIfExists(View::class.java, "setMiBackgroundBlurType", Integer.TYPE)
            ?: return
        val setMode = Reflect.findMethodIfExists(View::class.java, "setMiBackgroundBlurMode", Integer.TYPE)
            ?: return
        val setViewMode = Reflect.findMethodIfExists(View::class.java, "setMiViewBlurMode", Integer.TYPE)
            ?: return
        val setGradient = Reflect.findMethodIfExists(
            View::class.java,
            "setBackgroundGradientBlurParams",
            FloatArray::class.java,
            Integer.TYPE,
        ) ?: return
        val getPrimary = Reflect.findMethodIfExists(barClass, "getPrimaryBackground")
        val setPrimary = Reflect.findMethodIfExists(barClass, "setPrimaryBackground", Drawable::class.java)

        val painterIsOnDraw = painter.name == "onDraw" &&
            painter.parameterTypes.contentEquals(arrayOf(Canvas::class.java))

        // split 顶栏不接管（其子 View 计入测量，会撑大栏高）。
        val isSplitField = runCatching { Reflect.findField(barClass, "mIsSplit") }.getOrNull()

        val inflate = Reflect.findMethodIfExists(barClass, "onFinishInflate") ?: return
        HookHelper.intercept(inflate) { chain ->
            val result = chain.proceed()
            (chain.thisObject as? ViewGroup)?.let { bar ->
                if (isSplitField?.getBoolean(bar) != true) {
                    runCatching { ensure(bar, isSplitField != null) }
                }
            }
            result
        }

        val layout = Reflect.findMethodIfExists(
            barClass,
            "onLayout",
            java.lang.Boolean.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
        ) ?: return
        HookHelper.intercept(layout) { chain ->
            val result = chain.proceed()
            (chain.thisObject as? ViewGroup)?.let { bar ->
                // 只处理已接管的 bar；blur 用 MATCH_PARENT 测量，父级已摆好，这里仅兜底。
                states[bar]?.let { state ->
                    state.blur.layout(0, 0, bar.width, bar.height)
                    runCatching {
                        update(bar, alphaField, setType, setMode, setViewMode, setGradient, getPrimary, setPrimary)
                    }
                }
            }
            result
        }

        val onDraw = Reflect.findMethodIfExists(barClass, "onDraw", Canvas::class.java) ?: return
        HookHelper.intercept(onDraw) { chain ->
            val bar = chain.thisObject as? ViewGroup
            if (bar != null && states.containsKey(bar)) {
                runCatching { update(bar, alphaField, setType, setMode, setViewMode, setGradient, getPrimary, setPrimary) }
            }
            if (painterIsOnDraw && bar != null && enabled() && states.containsKey(bar)) {
                val original = runCatching { alphaField.getFloat(bar) }.getOrDefault(0f)
                try {
                    alphaField.setFloat(bar, 0f)
                    chain.proceed()
                } finally {
                    runCatching { alphaField.setFloat(bar, original) }
                }
            } else {
                chain.proceed()
            }
        }

        if (!painterIsOnDraw) {
            HookHelper.intercept(painter) { chain ->
                val bar = chain.thisObject as? ViewGroup
                if (bar != null && enabled() && states.containsKey(bar)) null else chain.proceed()
            }
        }
    }

    private fun ensure(bar: ViewGroup, matchParent: Boolean): State = states[bar] ?: run {
        val blur = View(bar.context).apply {
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.INVISIBLE
        }
        // 有 mIsSplit 字段的构建用 MATCH_PARENT（父级直接摆到整栏，避免每帧尺寸变化触发 requestLayout 死循环）。
        val size = if (matchParent) ViewGroup.LayoutParams.MATCH_PARENT else 0
        bar.addView(blur, 0, ViewGroup.LayoutParams(size, size))
        State(blur).also { states[bar] = it }
    }

    @Suppress("LongParameterList")
    private fun update(
        bar: ViewGroup,
        alphaField: Field,
        setType: Method,
        setMode: Method,
        setViewMode: Method,
        setGradient: Method,
        getPrimary: Method?,
        setPrimary: Method?,
    ) {
        val state = states[bar] ?: return
        if (!enabled()) {
            restore(bar, state, getPrimary, setPrimary)
            hide(state, setType, setMode, setViewMode)
            return
        }
        if (!state.cleared) {
            state.original = runCatching { getPrimary?.invoke(bar) as? Drawable }.getOrNull()
            state.cleared = true
        }
        if (getPrimary != null && setPrimary != null) {
            runCatching { if (getPrimary.invoke(bar) != null) setPrimary.invoke(bar, null) }
        }

        val strength = prefInt(TopBarKeys.STRENGTH, 10).coerceIn(0, 100)
        val opacity = prefInt(TopBarKeys.OPACITY, 100).coerceIn(0, 100)
        val fraction = runCatching { alphaField.getFloat(bar) }.getOrDefault(0f).coerceIn(0f, 1f)
        val radius = Math.min(strength * bar.resources.displayMetrics.density, bar.height * 0.5f)
        // 常驻模式：直接使用配置不透明度（等同本模块页面的 ScrollFadeDistance=0）；否则跟随原生遮罩。
        val alpha = (if (alwaysBlur) 1f else fraction) * opacity / 100f
        if (radius <= 0f || bar.height <= 0) {
            hide(state, setType, setMode, setViewMode)
            return
        }
        if (alpha <= 0f) {
            // 滚动会频繁把遮罩 alpha 压到 0；此处只淡出，不销毁模糊节点（避免与底栏渐变竞态）。
            if (state.lastAlpha != 0f) {
                state.blur.alpha = 0f
                state.lastAlpha = 0f
            }
            return
        }
        if (state.lastRadius != radius || state.lastHeight != bar.height) {
            setMode.invoke(state.blur, 1)
            setViewMode.invoke(state.blur, 1)
            setType.invoke(state.blur, 2)
            setGradient.invoke(state.blur, floatArrayOf(0f, 0f, radius, 0f, bar.height.toFloat(), 0f), 1)
            state.lastRadius = radius
            state.lastHeight = bar.height
        }
        if (state.lastAlpha != alpha) {
            state.blur.alpha = alpha
            state.lastAlpha = alpha
        }
        if (state.blur.visibility != View.VISIBLE) state.blur.visibility = View.VISIBLE
    }

    private fun restore(bar: ViewGroup, state: State, getPrimary: Method?, setPrimary: Method?) {
        if (!state.cleared) return
        if (getPrimary != null && setPrimary != null) {
            runCatching { if (getPrimary.invoke(bar) == null) setPrimary.invoke(bar, state.original) }
        }
        state.cleared = false
        state.original = null
    }

    private fun hide(state: State, setType: Method, setMode: Method, setViewMode: Method) {
        if (state.lastRadius >= 0f) {
            setType.invoke(state.blur, 0)
            setViewMode.invoke(state.blur, 0)
            setMode.invoke(state.blur, 0)
            state.lastRadius = -1f
            state.lastHeight = -1
        }
        state.lastAlpha = 0f
        if (state.blur.visibility != View.INVISIBLE) state.blur.visibility = View.INVISIBLE
    }

    protected open fun enabled(): Boolean = HookPrefs.getBoolean(TopBarKeys.KEY, false)

    /** 滑块以 Float 存储，优先按 Float 读取，兼容整数存储。 */
    private fun prefInt(key: String, default: Int): Int {
        val value = HookPrefs.getFloat(key, Float.NaN)
        if (!value.isNaN()) return value.roundToInt()
        return HookPrefs.getInt(key, default)
    }

    private companion object {
        const val BAR = "miuix.appcompat.internal.app.widget.ActionBarContainer"
        const val CANVAS = "android.graphics.Canvas"
        const val VALUE_ANIMATOR = "android.animation.ValueAnimator"
    }
}
