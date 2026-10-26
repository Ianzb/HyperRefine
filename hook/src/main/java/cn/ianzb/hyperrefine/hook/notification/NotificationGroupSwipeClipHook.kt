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
 * 通知组子通知侧滑时，避免被父级摘要行的 clipBounds 横向裁切。
 *
 * 普通通知是整行平移，行自身的 clipBounds 会随行一起移动；通知组里的子通知
 * 只是子 View 平移，父级摘要行仍保留 [0, width] 的 clipBounds，导致滑出组
 * 边界的部分被直接裁掉。这里在子通知侧滑期间临时把父级 clipBounds 向左右
 * 各扩一个通知宽度，松手/复位后恢复原值。
 */
class NotificationGroupSwipeClipHook : BaseHook() {

    override val tag: String = "NotificationGroupSwipeClip"

    /** 通知功能页的侧滑裁切开关。 */
    override val key: String = NotificationKeys.GROUP_SWIPE_CLIP

    private val savedClip = WeakHashMap<View, Rect?>()
    private val expandedClip = WeakHashMap<View, Rect>()
    private val activeChildren = WeakHashMap<View, MutableSet<View>>()
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
            } else {
                childParent[row] = parent
                activeChildren.getOrPut(parent) { newActiveSet() }.add(row)
                expand(parent, row)
            }
        }

        HookHelper.hookAfter(resetTranslation) { param ->
            val row = param.thisObject as? View ?: return@hookAfter
            val parent = notificationParent(row, getParent) ?: return@hookAfter
            clearChild(parent, row)
        }

        // 行被移除时不一定走 resetTranslation，补一次清理，避免父级保持扩大的裁剪范围。
        Reflect.findMethodIfExists(rowClass, "onDetachedFromWindow")?.let { detach ->
            HookHelper.hookAfter(detach) { param ->
                val row = param.thisObject as? View ?: return@hookAfter
                val parent = childParent.remove(row) ?: return@hookAfter
                clearChild(parent, row)
            }
        }

        // 滑动过程中系统可能因高度/布局变化重新调用 updateClipping，把父级
        // clipBounds 又收窄；这里在仍有子通知处于滑动状态时重新扩开。
        Reflect.findMethodIfExists(rowClass, "updateClipping")?.let { updateClipping ->
            HookHelper.hookAfter(updateClipping) { param ->
                val parent = param.thisObject as? View ?: return@hookAfter
                val child = activeChildren[parent]?.firstOrNull() ?: return@hookAfter
                if (child.isAttachedToWindow) expand(parent, child)
            }
        }
    }

    private fun newActiveSet(): MutableSet<View> =
        Collections.newSetFromMap(WeakHashMap<View, Boolean>())

    private fun clearChild(parent: View, child: View) {
        childParent.remove(child)
        val children = activeChildren[parent] ?: return
        children.remove(child)
        if (children.isEmpty()) {
            activeChildren.remove(parent)
            restore(parent)
        }
    }

    private fun notificationParent(row: View, getParent: Method): View? =
        runCatching { getParent.invoke(row) as? View }.getOrNull()

    private fun expand(parent: View, child: View) {
        val current = parent.clipBounds ?: run {
            restore(parent)
            return
        }
        val base = savedClip[parent] ?: Rect(current).also { savedClip[parent] = it }
        val width = maxOf(parent.width, child.width)
        if (width <= 0) return
        val rect = expandedClip.getOrPut(parent) { Rect() }
        rect.set(-width, base.top, width * 2, base.bottom)
        parent.clipBounds = rect
    }

    private fun restore(parent: View) {
        if (expandedClip.remove(parent) == null) return
        parent.clipBounds = savedClip.remove(parent)
    }

    private companion object {
        const val ROW_CLASS =
            "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow"
    }
}
