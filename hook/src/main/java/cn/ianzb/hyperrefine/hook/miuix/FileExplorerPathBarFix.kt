package cn.ianzb.hyperrefine.hook.miuix

import android.graphics.Rect
import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.Collections
import java.util.WeakHashMap

/**
 * 修复「文件管理」开启顶栏渐变模糊后，文件夹页文件列表上方边距过小、首个文件被路径栏遮挡的问题。
 *
 * 目标：`com.android.fileexplorer`（文件管理）。
 *
 * 文件夹页内容放在 `miuix.nestedheader.widget.NestedHeaderLayout` 里，其内容顶部内边距由
 * `NestedScrollingLayout#onContentInsetChanged(Rect)` 下发（`mContentInsetTop = rect.top`）。
 * 顶栏渐变接管顶栏后，系统下发的 `rect.top` 只到标题栏高度、未包含路径栏，导致文件网格上移到
 * 路径栏（「内部存储设备」胶囊）下方并被遮挡。
 *
 * 判定「需要修正的文件夹页」：跟踪普通文件夹 / 云盘页 Fragment 的显示生命周期 —— 源码中只有
 * `FileFragment` / `CloudFileFragment` 承载带路径栏的文件夹内容；主页（`HomeFileFragment` /
 * `PhoneMainFragment`）、远程 / NAS（`com.android.nas.*`）、最近 / 分类页都不是它们。
 * 生命周期方法可能声明在父类，故沿类层级查找，并用 `isInstance` 限定目标类后再计数。
 */
class FileExplorerPathBarFix : BaseHook() {

    override val key: String = TopBarKeys.ADVANCED_FILE_EXPLORER

    /** 当前存活的普通文件夹 / 云盘页 Fragment（弱引用，随对象回收自动移除）。 */
    private val activeFolders: MutableSet<Any> =
        Collections.newSetFromMap(WeakHashMap<Any, Boolean>())

    override fun init() {
        val loader = target.classLoader ?: return
        LOCAL_FOLDER_FRAGMENTS.forEach { className -> trackLifecycle(loader, className) }

        val clazz = Reflect.findClassIfExists(NESTED_SCROLLING, loader) ?: run {
            HookHelper.log("$tag: $NESTED_SCROLLING not found")
            return
        }
        val method = Reflect.findMethodIfExists(clazz, "onContentInsetChanged", Rect::class.java) ?: run {
            HookHelper.log("$tag: onContentInsetChanged not found")
            return
        }
        HookHelper.hookBefore(method) { param ->
            if (!enabled()) return@hookBefore
            val view = param.thisObject as? View ?: return@hookBefore
            val rect = param.args.getOrNull(0) as? Rect ?: return@hookBefore
            if (!isLocalFolderActive()) return@hookBefore
            val barHeight = actionBarContainerHeight(view)
            if (barHeight > rect.top) {
                param.setArg(0, Rect(rect).apply { top = barHeight })
            }
        }
    }

    private fun enabled(): Boolean = TopBarKeys.fileExplorerAdvancedEnabled()

    @Synchronized
    private fun isLocalFolderActive(): Boolean = activeFolders.isNotEmpty()

    @Synchronized
    private fun updateActive(fragment: Any, entering: Boolean) {
        if (entering) activeFolders.add(fragment) else activeFolders.remove(fragment)
    }

    /** 沿 [className] 的类层级挂载各生命周期方法，出现 / 销毁时维护 [activeFolders]。 */
    private fun trackLifecycle(loader: ClassLoader, className: String) {
        val cls = Reflect.findClassIfExists(className, loader) ?: return
        for ((methodName, entering) in LIFECYCLE) {
            findMethodInHierarchy(cls, methodName)?.let { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param ->
                        val obj = param.thisObject
                        if (obj != null && cls.isInstance(obj)) updateActive(obj, entering)
                    }
                }
            }
        }
    }

    /** 从 [cls] 起沿父类向上查找名为 [name] 的方法（生命周期方法常声明在父类）。 */
    private fun findMethodInHierarchy(cls: Class<*>, name: String): java.lang.reflect.Method? {
        var current: Class<*>? = cls
        while (current != null) {
            current.declaredMethods.firstOrNull { it.name == name }?.let { return it }
            current = current.superclass
        }
        return null
    }

    private fun actionBarContainerHeight(view: View): Int {
        val id = view.resources.getIdentifier("action_bar_container", "id", view.context.packageName)
        if (id == 0) return 0
        return view.rootView.findViewById<View>(id)?.height ?: 0
    }

    private companion object {
        const val NESTED_SCROLLING = "miuix.nestedheader.widget.NestedScrollingLayout"

        /** 普通文件夹 / 云盘页的 Fragment（主页、NAS、最近、分类页都不在此列）。 */
        val LOCAL_FOLDER_FRAGMENTS = listOf(
            "com.android.fileexplorer.fragment.FileFragment",
            "com.android.cloud.fragment.CloudFileFragment",
        )

        /** 进入 / 离开的生命周期方法（方法名 -> 是否为进入）。 */
        val LIFECYCLE = listOf(
            "onCreateView" to true,
            "onDestroyView" to false,
            "onAttach" to true,
            "onDetach" to false,
        )
    }
}
