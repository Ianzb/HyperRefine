package cn.ianzb.hyperrefine.hook.systemui

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Array as JArray
import java.lang.reflect.Constructor
import java.util.Locale

/**
 * 在控制中心「移动网络」详情卡片中、SIM 卡信息下方添加「5G 网络」开关。
 *
 * 目标进程：`com.android.systemui`。
 *
 * 实现参考 [HyperCeiler](https://github.com/ReChronoRain/HyperCeiler) 的 5G 磁贴（AGPL-3.0，
 * **仅思路参考**），但目标机型的 `QSDetailContent$ToggleItem` 无可用反射构造器，故改用
 * `sun.misc.Unsafe#allocateInstance` 分配实例后写字段。关键点：
 * - 详情卡片 `QSDetailContent` 的 SIM 项是 `SelectableItem`，在 `setItems(Item[])`
 *   （`suffix == "Cellular"`）里、**最后一个 `SelectableItem` 之后**插入一个 `TextDividerItem`（「网络」）
 *   + 一个 `ToggleItem`（「5G 网络」）；
 * - 点击经 `MiuiCellularTile$CellularDetailAdapter#onDetailItemClick` 拦截，读写
 *   `miui.telephony.TelephonyManager.isUserFiveGEnabled`；
 * - 多出一行会把面板撑高，需把详情容器（`detail_container`）的 `maxHeight` 增加 45dp，
 *   否则新增行会被裁掉看不到。
 */
class FiveGSwitchHook : BaseHook() {

    override val key: String = KEY

    private var itemClass: Class<*>? = null
    private var toggleClass: Class<*>? = null
    private var dividerCtor: Constructor<*>? = null
    private var telephony: Any? = null
    private var label: String = ""

    override fun init() {
        val loader = target.classLoader ?: return
        val contentClass = Reflect.findClassIfExists(QS_DETAIL_CONTENT, loader) ?: run {
            HookHelper.log("$tag: $QS_DETAIL_CONTENT not found")
            return
        }
        itemClass = Reflect.findClassIfExists("$QS_DETAIL_CONTENT\$Item", loader) ?: return
        toggleClass = Reflect.findClassIfExists("$QS_DETAIL_CONTENT\$ToggleItem", loader)
        dividerCtor = runCatching {
            Reflect.findClass("$QS_DETAIL_CONTENT\$TextDividerItem", loader).getConstructor(CharSequence::class.java)
        }.getOrNull()
        label = if (Locale.getDefault().language == "zh") "5G 网络" else "5G Network"
        if (toggleClass == null) HookHelper.log("$tag: ToggleItem not found")

        val adapter = Reflect.findClassIfExists(CELLULAR_ADAPTER, loader) ?: run {
            HookHelper.log("$tag: $CELLULAR_ADAPTER not found")
            return
        }
        adapter.declaredMethods.filter { it.name == "createDetailView" }.forEach { method ->
            runCatching { HookHelper.hookAfter(method) { param -> (param.result as? View)?.let(::adjustDetailHeight) } }
        }
        adapter.declaredMethods.filter { it.name == "onDetailItemClick" && it.parameterCount == 1 }.forEach { method ->
            runCatching { HookHelper.intercept(method) { chain -> onItemClick(chain.getArg(0), chain) } }
        }
        contentClass.declaredMethods.filter { it.name == "setItems" && it.parameterCount == 1 }.forEach { method ->
            runCatching { HookHelper.hookBefore(method) { param -> handleSetItems(param) } }
        }
    }

    private fun enabled(): Boolean = HookPrefs.getBoolean(KEY, false)

