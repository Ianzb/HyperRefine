package cn.ianzb.hyperrefine.hook.systemui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cn.ianzb.hyperrefine.hook.misound.AppVolumeKeys
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.systemui.glass.CcGlassKeys
import cn.ianzb.hyperrefine.hook.systemui.glass.OfficialExpandedMaterial
import cn.ianzb.hyperrefine.hook.systemui.radius.CcRadiusKeys
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Proxy

/**
 * 「系统界面模式」的多应用音量面板 —— 原生竖向列样式。
 *
 * 面板在侧边音量条窗口内展开：点击入口按钮后，按钮平滑放大为面板，同时隐藏侧边音量条其它内容
 * （`dialog.alpha = 0`），并接管窗口可触摸区域（内部 insets 覆盖），使音量条可正常拖动。
 * 点击空白关闭并还原侧边音量条。
 *
 * 背景复用官方展开态材质（`OfficialExpandedMaterial`）；音量列反射官方 `VolumeColumn`；
 * 应用图标叠在竖条底部；百分比样式与位置与「侧边音量条百分比」完全统一（`PercentText`）。
 */
object AppVolumePanel {

    private const val TAG = "AppVolumePanel"

    private const val PROVIDER_URI = "content://com.miui.misound.AppVolumeProvider/assistant"
    private const val PLAYER_STATE_STARTED = 2
    private const val USAGE_MEDIA = 1
    private const val USAGE_ASSISTANCE_NAVIGATION_GUIDANCE = 12

    private const val MARGIN_DP = 16
    private const val SLOT_SIZE_DP = 32
    private const val OVERLAY_INSET_DP = 10

    private const val SIDE_SLIDER_ID = "volume_column_slider"

    private const val PERCENT_PREF = "side_volume"
    private const val PERCENT_MASTER_KEY = "side_volume_percent"
    private const val PERCENT_HIGHLIGHT = 0xFF3482FF.toInt()

    private const val OPEN_DURATION_MS = 260L
    private const val CLOSE_DURATION_MS = 250L

    private var host: View? = null
    private var card: View? = null
    private var dialogRef: View? = null
    /** 正在淡出：暂停逐帧跟随（避免位置瞬移），改为整体平移 + 淡出。 */
    private var closing = false
    private var insetsProxy: Any? = null
    private var preDrawContainer: View? = null
    private var preDrawListener: ViewTreeObserver.OnPreDrawListener? = null
    private var windowAttrsOriginal: Pair<Float, Int>? = null
    private val officialColumns = mutableListOf<OfficialVolumeColumnFactory.Column>()

    /** 面板是否正在显示。 */
    fun isShowing(): Boolean = host != null

    /** 打开 / 关闭切换。 */
    fun toggle(root: ViewGroup, dialog: View, context: Context, pluginClassLoader: ClassLoader?, anchor: View) {
        if (isShowing()) hide() else show(root, dialog, context, pluginClassLoader, anchor)
    }

    /** 普通关闭（点击入口 / 空白）：原地淡出。 */
    fun hide() = close(dodge = false)

    /**
     * 侧边音量二级面板展开时的「避让」消失：整体平移 + 淡出（参照入口按钮的时长）。
     * 与 [hide] 区分开，普通关闭不做平移。
     */
    fun hideByExpand() = close(dodge = true)

    private fun close(dodge: Boolean) {
        val currentHost = host ?: return
        // 先摘出本面板的列：动画结束后再释放，期间保留音量条一起淡出。
        val toRelease = ArrayList(officialColumns)
        officialColumns.clear()
        val currentCard = card
        if (currentCard != null && currentCard.isAttachedToWindow) {
            closing = true
            val anim = currentCard.animate()
                .alpha(0f)
                .setDuration(CLOSE_DURATION_MS)
            if (dodge) {
                anim.translationX(currentCard.translationX + slideOutOffset(currentCard, dialogRef))
            }
            anim.withEndAction {
                toRelease.forEach { it.release() }
                cleanup(currentHost)
            }.start()
        } else {
            toRelease.forEach { it.release() }
            cleanup(currentHost)
        }
    }

