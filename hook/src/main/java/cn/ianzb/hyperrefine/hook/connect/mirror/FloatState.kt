package cn.ianzb.hyperrefine.hook.connect.mirror

import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 妙享投屏浮窗状态机（移植自「妙享桌面增强」的 `r3.q`）。
 *
 * 负责：原生窗口尺寸协调、拖动、四角缩放、收起为气泡、气泡拖动 / 点击恢复、边缘吸附、位置记忆。
 */
internal class FloatState(
    val root: ViewGroup,
    val view: View,
    val lp: WindowManager.LayoutParams,
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    sinkWidth: Int,
    sinkHeight: Int,
    val displaySize: Point,
) {

    companion object {
        private const val PREFS = "hyperrefine_mirror"
        private const val KEY_X = "floating_x"
        private const val KEY_Y = "floating_y"
        private const val KEY_EXPANDED_X = "floating_expanded_x"
        private const val KEY_EXPANDED_Y = "floating_expanded_y"
        private const val KEY_WIDTH = "floating_width"
        private const val KEY_HEIGHT = "floating_height"
        private const val KEY_SINK_WIDTH = "floating_sink_width"
        private const val KEY_SINK_HEIGHT = "floating_sink_height"
        private const val KEY_MODE = "floating_mode"
        private const val KEY_BUBBLE_X = "floating_bubble_x"
        private const val KEY_BUBBLE_Y = "floating_bubble_y"

        private const val GESTURE_NONE = 0
        private const val GESTURE_MOVE = 1
        private const val GESTURE_RESIZE = 2
        private const val GESTURE_BUBBLE = 3

        private const val DOUBLE_TAP_TIMEOUT = 300L

        const val SINK_VIEW_CLASS = "com.xiaomi.mirror.sink.SinkView"

        /**
         * 气泡（最小化图标）固定圆角（dp）。
         *
         * 气泡是独立的「最小化图标」，不随「浮窗圆角」设置变化，保持固定的圆角外观。
         */
        private const val BUBBLE_RADIUS_DP = 14
    }

    // 外部协调尺寸
    var extraW: Int = Math.max(0, width - sinkWidth)
        private set
    var extraH: Int = Math.max(0, height - sinkHeight)
        private set
    var aspect: Float = sinkWidth / Math.max(1, sinkHeight).toFloat()
        private set

    // 窗口 / 内容尺寸
    var winW: Int = width
    var winH: Int = height
    var sinkW: Int = sinkWidth
    var sinkH: Int = sinkHeight
    var nativeW: Int = sinkWidth
    var nativeH: Int = sinkHeight
    var minWidth: Int = 0

    /**
     * 基准（默认）窗口尺寸：以首次构建时的默认尺寸为参照，把配置的 dp 圆角按窗口大小
     * 等比缩放（窗口变大变小则圆角同比例变化，而非始终固定不变）。
     */
    private val baseSinkW: Int = Math.max(1, sinkW)
    private val baseSinkH: Int = Math.max(1, sinkH)

    // 位置
    var x: Int = x
    var y: Int = y
    var expandedX: Int = x
    var expandedY: Int = y
    var bubbleSavedX: Int = x
    var bubbleSavedY: Int = y
    var hasBubblePos: Boolean = false

    /** 0 = 展开，1 = 气泡。 */
    var mode: Int = 0

    var gestureKind: Int = GESTURE_NONE
    var resizeEdges: Int = 0
    var moved: Boolean = false
    private var lastHandleTapTime: Long = 0L
    var pendingCollapse: Boolean = false
    var pendingRestore: Boolean = false
    var busy: Boolean = false
    var suppress: Boolean = false
    var generation: Long = 0
    var restoreGraceUntil: Long = 0
    var listenersInstalled: Boolean = false
    var collapsing: Boolean = false
    var windowNotFocused: Boolean = false
    var bubbleController: Any? = null

    var touchStartRawX: Float = 0f
    var touchStartRawY: Float = 0f
    var startX: Int = 0
    var startY: Int = 0
    var startW: Int = 0
    var startH: Int = 0
    var startSinkW: Int = 0
    var startSinkH: Int = 0

    val bubbleSize: Int = MirrorCompat.dp(root.context, 64)
    val edgeSlop: Int = MirrorCompat.dp(root.context, 34)

    val bubble: ImageView
    val dragHandle: View

    /** 四角拖拽缩放提示（仅视觉，不拦截触摸）。 */
    val cornerHints = CornerHintView(root.context)

    init {
        updateMinWidth()

        val imageView = ImageView(root.context).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val pad = MirrorCompat.dp(root.context, 5)
            setPadding(pad, pad, pad, pad)
            contentDescription = "展开妙享投屏"
            elevation = 0f
            outlineProvider = null
            background = buildBubbleBackground(BUBBLE_RADIUS_DP)
            setImageDrawable(BubbleGlyphDrawable(root.resources.displayMetrics.density))
            isClickable = true
        }
        imageView.setOnTouchListener { _, event -> onGesture(event) }
        bubble = imageView

        dragHandle = buildHandle().apply { contentDescription = "拖动妙享投屏窗口" }

        root.addView(imageView, ViewGroup.LayoutParams(-1, -1))
        root.addView(dragHandle)
        root.addView(cornerHints, ViewGroup.LayoutParams(-1, -1))
    }

    /** 顶部拖动条（颜色随深浅色切换）。 */
    private fun buildHandle(): View = View(root.context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(handleColor())
            cornerRadius = MirrorCompat.dp(root.context, 3).toFloat()
        }
        elevation = MirrorCompat.dp(root.context, 10).toFloat()
        val handleW = MirrorCompat.dp(root.context, 56)
        layoutParams = ViewGroup.MarginLayoutParams(handleW, MirrorCompat.dp(root.context, 5)).apply {
            leftMargin = Math.max(0, (winW - handleW) / 2)
            topMargin = MirrorCompat.dp(root.context, 7)
        }
    }

    /** 提示条颜色：深色模式用黑、浅色模式用白。 */
    private fun handleColor(): Int =
        if (MirrorCompat.isNightMode(root.context)) 0xE7000000.toInt() else 0xE7FFFFFF.toInt()

    private var lastNight: Boolean? = null

    /** 深浅色切换时刷新两条提示条与圆弧提示的颜色。 */
    fun refreshHintColors() {
        val night = MirrorCompat.isNightMode(root.context)
        if (night == lastNight) return
        lastNight = night
        val color = handleColor()
        (dragHandle.background as? GradientDrawable)?.setColor(color)
        cornerHints.invalidate()
    }

    private fun buildBubbleBackground(radiusDp: Int): LayerDrawable {
        val ctx = root.context
        val radius = Math.max(0, radiusDp)
        val outer = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x10000000)
            cornerRadius = MirrorCompat.dp(ctx, radius).toFloat()
        }
        val mid = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0x1C000000)
            cornerRadius = MirrorCompat.dp(ctx, Math.max(0, radius - 1)).toFloat()
        }
        val inner = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(0xFFFDFDFD.toInt())
            setStroke(MirrorCompat.dp(ctx, 1), 0x20000000)
            cornerRadius = MirrorCompat.dp(ctx, Math.max(0, radius - 2)).toFloat()
        }
        return LayerDrawable(arrayOf(outer, mid, inner)).apply {
            val a = MirrorCompat.dp(ctx, 2)
            val b = MirrorCompat.dp(ctx, 4)
            setLayerInset(0, a, MirrorCompat.dp(ctx, 3), a, MirrorCompat.dp(ctx, 1))
            setLayerInset(1, MirrorCompat.dp(ctx, 3), MirrorCompat.dp(ctx, 5), MirrorCompat.dp(ctx, 3), MirrorCompat.dp(ctx, 1))
            setLayerInset(2, b, b, b, b)
        }
    }

    /**
     * 应用自定义圆角：投屏窗口（SinkWindow + 内部 container CardView）、实际画面（SinkView）、
     * 全窗口遮罩层与系统阴影框。圆角按窗口大小相对缩放。
     *
     * @param log 是否输出诊断日志（延迟重复应用时置 false，避免刷屏）
     */
    fun applyWindowRadius(log: Boolean = true) {
        val radiusDp = MirrorConfig.floatingRadius()
        val radiusPx = effectiveRadiusPx()
        val summary = applyRadius(radiusPx)
        bubble.background = buildBubbleBackground(BUBBLE_RADIUS_DP)
        if (log) {
            HookHelper.log(
                "MirrorFloat: corner radius ${radiusDp}dp -> ${radiusPx.toInt()}px " +
                    "hidePole=${MirrorConfig.hidePole()}; $summary"
            )
        }
    }

    /**
     * 窗口尺寸变化（拖动缩放 / 原生重算）时刷新圆角，使其随窗口大小等比缩放。
     *
     * 阴影（`setMiShadow`）与实际画面（`setRoundCorner`）由原生 hook 在窗口更新时自动读取当前尺寸，
     * 但窗口本体（SinkWindow / container CardView）的圆角只在此手动设置，尺寸变化时必须重设，
     * 否则会出现「阴影跟随缩放、本体圆角不变」的不一致。
     */
    fun refreshWindowRadius() {
        applyRadius(effectiveRadiusPx())
    }

    /** 按给定半径设置窗口本体 / 画面 / 遮罩 / 阴影框，返回用于日志的摘要。 */
    private fun applyRadius(radiusPx: Float): String {
        val sinkOk = runCatching { Reflect.callMethod(view, "setRadius", radiusPx) }.isSuccess
        var containerOk = false
        val containerId = view.resources.getIdentifier("container", "id", "com.xiaomi.mirror")
        if (containerId != 0) {
            val container: View? = view.findViewById(containerId)
            if (container != null) {
                containerOk = runCatching { Reflect.callMethod(container, "setRadius", radiusPx) }.isSuccess
                MirrorCompat.applyRoundedOutline(container, radiusPx)
            }
        }
        // 半透明系统阴影框（miuix SmoothFrameLayout2，原生固定 20dp）跟随自定义圆角。
        var shadowOk = false
        MirrorCompat.findViewByClass(root, "com.xiaomi.mirror.sink.ShadowView")?.let { shadow ->
            val frame = runCatching { Reflect.getObjectField(shadow, "h") }.getOrNull() as? View
            if (frame != null) {
                shadowOk = runCatching { Reflect.callMethod(frame, "setCornerRadius", radiusPx.toInt()) }.isSuccess
                runCatching { Reflect.callMethod(shadow, "c", frame) }
            }
        }
        // 实际画面区域：SinkView 通过 setRoundCorner 设置视频表面圆角。
        val sinkView = MirrorCompat.findViewByClass(view, SINK_VIEW_CLASS)
        val videoOk = if (sinkView != null) {
            runCatching { Reflect.callMethod(sinkView, "setRoundCorner", radiusPx) }.isSuccess
        } else {
            false
        }
        // 只对「全窗口」遮罩层补圆角，使边缘随窗口圆角裁剪。
        // 注意：不再对窗口内容里的任意子视图（连接提示文本、输入框等）逐一设圆角，
        // 否则会错误裁剪这些局部组件自身的圆角 / 文本。
        var masks = 0
        for (idName in arrayOf(
            "surfaceview_mask", "mask_view", "mask_view_root",
            "window_loading_view", "sink_loading_abort_layout",
        )) {
            val id = view.resources.getIdentifier(idName, "id", "com.xiaomi.mirror")
            if (id != 0) view.findViewById<View>(id)?.let {
                MirrorCompat.applyRoundedOutline(it, radiusPx)
                masks++
            }
        }
        // 左侧竖条（侧边关闭条）：与 SinkWindow 同级，挂在窗口根布局下，从 root 查找。
        if (MirrorConfig.hidePole()) {
            val poleId = root.resources.getIdentifier("pole_view_root", "id", "com.xiaomi.mirror")
            if (poleId != 0) {
                (root.findViewById<View>(poleId) ?: view.findViewById(poleId))?.visibility = View.GONE
            }
        }
        refreshHintColors()
        return "sink=$sinkOk container=$containerOk video=$videoOk masks=$masks shadow=$shadowOk"
    }

    /**
     * 当前生效的圆角（px）：以「基准窗口尺寸」为参照，把配置的 dp 圆角按窗口大小等比缩放，
     * 窗口缩放时圆角同步变化（而非固定不变）。
     */
    fun effectiveRadiusPx(): Float {
        val base = Math.min(baseSinkW, baseSinkH).toFloat()
        val current = Math.min(sinkW, sinkH).toFloat()
        val scale = if (base > 0f) current / base else 1f
        return MirrorCompat.dp(root.context, MirrorConfig.floatingRadius()) * scale.coerceIn(0.25f, 4f)
    }

    /** 从 `WindowManager.LayoutParams` 同步到窗口。 */
    fun applyLayout(params: WindowManager.LayoutParams) {
        params.x = x
        params.y = y
        params.width = if (mode == 1) bubbleSize else winW
        params.height = if (mode == 1) bubbleSize else winH
        if (MirrorConfig.refreshRateEnabled()) params.preferredRefreshRate = MirrorConfig.fps().toFloat()
        val contentLp = view.layoutParams ?: return
        if (contentLp.width == sinkW && contentLp.height == sinkH) return
        contentLp.width = sinkW
        contentLp.height = sinkH
        view.layoutParams = contentLp
    }

    fun rightClamp(): Int = displaySize.x - MirrorCompat.dp(root.context, 22)

    fun leftClamp(): Int = MirrorCompat.dp(root.context, 22) - bubbleSize

    /** 收起：淡出并进入 pending，等待原生回调或超时兜底。 */
    fun collapse() {
        if (mode != 0 || pendingRestore) return
        if (pendingCollapse && busy) return
        val gen = ++generation
        pendingCollapse = true
        busy = true
        root.visibility = View.INVISIBLE
        root.alpha = 0f
        view.visibility = View.INVISIBLE
        setChildrenAlpha(0f)
        bubble.alpha = 0f
        bubble.visibility = View.GONE
        lp.alpha = 0f
        MirrorCompat.updateWindow(root.context, this)
        root.postDelayed({
            if (generation == gen && pendingCollapse && !pendingRestore && mode == 0) {
                HookHelper.log("MirrorFloat: native collapse timeout; fallback")
                completeNativeCollapse()
            }
        }, 600L)
    }

    /** 原生收起动画完成后调用（无论成功 / 超时）。 */
    fun completeNativeCollapse() {
        val gen = ++generation
        pendingCollapse = false
        busy = false
        if (mode == 1) {
            pendingRestore = false
            nativeRestore()
            snapX()
            root.visibility = View.VISIBLE
            root.alpha = 1f
            applyLayout(lp)
            MirrorCompat.updateWindow(root.context, this)
            saveState()
            return
        }
        lp.alpha = 1f
        view.visibility = View.INVISIBLE
        expandedX = x
        expandedY = y
        mode = 1
        if (hasBubblePos) {
            x = MirrorCompat.clamp(bubbleSavedX, leftClamp(), rightClamp())
            y = MirrorCompat.clamp(bubbleSavedY, 0, Math.max(0, displaySize.y - bubbleSize))
        } else {
            x = MirrorCompat.clamp(x, leftClamp(), rightClamp())
            y = MirrorCompat.clamp(Math.max(0, (winH - bubbleSize) / 2) + expandedY, 0, Math.max(0, displaySize.y - bubbleSize))
        }
        snapX()
        bubbleSavedX = x
        bubbleSavedY = y
        hasBubblePos = true
        nativeRestore()
        setChildrenAlpha(0f)
        root.visibility = View.VISIBLE
        root.alpha = 1f
        bubble.alpha = 1f
        bubble.visibility = View.VISIBLE
        applyLayout(lp)
        MirrorCompat.updateWindow(root.context, this)
        saveState()
        root.requestLayout()
        root.invalidate()
        HookHelper.log("MirrorFloat: collapsed to bubble at $x,$y")
        for (delay in longArrayOf(0, 90, 260, 520)) {
            root.postDelayed({ revealBubble(gen) }, delay)
        }
    }

    private fun revealBubble(gen: Long) {
        if (generation != gen || mode != 1 || pendingRestore || busy) return
        view.visibility = View.INVISIBLE
        setChildrenAlpha(0f)
        bubble.visibility = View.VISIBLE
        bubble.alpha = 1f
        lp.alpha = 1f
        applyLayout(lp)
        MirrorCompat.updateWindow(root.context, this)
    }

    /** 从气泡恢复为窗口。 */
    fun completeRestore(fromNative: Boolean) {
        if (pendingRestore || mode == 0) {
            generation++
            pendingRestore = false
            pendingCollapse = false
            busy = false
            restoreGraceUntil = System.currentTimeMillis() + 750
            if (fromNative) nativeRestore()
            collapsing = false
            mode = 0
            x = MirrorCompat.clamp(expandedX, 0, Math.max(0, displaySize.x - winW))
            y = MirrorCompat.clamp(expandedY, 0, Math.max(0, displaySize.y - winH))
            setChildrenAlpha(1f)
            bubble.alpha = 0f
            bubble.visibility = View.GONE
            view.visibility = View.VISIBLE
            lp.alpha = 1f
            applyLayout(lp)
            MirrorCompat.updateWindow(root.context, this)
            root.alpha = 1f
            root.visibility = View.VISIBLE
            view.requestLayout()
            view.invalidate()
            root.requestLayout()
            root.invalidate()
            refreshShadow()
            saveState()
            MirrorCompat.scheduleSurfaceConfiguration(root)
            HookHelper.log("MirrorFloat: restored $winW x $winH")
        }
    }

    /** 进入收起前的准备（隐藏内容、切到气泡尺寸）。 */
    fun prepareCollapse() {
        mode = 1
        root.visibility = View.INVISIBLE
        root.alpha = 0f
        view.visibility = View.INVISIBLE
        setChildrenAlpha(1f)
        bubble.alpha = 0f
        bubble.visibility = View.GONE
        view.layoutParams = view.layoutParams.apply {
            width = sinkW
            height = sinkH
        }
        lp.width = winW
        lp.height = winH
        lp.alpha = 0f
        lp.x = expandedX
        lp.y = expandedY
        MirrorCompat.updateWindow(root.context, this)
        root.requestLayout()
        root.invalidate()
        bubble.invalidate()
    }

    /** 原生尺寸变化时协调浮窗尺寸（来自 SinkWindow 的参数回调）。 */
    fun reconcile(args: Array<out Any?>?) {
        if (busy) return
        val size = MirrorCompat.displaySize(root.context)
        displaySize.x = size.x
        displaySize.y = size.y
        val native = MirrorCompat.extractNativeSize(args)
        var w: Int
        var h: Int
        if (native != null) {
            w = native[0]
            h = native[1]
        } else {
            val contentLp = view.layoutParams
            w = contentLp?.width ?: 0
            h = contentLp?.height ?: 0
        }
        if (w <= 0 || h <= 0) return
        if (w < nativeW && h < nativeH) return
        if (w == nativeW && h == nativeH && winW <= displaySize.x && winH <= displaySize.y) return
        val wasBubble = mode == 1
        val oldExpandedX = expandedX
        val oldExpandedY = expandedY
        nativeW = w
        nativeH = h
        aspect = w / Math.max(1, h).toFloat()
        val scaled = MirrorCompat.scaledSize(
            w, h, Math.min(0.7f, Math.min(displaySize.x * 0.88f / w, displaySize.y * 0.88f / h)), 360
        )
        sinkW = scaled[0]
        sinkH = scaled[1]
        updateMinWidth()
        winW = sinkW + extraW
        winH = sinkH + extraH
        if (wasBubble) {
            x = MirrorCompat.clamp(x, leftClamp(), rightClamp())
            y = MirrorCompat.clamp(y, 0, Math.max(0, displaySize.y - bubbleSize))
            bubbleSavedX = x
            bubbleSavedY = y
            hasBubblePos = true
            expandedX = MirrorCompat.clamp(oldExpandedX, 0, Math.max(0, displaySize.x - winW))
            expandedY = MirrorCompat.clamp(oldExpandedY, 0, Math.max(0, displaySize.y - winH))
        } else {
            x = MirrorCompat.clamp(x, 0, Math.max(0, displaySize.x - winW))
            y = MirrorCompat.clamp(y, 0, Math.max(0, displaySize.y - winH))
            expandedX = x
            expandedY = y
        }
        updateHandleMargin()
        refreshWindowRadius()
        HookHelper.log("MirrorFloat: reconciled $winW x $winH screen=${displaySize.x}x${displaySize.y}")
        MirrorCompat.scheduleSurfaceConfiguration(root)
    }

    fun nativeRestore() {
        val controller = bubbleController ?: return
        runCatching { Reflect.callMethod(controller, "j") }
    }

    /** 通过原生收起动画（`l1` / `h1`）收起；失败时直接收起。 */
    fun collapseViaNative() {
        if (collapsing || mode != 0) return
        collapsing = true
        runCatching { Reflect.callMethod(view, "l1", null) }
            .recoverCatching { Reflect.callMethod(view, "h1", null) }
            .onFailure {
                HookHelper.log("MirrorFloat: shade collapse animation unavailable", it)
                collapse()
                completeNativeCollapse()
            }
    }

    /** 收起后从气泡恢复（点击气泡）。 */
    private fun restoreFromBubble() {
        val gen = ++generation
        pendingRestore = true
        pendingCollapse = false
        busy = true
        var nativeOk = false
        try {
            prepareCollapse()
            suppress = true
            try {
                nativeRestore()
            } finally {
                suppress = false
            }
            val cls = Reflect.findClassIfExists("com.xiaomi.mirror.sink.e", view.javaClass.classLoader)
                ?: Reflect.findClassIfExists("com.xiaomi.mirror.sink.C3570e", view.javaClass.classLoader)
                ?: throw ClassNotFoundException("native sink controller not found")
            val controller = callStaticAny(cls, arrayOf("S", "T", "m13677S"))
                ?: throw ClassNotFoundException("native sink controller returned null")
            suppress = true
            callMethodAny(controller, arrayOf("w1", "x1", "m13801w1"), false)
            suppress = false
            nativeOk = true
        } catch (t: Throwable) {
            suppress = false
            HookHelper.log("MirrorFloat: native restore unavailable, direct restore", t)
        }
        if (nativeOk) {
            root.postDelayed({
                if (generation == gen && pendingRestore) completeRestore(true)
            }, 520L)
        } else {
            completeRestore(true)
        }
    }

    private fun refreshShadow() {
        val gen = generation
        val runnable = Runnable {
            if (generation != gen || mode != 0 || pendingRestore || busy) return@Runnable
            val shadow = runCatching { Reflect.getObjectField(view, "p3") }.getOrNull() as? View
                ?: MirrorCompat.findShadowView(root)
                ?: return@Runnable
            shadow.invalidate()
            runCatching {
                if (!Reflect.findField(shadow.javaClass, "g").getBoolean(shadow)) return@Runnable
                val surfaceView = Reflect.getObjectField(shadow, "h") as? View ?: return@Runnable
                val rootImpl = Reflect.callMethod(surfaceView, "getViewRootImpl") ?: return@Runnable
                val surfaceControl = Reflect.callMethod(rootImpl, "getSurfaceControl") ?: return@Runnable
                if (Reflect.callMethod(surfaceControl, "isValid") == true) {
                    surfaceView.invalidate()
                    Reflect.callMethod(shadow, "c", surfaceView)
                }
            }.onFailure { HookHelper.log("MirrorFloat: shadow refresh skipped", it) }
        }
        root.post(runnable)
        root.postDelayed(runnable, 80L)
        root.postDelayed(runnable, 260L)
        root.postDelayed(runnable, 600L)
    }

    private fun setChildrenAlpha(alpha: Float) {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child !== bubble) child.alpha = alpha
        }
    }

    /** 吸附到左右边缘。 */
    fun snapX() {
        val margin = MirrorCompat.dp(root.context, 32)
        x = when {
            x <= margin -> leftClamp()
            x >= Math.max(0, displaySize.x - bubbleSize) - margin -> rightClamp()
            else -> MirrorCompat.clamp(x, 0, Math.max(0, displaySize.x - bubbleSize))
        }
    }

    fun updateHandleMargin() {
        val params = dragHandle.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        params.leftMargin = Math.max(0, (winW - params.width) / 2)
        dragHandle.layoutParams = params
    }

    fun updateMinWidth() {
        var min = Math.max(MirrorCompat.dp(root.context, 160), 360)
        if (aspect >= 1f) min = Math.round(min * aspect)
        minWidth = Math.min(sinkW, min)
    }

    /** 检测窗口是否被通知栏 / 输入法顶起（用于自动收起）。 */
    fun updateWindowFocusState() {
        val rect = Rect()
        root.getWindowVisibleDisplayFrame(rect)
        windowNotFocused = displaySize.y - Math.max(0, rect.bottom - rect.top) > MirrorCompat.dp(root.context, 180)
    }

    fun recordDragStart(event: MotionEvent) {
        touchStartRawX = event.rawX
        touchStartRawY = event.rawY
        startX = x
        startY = y
        startW = winW
        startH = winH
        startSinkW = sinkW
        startSinkH = sinkH
    }

    fun saveState() {
        runCatching {
            val prefs = root.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putInt(KEY_X, x)
                .putInt(KEY_Y, y)
                .putInt(KEY_EXPANDED_X, expandedX)
                .putInt(KEY_EXPANDED_Y, expandedY)
                .putInt(KEY_WIDTH, winW)
                .putInt(KEY_HEIGHT, winH)
                .putInt(KEY_SINK_WIDTH, sinkW)
                .putInt(KEY_SINK_HEIGHT, sinkH)
                .putInt(KEY_MODE, mode)
                .putInt(KEY_BUBBLE_X, if (hasBubblePos) bubbleSavedX else Int.MIN_VALUE)
                .putInt(KEY_BUBBLE_Y, if (hasBubblePos) bubbleSavedY else Int.MIN_VALUE)
                .apply()
        }
    }

    /** 从记忆位置恢复（仅在尺寸合法时）。 */
    fun restoreState() {
        val prefs = runCatching {
            root.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull() ?: return
        val sW = prefs.getInt(KEY_SINK_WIDTH, 0)
        val sH = prefs.getInt(KEY_SINK_HEIGHT, 0)
        val w = prefs.getInt(KEY_WIDTH, 0)
        val h = prefs.getInt(KEY_HEIGHT, 0)
        if (sW <= 0 || sH <= 0 || w <= 0 || h <= 0) return
        sinkW = MirrorCompat.clamp(sW, minWidth, Math.max(minWidth, displaySize.x - extraW))
        sinkH = Math.max(1, Math.round(sinkW / Math.max(0.01f, aspect)))
        winW = sinkW + extraW
        winH = sinkH + extraH
        expandedX = MirrorCompat.clamp(prefs.getInt(KEY_EXPANDED_X, 0), 0, Math.max(0, displaySize.x - winW))
        expandedY = MirrorCompat.clamp(prefs.getInt(KEY_EXPANDED_Y, 0), 0, Math.max(0, displaySize.y - winH))
        val bx = prefs.getInt(KEY_BUBBLE_X, Int.MIN_VALUE)
        val by = prefs.getInt(KEY_BUBBLE_Y, Int.MIN_VALUE)
        if (bx != Int.MIN_VALUE && by != Int.MIN_VALUE) {
            bubbleSavedX = bx
            bubbleSavedY = by
            hasBubblePos = true
        }
        val savedMode = prefs.getInt(KEY_MODE, 0)
        if (savedMode == 1) {
            mode = 1
            x = MirrorCompat.clamp(bubbleSavedX, leftClamp(), rightClamp())
            y = MirrorCompat.clamp(bubbleSavedY, 0, Math.max(0, displaySize.y - bubbleSize))
            view.visibility = View.INVISIBLE
            setChildrenAlpha(0f)
            bubble.visibility = View.VISIBLE
        } else {
            mode = 0
            x = expandedX
            y = expandedY
        }
        updateHandleMargin()
        applyLayout(lp)
    }

    // ---------------- 手势 ----------------

    fun onGesture(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> return onDown(event)
            MotionEvent.ACTION_MOVE -> return onMove(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return onUp(event)
        }
        return false
    }

    // ---------------- 拖动条手势 ----------------

    private var handleStartRawX = 0f
    private var handleStartRawY = 0f
    private var handleStartX = 0
    private var handleStartY = 0

    fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handleStartRawX = event.rawX
                handleStartRawY = event.rawY
                handleStartX = x
                handleStartY = y
                dragHandle.isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = Math.round(event.rawX - handleStartRawX)
                val dy = Math.round(event.rawY - handleStartRawY)
                x = MirrorCompat.clamp(handleStartX + dx, 0, Math.max(0, displaySize.x - winW))
                y = MirrorCompat.clamp(handleStartY + dy, 0, Math.max(0, displaySize.y - winH))
                expandedX = x
                expandedY = y
                applyLayout(lp)
                MirrorCompat.updateWindow(root.context, this)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragHandle.isPressed = false
                if (event.actionMasked == MotionEvent.ACTION_UP) saveState()
            }
        }
        return true
    }

    private fun onDown(event: MotionEvent): Boolean {
        if (mode == 1) {
            gestureKind = GESTURE_BUBBLE
            moved = false
            recordDragStart(event)
            return true
        }
        val xPos = event.x
        val yPos = event.y
        val left = xPos <= edgeSlop
        val right = xPos >= winW - edgeSlop
        val top = yPos <= edgeSlop
        val bottom = yPos >= winH - edgeSlop
        resizeEdges = if ((left || right) && (top || bottom)) {
            (if (left) 1 else 4) or (if (top) 2 else 8)
        } else {
            0
        }
        if (resizeEdges != 0) {
            gestureKind = GESTURE_RESIZE
            recordDragStart(event)
            return true
        }
        if (dragHandle.visibility != View.VISIBLE || dragHandle.alpha <= 0f) return false
        var hl = dragHandle.left
        var ht = dragHandle.top
        var hr = dragHandle.right
        var hb = dragHandle.bottom
        if (hr <= hl || hb <= ht) {
            val params = dragHandle.layoutParams
            val hw = params?.width ?: MirrorCompat.dp(root.context, 56)
            val hh = params?.height ?: MirrorCompat.dp(root.context, 5)
            hl = Math.max(0, (winW - hw) / 2)
            ht = MirrorCompat.dp(root.context, 7)
            hr = hl + hw
            hb = ht + hh
        }
        val slop = ViewConfiguration.get(root.context).scaledTouchSlop
        if (xPos >= hl - slop && xPos <= hr + slop && yPos >= ht - slop && yPos <= hb + slop) {
            // 拖动条双击 -> 最小化（收起为气泡）。
            val now = SystemClock.uptimeMillis()
            if (now - lastHandleTapTime in 1..DOUBLE_TAP_TIMEOUT) {
                lastHandleTapTime = 0L
                minimize()
                return true
            }
            gestureKind = GESTURE_MOVE
            moved = false
            recordDragStart(event)
            return true
        }
        return false
    }

    /** 收起为气泡（最小化）。 */
    fun minimize() {
        if (mode != 0 || busy) return
        collapseViaNative()
    }

    private fun onMove(event: MotionEvent): Boolean {
        if (gestureKind == GESTURE_NONE) return false
        val dx = event.rawX - touchStartRawX
        val dy = event.rawY - touchStartRawY
        if (gestureKind == GESTURE_RESIZE) {
            resize(dx, dy)
        } else {
            move(dx, dy)
        }
        return true
    }

    private fun onUp(event: MotionEvent): Boolean {
        val kind = gestureKind
        gestureKind = GESTURE_NONE
        // 本次没有开启任何浮窗手势（普通点击 / 画面内触摸）：DOWN 已下发给画面，
        // UP 必须同样放行，否则画面只收到 DOWN 收不到 UP，会被远端判定为长按。
        if (kind == GESTURE_NONE) return false
        if (kind == GESTURE_BUBBLE) {
            if (event.actionMasked != MotionEvent.ACTION_UP) return true
            if (!moved) {
                if (mode == 1 && !pendingRestore) restoreFromBubble()
            } else {
                snapX()
                applyLayout(lp)
                MirrorCompat.updateWindow(root.context, this)
                saveState()
            }
            return true
        }
        if (event.actionMasked != MotionEvent.ACTION_UP) return true
        when (kind) {
            GESTURE_MOVE -> {
                if (!moved) lastHandleTapTime = SystemClock.uptimeMillis()
                saveState()
            }
            GESTURE_RESIZE -> {
                expandedX = x
                expandedY = y
                MirrorCompat.scheduleSurfaceConfiguration(root)
                saveState()
            }
        }
        return true
    }

    private fun resize(dx: Float, dy: Float) {
        val leftRight = (resizeEdges and 5) != 0
        val topBottom = (resizeEdges and 10) != 0
        var scaleW = 1f
        var scaleH = 1f
        if (resizeEdges and 1 != 0) {
            scaleW = (startW - dx - extraW) / Math.max(1f, startSinkW.toFloat())
        } else if (resizeEdges and 4 != 0) {
            scaleW = (startW + dx - extraW) / Math.max(1f, startSinkW.toFloat())
        }
        if (resizeEdges and 2 != 0) {
            scaleH = (startH - dy - extraH) / Math.max(1f, startSinkH.toFloat())
        } else if (resizeEdges and 8 != 0) {
            scaleH = (startH + dy - extraH) / Math.max(1f, startSinkH.toFloat())
        }
        val scale = when {
            leftRight && topBottom -> if (Math.abs(scaleW - 1f) >= Math.abs(scaleH - 1f)) scaleW else scaleH
            leftRight -> scaleW
            else -> scaleH
        }
        val minScale = minWidth / Math.max(1f, startSinkW.toFloat())
        val limit = Math.min(
            (displaySize.x - extraW) / Math.max(1f, startSinkW.toFloat()),
            (displaySize.y - extraH) / Math.max(1f, startSinkH.toFloat()),
        )
        val finalScale = Math.max(minScale, Math.min(scale, limit))
        val newSinkW = Math.max(minWidth, Math.round(startSinkW * finalScale))
        val newSinkH = Math.max(1, Math.round(newSinkW / aspect))
        val newWinW = extraW + newSinkW
        val newWinH = extraH + newSinkH
        val newX = if (resizeEdges and 1 != 0) startX + startW - newWinW else startX
        val newY = if (resizeEdges and 2 != 0) startY + startH - newWinH else startY
        winW = newWinW
        winH = newWinH
        sinkW = newSinkW
        sinkH = newSinkH
        x = MirrorCompat.clamp(newX, 0, Math.max(0, displaySize.x - newWinW))
        y = MirrorCompat.clamp(newY, 0, Math.max(0, displaySize.y - newWinH))
        expandedX = x
        expandedY = y
        updateHandleMargin()
        applyLayout(lp)
        MirrorCompat.updateWindow(root.context, this)
        refreshWindowRadius()
    }

    private fun move(dx: Float, dy: Float) {
        val baseW = if (mode == 1) bubbleSize else winW
        val baseH = if (mode == 1) bubbleSize else winH
        val slop = ViewConfiguration.get(root.context).scaledTouchSlop
        if (Math.abs(Math.round(dx)) > slop || Math.abs(Math.round(dy)) > slop) moved = true
        val minX = if (mode == 1) leftClamp() else 0
        val maxX = if (mode == 1) rightClamp() else Math.max(0, displaySize.x - baseW)
        x = MirrorCompat.clamp(startX + Math.round(dx), minX, maxX)
        y = MirrorCompat.clamp(startY + Math.round(dy), 0, Math.max(0, displaySize.y - baseH))
        if (mode == 0) {
            expandedX = x
            expandedY = y
        }
        applyLayout(lp)
        MirrorCompat.updateWindow(root.context, this)
    }

    private fun callStaticAny(clazz: Class<*>, names: Array<String>): Any? {
        for (name in names) {
            val method = clazz.declaredMethods.firstOrNull { it.name == name && it.parameterTypes.isEmpty() }
            if (method != null) {
                method.isAccessible = true
                return method.invoke(null)
            }
        }
        throw NoSuchMethodException("${clazz.name}#${names.joinToString()}")
    }

    private fun callMethodAny(instance: Any, names: Array<String>, arg: Any?): Any? {
        for (name in names) {
            val method = instance.javaClass.declaredMethods.firstOrNull {
                it.name == name && it.parameterTypes.size == 1 && it.parameterTypes[0] == java.lang.Boolean.TYPE
            }
            if (method != null) {
                method.isAccessible = true
                return method.invoke(instance, arg)
            }
        }
        throw NoSuchMethodException("${instance.javaClass.name}#${names.joinToString()}")
    }
}
