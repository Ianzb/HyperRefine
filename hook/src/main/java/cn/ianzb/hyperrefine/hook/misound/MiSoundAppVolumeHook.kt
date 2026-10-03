package cn.ianzb.hyperrefine.hook.misound

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.status.HookStatusReporter
import cn.ianzb.hyperrefine.hook.systemui.radius.CcRadiusKeys
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.ref.WeakReference

/**
 * MiSound 侧「分应用音量」支撑（复用官方原生页面 / 组件，不手搓 UI）。
 *
 * - 隐藏系统左侧蓝色悬浮球
 * - 接收侧边音量条入口广播，反射调用官方控制器 `y()` 打开原生分应用音量页
 * - 按参考模块思路配置原生页卡片：ViewPager2 宽度（一屏多列）、卡片圆角 + 官方背景模糊、
 *   竖向滑块尺寸、指示点样式
 *
 * 与参考模块行为一致，但为独立原创实现（反射官方类 / 方法，不复制其代码）。
 */
class MiSoundAppVolumeHook : BaseHook() {

    override val key: String = AppVolumeKeys.ENTRY

    private var controllerRef: WeakReference<Any>? = null
    private var controllerClass: Class<*>? = null
    private var appContext: Context? = null
    private var receiver: BroadcastReceiver? = null

    /** 本次打开是否已播放进入动画。 */
    @Volatile
    private var animatedCurrentOpen = false

    /** 记录已绑定关闭监听的页面根，避免重复。 */
    private val dismissBound = java.util.WeakHashMap<View, Boolean>()

    /** 记录已 dump 的卡片，避免重复。 */
    private val dumped = java.util.WeakHashMap<View, Boolean>()

    /** 原生分应用音量页的窗口根视图（用于空白处点击关闭）。 */
    @Volatile
    private var pageWindowRoot: View? = null

    /** 最近一次「正在播放的应用」集合，用于面板打开期间检测变化并刷新。 */
    @Volatile
    private var lastActiveApps: List<String>? = null

    /** 上次检测正在播放应用的时间戳（限频）。 */
    @Volatile
    private var lastActiveCheck = 0L

    /** 上次展开的时间戳（去抖，避免同一次点击的多个入口重复展开）。 */
    @Volatile
    private var lastExpandAt = 0L

    /** 面板打开期间记录最近一次的主媒体音量，用于把音量键改动实时同步到主音量列。 */
    @Volatile
    private var lastMediaVolume = -1

    /** 每张卡片的打开时间戳，用于界定“稳定期”（其间才做全树关裁剪 / 归位）。 */
    private val openStartedAt = java.util.WeakHashMap<View, Long>()

    /** 「当前无正在播放的应用」占位文本。 */
    private var emptyHintView: WeakReference<TextView>? = null

    /**
     * 正在等待适配器重建 / 布局的卡片。刷新期间保持占位文本可见，待内容真正就绪后再切换，
     * 避免出现「既无占位、也无音量条」的空白帧或塌缩成很小的状态。
     */
    private val pendingContentRefresh = java.util.WeakHashMap<View, Boolean>()

    /** 每张卡片最近一次已套用的空状态（见 EMPTY_STATE_*），用于避免 poll 中重复套用。 */
    private val emptyStateApplied = java.util.WeakHashMap<View, Int>()

    override fun init() {
        hookWindowManager()
        hookController()
        hookVolumeUiService()
        hookMediaColumnCrashGuard()
        // 本 hook 同时实现「隐藏左侧悬浮球」「面板靠右」，据实上报其生效状态。
        HookStatusReporter.markInstalled(AppVolumeKeys.HIDE_FLOAT)
        HookStatusReporter.markInstalled(AppVolumeKeys.ALIGN_RIGHT)
    }

