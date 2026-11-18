package cn.ianzb.hyperrefine.hook.phrase

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import org.json.JSONArray
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * 给「剪贴板与常用语」的常用语列表加拖动排序。
 *
 * 官方数据库没有排序字段，直接改表会与账号同步冲突，因此只在目标进程内保存
 * 一份本地显示顺序；Hook 不替换 Adapter，只在 bind 后挂拖动手柄。
 */
class PhraseReorderHook : BaseHook() {

    override val key: String = PhraseReorderKeys.ENABLED

    private val helpers = WeakHashMap<RecyclerView, ItemTouchHelper>()
    private val hookedAdapters = WeakHashMap<RecyclerView.Adapter<*>, Boolean>()
    private val hookedDialogs = WeakHashMap<Dialog, Boolean>()
    private val popupChildListeners = WeakHashMap<ViewGroup, Any>()
    private var decorateFailureLogged = false
    private var handleLogged = false
    private var sogouManagerLogged = false

    override fun init() {
        HookHelper.log("PhraseReorder init, enabled=${PhraseReorderKeys.isEnabled()}")
        val activityClass = runCatching {
            target.classLoader?.loadClass("com.miui.phrase.PhraseEditActivity")
        }.getOrNull()
        if (activityClass == null) {
            HookHelper.log("PhraseReorder activity class not found")
        } else {
            val onCreate = runCatching {
                activityClass.getDeclaredMethod("onCreate", Bundle::class.java)
            }.getOrNull()
            if (onCreate == null) {
                HookHelper.log("PhraseReorder onCreate not found")
            } else {
                HookHelper.hookAfter(onCreate) { param ->
                    if (!PhraseReorderKeys.isEnabled()) return@hookAfter
                    val activity = param.thisObject as? Activity ?: return@hookAfter
                    runCatching { install(activity) }
                        .onFailure { HookHelper.log("PhraseReorder install failed", it) }
                }
                HookHelper.log("PhraseReorder onCreate hooked")
            }

            // LoaderCallbacks 的方法在部分 HyperOS 版本会由不同 ClassLoader
            // 加载，精确参数类型可能匹配不到，按方法名和参数个数兜底。
            val onLoadFinished = runCatching {
                activityClass.declaredMethods.firstOrNull {
                    it.name == "onLoadFinished" && it.parameterTypes.size == 2
                }
            }.getOrNull()
            if (onLoadFinished == null) {
                HookHelper.log("PhraseReorder onLoadFinished not found")
            } else {
                HookHelper.hookAfter(onLoadFinished) { param ->
                    if (!PhraseReorderKeys.isEnabled()) return@hookAfter
                    val activity = param.thisObject as? Activity ?: return@hookAfter
                    runCatching { install(activity) }
                        .onFailure { HookHelper.log("PhraseReorder load install failed", it) }
                    runCatching { applySavedOrder(activity) }
                        .onFailure { HookHelper.log("PhraseReorder apply failed", it) }
                }
                HookHelper.log("PhraseReorder onLoadFinished hooked")
            }

            // 进程可能是在 Activity 已经执行到 onResume 后才注入的，
            // onResume 再补一次安装，避免只依赖 onCreate 的时序。
            runCatching {
                val onResume = Activity::class.java.getDeclaredMethod("onResume")
                HookHelper.hookAfter(onResume) { param ->
                    if (!PhraseReorderKeys.isEnabled()) return@hookAfter
                    val activity = param.thisObject as? Activity ?: return@hookAfter
                    if (activity.javaClass.name != activityClass.name) return@hookAfter
                    runCatching { install(activity) }
                        .onFailure { HookHelper.log("PhraseReorder resume install failed", it) }
                }
            }.onFailure { HookHelper.log("PhraseReorder onResume hook failed", it) }

            // 个别 HyperOS 版本的 Activity 方法会被内联/替换，额外挂一层
            // framework Activity.onCreate，按类名过滤，保证至少有一次补装机会。
            runCatching {
                val frameworkCreate = Activity::class.java.getDeclaredMethod(
                    "onCreate",
                    Bundle::class.java,
                )
                HookHelper.hookAfter(frameworkCreate) { param ->
                    if (!PhraseReorderKeys.isEnabled()) return@hookAfter
                    val activity = param.thisObject as? Activity ?: return@hookAfter
                    if (activity.javaClass.name != "com.miui.phrase.PhraseEditActivity") {
                        return@hookAfter
                    }
                    HookHelper.log("PhraseReorder framework onCreate callback")
                    runCatching { install(activity) }
                        .onFailure { HookHelper.log("PhraseReorder framework install failed", it) }
                }
            }.onFailure { HookHelper.log("PhraseReorder framework onCreate hook failed", it) }
        }

        // 搜狗输入法自带“常用语/快捷短语”管理页，本身使用 DragSortListView，
        // 但正常模式下把拖动关闭了，且行内的拖动手柄也被隐藏。
        runCatching { installSogouManager() }
            .onFailure { HookHelper.log("PhraseReorder sogou install failed", it) }

        // 输入法弹出的剪贴板/常用语面板实际是 com.miui.phrase 的
        // InputMethodClipboardPhrasePopupView，内部用同一个 RecyclerView
        // 在剪贴板 Adapter 和常用语 Adapter 之间切换。
        runCatching { installPopupPhraseReorder() }
            .onFailure { HookHelper.log("PhraseReorder popup install failed", it) }

        // 实测输入法面板的窗口属于搜狗进程（LayoutParams.packageName 伪装成
        // com.miui.phrase），而 com.miui.phrase 进程本身可能没有任何窗口。
        // 因此窗口级兜底不能放在 installPopupPhraseReorder() 的类查找之后。
        runCatching { installPopupWindowProbe() }
            .onFailure { HookHelper.log("PhraseReorder popup show probe failed", it) }
        runCatching { installWindowAddProbe() }
            .onFailure { HookHelper.log("PhraseReorder window add probe failed", it) }

        // 输入法里弹出的“常用语”不是 PhraseEditActivity，而是
        // PhraseService 创建的 com.miui.phrase.l Dialog，列表为 ListView。
        runCatching {
            val show = Dialog::class.java.getDeclaredMethod("show")
            HookHelper.hookAfter(show) { param ->
                if (!PhraseReorderKeys.isEnabled()) return@hookAfter
                val dialog = param.thisObject as? Dialog ?: return@hookAfter
                if (dialog.javaClass.name != "com.miui.phrase.l") return@hookAfter
                runCatching { installDialog(dialog) }
                    .onFailure { HookHelper.log("PhraseReorder dialog install failed", it) }
            }
            HookHelper.log("PhraseReorder dialog show hooked")
        }.onFailure { HookHelper.log("PhraseReorder dialog show hook failed", it) }
    }

