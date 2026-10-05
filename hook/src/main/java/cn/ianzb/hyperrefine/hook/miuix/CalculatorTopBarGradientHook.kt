package cn.ianzb.hyperrefine.hook.miuix

import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.Collections
import java.util.WeakHashMap

/**
 * 计算器等**顶栏下方需要恢复间距**的 MIUIX 应用的顶栏渐变适配。
 *
 * 遮罩特征（新版 MIUIX 由 `Paint` + `Path` 绘制）已由 [TopBarGradientHook.isMaskPainter] 统一覆盖，
 * 本类只处理计算器特有的 overlay / 间距问题：
 *
 * - **overlay**：内容默认排在顶栏下方；开启 `ActionBarOverlayLayout` 的 overlay 让内容延伸到顶栏
 *   后方，顶栏模糊才真正可见。
 * - **间距**：开启 overlay 后内容顶到 `y=0`，原生间距消失。给 `content` 加 padding 会把内容推离
 *   顶栏、顶栏后方变空 → 模糊消失。因此间距只加在页面内**叶子滚动区内部**（`clipToPadding=false`），
 *   内容仍能在顶栏后方滚动。
 * - **两倍间距**：分屏等场景应用自身会给内容加一层顶栏 inset。通过「内容相对滚动区顶部的实际偏移
 *   （不含我方 padding）」判断：应用已加（偏移 ≥ 半个顶栏高）时不再补，并撤掉我方旧的 padding，
 *   避免「应用一层 + 我方一层」两倍。
 *
 * 说明：
 * - 计算器每页各有一套 `ActionBarOverlayLayout`（顶栏共享在外层），故处理**所有** overlay 的 content，
 *   仅跳过包着 `view_pager` 的外层。
 * - 每个窗口只保留一个布局监听器；顶栏高度从 `ActionBarContainer.onLayout` 缓存（取最大值，规避
 *   收起 / 隐藏态 0 高度同名顶栏）。
 */
class CalculatorTopBarGradientHook : TopBarGradientHook() {

    override val alwaysBlur: Boolean = true

    @Volatile
    private var barHeight: Int = 0

    /** 已注册全局布局监听的观察者，保证一个窗口只监听一次。 */
    private val watchedObservers: MutableSet<ViewTreeObserver> =
        Collections.newSetFromMap(WeakHashMap())

    /** 我方补过 padding 的滚动区，需撤销时用（弱引用，随视图回收）。 */
    private val paddedScrolls: MutableSet<View> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    override fun init() {
        super.init()
        hookOverlay()
        hookBar()
    }

    /** 开启 overlay，并注册布局监听。 */
    private fun hookOverlay() {
        val cls = overlayLayoutClass() ?: return
        listOf("onFinishInflate", "onAttachedToWindow").forEach { name ->
            Reflect.findMethodIfExists(cls, name)?.let { method ->
                HookHelper.hookAfter(method) { param ->
                    if (!on()) return@hookAfter
                    val layout = param.thisObject as? ViewGroup ?: return@hookAfter
                    runCatching { Reflect.callMethod(layout, "setOverlayMode", true) }
                        .onFailure { HookHelper.log("$tag: setOverlayMode failed", it) }
                    watchLayouts(layout)
                    applyForWindow(layout.rootView)
                }
            }
        }
    }

    /** 缓存可见顶栏高度（取最大值，应用内存在隐藏 / 收起态 0 高度同名顶栏）。 */
    private fun hookBar() {
        val loader = target.classLoader ?: return
        val barCls = Reflect.findClassIfExists(BAR, loader) ?: return
        Reflect.findMethodIfExists(
            barCls,
            "onLayout",
            java.lang.Boolean.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
        )?.let { method ->
            HookHelper.hookAfter(method) { param ->
                val bar = param.thisObject as? ViewGroup ?: return@hookAfter
                if (bar.height > barHeight) barHeight = bar.height
                if (!on()) return@hookAfter
                applyForWindow(bar.rootView)
            }
        }
    }

    /** 只在已附加窗口时注册，且每个窗口只保留一个监听器。 */
    private fun watchLayouts(view: View) {
        if (!view.isAttachedToWindow) return
        val observer = view.viewTreeObserver ?: return
        if (!watchedObservers.add(observer)) return
        observer.addOnGlobalLayoutListener { applyForWindow(view.rootView) }
    }

    /** 遍历当前窗口，给每个页面 overlay 的 content 处理间距。 */
    private fun applyForWindow(root: View) {
        if (!on()) return
        val overlays = ArrayList<View>()
        collectOverlays(root, overlays)
        val bars = ArrayList<View>()
        collectBars(root, bars)
        val top = maxOf(barHeight, bars.maxOfOrNull { it.height } ?: 0)
        if (top > 0) barHeight = top
        for (overlay in overlays) {
            // 包着 view_pager 的外层只负责容纳各页，真正处理交给各页自己的 overlay。
            if (findChildByName(overlay, "view_pager") != null) continue
            val content = findChildByName(overlay, "content") ?: continue
            applyInset(content, top)
        }
    }

