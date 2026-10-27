package cn.ianzb.hyperrefine.hook.sogou

import android.graphics.Outline
import android.inputmethodservice.InputMethodService
import android.util.Log
import android.view.View
import android.view.ViewOutlineProvider
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * 搜狗输入法玻璃材质：给键盘根视图开启 MIUI 模糊，并把根视图裁成圆角。
 *
 * 搜狗没有覆写 `onCreateInputView`，键盘根视图由框架经
 * `InputMethodService.setInputView(View)` 下发，所以在该边界统一处理。
 * MIUI 模糊 API 不存在时只保留圆角，不抛异常。
 */
class SogouImeGlassHook : BaseHook() {

    override val key: String = SogouImeKeys.GLASS

    private var setBlurMode: Method? = null
    private var setViewBlurMode: Method? = null
    private var setBlurType: Method? = null
    private var setGradient: Method? = null

    /** 每个键盘根视图的原始状态；弱键避免持有已销毁的输入法视图。 */
    private val states = WeakHashMap<View, AppliedState>()

    override fun init() {
        val viewClass = View::class.java
        setBlurMode = Reflect.findMethodIfExists(viewClass, "setMiBackgroundBlurMode", Integer.TYPE)
        setViewBlurMode = Reflect.findMethodIfExists(viewClass, "setMiViewBlurMode", Integer.TYPE)
        setBlurType = Reflect.findMethodIfExists(viewClass, "setMiBackgroundBlurType", Integer.TYPE)
        setGradient = Reflect.findMethodIfExists(
            viewClass,
            "setBackgroundGradientBlurParams",
            FloatArray::class.java,
            Integer.TYPE,
        )
        if (setBlurMode == null || setViewBlurMode == null || setBlurType == null || setGradient == null) {
            HookHelper.log("$tag: MIUI blur APIs unavailable, rounded corners only")
        }

        HookHelper.log(
            "$tag: blurApi=${setBlurMode != null} cornerApi=${setBlurType != null}",
        )
        Log.i(
            LOG_TAG,
            "init blurApi=${setBlurMode != null} viewApi=${setViewBlurMode != null} " +
                "typeApi=${setBlurType != null} gradientApi=${setGradient != null}",
        )

        val setInputView = Reflect.findMethodIfExists(
            InputMethodService::class.java,
            "setInputView",
            View::class.java,
        )
        if (setInputView == null) {
            HookHelper.log("$tag: InputMethodService.setInputView not found")
        } else {
            runCatching { HookHelper.deoptimize(setInputView) }
                .onFailure { HookHelper.log("$tag: deoptimize framework setInputView failed", it) }
            HookHelper.hookAfter(setInputView) { param ->
                val root = param.args.firstOrNull() as? View ?: return@hookAfter
                dispatch(root, "framework")
            }
            HookHelper.log("$tag: hooked framework setInputView")
        }

        // 部分 ROM / ART 版本会把 BaseInputMethodService 中的 super 调用内联，
        // 导致框架方法回调不触发；同时挂到搜狗自己的入口作为兜底，二者幂等。
        val sogouService = Reflect.findClassIfExists(
            "com.sogou.lib.spage.BaseInputMethodService",
            target.classLoader,
        )
        val sogouSetInputView = sogouService?.let {
            Reflect.findMethodIfExists(it, "setInputView", View::class.java)
        }
        if (sogouSetInputView != null) {
            runCatching { HookHelper.deoptimize(sogouSetInputView) }
                .onFailure { HookHelper.log("$tag: deoptimize Sogou setInputView failed", it) }
            HookHelper.hookAfter(sogouSetInputView) { param ->
                val root = param.args.firstOrNull() as? View ?: return@hookAfter
                dispatch(root, "sogou")
            }
            HookHelper.log("$tag: hooked Sogou BaseInputMethodService.setInputView")
        }

        // 搜狗真正的键盘面板并不在 InputMethodService.setInputView 的视图树里：
        // 输入视图只有 175px 高的候选栏容器，键盘由 NormalIMERootContainer.setKeyboardView
        // 单独塞进来。直接挂这个入口，拿到真正的 KeyboardRootComponentView，
        // 它稍后才会测量，apply 的布局监听会等到非 0 尺寸再上模糊/圆角。
        val rootContainer = Reflect.findClassIfExists(
            "com.sohu.inputmethod.ui.frame.NormalIMERootContainer",
            target.classLoader,
        )
        val setKeyboardView = rootContainer?.let {
            Reflect.findMethodIfExists(it, "setKeyboardView", View::class.java)
        }
        if (setKeyboardView != null) {
            runCatching { HookHelper.deoptimize(setKeyboardView) }
                .onFailure { HookHelper.log("$tag: deoptimize setKeyboardView failed", it) }
            HookHelper.hookAfter(setKeyboardView) { param ->
                val keyboard = param.args.firstOrNull() as? View ?: return@hookAfter
                dispatch(keyboard, "keyboard")
            }
            Log.i(LOG_TAG, "hooked NormalIMERootContainer.setKeyboardView")
            HookHelper.log("$tag: hooked NormalIMERootContainer.setKeyboardView")
        } else {
            Log.i(LOG_TAG, "NormalIMERootContainer.setKeyboardView not found")
        }

        // 输入法窗口本身才是键盘真正可见的区域：setInputView 下发的是候选栏容器，
        // setKeyboardView 下发的 KeyboardRootComponentView 在该版本里一直是 0x0。
        // 窗口显示后把 decorView 作为兜底目标，圆角与背景模糊至少落在真实键盘窗口上。
        val onWindowShown = Reflect.findMethodIfExists(
            InputMethodService::class.java,
            "onWindowShown",
        )
        if (onWindowShown != null) {
            HookHelper.hookAfter(onWindowShown) { param ->
                val service = param.thisObject as? InputMethodService ?: return@hookAfter
                val decor = service.window?.window?.decorView ?: return@hookAfter
                dispatch(decor, "window")
            }
            Log.i(LOG_TAG, "hooked InputMethodService.onWindowShown")
            HookHelper.log("$tag: hooked InputMethodService.onWindowShown")
        } else {
            Log.i(LOG_TAG, "InputMethodService.onWindowShown not found")
        }

        HookPrefs.registerListener {
            val roots = synchronized(states) { states.keys.toList() }
            roots.forEach { root -> root.post { apply(root) } }
        }
    }