    /**
     * 搜狗“常用语/快捷短语”管理页。它自己已经接好 DragSortListView 和
     * 落盘逻辑，只是正常模式下把拖动控制器和行内手柄都关了。
     */
    private fun installSogouManager() {
        val loader = target.classLoader ?: return

        // 两个页面都可能是用户看到的“常用语”管理入口：
        // ListActivity 是分类列表，ManageActivity 才是常用语条目列表。
        hookSogouActivity(
            loader,
            "com.sohu.inputmethod.shortcutphrase.ShortcutPhrasesListActivity",
            "F0",
            "d",
            "c",
        )
        hookSogouActivity(
            loader,
            "com.sohu.inputmethod.shortcutphrase.ShortcutPhrasesManageActivity",
            "S",
            "h",
            "g",
        )
        hookSogouAdapter(loader)

        if (!sogouManagerLogged) {
            sogouManagerLogged = true
            HookHelper.log("PhraseReorder sogou manager hooked")
        }
    }

    private fun hookSogouActivity(
        loader: ClassLoader,
        className: String,
        setupMethod: String,
        controllerField: String,
        listField: String,
    ) {
        val activityClass = runCatching { loader.loadClass(className) }.getOrNull()
        if (activityClass == null) {
            HookHelper.log("PhraseReorder sogou $className not found")
            return
        }
        val setup = activityClass.declaredMethods.firstOrNull {
            it.name == setupMethod && it.parameterTypes.isEmpty()
        }
        if (setup == null) {
            HookHelper.log("PhraseReorder sogou $className.$setupMethod not found")
            return
        }
        setup.isAccessible = true
        HookHelper.hookAfter(setup) { param ->
            if (!PhraseReorderKeys.isEnabled()) return@hookAfter
            val activity = param.thisObject
            runCatching {
                val controller = readField(activity, controllerField)
                    ?: error("$controllerField is null")
                val setDraggable = controller.javaClass.declaredMethods.firstOrNull {
                    it.name == "o" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                } ?: error("controller.o(boolean) not found")
                setDraggable.isAccessible = true
                setDraggable.invoke(controller, true)

                val list = readField(activity, listField)
                    ?: error("$listField is null")
                val setDragEnabled = list.javaClass.declaredMethods.firstOrNull {
                    it.name == "setDragEnabled" &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Boolean::class.javaPrimitiveType
                } ?: error("list.setDragEnabled(boolean) not found")
                setDragEnabled.isAccessible = true
                setDragEnabled.invoke(list, true)

                HookHelper.log("PhraseReorder sogou drag enabled: $className")
            }.onFailure {
                HookHelper.log("PhraseReorder sogou enable failed: $className", it)
            }
        }
        HookHelper.log("PhraseReorder sogou setup hooked: $className#$setupMethod")
    }

    private fun hookSogouAdapter(loader: ClassLoader) {
        val adapterClass = runCatching {
            loader.loadClass("com.sohu.inputmethod.shortcutphrase.h")
        }.getOrNull()
        val getView = adapterClass?.let { adapter ->
            runCatching {
                adapter.getMethod(
                    "getView",
                    Int::class.javaPrimitiveType,
                    View::class.java,
                    ViewGroup::class.java,
                )
            }.getOrNull()
        }
        if (getView == null) {
            HookHelper.log("PhraseReorder sogou getView not found")
            return
        }
        HookHelper.hookAfter(getView) { param ->
            if (!PhraseReorderKeys.isEnabled()) return@hookAfter
            if ((readField(param.thisObject, "k") as? Int) == 2) return@hookAfter
            val row = param.result as? View ?: return@hookAfter
            val id = sogouHandleId(row, loader)
            if (id != 0) {
                row.findViewById<View>(id)?.visibility = View.VISIBLE
            }
        }
    }

    private fun sogouHandleId(row: View, loader: ClassLoader): Int {
        val byName = row.resources.getIdentifier(
            "iv_shortcut_phrase_item_drag",
            "id",
            target.packageName,
        )
        if (byName != 0) return byName
        // 搜狗资源名被混淆，aapt2 里查不到该名字；R$id 字段仍保留。
        return runCatching {
            loader.loadClass("com.sohu.inputmethod.sogou.R\$id")
                .getField("iv_shortcut_phrase_item_drag")
                .getInt(null)
        }.getOrDefault(0)
    }

