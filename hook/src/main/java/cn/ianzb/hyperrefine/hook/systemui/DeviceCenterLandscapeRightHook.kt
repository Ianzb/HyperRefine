package cn.ianzb.hyperrefine.hook.systemui

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 横屏（手机）控制中心：融合设备中心右置。
 *
 * 控制中心在非竖向布局（手机横屏）下，由 `MainPanelContentDistributor` 按
 * `MainPanelContent.getRightOrLeft()` 把各组件分入左右两列：返回 true 入右列，false 入左列。
 * `DeviceCenterEntryController`（融合设备中心入口）内部字段 `rightOrLeft` 恒为 false，
 * 因此默认落在**左列**。开关开启时覆盖其 `getRightOrLeft()` 返回 true，把融合设备中心移到**右列**。
 *
 * 竖向布局（手机竖屏 / 平板 / 折叠屏）不会查询该方法，故天然只在横屏生效；
 * 再叠加 [deviceScope] 限制仅手机形态可用（平板 / 折叠屏跳过）。
 *
 * 目标类位于 MIUISystemUIPlugin 插件（`miui.systemui.plugin`）。
 */
class DeviceCenterLandscapeRightHook : BaseHook() {

    override val key: String = KEY

    override val deviceScope: Set<DeviceType> = setOf(DeviceType.PHONE)

    override fun init() {
        val systemCl = target.classLoader ?: return
        PluginLoader.register(key, systemCl) { pluginCl -> install(pluginCl) }
    }

    private fun install(pluginCl: ClassLoader) {
        Reflect.findClassIfExists(ENTRY_CONTROLLER, pluginCl)
            ?.declaredMethods
            ?.filter { it.name == "getRightOrLeft" && it.parameterCount == 0 }
            ?.forEach { method ->
                method.isAccessible = true
                runCatching { HookHelper.hookAfter(method) { param -> overrideRightOrLeft(param) } }
                    .onFailure { HookHelper.log("$tag: hook getRightOrLeft failed", it) }
            }
    }

    private fun overrideRightOrLeft(param: HookParam) {
        if (!HookPrefs.getBoolean(KEY, false)) return
        if (param.result == true) return
        param.result = true
    }

    companion object {
        const val KEY = "device_center_landscape_right"

        private const val ENTRY_CONTROLLER =
            "miui.systemui.controlcenter.panel.main.devicecenter.entry.DeviceCenterEntryController"
    }
}
