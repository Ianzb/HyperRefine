package cn.ianzb.hyperrefine.hook.miuix

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.Collections

/**
 * 关闭与顶栏渐变重复的笔记渐变遮罩：
 *
 * 1. MIUIX `NestedHeaderLayout` 在 `onFinishInflate` 新建的滚动渐变 `View`（`onDraw` 里画 `LinearGradient`，
 *    即主页面标题下方那条渐变透明度栏）；
 * 2. 笔记「筛选 / 分类标签」栏 `OverlayMaskFrameLayout`（`sticky_view`）内置的 `SearchViewOverlayMaskImpl`
 *    叠加遮罩（在 `dispatchDraw` 里绘制）。
 *
 * 仅当「顶栏渐变」开关开启时生效；不含对应类的应用自动跳过。
 */
class NestedHeaderMaskHook : BaseHook() {

    override val key: String = TopBarKeys.KEY

    private val hookedMaskClasses = Collections.synchronizedSet(HashSet<Class<*>>())

    override fun init() {
        val loader = target.classLoader ?: return

        Reflect.findClassIfExists(NESTED_HEADER, loader)?.let { cls ->
            Reflect.findMethodIfExists(cls, "onFinishInflate")?.let { inflate ->
                HookHelper.intercept(inflate) { chain ->
                    val result = chain.proceed()
                    if (enabled()) (chain.thisObject as? ViewGroup)?.let(::suppressNestedMask)
                    result
                }
            }
        }

        Reflect.findClassIfExists(NOTES_OVERLAY_MASK, loader)?.let { cls ->
            Reflect.findMethodIfExists(cls, "setOverlayMaskEnabled", java.lang.Boolean.TYPE)?.let { method ->
                HookHelper.hookBefore(method) { if (enabled()) it.setArg(0, false) }
                HookHelper.log("$tag: disabled notes overlay mask")
            }
            Reflect.findMethodIfExists(cls, "setMaskColor", Integer.TYPE)?.let { method ->
                HookHelper.hookBefore(method) { if (enabled()) it.setArg(0, 0) }
            }
        }
    }

    private fun enabled(): Boolean = HookPrefs.getBoolean(TopBarKeys.KEY, false)

    /** `NestedHeaderLayout` 直接子项中，容器之外的普通 `View` 即渐变遮罩。 */
    private fun suppressNestedMask(layout: ViewGroup) {
        for (i in 0 until layout.childCount) {
            val child = layout.getChildAt(i)
            if (child is ViewGroup) continue
            runCatching {
                child.background = null
                child.alpha = 0f
                child.visibility = View.GONE
            }
            hookMaskClass(child.javaClass)
        }
    }

    private fun hookMaskClass(cls: Class<*>) {
        if (cls == View::class.java) return
        synchronized(hookedMaskClasses) { if (!hookedMaskClasses.add(cls)) return }
        runCatching {
            val onDraw = cls.getDeclaredMethod("onDraw", Canvas::class.java)
            // 只处理自己覆写了 onDraw 的类，避免误伤 framework View。
            if (onDraw.declaringClass == View::class.java) return
            onDraw.isAccessible = true
            HookHelper.hookBefore(onDraw) { it.setResultValue(null) }
            HookHelper.log("$tag: suppressed nested header gradient (${cls.name})")
        }.onFailure { HookHelper.log("$tag: suppress nested header gradient failed", it) }
    }

    private companion object {
        const val NESTED_HEADER = "miuix.nestedheader.widget.NestedHeaderLayout"
        const val NOTES_OVERLAY_MASK =
            "com.miui.notes.notesui.feature.note.presentation.list.widget.OverlayMaskFrameLayout"
    }
}