    private fun readField(instance: Any?, name: String): Any? {
        if (instance == null) return null
        var type: Class<*>? = instance.javaClass
        while (type != null && type != Any::class.java) {
            val value = runCatching {
                type.getDeclaredField(name).apply { isAccessible = true }.get(instance)
            }.getOrNull()
            if (value != null) return value
            type = type.superclass
        }
        return null
    }

    private fun installDialog(dialog: Dialog) {
        if (hookedDialogs.containsKey(dialog)) return
        val list = dialog.javaClass.declaredFields
            .firstOrNull { ListView::class.java.isAssignableFrom(it.type) }
            ?.apply { isAccessible = true }
            ?.get(dialog) as? ListView
        if (list == null) {
            HookHelper.log("PhraseReorder dialog list not found")
            return
        }
        list.post {
            val adapter = list.adapter as? BaseAdapter ?: return@post
            val items = stringItems(adapter) ?: return@post
            if (items.isEmpty()) return@post

            applySavedWordOrder(list.context, adapter, items)
            list.setOnItemLongClickListener { _, row, position, _ ->
                if (!PhraseReorderKeys.isEnabled()) return@setOnItemLongClickListener false
                if (position !in items.indices) return@setOnItemLongClickListener false
                showReorderMenu(list, adapter, items, position, row)
                true
            }
            hookedDialogs[dialog] = true
            HookHelper.log("PhraseReorder dialog list installed size=${items.size}")
        }
    }

    /**
     * 输入法剪贴板/常用语浮层。它只有一个 RecyclerView，剪贴板和常用语
     * 共用，通过切换 Adapter 展示不同数据。
     *
     * 这个进程的 ClassLoader 看不到模块里的 AndroidX，不能强转
     * RecyclerView/ItemTouchHelper；这里只用 framework View + 反射。
     */
    private fun installPopupPhraseReorder() {
        val loader = target.classLoader ?: return
        val popupClass = runCatching {
            loader.loadClass("com.miui.inputmethod.InputMethodClipboardPhrasePopupView")
        }.getOrNull()
        if (popupClass == null) {
            HookHelper.log("PhraseReorder popup class not found")
            return
        }
        val adapterClass = runCatching {
            loader.loadClass("com.miui.inputmethod.InputMethodPhraseAdapter")
        }.getOrNull()
        if (adapterClass == null) {
            HookHelper.log("PhraseReorder popup adapter class not found")
            return
        }

        // 每次打开浮层/刷新常用语都会重新 setPhraseList；数据来自数据库，
        // 本地保存的顺序必须在这时重新施加，否则下一次打开又回到原序。
        val setPhraseList = adapterClass.declaredMethods.firstOrNull {
            it.name == "setPhraseList" && it.parameterTypes.size == 1
        }
        if (setPhraseList == null) {
            HookHelper.log("PhraseReorder popup setPhraseList not found")
            return
        }
        HookHelper.hookAfter(setPhraseList) { param ->
            if (!PhraseReorderKeys.isEnabled()) return@hookAfter
            val adapter = param.thisObject ?: return@hookAfter
            HookHelper.log(
                "PhraseReorder popup setPhraseList size=${(param.args.getOrNull(0) as? Collection<*>)?.size}",
            )
            runCatching { applySavedPhraseOrder(adapter) }
                .onFailure { HookHelper.log("PhraseReorder popup order apply failed", it) }
        }
        HookHelper.log("PhraseReorder popup setPhraseList hooked")

        // 浮层条目本身没有拖动柄；在 bind 之后补一个。
        val onBind = adapterClass.declaredMethods.firstOrNull {
            it.name == "onBindViewHolder" && it.parameterTypes.size == 2
        }
        if (onBind == null) {
            HookHelper.log("PhraseReorder popup onBindViewHolder not found")
            return
        }
        HookHelper.hookAfter(onBind) { param ->
            if (!PhraseReorderKeys.isEnabled()) return@hookAfter
            val holder = param.args.getOrNull(1) ?: return@hookAfter
            val adapter = param.thisObject ?: return@hookAfter
            HookHelper.log("PhraseReorder popup bind holder=${holder.javaClass.name}")
            runCatching { addPopupDragHandle(holder, adapter) }
                .onFailure { HookHelper.log("PhraseReorder popup handle failed", it) }
        }
        HookHelper.log("PhraseReorder popup bind hooked")

        // bind 回调在部分系统版本里不会对每条触发；再挂一次 initPopupWindow，
        // 直接监听 RecyclerView 的子 View 挂载，确保每条常用语都能补上拖动柄。
        val initPopup = popupClass.declaredMethods.firstOrNull {
            it.name == "initPopupWindow" && it.parameterTypes.size == 7
        }
        if (initPopup == null) {
            HookHelper.log("PhraseReorder popup initPopupWindow not found")
        } else {
            HookHelper.hookAfter(initPopup) { param ->
                if (!PhraseReorderKeys.isEnabled()) return@hookAfter
                HookHelper.log("PhraseReorder popup init hooked")
                val popup = param.thisObject ?: return@hookAfter
                val recycler = readField(popup, "mRecyclerView") as? ViewGroup
                    ?: return@hookAfter
                val adapter = readField(popup, "mInputMethodPhraseAdapter")
                    ?: return@hookAfter
                runCatching { installPopupChildAttach(recycler, adapter) }
                    .onFailure { HookHelper.log("PhraseReorder popup child attach failed", it) }
            }
            HookHelper.log("PhraseReorder popup child attach hooked")
        }

        installPopupLifecycleHooks(popupClass)
        installPopupManagerHook()
    }

