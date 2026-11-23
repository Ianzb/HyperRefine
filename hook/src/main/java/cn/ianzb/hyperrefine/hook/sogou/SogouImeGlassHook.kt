package cn.ianzb.hyperrefine.hook.sogou

import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import kotlin.math.roundToInt

class SogouImeGlassHook : BaseHook() {

    override val key: String = SogouImeKeys.GLASS

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var panel: Panel? = null
    private var options = Options.Disabled
    private var systemBlurEnabled = false
    private var windowBlurEnabled = false
    private var observingBlur = false
    private val upperEdge = Path()
    private val skinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.SRC }
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    override fun init() {
        options = readOptions()
        val toolbar = installDraw(TOOLBAR_CLASS, "dispatchDraw", "mBackgroundDrawable", toolbar = true)
        val keyboard = installDraw(KEYBOARD_CLASS, "onDraw", "mKeyboardBg", toolbar = false)
        val window = installWindow()
        if (!toolbar || !keyboard || !window) {
            throw HookSkippedException("Sogou keyboard window or drawing entry unavailable")
        }
        HookPrefs.registerListener {
            main.post {
                val updated = readOptions()
                if (options != updated) {
                    options = updated
                    panel?.invalidate()
                }
            }
        }
        HookHelper.log("$tag: installed keyboard window and drawing hooks")
    }

    private fun installDraw(className: String, methodName: String, fieldName: String, toolbar: Boolean): Boolean {
        val clazz = Reflect.findClassIfExists(className, target.classLoader)
            ?.takeIf { View::class.java.isAssignableFrom(it) } ?: return false
        val draw = Reflect.findMethodIfExists(clazz, methodName, Canvas::class.java)
            ?.takeIf { it.returnType == Void.TYPE } ?: return false
        val skin = runCatching { Reflect.findField(clazz, fieldName) }.getOrNull()
            ?.takeIf { it.type == Drawable::class.java } ?: return false

        HookHelper.intercept(draw) { chain ->
            val view = chain.thisObject as View
            val state = panel ?: return@intercept chain.proceed()
            if (view !== (if (toolbar) state.toolbar else state.keys)) return@intercept chain.proceed()
            val canvas = chain.args[0] as Canvas
            if (!canvas.isHardwareAccelerated) return@intercept chain.proceed()
            val original = if (state.blurActive) skin.get(view) else null
            if (state.blurActive) {
                // 用带透明度的底色替换皮肤底图，让按键区和工具栏共用窗口模糊。
                skinPaint.color = state.tint
                canvas.drawRect(0f, 0f, view.width.toFloat(), view.height.toFloat(), skinPaint)
            }
            if (original != null) skin.set(view, null)
            val result = try {
                chain.proceed()
            } finally {
                if (original != null && skin.get(view) == null) skin.set(view, original)
            }
            if (toolbar && state.corner > 0f) drawUpperEdge(view, canvas, state.corner)
            result
        }
        return true
    }

    private fun installWindow(): Boolean {
        val injector = Reflect.findClassIfExists(
            "android.inputmethodservice.InputMethodServiceInjector", target.classLoader,
        ) ?: return false
        val addBottom = Reflect.findMethodIfExists(
            injector, "addMiuiBottomView", LayoutInflater::class.java, ViewGroup::class.java,
            ViewGroup::class.java, View::class.java, ViewGroup::class.java,
            InputMethodManager::class.java, InputMethodService::class.java,
        ) ?: return false

        HookHelper.hookAfter(addBottom) { param ->
            val root = param.args.getOrNull(3) as? ViewGroup ?: return@hookAfter
            val input = param.args.getOrNull(2) as? ViewGroup ?: return@hookAfter
            val bottom = param.args.getOrNull(4) as? ViewGroup
            panel?.detach()
            panel = Panel(root, input, bottom).also(Panel::attach)
        }
        return true
    }

    private fun observeBlurSettings(view: View) {
        if (observingBlur) return
        observingBlur = true
        val context = view.context.applicationContext ?: view.context
        val resolver = context.contentResolver
        val manager = context.getSystemService(WindowManager::class.java) ?: return
        runCatching {
            fun refreshSystemSetting() {
                systemBlurEnabled = Settings.Secure.getInt(resolver, SYSTEM_BLUR, 0) == 1
            }
            refreshSystemSetting()
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(SYSTEM_BLUR),
                false,
                object : ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) {
                        refreshSystemSetting()
                        panel?.invalidate()
                    }
                },
            )
            windowBlurEnabled = manager.isCrossWindowBlurEnabled
            manager.addCrossWindowBlurEnabledListener(context.mainExecutor) { enabled ->
                windowBlurEnabled = enabled
                panel?.invalidate()
            }
        }.onFailure { HookHelper.log("$tag: unable to monitor native window blur", it) }
    }

    private fun drawUpperEdge(toolbar: View, canvas: Canvas, corner: Float) {
        val width = toolbar.width.toFloat()
        val diameter = 2 * corner
        upperEdge.rewind()
        upperEdge.moveTo(0f, corner)
        upperEdge.arcTo(0f, 0f, diameter, diameter, 180f, 90f, false)
        upperEdge.lineTo(width - corner, 0f)
        upperEdge.arcTo(width - diameter, 0f, width, diameter, 270f, 90f, false)
        edgePaint.strokeWidth = (toolbar.resources.displayMetrics.density * 0.5f).coerceAtLeast(1f)
        edgePaint.color = if (toolbar.isNight()) 0x44FFFFFF else 0x33808080
        val save = canvas.save()
        try {
            canvas.translate(0f, edgePaint.strokeWidth / 2f)
            canvas.drawPath(upperEdge, edgePaint)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    private fun View.isNight(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    private fun readOptions(): Options {
        if (!HookPrefs.getBoolean(key, false)) return Options.Disabled
        return Options(
            HookPrefs.getFloat(SogouImeKeys.BLUR_RADIUS, SogouImeKeys.DEFAULT_BLUR_RADIUS)
                .takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f,
            HookPrefs.getFloat(SogouImeKeys.CORNER_RADIUS, SogouImeKeys.DEFAULT_CORNER_RADIUS)
                .takeIf(Float::isFinite)?.coerceAtLeast(0f) ?: 0f,
        )
    }

    private class Background {
        private var original: Drawable? = null
        private var applied: Drawable? = null
        private var replacing = false

        fun replace(view: View, drawable: Drawable?) {
            if (!replacing || view.background !== applied) original = view.background
            replacing = true
            applied = drawable
            if (view.background !== drawable) view.background = drawable
        }

        fun restore(view: View) {
            if (replacing && view.background === applied) view.background = original
            replacing = false
            original = null
            applied = null
        }
    }

    private class Backdrop(val drawable: Drawable) : Drawable() {
        val region = Rect()

        override fun draw(canvas: Canvas) {
            drawable.bounds = region
            drawable.draw(canvas)
        }

        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit
        @Suppress("DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private inner class Panel(
        private val root: ViewGroup,
        private val input: ViewGroup,
        private val bottom: ViewGroup?,
    ) : ViewTreeObserver.OnPreDrawListener, View.OnAttachStateChangeListener {
        var toolbar: View? = null
            private set
        var keys: View? = null
            private set
        var corner = 0f
            private set
        var blurActive = false
            private set
        var tint = 0
            private set
        private val rootAt = IntArray(2)
        private val toolbarAt = IntArray(2)
        private val viewAt = IntArray(2)
        private val visiblePanel = Rect()
        private var backdrop: Backdrop? = null
        private var footer: View? = null
        private val rootBackground = Background()
        private val footerBackground = Background()
        private val originalOutline = root.outlineProvider
        private val originalClip = root.clipToOutline
        private var appliedRadius = 0
        private var appliedCorner = -1f
        private var appliedTint: Int? = null
        private var nativeBlurFailed = false
        private val outline = object : ViewOutlineProvider() {
            private val path = Path()
            private val corners = Path()

            override fun getOutline(view: View, outline: Outline) {
                path.rewind()
                corners.rewind()
                val left = visiblePanel.left.toFloat()
                val top = visiblePanel.top.toFloat()
                val right = visiblePanel.right.toFloat()
                val bottom = visiblePanel.bottom.toFloat()
                path.addRect(left, top - 1f, right, bottom, Path.Direction.CW)
                corners.addRoundRect(
                    left, top, right, bottom,
                    floatArrayOf(corner, corner, corner, corner, 0f, 0f, 0f, 0f),
                    Path.Direction.CW,
                )
                corners.op(path, corners, Path.Op.DIFFERENCE)
                path.rewind()
                path.addRect(0f, 0f, view.width.toFloat(), view.height.toFloat(), Path.Direction.CW)
                path.op(corners, Path.Op.DIFFERENCE)
                outline.setPath(path)
            }
        }

        fun attach() {
            root.addOnAttachStateChangeListener(this)
            if (root.isAttachedToWindow) onViewAttachedToWindow(root)
        }

        fun detach() {
            root.removeOnAttachStateChangeListener(this)
            onViewDetachedFromWindow(root)
        }

        fun invalidate() {
            root.invalidate()
            toolbar?.invalidate()
            keys?.invalidate()
        }

        override fun onViewAttachedToWindow(view: View) {
            view.viewTreeObserver.addOnPreDrawListener(this)
        }

        override fun onViewDetachedFromWindow(view: View) {
            view.viewTreeObserver.removeOnPreDrawListener(this)
            updateBlur(0)
            updateCorner(0f)
            backdrop = null
            appliedRadius = 0
            appliedCorner = -1f
            appliedTint = null
            nativeBlurFailed = false
        }

        override fun onPreDraw(): Boolean {
            if (options == Options.Disabled || !input.isShown) {
                updateCorner(0f)
                updateBlur(0)
                return true
            }
            val bar = findVisible(root) { it.javaClass.name == TOOLBAR_CLASS }
            val keyboard = findVisible(root) { it.javaClass.name == KEYBOARD_CLASS }
            if (bar == null || keyboard == null || !resolveRegion(bar, keyboard)) {
                updateCorner(0f)
                updateBlur(0)
                return true
            }
            toolbar = bar
            keys = keyboard
            val density = root.resources.displayMetrics.density
            updateCorner((options.cornerDp * density).coerceAtMost(minOf(bar.width / 2f, bar.height.toFloat())))
            if (options.blurDp > 0f) observeBlurSettings(root)
            val radius = if (systemBlurEnabled && windowBlurEnabled) {
                (options.blurDp * density).roundToInt().coerceIn(0, 400)
            } else 0
            updateBlur(radius)
            return true
        }

        private fun resolveRegion(bar: View, keyboard: View): Boolean {
            if (bar.width <= 0 || bar.height <= 0 || keyboard.height <= 0 || root.height <= 0) return false
            root.getLocationInWindow(rootAt)
            bar.getLocationInWindow(toolbarAt)
            keyboard.getLocationInWindow(viewAt)
            val left = toolbarAt[0] - rootAt[0]
            val top = toolbarAt[1] - rootAt[1]
            val right = left + bar.width
            var bottomEdge = viewAt[1] + keyboard.height - rootAt[1]
            val footerView = footer?.takeIf { it.isAttachedToWindow } ?: findFooter().also { footer = it }
            if (footerView != null && footerView.isShown && footerView.width == bar.width) {
                footerView.getLocationInWindow(viewAt)
                bottomEdge = maxOf(bottomEdge, viewAt[1] + footerView.height - rootAt[1])
            }
            if (left < 0 || top < 0 || right > root.width || bottomEdge > root.height || bottomEdge <= top) {
                return false
            }
            if (visiblePanel.left != left || visiblePanel.top != top ||
                visiblePanel.right != right || visiblePanel.bottom != bottomEdge
            ) {
                visiblePanel.set(left, top, right, bottomEdge)
                root.invalidateOutline()
                root.invalidate()
            }
            return true
        }

        private fun updateCorner(value: Float) {
            if (value == corner) return
            corner = value
            root.outlineProvider = if (value > 0f) outline else originalOutline
            root.clipToOutline = value > 0f || originalClip
            root.invalidateOutline()
            toolbar?.invalidate()
        }

        private fun updateBlur(radius: Int) {
            val drawable = if (radius > 0 && !nativeBlurFailed) prepareBackdrop(radius) else null
            setBlurActive(drawable != null)
            if (drawable == null) {
                backdrop?.drawable?.setVisible(false, false)
                rootBackground.restore(root)
                footer?.let(footerBackground::restore)
                return
            }
            rootBackground.replace(root, drawable)
            val footerView = footer ?: findFooter()?.also { footer = it }
            if (footerView != null) footerBackground.replace(footerView, null)
        }

        private fun setBlurActive(active: Boolean) {
            if (active == blurActive) return
            blurActive = active
            toolbar?.invalidate()
            keys?.invalidate()
        }

        private fun prepareBackdrop(radius: Int): Drawable? = runCatching {
            val wrapper = backdrop ?: createBackdrop()?.let(::Backdrop)?.also { backdrop = it } ?: return null
            val drawable = wrapper.drawable
            wrapper.region.set(visiblePanel)
            if (appliedRadius != radius) {
                Reflect.callMethod(drawable, "setBlurRadius", radius)
                appliedRadius = radius
            }
            if (appliedCorner != corner) {
                Reflect.callMethod(drawable, "setCornerRadius", corner, corner, 0f, 0f)
                appliedCorner = corner
            }
            tint = if (root.isNight()) 0x99232529.toInt() else 0x99E5E6E7.toInt()
            if (appliedTint != tint) {
                Reflect.callMethod(drawable, "setColor", tint)
                appliedTint = tint
                toolbar?.invalidate()
                keys?.invalidate()
            }
            drawable.setVisible(true, false)
            wrapper
        }.getOrElse {
            nativeBlurFailed = true
            HookHelper.log("$tag: unable to apply window backdrop", it)
            null
        }

        private fun createBackdrop(): Drawable? {
            val viewRoot = Reflect.callMethod(root, "getViewRootImpl") ?: return null
            val drawable = Reflect.callMethod(viewRoot, "createBackgroundBlurDrawable") as? Drawable ?: return null
            HookHelper.log("$tag: created shared native backdrop $visiblePanel in ${root.width}x${root.height}")
            return drawable
        }

        private fun findFooter(): View? {
            val container = bottom?.getChildAt(0) ?: return null
            val id = container.resources.getIdentifier("input_bottom_view", "id", "com.miui.phrase")
            return if (id == 0) null else container.findViewById<View>(id)
        }
    }

    private fun findVisible(view: View, predicate: (View) -> Boolean): View? {
        if (view.visibility != View.VISIBLE) return null
        if (predicate(view)) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findVisible(view.getChildAt(index), predicate)?.let { return it }
            }
        }
        return null
    }

    private data class Options(val blurDp: Float, val cornerDp: Float) {
        companion object {
            val Disabled = Options(0f, 0f)
        }
    }

    private companion object {
        const val SYSTEM_BLUR = "background_blur_enable"
        const val TOOLBAR_CLASS = "com.sohu.inputmethod.main.view.IMEInputCandidateViewContainer"
        const val KEYBOARD_CLASS = "com.sohu.inputmethod.sogou.keyboard.KeyboardRootComponentView"
    }
}