    private fun dispatch(root: View, source: String) {
        Log.i(LOG_TAG, "setInputView[$source] ${root.javaClass.name} ${root.width}x${root.height}")
        HookHelper.log(
            "$tag: setInputView[$source] ${root.javaClass.name} ${root.width}x${root.height}",
        )
        root.post {
            Log.i(LOG_TAG, "posted[$source] ${root.javaClass.name} ${root.width}x${root.height} attached=${root.isAttachedToWindow}")
            HookHelper.log(
                "$tag: setInputView[$source] posted ${root.width}x${root.height} attached=${root.isAttachedToWindow}",
            )
            resolveAndApply(root, source, 0)
        }
    }

    /**
     * `setInputView` 刚回调时，搜狗真正的键盘视图还没测量，`mCurKeyboardView` 常见为 0x0。
     * 只在 post 里解析一次会把模糊/圆角加在 175px 的候选栏容器上；这里分几个时间点重新解析，
     * 让后完成布局的键盘面板成为目标。重复 apply 对同一目标由 [AppliedState] 去重。
     */
    private fun resolveAndApply(root: View, source: String, attempt: Int) {
        root.postDelayed({
            val target = resolveEffectTarget(root)
            Log.i(
                LOG_TAG,
                "resolve[$source/$attempt] ${root.javaClass.simpleName}:${root.width}x${root.height}" +
                    " -> ${target.javaClass.simpleName}:${target.width}x${target.height}",
            )
            apply(target)
            if (attempt < RESOLVE_RETRY_DELAYS.size) {
                resolveAndApply(root, source, attempt + 1)
            }
        }, RESOLVE_RETRY_DELAYS[attempt])
    }