    private fun handleSetItems(param: HookParam) {
        if (!enabled()) return
        val content = param.thisObject ?: return
        if (runCatching { Reflect.getObjectField(content, "suffix") }.getOrNull() != "Cellular") return
        val rawItems = param.args.getOrNull(0) as? Array<*> ?: return
        if (rawItems.isEmpty()) return
        val manager = telephonyManager() ?: return
        if (runCatching { Reflect.callMethod(manager, "isFiveGCapable") }.getOrNull() == false) return
        val insertIndex = findInsertIndex(rawItems)
        if (insertIndex < 0) return
        val itemClz = itemClass ?: return
        val divider = dividerCtor ?: return
        val toggle = createToggleItem(
            checked = runCatching { Reflect.callMethod(manager, "isUserFiveGEnabled") }.getOrNull() == true,
        ) ?: return

        val newItems = JArray.newInstance(itemClz, rawItems.size + 2)
        System.arraycopy(rawItems, 0, newItems, 0, insertIndex)
        JArray.set(newItems, insertIndex, runCatching { divider.newInstance(dividerTitle()) }.getOrNull() ?: return)
        JArray.set(newItems, insertIndex + 1, toggle)
        System.arraycopy(rawItems, insertIndex, newItems, insertIndex + 2, rawItems.size - insertIndex)
        param.setArg(0, newItems)
    }

    /** 用 `Unsafe.allocateInstance` 分配 `ToggleItem`（该机型无可反射构造器）后写入字段。 */
    private fun createToggleItem(checked: Boolean): Any? {
        val clazz = toggleClass ?: return null
        val item = allocate(clazz) ?: return null
        runCatching { Reflect.setObjectField(item, "title", label) }
        runCatching { Reflect.setObjectField(item, "summary", null) }
        runCatching { Reflect.setObjectField(item, "isChecked", checked) }
        return item
    }

    private fun allocate(clazz: Class<*>): Any? = runCatching {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val field = unsafeClass.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, clazz)
    }.getOrNull()

    private fun dividerTitle(): String = if (Locale.getDefault().language == "zh") "网络" else "Network"

    private fun findInsertIndex(items: Array<*>): Int {
        for (i in items.indices.reversed()) {
            if (items[i]?.javaClass?.name?.endsWith("SelectableItem") == true) return i + 1
        }
        return -1
    }

    private fun onItemClick(item: Any?, chain: XposedInterface.Chain): Any? {
        if (enabled() && isFiveGItem(item)) {
            val manager = telephonyManager()
            if (manager != null) {
                val next = runCatching { Reflect.callMethod(manager, "isUserFiveGEnabled") }.getOrNull() != true
                runCatching { Reflect.callMethod(manager, "setUserFiveGEnabled", next) }
                    .onFailure { HookHelper.log("$tag: set 5G enabled failed", it) }
            }
            return null
        }
        return chain.proceed()
    }

    private fun isFiveGItem(item: Any?): Boolean {
        if (item == null || !item.javaClass.simpleName.contains("ToggleItem")) return false
        val title = runCatching { Reflect.getObjectField(item, "title") }.getOrNull()
        return title?.toString() == label
    }

    private fun adjustDetailHeight(view: View) {
        val context = view.context
        if (context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                var current: View = v
                while (current.parent is View) {
                    current = current.parent as View
                    val id = current.id
                    if (id != View.NO_ID) {
                        val name = runCatching { current.resources.getResourceName(id) }.getOrNull() ?: continue
                        if (name.endsWith("detail_container")) {
                            val max = runCatching { Reflect.callMethod(current, "getMaxHeight") as? Int }.getOrNull() ?: break
                            val add = (context.resources.displayMetrics.density * 45f + 0.5f).toInt()
                            runCatching { Reflect.callMethod(current, "setMaxHeight", max + add) }
                            break
                        }
                    }
                }
                v.removeOnAttachStateChangeListener(this)
            }

            override fun onViewDetachedFromWindow(v: View) {}
        })
    }

    private fun telephonyManager(): Any? {
        telephony?.let { return it }
        val clazz = Reflect.findClassIfExists(TELEPHONY) ?: return null
        val instance = runCatching { Reflect.callStaticMethod(clazz, "getDefault") }.getOrNull() ?: return null
        telephony = instance
        return instance
    }

    companion object {
        const val KEY = "five_g_switch"

        private const val QS_DETAIL_CONTENT = "com.android.systemui.qs.QSDetailContent"
        private const val CELLULAR_ADAPTER =
            "com.android.systemui.qs.tiles.MiuiCellularTile\$CellularDetailAdapter"
        private const val TELEPHONY = "miui.telephony.TelephonyManager"
    }
}