    /**
     * 兜底入口：不依赖具体浮层类或 InputMethodBottomManager 的私有方法，
     * 直接监听系统 PopupWindow 的显示。部分 ROM 会把 showClipBoard 内联，
     * 但 PopupWindow.showAtLocation/showAsDropDown 一定会执行。
     */
    private fun installPopupWindowProbe() {
        PopupWindow::class.java.declaredMethods
            .filter { it.name == "showAtLocation" || it.name == "showAsDropDown" }
            .forEach { hookPopupShow(it) }
    }

    private fun installWindowAddProbe() {
        val windowManagerGlobal = runCatching {
            Class.forName("android.view.WindowManagerGlobal")
        }.getOrNull()
        if (windowManagerGlobal == null) {
            HookHelper.log("PhraseReorder window manager class not found")
            return
        }
        val addViewMethods = windowManagerGlobal.declaredMethods.filter { it.name == "addView" }
        if (addViewMethods.isEmpty()) {
            HookHelper.log("PhraseReorder window add method not found")
            return
        }
        addViewMethods.forEach { method ->
            runCatching { HookHelper.deoptimize(method) }
            HookHelper.hookAfter(method) { param ->
                if (!PhraseReorderKeys.isEnabled()) return@hookAfter
                val root = param.args.getOrNull(0) as? View ?: return@hookAfter
                val packageName = runCatching { root.context.packageName }.getOrNull()
                val layoutParams = param.args.getOrNull(1) as? WindowManager.LayoutParams
                val title = runCatching { layoutParams?.title?.toString() }.getOrNull().orEmpty()
                HookHelper.log(
                    "PhraseReorder window add class=${root.javaClass.name} pkg=$packageName title=$title",
                )
                if (packageName != "com.miui.phrase") return@hookAfter
                if (!title.contains("PopupWindow", ignoreCase = true)) return@hookAfter
                Handler(Looper.getMainLooper()).postDelayed({
                    probeWindowRoot(root, 0)
                }, 250)
            }
            HookHelper.log("PhraseReorder window add hooked: ${method.parameterCount}")
        }

        // 浮层可能在本模块完成 Hook 之前就已经 addView；addView 回调不会再补发。
        // 进程启动后扫描一次现有窗口，按 LayoutParams.title 找到 PopupWindow 根。
        Handler(Looper.getMainLooper()).postDelayed({ scanExistingWindows() }, 1_200)
        Handler(Looper.getMainLooper()).postDelayed({ scanExistingWindows() }, 3_000)
    }

    private fun scanExistingWindows() {
        if (!PhraseReorderKeys.isEnabled()) return
        val globalClass = runCatching {
            Class.forName("android.view.WindowManagerGlobal")
        }.getOrNull() ?: return
        val instance = runCatching {
            globalClass.getDeclaredMethod("getInstance").apply { isAccessible = true }.invoke(null)
        }.getOrNull() ?: return
        val viewsField = runCatching {
            globalClass.getDeclaredField("mViews").apply { isAccessible = true }
        }.getOrNull() ?: return
        val views = runCatching { viewsField.get(instance) as? List<*> }.getOrNull() ?: return
        HookHelper.log("PhraseReorder existing windows=${views.size}")
        views.filterIsInstance<View>().forEach { root ->
            val packageName = runCatching { root.context.packageName }.getOrNull()
            if (packageName != "com.miui.phrase") return@forEach
            val title = runCatching {
                (root.layoutParams as? WindowManager.LayoutParams)?.title?.toString()
            }.getOrNull().orEmpty()
            HookHelper.log(
                "PhraseReorder existing root=${root.javaClass.name} pkg=$packageName title=$title",
            )
            if (title.contains("PopupWindow", ignoreCase = true)) {
                probeWindowRoot(root, 0)
            }
        }
    }

    private fun probeWindowRoot(root: View, attempt: Int) {
        if (!PhraseReorderKeys.isEnabled()) return
        val recycler = findPopupRecyclerView(root)
        if (recycler == null) {
            if (attempt < 2) {
                Handler(Looper.getMainLooper()).postDelayed({
                    probeWindowRoot(root, attempt + 1)
                }, 300)
            } else {
                HookHelper.log("PhraseReorder window recycler not found class=${root.javaClass.name}")
            }
            return
        }
        val adapter = runCatching {
            recycler.javaClass.getMethod("getAdapter").invoke(recycler)
        }.getOrNull()
        if (adapter == null) {
            if (attempt < 2) {
                Handler(Looper.getMainLooper()).postDelayed({
                    probeWindowRoot(root, attempt + 1)
                }, 300)
            } else {
                HookHelper.log("PhraseReorder window adapter null class=${recycler.javaClass.name}")
            }
            return
        }
        HookHelper.log(
            "PhraseReorder window recycler=${recycler.javaClass.name} adapter=${adapter.javaClass.name}",
        )
        installPopupChildAttach(recycler, adapter)
    }

    private fun hookPopupShow(method: java.lang.reflect.Method) {
        val name = "${method.name}/${method.parameterCount}"
        runCatching { HookHelper.deoptimize(method) }
        HookHelper.hookAfter(method) { param ->
            if (!PhraseReorderKeys.isEnabled()) return@hookAfter
            val popup = param.thisObject as? PopupWindow ?: return@hookAfter
            val popupClass = popup.javaClass.name
            HookHelper.log("PhraseReorder popup show callback class=$popupClass")
            if (!popupClass.startsWith("com.miui.")) return@hookAfter
            Handler(Looper.getMainLooper()).postDelayed({
                installPopupFromWindow(popup)
            }, 120)
        }
        HookHelper.log("PhraseReorder popup show hooked: $name")
    }

