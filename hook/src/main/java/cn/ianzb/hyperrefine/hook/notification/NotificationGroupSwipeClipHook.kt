package cn.ianzb.hyperrefine.hook.notification

import android.graphics.Rect
import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Method
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * 通知组子通知侧滑时，避免被父级摘要行的裁剪区域横向裁切。
 *
 * 普通通知是整行平移，行自身的裁剪区域会随行一起移动；通知组里的子通知
 * 只是子 View 平移，父级仍保留自身宽度的裁剪，滑出组边界的部分会被裁掉。
 * 这里在子通知侧滑期间临时关闭父级和直接容器的 clipChildren/clipBounds，
 * 松手/复位后恢复。
 *
 * 注意：不能把 clipBounds 扩成负数/超屏矩形。悬浮通知的窗口可触摸区域会
 * 参考 View 的可见区域，超屏 clipBounds 会让整块屏幕都被判定为通知可触摸。
 */
class NotificationGroupSwipeClipHook : BaseHook() {

    override val tag: String = "NotificationGroupSwipeClip"

    /** 通知功能页的侧滑裁切开关。 */
    override val key: String = NotificationKeys.GROUP_SWIPE_CLIP

    private data class ClipState(
        val bounds: Rect?,
        val clipChildren: Boolean,
        val clipToPadding: Boolean,
    )

    private val savedState = WeakHashMap<View, ClipState>()
    private val activeChildren = WeakHashMap<View, MutableSet<View>>()
    private val childTargets = WeakHashMap<View, MutableSet<View>>()
    private val childParent = WeakHashMap<View, View>()

    override fun init() {
        val classLoader = target.classLoader
        val rowClass = Reflect.findClassIfExists(ROW_CLASS, classLoader) ?: run {
            HookHelper.log("$tag: $ROW_CLASS not found")
            return
        }
        val setTranslation = Reflect.findMethodIfExists(
            rowClass,
            "setTranslation",
            Float::class.javaPrimitiveType!!,
        )
        val resetTranslation = Reflect.findMethodIfExists(rowClass, "resetTranslation")
        val getParent = Reflect.findMethodIfExists(rowClass, "getNotificationParent")
        if (setTranslation == null || resetTranslation == null || getParent == null) {
            HookHelper.log(
                "$tag: method not found " +
                    "(setTranslation=${setTranslation != null}, " +
                    "resetTranslation=${resetTranslation != null}, " +
                    "getNotificationParent=${getParent != null})",
            )
            return
        }

        HookHelper.hookAfter(setTranslation) { param ->
            val row = param.thisObject as? View ?: return@hookAfter
            val parent = notificationParent(row, getParent) ?: return@hookAfter
            val translation = (param.args.firstOrNull() as? Float) ?: 0f
            if (abs(translation) < 0.5f) {
                clearChild(parent, row)
            } else if (isHeadsUp(row, parent)) {
                // 悬浮通知的窗口可触摸区域依赖自身可见区域；不要对它关闭裁剪。
                clearChild(parent, row)
            } else {
                childParent[row] = parent
                activeChildren.getOrPut(parent) { newActiveSet() }.add(row)
                expand(parent, row)
            }
        }

        HookHelper.hookAfter(resetTranslation) { param ->
            val row = param.thisObject as? View ?: return@hookAfter
            val parent = notificationParent(row, getParent)
            if (parent != null) {
                clearChild(parent, row)
            } else {
                childTargets.remove(row)
                restoreUnused()
            }
        }

        // 行被移除时不一定走 resetTranslation，补一次清理。
        Reflect.findMethodIfExists(rowClass, "onDetachedFromWindow")?.let { detach ->
            HookHelper.hookAfter(detach) { param ->
                val row = param.thisObject as? View ?: return@hookAfter
                childTargets.remove(row)
                val parent = childParent.remove(row)
                if (parent != null) {
                    activeChildren[parent]?.remove(row)
                    if (activeChildren[parent].isNullOrEmpty()) activeChildren.remove(parent)
                }
                restoreUnused()
            }
        }

        // 系统更新裁剪时会重新写回 clipBounds；只要该 View 仍处于侧滑状态，
        // 就再次关闭裁剪，避免它把我们的状态覆盖掉。
        Reflect.findMethodIfExists(rowClass, "updateClipping")?.let { updateClipping ->
            HookHelper.hookAfter(updateClipping) { param ->
                val view = param.thisObject as? View ?: return@hookAfter
                if (savedState.containsKey(view)) disableClipping(view)
            }
        }
    }

    private fun newActiveSet(): MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    private fun clearChild(parent: View, child: View) {
        childParent.remove(child)
        activeChildren[parent]?.let { children ->
            children.remove(child)
            if (children.isEmpty()) activeChildren.remove(parent)
        }
        childTargets.remove(child)
        restoreUnused()
    }

    private fun notificationParent(row: View, getParent: Method): View? =
        runCatching { getParent.invoke(row) as? View }.getOrNull()

    /** 悬浮通知（HUN）及其所在窗口不能参与裁剪关闭。 */
    private fun isHeadsUp(row: View, parent: View): Boolean {
        if (Reflect.getObjectField(row, "mIsHeadsUp") as? Boolean == true) return true
        if (Reflect.getObjectField(parent, "mIsHeadsUp") as? Boolean == true) return true
        var current: android.view.ViewParent? = row.parent
        while (current is View) {
            if (current.javaClass.name.contains("HeadsUp", ignoreCase = true)) return true
            current = current.parent
        }
        return false
    }

    private fun expand(parent: View, child: View) {
        val targets = childTargets.getOrPut(child) { newActiveSet() }
        disableClipping(parent)
        targets.add(parent)

        // 子通知的直接父容器通常也是裁剪来源；一起关闭，避免只放开摘要行
        // 却被中间容器继续裁掉。
        val container = child.parent as? View
        if (container != null && container !== parent) {
            disableClipping(container)
            targets.add(container)
        }
    }

    private fun disableClipping(view: View) {
        val group = view as? android.view.ViewGroup ?: return
        if (!savedState.containsKey(group)) {
            savedState[group] = ClipState(
                bounds = group.clipBounds?.let { Rect(it) },
                clipChildren = group.clipChildren,
                clipToPadding = group.clipToPadding,
            )
        }
        group.clipBounds = null
        group.clipChildren = false
        group.clipToPadding = false
    }

    private fun restoreUnused() {
        val used = HashSet<View>()
        childTargets.values.forEach { used.addAll(it) }
        savedState.keys.toList()
            .filterNot { it in used }
            .forEach { restore(it) }
    }

    private fun restore(view: View) {
        val group = view as? android.view.ViewGroup ?: return
        val state = savedState.remove(group) ?: return
        group.clipBounds = state.bounds
        group.clipChildren = state.clipChildren
        group.clipToPadding = state.clipToPadding
    }

    private companion object {
        const val ROW_CLASS =
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    }
}
