package cn.ianzb.hyperrefine.hook.systemui

import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 融合设备中心（控制中心）隐藏末尾「…」省略卡片。
 *
 * 目标：`miui.systemui.controlcenter.panel.main.devicecenter.devices.DeviceCenterCardController`
 * 在设备列表末尾总会追加一个 `DeviceItem.DetailItem`（三个点，图标 `ic_device_center_detail_item`）。
 * 当设备正好 4 个时，列表长度变为 5，`getMode()` 返回 `MODE_2_ROWS`，把省略号挤到第二行。
 *
 * 处理方式：开关开启时
 * 1. 在 `getMode()` 结果上忽略末尾的 `DetailItem` 重新计算行数（4 个设备保持一行）；
 * 2. 在 `DetailViewHolder.onBind()` 后隐藏该条目并把尺寸置零，不再渲染省略号。
 */
class DeviceCenterMoreHook : BaseHook() {

    override val key: String = KEY

    override fun init() {
        val systemCl = target.classLoader ?: return
        PluginLoader.register(key, systemCl) { pluginCl -> install(pluginCl) }
    }

    private fun install(pluginCl: ClassLoader) {
        Reflect.findClassIfExists(CARD_CONTROLLER, pluginCl)
            ?.declaredMethods
            ?.filter { it.name == "getMode" && it.parameterCount == 0 }
            ?.forEach { method ->
                method.isAccessible = true
                runCatching { HookHelper.hookAfter(method) { param -> overrideMode(param) } }
                    .onFailure { HookHelper.log("$tag: hook getMode failed", it) }
            }

        Reflect.findClassIfExists(DETAIL_HOLDER, pluginCl)
            ?.declaredMethods
            ?.filter { it.name == "onBind" && it.parameterCount == 0 }
            ?.forEach { method ->
                method.isAccessible = true
                runCatching { HookHelper.hookAfter(method) { param -> hideDetail(param) } }
                    .onFailure { HookHelper.log("$tag: hook onBind failed", it) }
            }
    }

    private fun overrideMode(param: HookParam) {
        if (!HookPrefs.getBoolean(KEY, false)) return
        val controller = param.thisObject ?: return
        val items = runCatching { Reflect.getObjectField(controller, "deviceItems") as? List<*> }
            .getOrNull() ?: return
        val last = items.lastOrNull() ?: return
        if (last.javaClass.name != DETAIL_ITEM) return

        val realCount = items.size - 1
        val current = param.result as? Enum<*> ?: return
        val wanted = when {
            realCount <= 1 -> "MODE_COLLAPSED"
            realCount > 4 -> "MODE_2_ROWS"
            else -> "MODE_1_ROW"
        }
        if (current.name == wanted) return

        val target = current.javaClass.enumConstants
            ?.firstOrNull { it.name == wanted } ?: return
        param.result = target
    }

    private fun hideDetail(param: HookParam) {
        if (!HookPrefs.getBoolean(KEY, false)) return
        val holder = param.thisObject ?: return
        val view = itemViewOf(holder) ?: return
        view.visibility = View.GONE
        view.layoutParams?.let { params ->
            params.width = 0
            params.height = 0
            view.layoutParams = params
        }
    }

    private fun itemViewOf(holder: Any): View? {
        var current: Class<*>? = holder.javaClass
        while (current != null) {
            val field = runCatching { current.getDeclaredField("itemView") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return field.get(holder) as? View
            }
            current = current.superclass
        }
        return null
    }

    companion object {
        const val KEY = "device_center_hide_more"

        private const val CARD_CONTROLLER =
            "miui.systemui.controlcenter.panel.main.devicecenter.devices.DeviceCenterCardController"
        private const val DETAIL_HOLDER =
            "miui.systemui.controlcenter.panel.main.devicecenter.devices.DetailViewHolder"
        private const val DETAIL_ITEM =
            "miui.systemui.controlcenter.panel.main.devicecenter.devices.DeviceItem\$DetailItem"
    }
}
