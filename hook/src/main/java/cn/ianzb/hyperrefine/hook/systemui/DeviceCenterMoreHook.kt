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
 * 2. 在 `DetailViewHolder.onBind()` 后隐藏该条目并把尺寸置零，不再渲染省略号；
 * 3. 官方 `DeviceCenterController` 默认只取前 7 个设备（原为给省略号留第 8 个位置），
 *    省略号隐藏后这里放宽到 8 个，让第 8 个设备填满两行。
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

        Reflect.findClassIfExists(DEVICE_CENTER_CONTROLLER, pluginCl)
            ?.declaredMethods
            ?.filter { it.name == "handleDeviceListUpdate" && it.parameterCount == 1 }
            ?.forEach { method ->
                method.isAccessible = true
                runCatching {
                    HookHelper.hookAfter(method) { param -> raiseDeviceLimit(param, pluginCl) }
                }.onFailure { HookHelper.log("$tag: hook device limit failed", it) }
            }
    }

    /**
     * 官方数据侧 (`DeviceCenterController`) 默认最多只向卡片推送 7 个设备，为省略号留位。
     * 省略号隐藏后重新构造前 [MAX_DEVICES] 个设备的 wrapper 列表并通知监听者，让第 8 个
     * 设备也能显示、填满两行。
     */
    private fun raiseDeviceLimit(param: HookParam, pluginCl: ClassLoader) {
        if (!HookPrefs.getBoolean(KEY, false)) return
        val controller = param.thisObject ?: return
        val deviceList = runCatching { Reflect.getObjectField(controller, "deviceList") as? List<*> }
            .getOrNull() ?: return
        if (deviceList.size <= MAX_WITH_ELLIPSIS) return

        val wrapperCl = Reflect.findClassIfExists(DEVICE_INFO_WRAPPER, pluginCl) ?: return
        val companion = runCatching { Reflect.getStaticObjectField(wrapperCl, "Companion") }
            .getOrNull() ?: return
        val context = runCatching { Reflect.getObjectField(controller, "context") }
            .getOrNull() ?: return
        val wrapperList = runCatching { Reflect.getObjectField(controller, "wrapperList") as? MutableList<Any?> }
            .getOrNull() ?: return
        val listeners = runCatching { Reflect.getObjectField(controller, "listeners") as? List<*> }
            .getOrNull() ?: return

        val count = minOf(deviceList.size, MAX_DEVICES)
        val wrappers = ArrayList<Any?>(count)
        for (i in 0 until count) {
            val info = deviceList[i] ?: continue
            val wrapper = runCatching { Reflect.callMethod(companion, "create", info, context) }
                .getOrNull() ?: continue
            wrappers.add(wrapper)
        }
        if (wrappers.isEmpty()) return

        wrapperList.clear()
        wrapperList.addAll(wrappers)
        listeners.forEach { listener ->
            if (listener == null) return@forEach
            runCatching { Reflect.callMethod(listener, "onDeviceListChanged", wrapperList) }
                .onFailure { HookHelper.log("$tag: notify expanded device list failed", it) }
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
        private const val DEVICE_CENTER_CONTROLLER =
            "miui.systemui.devicecenter.DeviceCenterController"
        private const val DEVICE_INFO_WRAPPER =
            "miui.systemui.devicecenter.devices.DeviceInfoWrapper"

        /** 官方默认给省略号预留的位数（最多 7 个设备 + 1 个省略号 = 8 格）。 */
        private const val MAX_WITH_ELLIPSIS = 7

        /** 两行满格可放的设备数（4 × 2）。 */
        private const val MAX_DEVICES = 8
    }
}