    /**
     * `setInputView` 收到的是搜狗的 `NormalIMERootContainer`，它本身经常只有候选栏高度；
     * 真正的键盘面板是它内部的 `mCurKeyboardView` / `PlatformRootView`。
     * 在这些候选里选面积最大的视图，避免把圆角和模糊只加到 175px 高的候选条上。
     */
    private fun resolveEffectTarget(root: View): View {
        val candidates = ArrayList<View>()
        candidates.add(root)
        runCatching {
            val field = Reflect.findField(root.javaClass, "mCurKeyboardView")
            (field.get(root) as? View)?.let(candidates::add)
        }
        if (root is android.view.ViewGroup) {
            val stack = ArrayDeque<View>()
            for (i in 0 until root.childCount) stack.addLast(root.getChildAt(i))
            while (stack.isNotEmpty()) {
                val child = stack.removeLast()
                if (child.javaClass.name == "com.sohu.inputmethod.platform.widget.PlatformRootView") {
                    candidates.add(child)
                }
                if (child is android.view.ViewGroup) {
                    for (i in 0 until child.childCount) stack.addLast(child.getChildAt(i))
                }
            }
        }
        val target = candidates.maxByOrNull { it.width.toLong() * it.height.toLong() } ?: root
        Log.i(
            LOG_TAG,
            "resolveTarget " + candidates.joinToString { "${it.javaClass.simpleName}:${it.width}x${it.height}" } +
                " -> ${target.javaClass.simpleName}:${target.width}x${target.height}",
        )
        return target
    }

    private fun apply(root: View) {
        val state = states.getOrPut(root) { AppliedState(root) }
        if (state.applying) return
        state.applying = true
        try {
            applyInner(root, state)
        } finally {
            state.applying = false
        }
    }

    private fun applyInner(root: View, state: AppliedState) {
        val enabled = HookPrefs.getBoolean(key, false)
        Log.i(LOG_TAG, "apply ${root.javaClass.name} ${root.width}x${root.height} enabled=$enabled")
        if (!enabled) {
            restore(root, state)
            return
        }
        state.ensureLayoutListener(root) { apply(root) }
        if (root.width <= 0 || root.height <= 0) {
            if (state.retries < 8) {
                state.retries++
                root.post { apply(root) }
            }
            return
        }
        state.retries = 0

        val density = root.resources.displayMetrics.density
        val cornerPx = (HookPrefs.getFloat(SogouImeKeys.CORNER_RADIUS, SogouImeKeys.DEFAULT_CORNER_RADIUS) * density)
            .roundToInt()
            .coerceAtLeast(0)
        val blurPx = (HookPrefs.getFloat(SogouImeKeys.BLUR_RADIUS, SogouImeKeys.DEFAULT_BLUR_RADIUS) * density)
            .roundToInt()
            .coerceAtLeast(0)

        applyCorners(root, state, cornerPx)
        applyBlur(root, state, blurPx)
    }