    private fun installPopupFromWindow(popup: PopupWindow) {
        if (!PhraseReorderKeys.isEnabled()) return
        val content = popup.contentView ?: run {
            HookHelper.log("PhraseReorder popup content null")
            return
        }
        val recycler = findPopupRecyclerView(content) ?: run {
            HookHelper.log("PhraseReorder popup content recycler not found")
            return
        }
        val adapter = runCatching {
            recycler.javaClass.getMethod("getAdapter").invoke(recycler)
        }.getOrNull() ?: run {
            HookHelper.log("PhraseReorder popup content adapter null")
            return
        }
        installPopupChildAttach(recycler, adapter)
    }

    private fun findPopupRecyclerView(root: View): ViewGroup? {
        if (root.javaClass.name == "androidx.recyclerview.widget.RecyclerView") {
            return root as? ViewGroup
        }
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findPopupRecyclerView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    /**
     * 浮层真正的创建入口在 InputMethodBottomManager.showClipBoard 里：
     * 它 new 出 InputMethodClipboardPhrasePopupView、赋给静态字段 sClipboardWindow，
     * 再调用 initPopupWindow/initData。直接挂这个方法，避免依赖浮层类自身的
     * 构造函数或 init 回调是否被内联。
     */
    private fun installPopupManagerHook() {
        val loader = target.classLoader ?: return
        val managerClass = runCatching {
            loader.loadClass("com.miui.inputmethod.InputMethodBottomManager")
        }.getOrNull()
        if (managerClass == null) {
            HookHelper.log("PhraseReorder popup manager class not found")
            return
        }
        val show = managerClass.declaredMethods.firstOrNull {
            it.name == "showClipBoard" && it.parameterTypes.size == 8
        }
        if (show == null) {
            HookHelper.log("PhraseReorder popup manager showClipBoard not found")
            return
        }
        runCatching { HookHelper.deoptimize(show) }
        HookHelper.hookAfter(show) {
            if (!PhraseReorderKeys.isEnabled()) return@hookAfter
            HookHelper.log("PhraseReorder popup manager callback")
            val popup = readStaticField(managerClass, "sClipboardWindow") ?: return@hookAfter
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { installPopupFromInstance(popup) }
                    .onFailure { HookHelper.log("PhraseReorder popup manager install failed", it) }
            }, 80)
        }
        HookHelper.log("PhraseReorder popup manager hooked")
    }

    private fun readStaticField(clazz: Class<*>, name: String): Any? {
        var current: Class<*>? = clazz
        while (current != null) {
            val value = runCatching {
                current.getDeclaredField(name).apply { isAccessible = true }.get(null)
            }.getOrNull()
            if (value != null) return value
            current = current.superclass
        }
        return null
    }

    /**
     * initPopupWindow 的 after 回调在部分版本上不稳定；构造函数一定执行，
     * initData 又紧随 initPopupWindow 之后调用。两个入口都延迟读取已经初始化好的
     * RecyclerView，谁先命中就用谁，重复安装由 popupChildListeners 去重。
     */
    private fun installPopupLifecycleHooks(popupClass: Class<*>) {
        val constructor = popupClass.declaredConstructors.firstOrNull {
            it.parameterTypes.size == 6
        }
        if (constructor == null) {
            HookHelper.log("PhraseReorder popup constructor not found")
        } else {
            HookHelper.hookAfter(constructor) { param ->
                HookHelper.log("PhraseReorder popup ctor hooked")
                val popup = param.thisObject ?: return@hookAfter
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { installPopupFromInstance(popup) }
                        .onFailure { HookHelper.log("PhraseReorder popup ctor install failed", it) }
                }, 300)
            }
            HookHelper.log("PhraseReorder popup ctor hooked installed")
        }

        val initData = popupClass.declaredMethods.firstOrNull {
            it.name == "initData" && it.parameterTypes.isEmpty()
        }
        if (initData == null) {
            HookHelper.log("PhraseReorder popup initData not found")
            return
        }
        HookHelper.hookAfter(initData) { param ->
            HookHelper.log("PhraseReorder popup initData hooked")
            val popup = param.thisObject ?: return@hookAfter
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { installPopupFromInstance(popup) }
                    .onFailure { HookHelper.log("PhraseReorder popup initData install failed", it) }
            }, 100)
        }
        HookHelper.log("PhraseReorder popup initData hooked installed")
    }

    private fun installPopupFromInstance(popup: Any) {
        if (!PhraseReorderKeys.isEnabled()) return
        val recycler = readField(popup, "mRecyclerView") as? ViewGroup ?: run {
            HookHelper.log("PhraseReorder popup recycler null")
            return
        }
        val adapter = readField(popup, "mInputMethodPhraseAdapter") ?: run {
            HookHelper.log("PhraseReorder popup adapter null")
            return
        }
        installPopupChildAttach(recycler, adapter)
    }

    private fun installPopupChildAttach(recycler: ViewGroup, phraseAdapter: Any) {
        if (popupChildListeners.containsKey(recycler)) return
        val loader = target.classLoader ?: return
        val listenerClass = runCatching {
            loader.loadClass("androidx.recyclerview.widget.RecyclerView\$OnChildAttachStateChangeListener")
        }.getOrNull()
        if (listenerClass == null) {
            HookHelper.log("PhraseReorder popup child listener class not found")
            return
        }
        val listener = java.lang.reflect.Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass),
        ) { _, method, args ->
            if (method.name == "onChildViewAttachedToWindow") {
                val child = args?.getOrNull(0) as? View
                if (child != null) {
                    runCatching { addPopupHandleForChild(recycler, child, phraseAdapter) }
                }
            }
            null
        }
        runCatching {
            recycler.javaClass
                .getMethod("addOnChildAttachStateChangeListener", listenerClass)
                .invoke(recycler, listener)
            popupChildListeners[recycler] = listener
            HookHelper.log("PhraseReorder popup child listener installed")
        }.onFailure {
            HookHelper.log("PhraseReorder popup child listener add failed", it)
            return
        }
        recycler.post {
            for (index in 0 until recycler.childCount) {
                addPopupHandleForChild(recycler, recycler.getChildAt(index), phraseAdapter)
            }
        }
    }

    private fun addPopupHandleForChild(recycler: ViewGroup, child: View, phraseAdapter: Any) {
        if (!PhraseReorderKeys.isEnabled()) return
        val current = runCatching {
            recycler.javaClass.getMethod("getAdapter").invoke(recycler)
        }.getOrNull()
        if (current !== phraseAdapter) return
        val holder = runCatching {
            recycler.javaClass
                .getMethod("getChildViewHolder", View::class.java)
                .invoke(recycler, child)
        }.getOrNull() ?: return
        addPopupDragHandle(holder, phraseAdapter)
    }

    private fun addPopupDragHandle(holder: Any, adapter: Any) {
        val itemView = readField(holder, "itemView") as? View
        if (itemView == null) {
            HookHelper.log("PhraseReorder popup handle itemView=null holder=${holder.javaClass.name}")
            return
        }
        val rowId = itemView.resources.getIdentifier(
            "phrase_item_layout",
            "id",
            itemView.context.packageName,
        )
        val row = itemView.findViewById<ViewGroup>(rowId)
        HookHelper.log(
            "PhraseReorder popup handle item=${itemView.javaClass.name} rowId=$rowId row=${row?.javaClass?.name}",
        )
        if (row == null) return
        if (row.findViewWithTag<View>(DRAG_HANDLE_TAG) != null) return

        val context = itemView.context
        val handle = TextView(context).apply {
            tag = DRAG_HANDLE_TAG
            text = "≡"
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            alpha = 0.55f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = LinearLayout.LayoutParams(dp(context, 28), ViewGroup.LayoutParams.MATCH_PARENT)
            setOnTouchListener(PopupDragTouchListener(itemView, holder, adapter))
        }
        row.addView(handle)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private fun applySavedPhraseOrder(adapter: Any) {
        val context = readField(adapter, "mContext") as? Context ?: return
        val items = adapterItems(adapter) ?: return
        if (items.size < 2) return
        val words = items.map { phraseWords(it) }
        if (words.any { it.isEmpty() }) return

        val saved = loadWordOrder(context)
        if (saved.isEmpty()) return
        val original = words.withIndex().associate { it.value to it.index }
        val sorted = items.sortedWith(
            compareBy(
                { phraseWords(it).let { word -> saved.indexOf(word).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE } },
                { phraseWords(it).let { word -> original[word] ?: Int.MAX_VALUE } },
            ),
        )
        if (sorted == items) return
        items.clear()
        items.addAll(sorted)
        notifyAdapterChanged(adapter)
    }

    private fun notifyAdapterChanged(adapter: Any) {
        runCatching { adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter) }
    }

    private fun adapterPosition(holder: Any): Int {
        for (name in arrayOf("getBindingAdapterPosition", "getAbsoluteAdapterPosition", "getAdapterPosition")) {
            val value = runCatching {
                holder.javaClass.getMethod(name).invoke(holder) as? Int
            }.getOrNull()
            if (value != null && value >= 0) return value
        }
        return -1
    }

    private fun adapterItemCount(adapter: Any): Int = runCatching {
        adapter.javaClass.getMethod("getItemCount").invoke(adapter) as? Int
    }.getOrNull() ?: 0

    private fun phraseWords(entity: Any): String = runCatching {
        entity.javaClass.getMethod("getWords").invoke(entity) as? String
    }.getOrNull().orEmpty()

    private fun savePhraseWordOrder(context: Context, items: List<Any>) {
        val array = JSONArray()
        items.forEach { array.put(phraseWords(it)) }
        prefs(context).edit().putString(WORD_ORDER_KEY, array.toString()).apply()
    }

    /**
     * framework-only 的纵向拖动：不依赖 RecyclerView/ItemTouchHelper。
     * 按住手柄后，每跨过一行高度就把当前条目与相邻条目交换一次。
     */
    private inner class PopupDragTouchListener(
        private val itemView: View,
        private val holder: Any,
        private val adapter: Any,
    ) : View.OnTouchListener {
        private var startY = 0f
        private var startPosition = -1
        private var currentPosition = -1
        private var rowHeight = 1

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (!PhraseReorderKeys.isEnabled()) return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startPosition = adapterPosition(holder)
                    if (startPosition < 0 || adapterItemCount(adapter) < 2) return false
                    startY = event.rawY
                    currentPosition = startPosition
                    rowHeight = itemView.height.takeIf { it > 0 } ?: dp(v.context, 48)
                    v.isPressed = true
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (startPosition < 0) return false
                    val steps = ((event.rawY - startY) / rowHeight).toInt()
                    val target = (startPosition + steps)
                        .coerceIn(0, adapterItemCount(adapter) - 1)
                    while (currentPosition < target) {
                        if (!moveItem(currentPosition, currentPosition + 1)) break
                    }
                    while (currentPosition > target) {
                        if (!moveItem(currentPosition, currentPosition - 1)) break
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (startPosition >= 0) {
                        val items = adapterItems(adapter)
                        if (items != null) savePhraseWordOrder(itemView.context, items)
                    }
                    startPosition = -1
                    v.isPressed = false
                    return true
                }
            }
            return false
        }

        private fun moveItem(from: Int, to: Int): Boolean {
            val items = adapterItems(adapter) ?: return false
            if (from !in items.indices || to !in items.indices) return false
            Collections.swap(items, from, to)
            val moved = runCatching {
                adapter.javaClass.getMethod(
                    "notifyItemMoved",
                    Integer.TYPE,
                    Integer.TYPE,
                ).invoke(adapter, from, to)
            }.isSuccess
            if (!moved) return false
            currentPosition = to
            return true
        }
    }

    private fun showReorderMenu(
        list: ListView,
        adapter: BaseAdapter,
        items: MutableList<Any>,
        position: Int,
        anchor: View,
    ) {
        val popup = PopupMenu(anchor.context, anchor)
        popup.menu.add(0, MENU_MOVE_UP, 0, "上移").apply {
            isEnabled = position > 0
        }
        popup.menu.add(0, MENU_MOVE_DOWN, 1, "下移").apply {
            isEnabled = position < items.lastIndex
        }
        popup.setOnMenuItemClickListener { menuItem ->
            val target = when (menuItem.itemId) {
                MENU_MOVE_UP -> position - 1
                MENU_MOVE_DOWN -> position + 1
                else -> return@setOnMenuItemClickListener false
            }
            if (target !in items.indices) return@setOnMenuItemClickListener false
            Collections.swap(items, position, target)
            adapter.notifyDataSetChanged()
            list.invalidateViews()
            saveWordOrder(list.context, items)
            true
        }
        popup.show()
    }

    private fun applySavedWordOrder(
        context: Context,
        adapter: BaseAdapter,
        items: MutableList<Any>,
    ) {
        val saved = loadWordOrder(context)
        if (saved.isEmpty() || items.size < 2) return
        val words = items.map { it as String }
        val original = words.withIndex().associate { (it.value) to it.index }
        val sorted = words.sortedWith(
            compareBy(
                { word -> saved.indexOf(word).takeIf { it >= 0 } ?: Int.MAX_VALUE },
                { word -> original[word] ?: Int.MAX_VALUE },
            ),
        )
        if (sorted == words) return
        items.clear()
        items.addAll(sorted)
        adapter.notifyDataSetChanged()
    }

    private fun stringItems(adapter: BaseAdapter): MutableList<Any>? {
        val field = adapter.javaClass.declaredFields.firstOrNull {
            ArrayList::class.java.isAssignableFrom(it.type)
        } ?: return null
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val items = field.get(adapter) as? MutableList<Any> ?: return null
        if (items.any { it !is String }) return null
        return items
    }

    private fun saveWordOrder(context: Context, items: List<Any>) {
        val array = JSONArray()
        items.forEach { array.put(it as String) }
        prefs(context).edit().putString(WORD_ORDER_KEY, array.toString()).apply()
    }

    private fun loadWordOrder(context: Context): List<String> {
        val raw = prefs(context).getString(WORD_ORDER_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.getString(index) }
        }.getOrDefault(emptyList())
    }

    private fun install(activity: Activity, attempt: Int = 0) {
        val id = listId(activity)
        val list = activity.findViewById<RecyclerView>(id)
            ?: findRecyclerView(activity.window?.decorView)
        HookHelper.log("PhraseReorder install attempt=$attempt id=$id list=${list != null}")
        if (list == null) {
            if (attempt < INSTALL_RETRY_LIMIT) {
                activity.window?.decorView?.postDelayed(
                    { install(activity, attempt + 1) },
                    INSTALL_RETRY_DELAY_MS,
                )
            } else {
                HookHelper.log("PhraseReorder rv_list not found")
            }
            return
        }
        val adapter = list.adapter
        if (adapter == null) {
            if (attempt < INSTALL_RETRY_LIMIT) {
                list.postDelayed({ install(activity, attempt + 1) }, INSTALL_RETRY_DELAY_MS)
            } else {
                HookHelper.log("PhraseReorder adapter not found")
            }
            return
        }
        HookHelper.log("PhraseReorder install adapter=${adapter.javaClass.name}")

        installList(list)
    }

    /**
     * 列表拿到 Adapter 后的统一挂载点。Activity 生命周期和
     * RecyclerView.setAdapter 两条路径都会走到这里，重复调用是幂等的。
     */
    private fun installList(list: RecyclerView) {
        val adapter = list.adapter ?: run {
            HookHelper.log("PhraseReorder installList adapter null")
            return
        }
        if (helpers[list] == null) {
            val helper = ItemTouchHelper(ReorderCallback(list))
            helper.attachToRecyclerView(list)
            helpers[list] = helper
        }
        hookAdapter(adapter, list)

        // Adapter 可能在 onCreate 期间已经完成第一次 bind。
        list.post {
            for (index in 0 until list.childCount) {
                decorate(list.getChildAt(index), list)
            }
        }
    }

    private fun hookAdapter(adapter: RecyclerView.Adapter<*>, list: RecyclerView) {
        if (hookedAdapters.containsKey(adapter)) return
        // 官方 Adapter 的形参是混淆后的 ViewHolder 子类，不能用
        // RecyclerView.ViewHolder 做精确 getMethod，否则永远找不到。
        val method = adapter.javaClass.methods.firstOrNull {
            it.name == "onBindViewHolder" &&
                it.parameterTypes.size == 2 &&
                RecyclerView.ViewHolder::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.parameterTypes[1] == Int::class.javaPrimitiveType
        }
        if (method == null) {
            HookHelper.log("PhraseReorder onBindViewHolder not found")
            return
        }

        HookHelper.hookAfter(method) { param ->
            if (!PhraseReorderKeys.isEnabled()) return@hookAfter
            val holder = param.args.getOrNull(0) as? RecyclerView.ViewHolder ?: return@hookAfter
            decorate(holder.itemView, list)
        }
        hookedAdapters[adapter] = true
        HookHelper.log("PhraseReorder onBindViewHolder hooked")
    }

    private fun decorate(itemView: View, list: RecyclerView) {
        val root = itemView as? LinearLayout
        val text = itemView.findViewById<TextView>(android.R.id.text1)
        if (root == null || text == null) {
            if (!decorateFailureLogged) {
                decorateFailureLogged = true
                HookHelper.log(
                    "PhraseReorder decorate skipped root=${itemView.javaClass.name} text=$text",
                )
            }
            return
        }
        val checkbox = itemView.findViewById<View>(android.R.id.checkbox)

        val handle = itemView.findViewWithTag<View>(HANDLE_TAG) ?: createHandle(list, text).also {
            root.addView(it)
            if (!handleLogged) {
                handleLogged = true
                HookHelper.log("PhraseReorder handle added")
            }
        }

        // 进入多选/操作模式后只显示复选框，避免两个可点区域重叠。
        handle.visibility = if (checkbox?.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    private fun createHandle(list: RecyclerView, text: TextView): TextView {
        val context = list.context
        return TextView(context).apply {
            tag = HANDLE_TAG
            this.text = "≡"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(text.currentTextColor)
            gravity = Gravity.CENTER
            alpha = 0.55f
            isClickable = true
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = "拖动排序"
            layoutParams = LinearLayout.LayoutParams(dp(context, 36f), ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(dp(context, 4f), 0, dp(context, 4f), 0)
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    val holder = list.findContainingViewHolder(view)
                    if (holder != null) {
                        helpers[list]?.startDrag(holder)
                        true
                    } else {
                        false
                    }
                } else {
                    false
                }
            }
        }
    }

    private fun applySavedOrder(activity: Activity) {
        val list = activity.findViewById<RecyclerView>(listId(activity)) ?: return
        val adapter = list.adapter ?: return
        val items = adapterItems(adapter) ?: return
        if (items.size < 2) return

        val saved = loadOrder(activity)
        if (saved.isEmpty()) return

        val original = items.withIndex().associate { entityKey(it.value) to it.index }
        val sorted = items.sortedWith(
            compareBy(
                { entityKey(it).let { key -> saved.indexOf(key).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE } },
                { original[entityKey(it)] ?: Int.MAX_VALUE },
            ),
        )
        if (sorted == items) return

        items.clear()
        items.addAll(sorted)
        notifyAdapterChanged(adapter)
    }

    private fun adapterItems(adapter: Any): MutableList<Any>? {
        val field = adapter.javaClass.declaredFields.firstOrNull {
            ArrayList::class.java.isAssignableFrom(it.type)
        } ?: return null
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(adapter) as? MutableList<Any>
    }

    private fun saveOrder(context: Context, items: List<Any>) {
        val array = JSONArray()
        items.forEach { array.put(entityKey(it)) }
        prefs(context).edit().putString(ORDER_KEY, array.toString()).apply()
    }

    private fun loadOrder(context: Context): List<String> {
        val raw = prefs(context).getString(ORDER_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.getString(index) }
        }.getOrDefault(emptyList())
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun entityKey(entity: Any): String {
        val syncId = runCatching {
            entity.javaClass.getMethod("getSyncId").invoke(entity) as? String
        }.getOrNull()
        if (!syncId.isNullOrBlank()) return "s:$syncId"
        val id = runCatching {
            entity.javaClass.getMethod("getId").invoke(entity) as? Long
        }.getOrNull() ?: 0L
        return "i:$id"
    }

    private fun listId(context: Context): Int =
        context.resources.getIdentifier("rv_list", "id", context.packageName)

    private fun findRecyclerView(root: View?): RecyclerView? {
        if (root == null) return null
        if (root is RecyclerView) return root
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) {
                findRecyclerView(root.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun dp(context: Context, value: Float): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    private inner class ReorderCallback(
        private val list: RecyclerView,
    ) : ItemTouchHelper.Callback() {

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ): Int = makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0)

        override fun isLongPressDragEnabled(): Boolean = false

        override fun canDropOver(
            recyclerView: RecyclerView,
            current: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val last = (recyclerView.adapter?.itemCount ?: 0) - 1
            return target.bindingAdapterPosition in 0 until last
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            val adapter = recyclerView.adapter ?: return false
            val items = adapterItems(adapter) ?: return false
            val last = adapter.itemCount - 1
            if (from !in 0 until last || to !in 0 until last) return false

            Collections.swap(items, from, to)
            adapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun clearView(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
        ) {
            super.clearView(recyclerView, viewHolder)
            val adapter = recyclerView.adapter ?: return
            val items = adapterItems(adapter) ?: return
            saveOrder(recyclerView.context, items)
        }
    }

    private companion object {
        const val HANDLE_TAG = "hyperrefine_phrase_drag_handle"
        const val PREFS_NAME = "hyperrefine_phrase"
        const val ORDER_KEY = "frequent_phrase_order"
        const val WORD_ORDER_KEY = "frequent_phrase_word_order"
        const val DRAG_HANDLE_TAG = "hyperrefine_phrase_drag_handle"
        const val MENU_MOVE_UP = 1
        const val MENU_MOVE_DOWN = 2
        const val INSTALL_RETRY_LIMIT = 20
        const val INSTALL_RETRY_DELAY_MS = 100L
    }
}