    /**
     * 防御：媒体列 `a$j` 的 SeekBar 可能尚未绑定（其 `a()` 未调用）时，
     * 原生音量变化回调 `a$g` 仍会调用 `a$j.f(volume)` 触发 `setProgress` 空指针，导致
     * MiSound 进程崩溃（表现为收起后无法再次打开）。这里在滑块为空时跳过该次更新。
     */
    private fun hookMediaColumnCrashGuard() {
        val cls = Reflect.findClassIfExists(MEDIA_COLUMN_CLASS, target.classLoader) ?: return
        cls.declaredMethods
            .filter { it.name == "f" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType)) }
            .forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookBefore(method) { param ->
                        val target = param.thisObject ?: return@hookBefore
                        val seekBar = runCatching { Reflect.getObjectField(target, "b") }.getOrNull()
                        if (seekBar == null) {
                            HookHelper.log("$tag: skip media column progress (seekBar null)")
                            param.setResultValue(null)
                        }
                    }
                }.onFailure { HookHelper.log("$tag: hook media column f failed", it) }
            }
    }

    // ---------------- 隐藏悬浮球 / 处理原生页窗口 ----------------

    private fun hookWindowManager() {
        val cls = Reflect.findClassIfExists(WINDOW_MANAGER_IMPL, target.classLoader)
        if (cls == null) {
            HookHelper.log("$tag: $WINDOW_MANAGER_IMPL not found")
            return
        }
        cls.declaredMethods.filter { it.name == "addView" }.forEach { method ->
            method.isAccessible = true
            runCatching {
                HookHelper.hookBefore(method) { param ->
                    val view = param.args.getOrNull(0) as? View ?: return@hookBefore
                    val lp = param.args.getOrNull(1) as? WindowManager.LayoutParams
                    when {
                        HookPrefs.getBoolean(AppVolumeKeys.HIDE_FLOAT, true) && isFloatButton(view, lp) -> {
                            param.setResultValue(null)
                            HookHelper.log("$tag: suppressed float button addView")
                        }
                        isMediaVolumePageView(view) && lp != null -> {
                            configurePageWindow(lp)
                            // 复位透明度（上次收起时为渐隐被置 0），保证每次打开可见。
                            view.alpha = 1f
                            view.translationX = 0f
                            pageWindowRoot = view
                            HookHelper.log("$tag: configured MediaVolumePageView window")
                        }
                    }
                }
            }.onFailure { HookHelper.log("$tag: hook addView failed", it) }
        }
    }

    private fun isFloatButton(view: View, layoutParams: Any?): Boolean {
        val name = view.javaClass.name
        if (name.contains("MediaVolumePageView")) return false
        val type = runCatching { (layoutParams as? WindowManager.LayoutParams)?.type }.getOrNull()
        if (type == VOLUME_OVERLAY_TYPE &&
            runCatching { view.context.packageName == AppVolumeKeys.TARGET_PACKAGE }.getOrDefault(false)
        ) {
            return true
        }
        return name.contains("FloatingActionButton") &&
            runCatching { view.context.packageName == AppVolumeKeys.TARGET_PACKAGE }.getOrDefault(false)
    }

    private fun isMediaVolumePageView(view: View): Boolean = view.javaClass.name.contains("MediaVolumePageView")

    /**
     * 把原生分应用音量页的窗口改为**全屏、可接收空白处点击、无全屏变暗/模糊**，
     * 以便「点击卡片外空白区域关闭」。
     */
    private fun configurePageWindow(lp: WindowManager.LayoutParams) {
        runCatching {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.dimAmount = 0f
            lp.flags = (lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
                WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            lp.javaClass.getMethod("setBlurBehindRadius", Int::class.java).invoke(lp, 0)
        }.onFailure { HookHelper.log("$tag: configurePageWindow failed", it) }
    }

    // ---------------- 控制器 / 原生页配置 ----------------

    private fun hookController() {
        val cls = Reflect.findClassIfExists(CONTROLLER_CLASS, target.classLoader)
        if (cls == null) {
            HookHelper.log("$tag: $CONTROLLER_CLASS not found")
            return
        }
        controllerClass = cls

        cls.declaredConstructors.forEach { constructor ->
            constructor.isAccessible = true
            runCatching {
                HookHelper.hookAfter(constructor) { param ->
                    controllerRef = WeakReference(param.thisObject)
                    HookHelper.log("$tag: captured controller instance")
                }
            }.onFailure { HookHelper.log("$tag: hook controller ctor failed", it) }
        }

        // n()：卡片初始化；y()：右侧滑入（展开）
        cls.declaredMethods
            .filter { it.name == "n" || it.name == "y" }
            .forEach { method ->
                method.isAccessible = true
                val animate = method.name == "y"
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        param.thisObject?.let { setupCardLayout(it, animate) }
                    }
                }.onFailure { HookHelper.log("$tag: hook ${method.name} failed", it) }
            }

        // g()：收起。不要改动状态位 a，否则原生收起逻辑会提前 return、不清理。（曾因此崩溃）
        cls.declaredMethods.firstOrNull { it.name == "g" && it.parameterCount == 0 }?.let { method ->
            method.isAccessible = true
            runCatching {
                HookHelper.hookBefore(method) { _ ->
                    setAnimatorScale(1f)
                    lastActiveApps = null
                }
            }.onFailure { HookHelper.log("$tag: hook g failed", it) }
        }

        // 适配器 a$i：列表项尺寸
        Reflect.findClassIfExists(ADAPTER_CLASS, target.classLoader)?.let { adapter ->
            adapter.declaredMethods.filter { it.name == "onCreateViewHolder" }.forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        itemView(param.result)?.let { adjustAllSlidersInView(it) }
                    }
                }.onFailure { HookHelper.log("$tag: hook onCreateViewHolder failed", it) }
            }
            adapter.declaredMethods.filter { it.name == "onBindViewHolder" }.forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        itemView(param.args.getOrNull(0))?.let { adjustAllSlidersInView(it) }
                    }
                }.onFailure { HookHelper.log("$tag: hook onBindViewHolder failed", it) }
            }
        }

        // 页面切换 a$e.onPageSelected
        Reflect.findClassIfExists(PAGE_CB_CLASS, target.classLoader)
            ?.declaredMethods
            ?.firstOrNull { it.name == "onPageSelected" && it.parameterCount == 1 }
            ?.let { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        onPageSelected(param.args.getOrNull(0) as? Int ?: 0)
                    }
                }.onFailure { HookHelper.log("$tag: hook onPageSelected failed", it) }
            }
    }

    private fun itemView(holder: Any?): View? =
        holder?.let { runCatching { Reflect.getObjectField(it, "itemView") as? View }.getOrNull() }

    private fun setupCardLayout(controller: Any, animate: Boolean = false) {
        runCatching {
            val container = Reflect.getObjectField(controller, "o") as? ViewGroup ?: return
            val alignRight = HookPrefs.getBoolean(AppVolumeKeys.ALIGN_RIGHT, true)
            // 垂直位置改由 topMargin（按百分比）控制，故 gravity 只负责水平对齐。
            val gravity = if (alignRight) (Gravity.END or Gravity.TOP) else (Gravity.CENTER_HORIZONTAL or Gravity.TOP)
            (container as? LinearLayout)?.gravity = gravity
            container.clipChildren = false
            container.clipToPadding = false

            val card = findCardContainer(container) ?: container
            val context = card.context
            val density = context.resources.displayMetrics.density
            // 无活跃播放应用时，隐藏面板、居中显示占位文本。
            val empty = runCatching { activeAppPackages(context).isEmpty() }.getOrDefault(false)

            val vp = findViewPager2(card) ?: (Reflect.getObjectField(controller, "p") as? View)
            // 内容已真正布局出来才隐藏占位文本，避免刷新瞬间出现空白帧。
            val contentReady = !empty && pagerHasLaidOutContent(vp)
            val pages = (Reflect.getObjectField(controller, "u") as? List<*>)?.size ?: 1
            if (vp != null) {
                // 应用数量增多时面板会被拉宽，限制在屏幕可用宽度内，避免溢出屏幕。
                val width = calculateViewPagerWidth(context, pages.coerceIn(1, 3))
                    .coerceAtMost(maxPanelWidth(context, alignRight))
                val lp = vp.layoutParams
                if (lp != null) {
                    lp.width = width
                    (lp as? ViewGroup.MarginLayoutParams)?.let { it.topMargin = 0; it.bottomMargin = 0 }
                    vp.layoutParams = lp
                } else {
                    vp.layoutParams = ViewGroup.MarginLayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT)
                }
            }

            val indicator = Reflect.getObjectField(controller, "q") as? ViewGroup
            val current = vp?.let { runCatching { Reflect.callMethod(it, "getCurrentItem") as? Int }.getOrNull() } ?: 0
            if (indicator != null) {
                indicator.visibility = if (empty || indicator.childCount <= 1) View.GONE else View.VISIBLE
                for (i in 0 until indicator.childCount) {
                    updateIndicatorDotStyle(indicator.getChildAt(i), i == current)
                }
            }

            adjustAllSlidersInView(vp ?: container)

            val radius = panelRadiusDp().coerceIn(0f, 60f) * density
            val sideMargin = ((if (alignRight) PANEL_RIGHT_MARGIN_DP else PANEL_SIDE_MARGIN_DP) * density).toInt()
            val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
            card.layoutParams = when (val parent = card.parent) {
                is LinearLayout -> LinearLayout.LayoutParams(wrap, wrap).apply {
                    this.gravity = gravity
                    marginEnd = sideMargin
                }
                is android.widget.FrameLayout -> android.widget.FrameLayout.LayoutParams(wrap, wrap).apply {
                    this.gravity = gravity
                    marginEnd = sideMargin
                }
                else -> ViewGroup.MarginLayoutParams(wrap, wrap).apply { marginEnd = sideMargin }
            }
            val pad = (16 * density).toInt()
            card.setPadding(pad, pad, pad, pad)
            card.isClickable = true
            card.isFocusable = true
            card.clipChildren = false
            card.clipToPadding = false
            (vp as? ViewGroup)?.let {
                it.clipToPadding = false
                it.clipChildren = false
            }
            // 不裁剪子视图（否则最两边音量条会被圆角裁掉一点点）；圆角由背景 drawable 自身绘制。
            card.clipToOutline = false
            card.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radius)
                }
            }
            val hideBlurBg = HookPrefs.getBoolean(AppVolumeKeys.HIDE_BLUR_BG, false)
            card.elevation = if (hideBlurBg) 0f else 6f * density
            applyCardBackground(card, radius, isNightMode(context))
            // 首帧模糊 / 边框可能未就绪，布局完成后补套几次。
            card.post { applyCardBackground(card, radius, isNightMode(context)) }
            card.postDelayed({ applyCardBackground(card, radius, isNightMode(context)) }, 200L)

            // 关掉内部音量条/列表项的入场动画 + 逐层关闭裁剪（只保留整体卡片的进入动画）。
            disableChildAnimations(card)
            disableChildAnimations(container)
            setupDismiss(controller, container, card)
            applyEmptyState(card, vp, empty, contentReady)
            emptyStateApplied[card] = when {
                empty -> EMPTY_STATE_EMPTY
                contentReady -> EMPTY_STATE_CONTENT
                else -> EMPTY_STATE_PENDING
            }
            if (animate) animateIn(card, waitForContent = !empty)
            startPercentPoll(card, controller)
            HookHelper.log("$tag: setupCardLayout done")
        }.onFailure { HookHelper.log("$tag: setupCardLayout failed", it) }
    }

    // ---------------- 关闭 / 进入动画 ----------------

    /** 点击卡片外任意处关闭原生分应用音量页（避免打开后关不上）。 */
    private fun setupDismiss(controller: Any, container: ViewGroup, card: View) {
        // 卡片自身消费点击，保证只有「卡片外」才触发关闭。
        card.isClickable = true
        if (!card.hasOnClickListeners()) card.setOnClickListener { }
        val root = (pageWindowRoot as? ViewGroup) ?: container
        if (dismissBound[root] == true) return
        dismissBound[root] = true
        root.isClickable = true
        root.setOnTouchListener { _, event ->
            if (event.actionMasked != android.view.MotionEvent.ACTION_DOWN) return@setOnTouchListener false
            if (!card.isAttachedToWindow) return@setOnTouchListener false
            // 空状态（无正在播放应用）时卡片已隐藏，任意点击即关闭。
            if (card.visibility != View.VISIBLE) {
                HookHelper.log("$tag: tap while empty -> close")
                dismissNative(controller)
                return@setOnTouchListener true
            }
            // 未完成布局前不判定，避免误关闭。
            if (card.width <= 0 || card.height <= 0) return@setOnTouchListener false
            val rect = android.graphics.Rect()
            if (!card.getGlobalVisibleRect(rect) || rect.width() <= 0) return@setOnTouchListener false
            if (!rect.contains(event.rawX.toInt(), event.rawY.toInt())) {
                HookHelper.log("$tag: blank tap -> close (raw=${event.rawX.toInt()},${event.rawY.toInt()} card=$rect)")
                dismissNative(controller)
                return@setOnTouchListener true
            }
            false
        }
    }

    private fun dismissNative(controller: Any) {
        animatedCurrentOpen = false
        lastActiveApps = null
        // 恢复本进程动画时长缩放。
        setAnimatorScale(1f)
        val view = pageWindowRoot
        // 原地渐隐：先把整页淡出，再走原生收纳 / 移除，避免右侧滑出。
        if (view != null && view.isAttachedToWindow) {
            view.animate().alpha(0f).setDuration(160L).withEndAction {
                // 保持 alpha=0（下次打开时在 addView 处复位为 1），再由原生清理 / 移除。
                finishDismiss(controller, view)
            }.start()
        } else {
            finishDismiss(controller, view)
        }
    }

    private fun finishDismiss(controller: Any, view: View?) {
        val status = runCatching { Reflect.getObjectField(controller, "a") as? Int }.getOrNull()
        if (status == STATUS_EXPANDED) {
            // 正常收起：让原生 g() 播放滑出动画并在 ~300ms 后调用 p() 移除窗口并清理，
            // 避免强行 removeViewImmediate 导致原生后续回调（媒体列 setProgress）空指针崩溃。
            runCatching { Reflect.callMethod(controller, "g") }
                .onFailure { HookHelper.log("$tag: native dismiss g() failed", it) }
            if (view != null) {
                view.postDelayed({
                    if (view.isAttachedToWindow) {
                        runCatching {
                            val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                            wm?.removeViewImmediate(view)
                        }
                    }
                }, 700L)
            }
            HookHelper.log("$tag: dismissed native page (native animation)")
        } else {
            runCatching { Reflect.setObjectField(controller, "a", 0) }
            if (view != null) {
                runCatching {
                    val wm = view.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    wm?.removeViewImmediate(view)
                    HookHelper.log("$tag: dismissed native page (window removed)")
                }.onFailure { HookHelper.log("$tag: remove page window failed", it) }
            } else {
                HookHelper.log("$tag: dismissed native page (no window ref)")
            }
        }
        pageWindowRoot = null
    }

    /**
     * 卡片原地渐显（每次打开播放一次）。
     *
     * 用逐帧手动驱动，**不受 `ValueAnimator` 全局时长缩放影响**：
     * 打开期间把原生进程的动画时长缩放设为 0 以彻底关掉原生内置的入场动画。
     */
    private fun animateIn(card: View, waitForContent: Boolean = true) {
        if (animatedCurrentOpen) return
        animatedCurrentOpen = true
        card.post {
            // 等原生音量列真正布局完成后再渐显，避免「先空卡片、音量条再冒出来」的二次入场感。
            card.alpha = 0f
            card.translationX = 0f
            var waited = 0L
            lateinit var wait: Runnable
            wait = Runnable {
                if (!card.isAttachedToWindow) return@Runnable
                // 等待期间持续压制（原生可能把 alpha / translation 恢复）。
                card.alpha = 0f
                card.translationX = 0f
                waited += 16L
                val ready = !waitForContent || (card.width > 0 && hasLaidOutSlider(card))
                if (!ready && waited < 1500L) {
                    card.postOnAnimation(wait)
                    return@Runnable
                }
                val startTime = android.view.animation.AnimationUtils.currentAnimationTimeMillis()
                val duration = 200f
                val interpolator = android.view.animation.DecelerateInterpolator(1.4f)
                card.alpha = 0f
                val frame = object : Runnable {
                    override fun run() {
                        if (!card.isAttachedToWindow) return
                        val t = ((android.view.animation.AnimationUtils.currentAnimationTimeMillis() - startTime) / duration)
                            .coerceIn(0f, 1f)
                        card.alpha = interpolator.getInterpolation(t).coerceIn(0f, 1f)
                        if (t < 1f) card.postOnAnimation(this) else {
                            card.translationX = 0f
                            card.alpha = 1f
                        }
                    }
                }
                card.postOnAnimation(frame)
            }
            card.postOnAnimation(wait)
        }
    }

    /**
     * 无活跃播放应用时，保留卡片的模糊区域、隐藏音量列，并在模糊区域内部居中显示占位文本；
     * 有应用时恢复音量列、隐藏占位文本。
     */
    private fun applyEmptyState(card: View, vp: View?, empty: Boolean, contentReady: Boolean) {
        runCatching {
            val hint = getOrCreateEmptyHint(card as? ViewGroup ?: return)
            // 过渡态（非空但内容尚未布局）用 INVISIBLE：既保证音量列参与布局（不塌缩），
            // 又保留占位文本直到内容真正就绪，避免「既无占位、也无音量条」的空白帧。
            vp?.visibility = when {
                empty -> View.GONE
                contentReady -> View.VISIBLE
                else -> View.INVISIBLE
            }
            hint.visibility = if (empty || !contentReady) View.VISIBLE else View.GONE
        }.onFailure { HookHelper.log("$tag: applyEmptyState failed", it) }
    }

    /**
     * 在 poll 中轻量对齐空状态：内容完成布局后收起占位文本、显示音量条。
     * 使用 [lastActiveApps]（由展开 / 刷新链路维护）判断是否为空，避免每次轮询都做重量级探测。
     */
    private fun syncEmptyState(controller: Any, card: View) {
        val apps = lastActiveApps ?: return
        val vp = findViewPager2(card) ?: (Reflect.getObjectField(controller, "p") as? View)
        val empty = apps.isEmpty()
        val contentReady = !empty && pagerHasLaidOutContent(vp)
        val state = when {
            empty -> EMPTY_STATE_EMPTY
            contentReady -> EMPTY_STATE_CONTENT
            else -> EMPTY_STATE_PENDING
        }
        if (emptyStateApplied[card] == state) return
        emptyStateApplied[card] = state
        applyEmptyState(card, vp, empty, contentReady)
    }

    /** ViewPager2 内是否已有完成布局的音量条（用于判断刷新后内容真正就绪）。 */
    private fun pagerHasLaidOutContent(vp: View?): Boolean {
        val v = vp ?: return false
        if (v.width <= 0 || v.height <= 0) return false
        return hasLaidOutSlider(v)
    }

    private fun getOrCreateEmptyHint(card: ViewGroup): TextView {
        emptyHintView?.get()?.let { if (it.parent === card) return it }
        val ctx = card.context
        val tv = TextView(ctx).apply {
            text = moduleString(ctx, EMPTY_HINT_STRING) ?: "当前无正在播放的应用"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, (12 * resources.displayMetrics.density).toInt())
            setShadowLayer(4f, 0f, 0f, 0x99000000.toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val lp: ViewGroup.LayoutParams = when (card) {
            is LinearLayout -> LinearLayout.LayoutParams(wrap, wrap).apply { gravity = Gravity.CENTER }
            is android.widget.FrameLayout -> android.widget.FrameLayout.LayoutParams(wrap, wrap).apply {
                gravity = Gravity.CENTER
            }
            else -> ViewGroup.LayoutParams(wrap, wrap)
        }
        card.addView(tv, lp)
        emptyHintView = WeakReference(tv)
        return tv
    }

    /** 从模块资源按名解析字符串（跟随模块语言设置）。 */
    private fun moduleString(context: Context, name: String): String? = runCatching {
        val module = context.createPackageContext(AppVolumeKeys.MODULE_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
        val id = module.resources.getIdentifier(name, "string", AppVolumeKeys.MODULE_PACKAGE)
        if (id != 0) module.resources.getString(id) else null
    }.getOrNull()

    /** 是否已有完成布局（有尺寸）的原生音量条，用于判断「内容已就绪」。 */
    private fun hasLaidOutSlider(root: View): Boolean {
        var found = false
        forEachSlider(root) { if (it.width > 0 && it.height > 0) found = true }
        return found
    }

    /**
     * 打开原生页期间把本进程动画时长缩放设为 0，彻底关闭原生音量列的入场动画
     * （原生用 `ValueAnimator`/`ViewPropertyAnimator` 驱动的尺寸/透明度过渡都会被即时完成），
     * 关闭时恢复。仅影响 `com.miui.misound` 进程。
     */
    private fun setAnimatorScale(scale: Float) {
        runCatching {
            val method = android.animation.ValueAnimator::class.java
                .getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(null, scale)
            HookHelper.log("$tag: animator duration scale -> $scale")
        }.onFailure { HookHelper.log("$tag: setDurationScale($scale) failed", it) }
    }

    private fun onPageSelected(index: Int) {
        val controller = controllerRef?.get() ?: return
        runCatching {
            val vp = Reflect.getObjectField(controller, "p") as? View
            adjustAllSlidersInView(vp)
            val indicator = Reflect.getObjectField(controller, "q") as? ViewGroup ?: return
            for (i in 0 until indicator.childCount) {
                updateIndicatorDotStyle(indicator.getChildAt(i), i == index)
            }
        }.onFailure { HookHelper.log("$tag: onPageSelected failed", it) }
    }

    // ---------------- 样式辅助（复刻官方数值） ----------------

    /** 与官方侧边音量二级菜单一致的列尺寸（dp）。 */
    private fun dimenPx(context: Context, name: String, fallbackDp: Int): Int {
        val id = runCatching { context.resources.getIdentifier(name, "dimen", context.packageName) }.getOrDefault(0)
        val value = if (id != 0) context.resources.getDimensionPixelSize(id)
        else (fallbackDp * context.resources.displayMetrics.density).toInt()
        return value
    }

    private fun calculateViewPagerWidth(context: Context, count: Int): Int {
        val columnWidth = dimenPx(context, "o3_miui_volume_expend_width", 64)
        return count.coerceIn(1, 3) * (columnWidth + COLUMN_MARGIN_DP * 2)
    }

    /** 面板可用的最大宽度（屏幕宽减去两侧边距）。 */
    private fun maxPanelWidth(context: Context, alignRight: Boolean): Int {
        val dm = context.resources.displayMetrics
        val left = (PANEL_SIDE_MARGIN_DP * dm.density).toInt()
        val right = ((if (alignRight) PANEL_RIGHT_MARGIN_DP else PANEL_SIDE_MARGIN_DP) * dm.density).toInt()
        return dm.widthPixels - left - right
    }

    private fun adjustAllSlidersInView(view: View?) {
        val v = view ?: return
        val name = v.javaClass.name
        if (name.contains("MiuiVolumeSeekBar") || name.contains("VerticalSeekBar")) {
            ensurePercentText(v)
            renderPercent(v)
            applyBarRadius(v)
            val ctx = v.context
            // 与官方侧边音量二级菜单展开列完全同尺寸（64dp × 172dp，间距 14dp）。
            val height = dimenPx(ctx, "o3_miui_volume_expend_height", 172)
            val width = dimenPx(ctx, "o3_miui_volume_expend_width", 64)
            val margin = (COLUMN_MARGIN_DP * ctx.resources.displayMetrics.density).toInt()
            // 让滑块在其父容器内水平居中（与百分比文本同样的水平中心）。
            // 仅在类型 / 尺寸变化时写入，避免拖动逐帧 relayout 造成卡顿。
            val targetLp: ViewGroup.LayoutParams = when (v.parent) {
                is android.widget.FrameLayout ->
                    android.widget.FrameLayout.LayoutParams(width, height).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
                is android.widget.RelativeLayout ->
                    android.widget.RelativeLayout.LayoutParams(width, height).apply {
                        addRule(android.widget.RelativeLayout.CENTER_HORIZONTAL)
                        addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP)
                    }
                is android.widget.LinearLayout -> android.widget.LinearLayout.LayoutParams(width, height)
                else -> ViewGroup.MarginLayoutParams(width, height)
            }
            if (v.clipToOutline) v.clipToOutline = false
            val cur = v.layoutParams
            if (cur == null || cur.javaClass != targetLp.javaClass ||
                cur.width != width || cur.height != height
            ) {
                v.layoutParams = targetLp
            }
            (v.parent as? View)?.let { parent ->
                val plp = parent.layoutParams
                if (plp is ViewGroup.MarginLayoutParams) {
                    val slot = width + margin * 2
                    if (plp.width != slot || plp.height != height) {
                        plp.width = slot
                        plp.height = height
                        plp.marginStart = 0
                        plp.marginEnd = 0
                        plp.topMargin = 0
                        plp.bottomMargin = 0
                        parent.layoutParams = plp
                    }
                }
                if (parent is ViewGroup) {
                    if (parent.clipChildren) parent.clipChildren = false
                    if (parent.clipToPadding) parent.clipToPadding = false
                }
                if (parent.paddingLeft != 0 || parent.paddingTop != 0 ||
                    parent.paddingRight != 0 || parent.paddingBottom != 0
                ) {
                    parent.setPadding(0, 0, 0, 0)
                }
            }
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) adjustAllSlidersInView(v.getChildAt(i))
        }
    }

    /**
     * 音量条圆角：改写原生进度 drawable（LayerDrawable 内的 GradientDrawable）与平滑背景
     * （`miuix.smooth.SmoothContainerDrawable(2)`）的圆角半径。默认值 20dp 与原生一致，等于不改变外观。
     */
    private fun applyBarRadius(slider: View) {
        val radiusDp = barRadiusDp().coerceIn(0f, 60f)
        val radiusPx = radiusDp * slider.resources.displayMetrics.density
        runCatching {
            (slider as? android.widget.ProgressBar)?.let { pb ->
                setDrawableCornerRadius(pb.progressDrawable, radiusPx)
            }
        }.onFailure { HookHelper.log("$tag: apply progressDrawable radius failed", it) }
        runCatching { setSmoothContainerRadius(slider.background, radiusPx) }
            .onFailure { HookHelper.log("$tag: apply seekbar background radius failed", it) }
        (slider as? ViewGroup)?.let { group ->
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i)
                runCatching { setSmoothContainerRadius(child.background, radiusPx) }
                runCatching {
                    (child as? android.widget.ProgressBar)?.let {
                        setDrawableCornerRadius(it.progressDrawable, radiusPx)
                    }
                }
            }
        }
        // 轨道圆角可能挂在音量条的父容器（RoundRectFrameLayout / 列容器）上。
        var ancestor = slider.parent as? View
        var depth = 0
        while (ancestor != null && depth < 3) {
            runCatching { setSmoothContainerRadius(ancestor.background, radiusPx) }
            ancestor = ancestor.parent as? View
            depth++
        }
    }

    /** 递归改写 drawable 树中 GradientDrawable 的圆角半径（兼容 Layer / 包装 drawable）。 */
    private fun setDrawableCornerRadius(drawable: Drawable?, radiusPx: Float) {
        val d = drawable ?: return
        when (d) {
            is android.graphics.drawable.GradientDrawable -> {
                runCatching {
                    d.setCornerRadius(radiusPx)
                    d.invalidateSelf()
                }
            }
            is android.graphics.drawable.LayerDrawable -> {
                for (i in 0 until d.numberOfLayers) setDrawableCornerRadius(d.getDrawable(i), radiusPx)
            }
            else -> {
                // InsetDrawable / ClipDrawable / RotateDrawable / ScaleDrawable 等包装类。
                val nested = runCatching {
                    d.javaClass.getMethod("getDrawable").invoke(d) as? Drawable
                }.getOrNull()
                if (nested != null && nested !== d) setDrawableCornerRadius(nested, radiusPx)
            }
        }
    }

    /**
     * 改写 `miuix.smooth.SmoothContainerDrawable(2)` 的圆角半径。
     * 方法名为混淆后的单字母，分别尝试 setter 与字段回退。
     */
    private fun setSmoothContainerRadius(drawable: Drawable?, radiusPx: Float) {
        val d = drawable ?: return
        val name = d.javaClass.name
        val setters = when {
            name.endsWith("SmoothContainerDrawable2") -> listOf("i", "setRadius", "setCornerRadius")
            name.endsWith("SmoothContainerDrawable") -> listOf("e", "setRadius", "setCornerRadius")
            else -> return
        }
        for (methodName in setters) {
            val ok = runCatching {
                val m = d.javaClass.getMethod(methodName, Float::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(d, radiusPx)
                d.invalidateSelf()
            }.isSuccess
            if (ok) return
        }
        // 字段回退：`j` / `mRadius` 单一半径，`h` / `mRadii` 四角数组。
        runCatching {
            val f = d.javaClass.getDeclaredField("j")
            f.isAccessible = true
            f.setFloat(d, radiusPx)
            d.invalidateSelf()
        }.onFailure {
            runCatching {
                val f = d.javaClass.getDeclaredField("mRadius")
                f.isAccessible = true
                f.setFloat(d, radiusPx)
                d.invalidateSelf()
            }
        }
    }

    // ---------------- 模块百分比功能（外观统一，跟随开关） ----------------

    private val percentTexts = java.util.WeakHashMap<View, TextView>()
    private val percentPolls = java.util.WeakHashMap<View, Runnable>()
    private val lastProgress = java.util.WeakHashMap<View, Int>()

    /** 为每个音量条添加（或复用）一个百分比文本视图，样式对齐侧边音量条。 */
    private fun ensurePercentText(slider: View) {
        val parent = slider.parent as? ViewGroup ?: return
        val existing = percentTexts[slider]
        if (existing != null && existing.parent === parent) return
        val tv = android.widget.TextView(slider.context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val lp = android.widget.FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = Gravity.TOP
            topMargin = (PERCENT_TOP_MARGIN_DP * slider.resources.displayMetrics.density).toInt()
        }
        runCatching { parent.addView(tv, lp) }
        percentTexts[slider] = tv
    }

    /** 单个音量条的百分比文本：跟随模块「侧边音量条百分比」开关与样式。 */
    private fun renderPercent(slider: View) {
        val seek = slider as? android.widget.SeekBar ?: return
        val tv = percentTexts[slider] ?: return
        val enabled = HookPrefs.getBoolean(PERCENT_MASTER_KEY, false)
        if (!enabled) {
            if (tv.visibility != View.GONE) tv.visibility = View.GONE
            lastProgress.remove(slider)
            return
        }
        // 进度未变则跳过，降低拖动时的开销。
        if (lastProgress[slider] == seek.progress && tv.visibility == View.VISIBLE) return
        lastProgress[slider] = seek.progress
        runCatching {
            cn.ianzb.hyperrefine.hook.systemui.PercentText.show(
                tv = tv,
                value = seek.progress,
                max = seek.max,
                pref = PERCENT_PREF,
                icon = null,
                highlightColor = PERCENT_HIGHLIGHT,
            )
            // 跟随侧边音量百分比的高度设置
            cn.ianzb.hyperrefine.hook.systemui.PercentText.applyVerticalPosition(tv, PERCENT_PREF)
        }.onFailure { HookHelper.log("$tag: renderPercent failed", it) }
    }

    private fun updatePercents(root: View) {
        // 按配置的垂直百分比定位面板（仅在需要时改动，未变化则不动 layoutParams）。
        applyVerticalPosition(root)
        // 全树关裁剪 / 归位只在刚打开的一小段“稳定期”内执行，用于抵消原生入场动画与首帧裁剪；
        // 稳定后不再每 100ms 遍历整棵树，避免面板打开时卡顿。
        val settling = SystemClock.uptimeMillis() - (openStartedAt[root] ?: 0L) < SETTLE_MS
        if (settling) disableClipping(root)
        forEachSlider(root) { slider ->
            if (settling) {
                normalizeTransform(slider)
                (slider.parent as? View)?.let {
                    normalizeTransform(it)
                    disableClipping(it)
                }
            }
            ensurePercentText(slider)
            renderPercent(slider)
        }
    }

    /** 按「高度」百分比设置卡片顶部外边距：0%=最低（底部），100%=最高（顶部），50%=居中。 */
    private fun applyVerticalPosition(card: View) {
        runCatching {
            val parent = card.parent as? View ?: return
            if (parent.height <= 0 || card.height <= 0) return
            val available = parent.height - card.height
            if (available <= 0) return
            val percent = HookPrefs.getFloat(AppVolumeKeys.HEIGHT_PERCENT, 50f).coerceIn(0f, 100f)
            val margin = ((1f - percent / 100f) * available).toInt().coerceIn(0, available)
            val lp = card.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (lp.topMargin != margin) {
                lp.topMargin = margin
                card.layoutParams = lp
            }
        }.onFailure { HookHelper.log("$tag: applyVerticalPosition failed", it) }
    }

    /** 逐层关闭裁剪（clipChildren / clipToPadding）。 */
    private fun disableClipping(view: View) {
        fun walk(v: View) {
            if (v is ViewGroup) {
                if (v.clipChildren) v.clipChildren = false
                if (v.clipToPadding) v.clipToPadding = false
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        runCatching { walk(view) }
    }

    private fun normalizeTransform(view: View) {
        if (view.alpha != 1f) view.alpha = 1f
        if (view.translationX != 0f) view.translationX = 0f
        if (view.translationY != 0f) view.translationY = 0f
        if (view.scaleX != 1f) view.scaleX = 1f
        if (view.scaleY != 1f) view.scaleY = 1f
        if (view.rotation != 0f) view.rotation = 0f
    }

    /**
     * 关闭内部视图的入场动画（RecyclerView 的 itemAnimator、各 ViewGroup 的 layoutTransition），
     * 并逐层关闭裁剪（clipChildren / clipToPadding），避免最两边音量条被父容器裁掉一点点。
     */
    private fun disableChildAnimations(root: View) {
        fun walk(v: View) {
            if (v is ViewGroup) {
                if (v.layoutTransition != null) v.layoutTransition = null
                if (v.clipChildren) v.clipChildren = false
                if (v.clipToPadding) v.clipToPadding = false
                if (v.javaClass.name.contains("RecyclerView")) {
                    runCatching { Reflect.callMethod(v, "setItemAnimator", null as Any?) }
                }
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
        }
        runCatching { walk(root) }
    }

    private fun forEachSlider(view: View, action: (View) -> Unit) {
        val name = view.javaClass.name
        if (name.contains("MiuiVolumeSeekBar") || name.contains("VerticalSeekBar")) {
            action(view)
            return
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) forEachSlider(view.getChildAt(i), action)
    }

    /**
     * 面板打开时按音量键只会改动系统音量，原生主音量列不会自动刷新；
     * 这里轮询系统媒体音量，变化时驱动原生媒体列更新（等价于原生 `a$g` 的 `a$j.f(volume)`）。
     */
    private fun syncMainMediaVolume(controller: Any, card: View) {
        val ctx = appContext ?: card.context.applicationContext
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager ?: return
        val vol = runCatching { am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) }.getOrDefault(-1)
        if (vol < 0 || vol == lastMediaVolume) return
        lastMediaVolume = vol
        val column = runCatching { Reflect.getObjectField(controller, "s") }.getOrNull() ?: return
        runCatching { Reflect.callMethod(column, "f", vol) }
            .onFailure { HookHelper.log("$tag: sync main media volume failed", it) }
    }

    /** 轮询刷新百分比（原生滑块未暴露回调，轮询简单可靠），并顺手检测正在播放应用的变化。 */
    private fun startPercentPoll(card: View, controller: Any) {
        if (percentPolls.containsKey(card)) return
        openStartedAt[card] = SystemClock.uptimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                if (!card.isAttachedToWindow) {
                    percentPolls.remove(card)
                    openStartedAt.remove(card)
                    pendingContentRefresh.remove(card)
                    emptyStateApplied.remove(card)
                    return
                }
                runCatching { syncMainMediaVolume(controller, card) }
                runCatching { updatePercents(card) }
                runCatching { syncEmptyState(controller, card) }
                runCatching { reconcileContentRefresh(controller, card) }
                runCatching { maybeRefreshActiveApps(controller, card) }
                card.postDelayed(this, 100L)
            }
        }
        percentPolls[card] = runnable
        card.post(runnable)
    }

    /**
     * 面板打开期间，若「正在播放的应用」发生变化，则让原生控制器重建页面并通知适配器刷新。
     *
     * 原生只在打开时调用一次 `a.u()` 构建列表，之后不会监听播放变化，因此需要本模块补刷。
     */
    private fun maybeRefreshActiveApps(controller: Any, card: View) {
        val now = SystemClock.uptimeMillis()
        if (now - lastActiveCheck < ACTIVE_APP_CHECK_INTERVAL_MS) return
        lastActiveCheck = now
        val context = appContext ?: card.context.applicationContext
        val apps = activeAppPackages(context)
        val previous = lastActiveApps
        if (previous == null) {
            lastActiveApps = apps
            return
        }
        if (apps == previous) return
        lastActiveApps = apps
        HookHelper.log("$tag: active apps changed -> ${apps.joinToString()}")
        pendingContentRefresh[card] = true
        runCatching { Reflect.callMethod(controller, "u") }
            .onFailure { HookHelper.log("$tag: refresh u() failed", it) }
        notifyPager(card)
        // 列数可能变化，重新套用尺寸 / 百分比。此时内容可能尚未布局，占位文本会保留，
        // 待 poll 检测到内容真正就绪后再收起占位（见 reconcileContentRefresh）。
        setupCardLayout(controller)
        card.postDelayed({
            if (card.isAttachedToWindow) runCatching { setupCardLayout(controller) }
        }, CONTENT_REFRESH_RETRY_MS)
    }

    /**
     * 刷新「正在播放应用」后，等适配器重建并完成布局，再重新套用尺寸与空状态。
     *
     * 若只做一次同步 `setupCardLayout`，适配器尚未 notify / 布局时会出现「既无占位文本、
     * 也无音量条且卡片塌缩」的空白帧。这里在 poll 中持续检查，内容就绪后再收敛一次。
     */
    private fun reconcileContentRefresh(controller: Any, card: View) {
        if (pendingContentRefresh[card] != true) return
        val context = appContext ?: card.context.applicationContext
        val empty = runCatching { activeAppPackages(context).isEmpty() }.getOrDefault(false)
        val vp = findViewPager2(card) ?: (Reflect.getObjectField(controller, "p") as? View)
        if (empty || pagerHasLaidOutContent(vp)) {
            pendingContentRefresh[card] = false
            runCatching { setupCardLayout(controller) }
        }
    }

    /** 读取原生 `playervolume.f.a(Context)` 的活跃播放配置并映射为包名列表。 */
    private fun activeAppPackages(context: Context): List<String> {
        val cls = Reflect.findClassIfExists(ACTIVE_APPS_CLASS, target.classLoader) ?: return emptyList()
        val list = runCatching { Reflect.callStaticMethod(cls, "a", context) as? List<*> }.getOrNull()
            ?: return emptyList()
        val pm = context.packageManager
        return list.mapNotNull { config ->
            config ?: return@mapNotNull null
            val uid = runCatching { Reflect.callMethod(config, "getClientUid") as? Int }.getOrNull()
                ?: return@mapNotNull null
            runCatching { pm.getNameForUid(uid) }.getOrNull()
        }
    }

    private fun notifyPager(card: View) {
        val vp = findViewPager2(card) ?: return
        val adapter = runCatching { Reflect.callMethod(vp, "getAdapter") }.getOrNull() ?: return
        // 延迟到布局之外再通知，避免在 ViewPager2 测量期间触发
        // 「The specified child already has a parent」而崩溃。
        vp.postDelayed({
            runCatching { Reflect.callMethod(adapter, "notifyDataSetChanged") }
                .onFailure { HookHelper.log("$tag: notifyDataSetChanged failed", it) }
        }, 150L)
    }

    /** 套用卡片背景：默认模糊边框；开启「隐藏背景模糊边框」时置空背景。 */
    private fun applyCardBackground(view: View, cornerRadius: Float, isNight: Boolean) {
        if (HookPrefs.getBoolean(AppVolumeKeys.HIDE_BLUR_BG, false)) {
            if (view.background != null) view.background = null
            return
        }
        applyBackdropBlur(view, cornerRadius, isNight)
    }

    private fun applyBackdropBlur(view: View, cornerRadius: Float, isNight: Boolean) {
        runCatching {
            val viewRoot = Reflect.callMethod(view, "getViewRootImpl") ?: return
            val drawable = Reflect.callMethod(viewRoot, "createBackgroundBlurDrawable") as? Drawable ?: return
            Reflect.callMethod(drawable, "setBlurRadius", 100)
            Reflect.callMethod(drawable, "setCornerRadius", cornerRadius, cornerRadius, cornerRadius, cornerRadius)
            Reflect.callMethod(drawable, "setColor", Color.parseColor(if (isNight) "#801E1E22" else "#77626262"))
            view.background = drawable
        }.onFailure { HookHelper.log("$tag: applyBackdropBlur failed", it) }
    }

    private fun updateIndicatorDotStyle(view: View, selected: Boolean) {
        view.isSelected = selected
        val color = Color.parseColor("#55FFFFFF")
        if (view is ImageView) {
            view.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            view.imageTintList = ColorStateList.valueOf(color)
            view.imageTintMode = PorterDuff.Mode.SRC_IN
        }
        view.backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun findViewPager2(view: View): View? {
        if (view.javaClass.name.contains("ViewPager2")) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findViewPager2(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun findCardContainer(root: ViewGroup): ViewGroup? {
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is ViewGroup && findViewPager2(child) != null) return child
        }
        for (i in 0 until root.childCount) {
            val child = root.getChildAt(i)
            if (child is ViewGroup) findCardContainer(child)?.let { return it }
        }
        return null
    }

    private fun isNightMode(context: Context): Boolean =
        (context.resources.configuration.uiMode and 0x30) == 0x20

    /**
     * 分应用音量面板圆角（dp）。未启用「圆角调整」时保持 1.3.0 的默认值；
     * 启用后由统一背景值 / 单项自定义控制。
     */
    private fun panelRadiusDp(): Float = ccRadiusDp(CcRadiusKeys.APP_VOLUME_PANEL)

    /**
     * 分应用音量**内部音量条**圆角（dp）。未启用「圆角调整」时保持 1.3.0 的默认值；
     * 启用后由统一组件值 / 单项自定义控制。
     */
    private fun barRadiusDp(): Float = ccRadiusDp(CcRadiusKeys.APP_VOLUME_BAR)

    /**
     * 解析分应用音量圆角配置（dp）：
     * - 未启用「圆角调整」总开关：使用该功能 1.3.0 的默认圆角（开启自定义圆角前的值）；
     * - 启用后：单项自定义优先，否则取统一组件 / 背景值。
     */
    private fun ccRadiusDp(item: String): Float {
        val defaultValue = CcRadiusKeys.itemValueDefault(item)
        if (!HookPrefs.getBoolean(CcRadiusKeys.MASTER, false)) return defaultValue
        if (HookPrefs.getBoolean(CcRadiusKeys.customKey(item), CcRadiusKeys.itemCustomDefault(item))) {
            return HookPrefs.getFloat(CcRadiusKeys.valueKey(item), defaultValue)
        }
        val background = CcRadiusKeys.isBackground(item)
        val key = if (background) CcRadiusKeys.BACKGROUND else CcRadiusKeys.COMPONENT
        val unifiedDefault =
            if (background) CcRadiusKeys.DEFAULT_BACKGROUND else CcRadiusKeys.DEFAULT_COMPONENT
        return HookPrefs.getFloat(key, unifiedDefault)
    }

    // ---------------- 展开 / 接收器 ----------------

    private fun expand() {
        val context = appContext
        val controller = controllerRef?.get() ?: resolveController(context)
        if (controller == null) {
            HookHelper.log("$tag: controller instance not available")
            return
        }
        runCatching {
            // 同一次点击可能触发多个入口视图，展开去抖，避免重复展开。
            val now = SystemClock.uptimeMillis()
            if (now - lastExpandAt < EXPAND_DEBOUNCE_MS) {
                HookHelper.log("$tag: expand debounced")
                return@runCatching
            }
            lastExpandAt = now
            val status = Reflect.getObjectField(controller, "a") as? Int
            // 只要入口可见即说明面板不在显示（展开时入口会隐藏），任何残留的展开状态
            // 都视为僵尸窗口：强制移除旧窗口并复位状态后再重新展开，避免「连续打开后打不开」。
            if (status == STATUS_EXPANDED) {
                HookHelper.log("$tag: stale expanded state, force reopening")
                runCatching {
                    pageWindowRoot?.let { old ->
                        val wm = old.context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                        wm?.removeViewImmediate(old)
                    }
                }
                pageWindowRoot = null
            }
            Reflect.setObjectField(controller, "a", STATUS_IDLE)
            // 关闭原生入场动画（仅整卡由本模块手动滑入）。
            setAnimatorScale(0f)
            // 仅在无页时重建列表。频繁调用 u() 会在不通知适配器的情况下替换媒体列实例，
            // 导致可见列与 controller.s 不是同一实例（主音量不随音量键更新的根因）。
            val pages = Reflect.getObjectField(controller, "u") as? List<*>
            if (pages.isNullOrEmpty()) {
                runCatching { Reflect.callMethod(controller, "u") }
                    .onFailure { HookHelper.log("$tag: refresh u() failed", it) }
            }
            // 记录基线，避免刚打开就触发一次无谓刷新。
            lastActiveCheck = SystemClock.uptimeMillis()
            lastActiveApps = context?.let { activeAppPackages(it) }
            Reflect.callMethod(controller, "y")
            // 不再主动 notifyDataSetChanged：页面重新附着到窗口时 ViewPager2 会按新的列表自行
            // 重新布局；主动通知会在测量期触发「child already has a parent」崩溃。
            HookHelper.log("$tag: expand invoked (y)")
        }.onFailure { HookHelper.log("$tag: expand failed", it) }
    }

    /** 参考模块 `getControllerInstance`：单例字段 `E`，或静态工厂 `j(Context)`。 */
    private fun resolveController(context: Context?): Any? {
        val cls = controllerClass ?: return null
        runCatching { Reflect.getStaticObjectField(cls, "E") }.getOrNull()?.let { return it }
        return context?.let { ctx ->
            runCatching { Reflect.callStaticMethod(cls, "j", ctx) }.getOrNull()
        }
    }

    private fun hookVolumeUiService() {
        val cls = Reflect.findClassIfExists(VOLUME_UI_SERVICE, target.classLoader)
        if (cls == null) {
            HookHelper.log("$tag: $VOLUME_UI_SERVICE not found")
            return
        }
        cls.declaredMethods
            .filter { it.name == "onCreate" || it.name == "onStartCommand" }
            .forEach { method ->
                val isStartCommand = method.name == "onStartCommand"
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        val service = param.thisObject as? android.app.Service ?: return@hookAfter
                        val context = service.applicationContext
                        appContext = context
                        ensureReceiver(context)
                        if (isStartCommand) {
                            val intent = param.args.getOrNull(0) as? Intent
                            if (intent?.getBooleanExtra(AppVolumeKeys.EXTRA_EXPAND, false) == true) {
                                // 入口点击显式拉起服务时携带的展开请求：等初始化完成后再展开。
                                HookHelper.log("$tag: expand requested via service start")
                                android.os.Handler(android.os.Looper.getMainLooper())
                                    .postDelayed({ expand() }, SERVICE_EXPAND_DELAY_MS)
                            }
                        }
                    }
                }.onFailure { HookHelper.log("$tag: hook ${method.name} failed", it) }
            }
    }

    @Synchronized
    private fun ensureReceiver(context: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                HookHelper.log("$tag: received ${intent?.action}")
                if (intent?.action == AppVolumeKeys.ACTION_EXPAND) expand()
            }
        }
        runCatching {
            context.registerReceiver(r, IntentFilter(AppVolumeKeys.ACTION_EXPAND), Context.RECEIVER_EXPORTED)
            receiver = r
            HookHelper.log("$tag: expand receiver registered")
        }.onFailure { HookHelper.log("$tag: register receiver failed", it) }
    }

    companion object {
        private const val WINDOW_MANAGER_IMPL = "android.view.WindowManagerImpl"
        private const val CONTROLLER_CLASS = "com.miui.misound.playervolume.a"
        private const val ADAPTER_CLASS = "com.miui.misound.playervolume.a\$i"
        private const val PAGE_CB_CLASS = "com.miui.misound.playervolume.a\$e"
        private const val VOLUME_UI_SERVICE = "com.miui.misound.playervolume.VolumeUIService"

        /** 媒体列（媒体音量列）；其 SeekBar 未绑定时需拦截进度更新以防崩溃。 */
        private const val MEDIA_COLUMN_CLASS = "com.miui.misound.playervolume.a\$j"

        /** 原生「活跃播放列表」工具类（静态方法 `a(Context)`）。 */
        private const val ACTIVE_APPS_CLASS = "com.miui.misound.playervolume.f"

        /** 面板打开后做全树关裁剪 / 归位的“稳定期”（ms）。 */
        private const val SETTLE_MS = 2500L

        /** 面板打开期间检测「正在播放应用」变化的间隔（ms）。 */
        private const val ACTIVE_APP_CHECK_INTERVAL_MS = 1000L

        /** 展开去抖窗口（ms）：同一次点击的多个入口只展开一次。 */
        private const val EXPAND_DEBOUNCE_MS = 1200L

        /** 通过服务启动拉起后，延迟多久再展开面板（等待服务/控制器初始化）。 */
        private const val SERVICE_EXPAND_DELAY_MS = 250L

        /** 「当前无正在播放的应用」字符串资源名（模块资源）。 */
        private const val EMPTY_HINT_STRING = "app_volume_empty"

        /** `WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY` */
        private const val VOLUME_OVERLAY_TYPE = 2020

        /** 控制器状态字段 `a` 的取值。 */
        private const val STATUS_IDLE = 0
        private const val STATUS_EXPANDED = 5000

        /** 列间距（dp）。 */
        private const val COLUMN_MARGIN_DP = 8

        /** 刷新「正在播放应用」后，补套一次尺寸 / 空状态的延迟（ms）。 */
        private const val CONTENT_REFRESH_RETRY_MS = 350L

        /** 空状态：无正在播放应用（显示占位文本）。 */
        private const val EMPTY_STATE_EMPTY = 0

        /** 空状态：有应用且音量列已布局完成（显示音量条）。 */
        private const val EMPTY_STATE_CONTENT = 1

        /** 空状态：有应用但音量列尚未布局完成（占位文本 + 隐藏音量列过渡）。 */
        private const val EMPTY_STATE_PENDING = 2

        /** 面板与屏幕边缘的间距（dp）。 */
        private const val PANEL_SIDE_MARGIN_DP = 16

        /** 面板靠右时与屏幕右边的间距（dp）。 */
        private const val PANEL_RIGHT_MARGIN_DP = 100

        /** 复用「侧边音量条」百分比配置（外观统一、跟随开关）。 */
        private const val PERCENT_MASTER_KEY = "side_volume_percent"
        private const val PERCENT_PREF = "side_volume"
        private val PERCENT_HIGHLIGHT = 0xFF3482FF.toInt()

        /** 百分比文本与音量条顶部的间距（dp）。 */
        private const val PERCENT_TOP_MARGIN_DP = 22
    }
}