    /**
     * 给 content 内每个**叶子**滚动区处理顶部间距：
     * 1. 内容已被应用顶下去（实际偏移 ≥ 半个顶栏高）→ 撤销我方 padding 并跳过；
     * 2. 我方已补到位 → 跳过；
     * 3. 否则补到顶栏高度（`clipToPadding=false`）。
     *
     * 顺序必须是 1→2，否则我方旧 padding 会让第 1 步被跳过、残留成两倍。
     */
    private fun applyInset(content: View, top: Int) {
        val scrolls = ArrayList<View>()
        collectScrollables(content, scrolls)
        for (scroll in scrolls) {
            if (containsScrollable(scroll)) continue
            val appTop = contentTopOffset(scroll) - scroll.paddingTop
            if (appTop >= top / 2) {
                if (paddedScrolls.remove(scroll)) {
                    scroll.setPadding(scroll.paddingLeft, 0, scroll.paddingRight, scroll.paddingBottom)
                }
                continue
            }
            if (scroll.paddingTop == top) {
                paddedScrolls.add(scroll)
                continue
            }
            if (scroll is ViewGroup) scroll.clipToPadding = false
            scroll.setPadding(scroll.paddingLeft, top, scroll.paddingRight, scroll.paddingBottom)
            paddedScrolls.add(scroll)
        }
    }

    /**
     * 滚动区内容相对其顶部的实际偏移：沿首子链累加各子项的 `top`。
     *
     * 应用自带的顶栏 inset（加在滚动区自身或任意后代上）都会体现在这条链上；不含我方 padding
     * （调用处会减去 `scroll.paddingTop`）。
     */
    private fun contentTopOffset(scroll: View): Int {
        var offset = 0
        var current: View = scroll
        var depth = 0
        while (current is ViewGroup && current.childCount > 0 && depth < MAX_CHAIN_DEPTH) {
            val child = current.getChildAt(0)
            offset += child.top
            current = child
            depth++
        }
        return offset
    }

    /** 收集 content 下**所有**滚动区（不因遇到滚动区而停止，便于区分叶子 / 外层）；遇嵌套 overlay 即停。 */
    private fun collectScrollables(v: View, out: MutableList<View>) {
        if (isOverlayLayout(v)) return
        if (isScrollable(v)) out.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectScrollables(v.getChildAt(i), out)
        }
    }

    /** 该滚动区内部是否还有滚动区（是则为外层包裹，不作为间距落点）。 */
    private fun containsScrollable(v: View): Boolean {
        if (v !is ViewGroup) return false
        for (i in 0 until v.childCount) {
            val child = v.getChildAt(i)
            if (isScrollable(child) || containsScrollable(child)) return true
        }
        return false
    }

    /** 收集 root 下所有 `action_bar_container`（存在隐藏 / 收起态同名顶栏，需取可见那个）。 */
    private fun collectBars(v: View, out: MutableList<View>) {
        if (v !is ViewGroup) return
        for (i in 0 until v.childCount) {
            val child = v.getChildAt(i)
            if (idName(child) == "action_bar_container") out.add(child)
            collectBars(child, out)
        }
    }

    /**
     * 收集所有 `ActionBarOverlayLayout`。**不能只收含顶栏的**：计算器各页 overlay 没有自己的顶栏
     * （顶栏共享在外层），只收含顶栏的会漏掉各页 → 页面内容永远不加间距。
     */
    private fun collectOverlays(v: View, out: MutableList<View>) {
        if (isOverlayLayout(v)) out.add(v)
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) collectOverlays(v.getChildAt(i), out)
        }
    }

    private fun isOverlayLayout(v: View): Boolean =
        v.javaClass.name == OVERLAY_LAYOUT || v.javaClass.name == ANDROIDX_OVERLAY_LAYOUT

    private fun isScrollable(v: View): Boolean {
        val name = v.javaClass.name
        return name.contains("ScrollView") ||
            name.contains("RecyclerView") ||
            name.contains("ListView") ||
            name.contains("SpringBack")
    }

    private fun overlayLayoutClass(): Class<*>? {
        val loader = target.classLoader ?: return null
        return Reflect.findClassIfExists(OVERLAY_LAYOUT, loader)
            ?: Reflect.findClassIfExists(ANDROIDX_OVERLAY_LAYOUT, loader)
    }

    private fun findChildByName(v: View, name: String): View? {
        if (v !is ViewGroup) return null
        for (i in 0 until v.childCount) {
            val child = v.getChildAt(i)
            if (idName(child) == name) return child
            findChildByName(child, name)?.let { return it }
        }
        return null
    }

    private fun idName(v: View): String =
        runCatching { v.resources.getResourceEntryName(v.id) }.getOrNull().orEmpty()

    /** 计算器专属适配需总开关 + 应用专属适配总开关 + 计算器开关同时开启。 */
    override fun enabled(): Boolean = super.enabled() && TopBarKeys.calculatorAdvancedEnabled()

    private fun on(): Boolean = enabled()

    private companion object {
        const val BAR = "miuix.appcompat.internal.app.widget.ActionBarContainer"
        const val OVERLAY_LAYOUT = "miuix.appcompat.internal.app.widget.ActionBarOverlayLayout"
        const val ANDROIDX_OVERLAY_LAYOUT = "androidx.appcompat.widget.ActionBarOverlayLayout"

        /** 首子链最大遍历深度，避免异常布局导致的过深递归。 */
        const val MAX_CHAIN_DEPTH = 12
    }
}