    /** 关闭时整体平移的方向与幅度：朝远离侧边音量条的一侧滑出。 */
    private fun slideOutOffset(panel: View, dialog: View?): Float {
        val panelLoc = IntArray(2)
        panel.getLocationOnScreen(panelLoc)
        val panelCenter = panelLoc[0] + panel.width / 2f
        val toLeft = if (dialog == null) {
            true
        } else {
            val dialogLoc = IntArray(2)
            dialog.getLocationOnScreen(dialogLoc)
            panelCenter <= dialogLoc[0] + dialog.width / 2f
        }
        return (if (toLeft) -1f else 1f) * panel.width * 0.25f
    }

    private fun cleanup(currentHost: View) {
        if (host !== currentHost) return
        host = null
        card = null
        closing = false
        preDrawListener?.let { listener ->
            runCatching {
                preDrawContainer?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener)
            }
        }
        preDrawListener = null
        preDrawContainer = null
        runCatching { (currentHost.parent as? ViewGroup)?.removeView(currentHost) }
        restoreDialog()
    }

    private fun restoreDialog() {
        dialogRef?.let { dialog ->
            restoreWindowDim(dialog)
            restoreInsets(dialog)
        }
        dialogRef = null
    }

    fun show(
        root: ViewGroup,
        dialog: View,
        context: Context,
        pluginClassLoader: ClassLoader?,
        anchor: View,
    ) {
        hide()
        closing = false
        val density = context.resources.displayMetrics.density
        val apps = activeApps(context)

        // 全窗口透明容器（不消费触摸），面板放在侧边音量条左侧，二者同时可见。
        val container = FrameLayout(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            clipChildren = false
            clipToPadding = false
            isClickable = false
        }

        val panel = FrameLayout(context).apply {
            background = null
            clipChildren = false
            clipToPadding = false
            isClickable = true
            // 初始透明：避免在展开动画播放前先闪一帧最终形态。
            alpha = 0f
            // 面板背景内边距：「多应用面板背景调节」开启后按上下 / 左右分别设置，否则维持现状 16dp。
            val bgCustom = HookPrefs.getBoolean(VolumeBarKeys.PANEL_BG, false)
            val padV = (if (bgCustom) {
                HookPrefs.getFloat(VolumeBarKeys.PANEL_PAD_VERTICAL, VolumeBarKeys.DEFAULT_PAD)
            } else {
                VolumeBarKeys.DEFAULT_PAD
            }).coerceIn(0f, 120f)
            val padH = (if (bgCustom) {
                HookPrefs.getFloat(VolumeBarKeys.PANEL_PAD_HORIZONTAL, VolumeBarKeys.DEFAULT_PAD)
            } else {
                VolumeBarKeys.DEFAULT_PAD
            }).coerceIn(0f, 120f)
            setPadding(
                (padH * density).toInt(),
                (padV * density).toInt(),
                (padH * density).toInt(),
                (padV * density).toInt(),
            )
        }
        val maxWidth = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
        container.addView(
            panel,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = (12 * density).toInt()
                topMargin = (MARGIN_DP * density).toInt()
                bottomMargin = (MARGIN_DP * density).toInt()
            },
        )

        if (apps.isEmpty() || !OfficialVolumeColumnFactory.isAvailable(pluginClassLoader)) {
            panel.addView(
                TextView(context).apply {
                    text = moduleString(context, "app_volume_empty") ?: "No app is playing"
                    setTextColor(Color.parseColor("#B3FFFFFF"))
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding((24 * density).toInt(), (28 * density).toInt(), (24 * density).toInt(), (28 * density).toInt())
                },
            )
        } else {
            panel.addView(
                buildBars(context, density, apps, pluginClassLoader!!, maxWidth),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        // 竖直位置：「高度自动」时与侧边音量条竖直对齐；否则用「高度」百分比（0% 最低、100% 最高、50% 居中）。
        val heightAuto = HookPrefs.getBoolean(AppVolumeKeys.HEIGHT_AUTO, true)
        val heightPercent = HookPrefs.getFloat(AppVolumeKeys.HEIGHT_PERCENT, 50f).coerceIn(0f, 100f)
        panel.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = applyPanelBackground(v, context, pluginClassLoader)
            override fun onViewDetachedFromWindow(v: View) {}
        })
        // 音量条窗口隐藏（侧边音量条收回 / 超时消失）时一并移除面板，避免下次打开残留在最右。
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                if (host === v) cleanup(v)
            }
        })

        host = container
        card = panel
        dialogRef = dialog
        root.addView(container)

        // 侧边音量条保持显示；仅接管窗口触摸区域，使位于其左侧的面板可正常拖动。
        pauseInsets(dialog)
        clearWindowDim(dialog)

        // 每帧跟踪侧边音量条位置（避免收起后重开时位置跳变 / 遮挡主音量条）；
        // 首帧完成布局后再启动展开动画（面板初始 alpha=0，不会先闪最终形态）。
        var started = false
        val listener = object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                // 淡出期间暂停跟随（否则侧边条一变就瞬移），交给整体平移 + 淡出。
                if (!closing) {
                    if (heightAuto) {
                        alignToDialogBar(panel, dialog)
                    } else {
                        val available = container.height - panel.height
                        if (available > 0) panel.translationY = (0.5f - heightPercent / 100f) * available
                    }
                    positionLeftOfDialog(container, panel, dialog, density)
                }
                // 官方可能在展开 / 材质变化后重新应用窗口压暗，这里每帧兜底清除。
                clearWindowDim(dialog)
                if (!started && panel.width > 0) {
                    started = true
                    startShowAnimation(panel)
                }
                return true
            }
        }
        container.viewTreeObserver.addOnPreDrawListener(listener)
        preDrawContainer = container
        preDrawListener = listener
    }

    /** 把面板右边缘贴到侧边音量条左边缘（垂直居中）。 */
    private fun positionLeftOfDialog(container: View, panel: View, dialog: View, density: Float) {
        runCatching {
            if (container.width <= 0 || panel.width <= 0) return
            val dialogLoc = IntArray(2)
            val containerLoc = IntArray(2)
            dialog.getLocationOnScreen(dialogLoc)
            container.getLocationOnScreen(containerLoc)
            val containerRight = containerLoc[0] + container.width
            val gap = (12 * density).toInt()
            val endMargin = (containerRight - dialogLoc[0] + gap).coerceAtLeast(gap)
            val lp = panel.layoutParams as? FrameLayout.LayoutParams ?: return
            if (lp.marginEnd != endMargin) {
                lp.marginEnd = endMargin
                panel.layoutParams = lp
            }
        }
    }

    /**
     * 「高度自动」：把面板内音量条竖直中心对齐到侧边音量条音量条的中心。
     *
     * 使用**布局坐标**（`getTop()` 逐级累加，不含 `translationY`/`scale`）计算，因此不会跟随
     * 侧边音量条拉到端点时的边界弹性动画（整列上下平移 / 缩放），只跟随面板整体的真实位移
     * （如入口按钮出现时整条侧边音量条的上移）。
     */
    private fun alignToDialogBar(panel: View, dialog: View) {
        runCatching {
            val panelSlider = officialColumns.firstOrNull()?.slider ?: return
            val dialogSlider = findSideVolumeSlider(dialog) ?: return
            if (panelSlider.height <= 0 || dialogSlider.height <= 0) return
            val root = panel.rootView
            val panelTop = layoutTopTo(panelSlider, root) ?: return
            val dialogTop = layoutTopTo(dialogSlider, root) ?: return
            val panelCenter = panelTop + panelSlider.height / 2f
            // 侧边面板整体位移（如入口出现时的上移）用 translationY 表示，需保留。
            val dialogCenter = dialogTop + dialogSlider.height / 2f + dialog.translationY
            val ty = dialogCenter - panelCenter
            if (kotlin.math.abs(panel.translationY - ty) > 0.5f) panel.translationY = ty
        }
    }

    /** 计算 [view] 相对 [ancestor] 的布局 Y（逐级累加 `getTop()`，不含平移 / 缩放）。 */
    private fun layoutTopTo(view: View, ancestor: View): Int? {
        var current: View? = view
        var top = 0
        while (current != null && current !== ancestor) {
            top += current.top
            current = current.parent as? View
        }
        return if (current === ancestor) top else null
    }

    /** 在侧边音量条视图里找到它的音量列滑条（id `volume_column_slider`）。 */
    private fun findSideVolumeSlider(root: View): View? {
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findSideVolumeSlider(root.getChildAt(i))?.let { return it }
            }
        }
        val name = runCatching { root.resources.getResourceEntryName(root.id) }.getOrNull()
        return if (name == SIDE_SLIDER_ID) root else null
    }

    /** 面板原地透明度淡入（跟随侧边音量条出现 / 隐藏，不缩放、不位移）。 */
    private fun startShowAnimation(panel: View) {
        runCatching {
            panel.animate()
                .alpha(1f)
                .setDuration(OPEN_DURATION_MS)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }

    /**
     * 关闭音量条窗口的全屏变暗 / 模糊（参考对「侧边二级」的处理）。
     * 仅在本模块面板显示期间生效，关闭时还原。
     */
    private fun clearWindowDim(dialog: View) {
        runCatching {
            val viewRoot = Reflect.callMethod(dialog, "getViewRootImpl") ?: return@runCatching
            val attrs = Reflect.getObjectField(viewRoot, "mWindowAttributes") as? WindowManager.LayoutParams
                ?: return@runCatching
            if (windowAttrsOriginal == null) windowAttrsOriginal = attrs.dimAmount to attrs.flags
            val hasDim = attrs.dimAmount != 0f ||
                (attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0
            // 仅在确实有压暗时才改，避免每帧重复触发窗口重排。
            if (!hasDim) return@runCatching
            attrs.dimAmount = 0f
            attrs.flags = attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            runCatching {
                attrs.javaClass.getMethod("setBlurBehindRadius", Int::class.javaPrimitiveType).invoke(attrs, 0)
            }
            Reflect.callMethod(viewRoot, "setLayoutParams", attrs)
        }.onFailure { HookHelper.log("$TAG: clearWindowDim failed", it) }
    }

    private fun restoreWindowDim(dialog: View) {
        val original = windowAttrsOriginal ?: return
        windowAttrsOriginal = null
        runCatching {
            val viewRoot = Reflect.callMethod(dialog, "getViewRootImpl") ?: return@runCatching
            val attrs = Reflect.getObjectField(viewRoot, "mWindowAttributes") as? WindowManager.LayoutParams
                ?: return@runCatching
            attrs.dimAmount = original.first
            attrs.flags = original.second
            Reflect.callMethod(viewRoot, "setLayoutParams", attrs)
        }
    }

    // ---------------- 窗口触摸区域 ----------------

    private fun pauseInsets(dialog: View) {
        runCatching {
            val listenerType = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            if (!listenerType.isInstance(dialog)) return@runCatching
            val observer = dialog.viewTreeObserver
            val proxy = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { p, method, args ->
                when (method.name) {
                    "onComputeInternalInsets" -> {
                        val info = args?.singleOrNull()
                        info?.javaClass?.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
                            ?.invoke(info, TOUCHABLE_INSETS_FRAME)
                        null
                    }
                    "toString" -> "HyperRefineVolumeInsets"
                    "hashCode" -> System.identityHashCode(p)
                    "equals" -> false
                    else -> null
                }
            }
            val remove = ViewTreeObserver::class.java.getMethod("removeOnComputeInternalInsetsListener", listenerType)
            val add = ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", listenerType)
            remove.invoke(observer, dialog)
            add.invoke(observer, proxy)
            insetsProxy = proxy
            dialog.requestLayout()
            (dialog.rootView)?.requestLayout()
            (dialog.rootView)?.invalidate()
        }.onFailure { HookHelper.log("$TAG: pauseInsets failed", it) }
    }

    private fun restoreInsets(dialog: View) {
        val proxy = insetsProxy ?: return
        insetsProxy = null
        runCatching {
            val listenerType = Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val observer = dialog.viewTreeObserver
            val remove = ViewTreeObserver::class.java.getMethod("removeOnComputeInternalInsetsListener", listenerType)
            val add = ViewTreeObserver::class.java.getMethod("addOnComputeInternalInsetsListener", listenerType)
            remove.invoke(observer, proxy)
            if (listenerType.isInstance(dialog)) add.invoke(observer, dialog)
            dialog.requestLayout()
            (dialog.rootView)?.requestLayout()
        }.onFailure { HookHelper.log("$TAG: restoreInsets failed", it) }
    }

    // ---------------- 背景 ----------------

    private fun applyPanelBackground(view: View, context: Context, pluginClassLoader: ClassLoader?) {
        // 「隐藏面板背景」：不套玻璃 / 背景，只保留音量条。
        if (HookPrefs.getBoolean(AppVolumeKeys.HIDE_PANEL_BG, false)) {
            view.background = null
            view.clipToOutline = false
            view.outlineProvider = null
            view.invalidateOutline()
            return
        }
        val density = context.resources.displayMetrics.density
        val radiusPx = radiusDp(CcRadiusKeys.APP_VOLUME_PANEL) * density
        if (pluginClassLoader != null) {
            runCatching { OfficialExpandedMaterial(pluginClassLoader, context).apply(view) }
                .onFailure { HookHelper.log("$TAG: official material failed", it) }
        } else {
            view.background = GradientDrawable().apply {
                setColor(Color.parseColor("#F21E1E22"))
                cornerRadius = radiusPx
            }
        }
        applyRadius(view, radiusPx)
    }

    private fun applyRadius(view: View, radiusPx: Float) {
        view.clipToOutline = true
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) {
                outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
            }
        }
        view.invalidateOutline()
    }

    // ---------------- 音量条 ----------------

    private fun buildBars(
        context: Context,
        density: Float,
        apps: List<String>,
        pluginClassLoader: ClassLoader,
        maxWidth: Int,
    ): View {
        val streams = OfficialVolumeColumnFactory.allocateFakeStreams(apps)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
        }
        // 音量条间距：「多应用音量条间距调节」开启后按配置值，否则维持现状 6dp。
        val spacingDp = if (HookPrefs.getBoolean(VolumeBarKeys.SPACING, false)) {
            HookPrefs.getFloat(VolumeBarKeys.COLUMN_SPACING, VolumeBarKeys.DEFAULT_SPACING)
        } else {
            VolumeBarKeys.DEFAULT_SPACING
        }
        val margin = (spacingDp.coerceIn(0f, 200f) * density).toInt()
        apps.forEachIndexed { index, pkg ->
            val percent = (currentVolume(context, pkg) * 100f).toInt().coerceIn(0, 100)
            val column = buildOfficialColumn(context, density, pkg, percent, streams.getValue(pkg), pluginClassLoader)
                ?: return@forEachIndexed
            row.addView(
                column,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    // 首/末列不设外侧边距，使左右边距与面板上下内边距一致。
                    marginStart = if (index == 0) 0 else margin
                    marginEnd = if (index == apps.lastIndex) 0 else margin
                },
            )
        }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(
                row,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            post {
                if (width > maxWidth) layoutParams = layoutParams.apply { width = maxWidth }
            }
        }
    }

    /** 官方 VolumeColumn 竖向列：竖条 + 底部应用图标 + 与侧边音量条统一的百分比。 */
    private fun buildOfficialColumn(
        context: Context,
        density: Float,
        pkg: String,
        percent: Int,
        fakeStream: Int,
        pluginClassLoader: ClassLoader,
    ): View? {
        val pm = context.packageManager
        val showPercent = HookPrefs.getBoolean(PERCENT_MASTER_KEY, false)
        val percentText = TextView(context).apply {
            gravity = Gravity.CENTER
            // 单行不换行，避免「100%」的百分号被折到下一行。
            isSingleLine = true
            visibility = if (showPercent) View.VISIBLE else View.GONE
        }
        PercentText.show(percentText, percent, 100, PERCENT_PREF, null, null, PERCENT_HIGHLIGHT)

        val official = OfficialVolumeColumnFactory.create(
            context = context,
            pluginClassLoader = pluginClassLoader,
            fakeStream = fakeStream,
            initialPercent = percent,
            barRadiusPx = (radiusDp(CcRadiusKeys.APP_VOLUME_BAR) * density).toInt(),
            glass = HookPrefs.getBoolean(CcGlassKeys.MASTER, false),
            onPercent = { value -> PercentText.show(percentText, value, 100, PERCENT_PREF, null, null, PERCENT_HIGHLIGHT) },
            onCommit = { value -> forward(context, pkg, value / 100f) },
        ) ?: return null
        officialColumns.add(official)

        val slot = (SLOT_SIZE_DP * density).toInt()
        val inset = (OVERLAY_INSET_DP * density).toInt()
        val iconSize = (SLOT_SIZE_DP * density).toInt()
        val appIcon = ImageView(context).apply {
            runCatching { setImageDrawable(pm.getApplicationIcon(pkg)) }
            imageTintList = null
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            isEnabled = false
            isClickable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        val root = official.view
        if (root is FrameLayout) {
            root.clipChildren = false
            root.addView(appIcon, FrameLayout.LayoutParams(iconSize, iconSize, Gravity.LEFT or Gravity.TOP))
            var positioned = false
            root.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
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
                    val slider = official.slider
                    if (positioned || slider.height <= 0 || v.height <= 0) return
                    val sliderLoc = IntArray(2)
                    val rootLoc = IntArray(2)
                    slider.getLocationOnScreen(sliderLoc)
                    v.getLocationOnScreen(rootLoc)
                    val sliderTop = sliderLoc[1] - rootLoc[1]
                    val sliderBottom = sliderTop + slider.height
                    if (sliderBottom <= sliderTop) return
                    appIcon.layoutParams = FrameLayout.LayoutParams(iconSize, iconSize, Gravity.LEFT or Gravity.TOP)
                        .apply {
                            leftMargin = (v.width - iconSize) / 2
                            topMargin = sliderBottom - iconSize - inset
                        }
                    if (showPercent) {
                        // 百分比容器与竖条同高同位置，位置计算与侧边音量条完全统一。
                        val percentHeight = slider.height
                        val percentHost = FrameLayout(context).apply {
                            addView(
                                percentText,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                                ),
                            )
                        }
                        (v as? ViewGroup)?.addView(
                            percentHost,
                            FrameLayout.LayoutParams(v.width, percentHeight, Gravity.LEFT or Gravity.TOP)
                                .apply {
                                    leftMargin = 0
                                    topMargin = sliderTop
                                },
                        )
                        percentHost.post { PercentText.applyVerticalPosition(percentText, PERCENT_PREF) }
                    }
                    positioned = true
                    appIcon.requestLayout()
                }
            })
        }

        return FrameLayout(context).apply {
            background = null
            clipChildren = false
            clipToPadding = false
            addView(
                root,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
    }

    // ---------------- 配置 ----------------

    private fun radiusDp(item: String): Float {
        val defaultValue = CcRadiusKeys.itemValueDefault(item)
        if (HookPrefs.getBoolean(CcRadiusKeys.customKey(item), CcRadiusKeys.itemCustomDefault(item))) {
            return HookPrefs.getFloat(CcRadiusKeys.valueKey(item), defaultValue)
        }
        if (!HookPrefs.getBoolean(CcRadiusKeys.MASTER, false)) {
            return CcRadiusKeys.itemUncustomizedDefault(item)
        }
        val background = CcRadiusKeys.isBackground(item)
        val key = if (background) CcRadiusKeys.BACKGROUND else CcRadiusKeys.COMPONENT
        val unifiedDefault =
            if (background) CcRadiusKeys.DEFAULT_BACKGROUND else CcRadiusKeys.DEFAULT_COMPONENT
        return HookPrefs.getFloat(key, unifiedDefault)
    }

    // ---------------- 数据 ----------------

    private fun activeApps(context: Context): List<String> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        val pm = context.packageManager
        return runCatching {
            am.activePlaybackConfigurations.mapNotNull { config ->
                val uid = Reflect.callMethod(config, "getClientUid") as? Int ?: return@mapNotNull null
                if (uid < 10000) return@mapNotNull null
                val state = Reflect.callMethod(config, "getPlayerState") as? Int
                if (state != PLAYER_STATE_STARTED) return@mapNotNull null
                val attrs = Reflect.callMethod(config, "getAudioAttributes")
                val usage = attrs?.let { Reflect.callMethod(it, "getUsage") as? Int }
                if (usage != USAGE_MEDIA && usage != USAGE_ASSISTANCE_NAVIGATION_GUIDANCE) return@mapNotNull null
                pm.getNameForUid(uid)
            }.distinct()
        }.getOrDefault(emptyList())
    }

    private fun currentVolume(context: Context, pkg: String): Float {
        runCatching {
            context.contentResolver.query(Uri.parse(PROVIDER_URI), null, null, null, null)?.use { c ->
                val pkgIdx = c.getColumnIndex("_package_name")
                val volIdx = c.getColumnIndex("_volume")
                while (c.moveToNext()) {
                    if (pkgIdx >= 0 && c.getString(pkgIdx) == pkg) {
                        return (c.getFloat(volIdx) / 100f).coerceIn(0f, 1f)
                    }
                }
            }
        }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        return runCatching {
            val get = AudioManager::class.java.getMethod("getPlayerVolume", String::class.java)
            ((get.invoke(am, pkg) as? Float) ?: 1f).coerceIn(0f, 1f)
        }.getOrDefault(1f)
    }

    private fun forward(context: Context, pkg: String, volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        runCatching {
            context.sendBroadcast(
                Intent(AppVolumeKeys.ACTION_SET_VOLUME)
                    .setPackage(AppVolumeKeys.TARGET_PACKAGE)
                    .putExtra(AppVolumeKeys.EXTRA_PACKAGE, pkg)
                    .putExtra(AppVolumeKeys.EXTRA_VOLUME, v)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            )
        }.onFailure { HookHelper.log("$TAG: forward failed", it) }

        runCatching {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return@runCatching
            val config = am.activePlaybackConfigurations.firstOrNull {
                val uid = Reflect.callMethod(it, "getClientUid") as? Int ?: return@firstOrNull false
                context.packageManager.getPackagesForUid(uid)?.contains(pkg) == true
            } ?: return@runCatching
            val set = AudioManager::class.java.getMethod(
                "setPlayerVolume",
                android.media.AudioPlaybackConfiguration::class.java,
                Float::class.javaPrimitiveType,
            )
            set.invoke(am, config, v)
        }
    }

    private fun moduleString(context: Context, name: String): String? = runCatching {
        val module = context.createPackageContext(AppVolumeKeys.MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        val id = module.resources.getIdentifier(name, "string", AppVolumeKeys.MODULE_PACKAGE)
        if (id != 0) module.getString(id) else null
    }.getOrNull()

    private const val TOUCHABLE_INSETS_FRAME = 0
}