    private fun applyCorners(root: View, state: AppliedState, cornerPx: Int) {
        if (cornerPx <= 0) {
            if (!state.cornerApplied) return
            root.clipToOutline = state.originalClipToOutline
            root.outlineProvider = state.originalOutlineProvider
            root.invalidateOutline()
            state.cornerApplied = false
            return
        }
        if (state.cornerApplied && state.cornerPx == cornerPx) return
        // 先置状态再改 View：clipToOutline/invalidateOutline 可能同步触发
        // onLayoutChange，监听器会重入 apply；后置状态会造成无限递归。
        state.cornerApplied = true
        state.cornerPx = cornerPx
        root.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cornerPx.toFloat())
            }
        }
        root.clipToOutline = true
        root.invalidateOutline()
    }

    private fun applyBlur(root: View, state: AppliedState, blurPx: Int) {
        val mode = setBlurMode
        val viewMode = setViewBlurMode
        val type = setBlurType
        val gradient = setGradient
        if (blurPx <= 0 || mode == null || viewMode == null || type == null || gradient == null) {
            Log.i(
                LOG_TAG,
                "applyBlur skipped blurPx=$blurPx mode=${mode != null} view=${viewMode != null} " +
                    "type=${type != null} gradient=${gradient != null}",
            )
            removeBlur(state)
            return
        }
        if (state.blurApplied && state.blurPx == blurPx && state.blurHeight == root.height) return

        // 不能垫一层透明子 View：搜狗键盘的内容层是不透明的，垫在 index 0 的模糊节点
        // 会被完全盖住，表现为“开关生效但肉眼无变化”。直接给键盘根视图开模糊，
        // 子 View（按键、候选词）仍绘制在模糊背景之上。
        // 先置状态再反射调用：MIUI 的 setMiBackgroundBlurMode 可能同步触发
        // requestLayout/onLayoutChange，监听器重入 apply 时要能看到已完成标记。
        state.blurView = WeakReference(root)
        state.blurApplied = true
        state.blurPx = blurPx
        state.blurHeight = root.height
        runCatching {
            mode.invoke(root, 1)
            viewMode.invoke(root, 1)
            type.invoke(root, 2)
            gradient.invoke(root, floatArrayOf(0f, 0f, blurPx.toFloat(), 0f, root.height.toFloat(), 0f), 1)
            HookHelper.log("$tag: blur applied ${root.width}x${root.height} radius=$blurPx")
        }.onFailure {
            HookHelper.log("$tag: apply blur failed", it)
            removeBlur(state)
        }
    }

    private fun removeBlur(state: AppliedState) {
        if (!state.blurApplied) return
        val target = state.blurView?.get()
        if (target != null) {
            runCatching {
                setBlurMode?.invoke(target, 0)
                setViewBlurMode?.invoke(target, 0)
                setBlurType?.invoke(target, 0)
            }
        }
        state.blurView = null
        state.blurApplied = false
        state.blurPx = -1
        state.blurHeight = -1
    }

    private fun restore(root: View, state: AppliedState) {
        removeBlur(state)
        state.removeLayoutListener(root)
        root.clipToOutline = state.originalClipToOutline
        root.outlineProvider = state.originalOutlineProvider
        root.invalidateOutline()
        state.cornerApplied = false
        state.cornerPx = -1
    }

    private class AppliedState(root: View) {
        val originalOutlineProvider: ViewOutlineProvider? = root.outlineProvider
        val originalClipToOutline: Boolean = root.clipToOutline
        var blurView: WeakReference<View>? = null
        var blurApplied: Boolean = false
        var blurPx: Int = -1
        var blurHeight: Int = -1
        var retries: Int = 0
        var cornerApplied: Boolean = false
        var cornerPx: Int = -1
        var applying: Boolean = false
        private var layoutListener: View.OnLayoutChangeListener? = null

        fun ensureLayoutListener(root: View, onLayout: () -> Unit) {
            if (layoutListener != null) return
            val listener = object : View.OnLayoutChangeListener {
                override fun onLayoutChange(
                    v: View,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    oldLeft: Int,
                    oldTop: Int,
                    oldRight: Int,
                    oldBottom: Int,
                ) {
                    if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                        onLayout()
                    }
                }
            }
            layoutListener = listener
            root.addOnLayoutChangeListener(listener)
        }

        fun removeLayoutListener(root: View) {
            layoutListener?.let { root.removeOnLayoutChangeListener(it) }
            layoutListener = null
        }
    }

    private companion object {
        const val LOG_TAG = "HyperRefineSogou"
        val RESOLVE_RETRY_DELAYS = longArrayOf(80L, 200L, 450L, 900L)
    }
}
