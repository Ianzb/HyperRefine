package cn.ianzb.hyperrefine.hook.systemui

import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 融合设备中心（控制中心）图标快速加载。
 *
 * 打开控制中心时，`miui.systemui.devicecenter.DeviceCenterController.setListening` 会调用
 * `com.miui.circulate.device.api.DeviceControlManager.start` 开始设备发现。当天已扫描过时它会
 * 传入 1~2 秒延迟（相机占用 350ms / 快捷控制 1s / 其余 2s），而 `DeviceControlManager` 只有
 * 在延迟结束后才会把**缓存设备（含已连接设备）**回调给控制中心卡片，于是出现
 * 「设备明明已连接，控制中心图标却要等一两秒才刷出来」。
 *
 * 处理方式：开关开启时把 `start` 的延迟参数改为 0，立即读取缓存设备并回调卡片（随后仍会
 * 触发一次实时扫描补全最新状态）。
 *
 * 目标类位于 MIUISystemUIPlugin 插件（`miui.systemui.plugin`）。
 */
class DeviceCenterFastLoadHook : BaseHook() {

    override val key: String = KEY

    override fun init() {
        val systemCl = target.classLoader ?: return
        PluginLoader.register(key, systemCl) { pluginCl -> install(pluginCl) }
    }

    private fun install(pluginCl: ClassLoader) {
        Reflect.findClassIfExists(MANAGER, pluginCl)
            ?.declaredMethods
            ?.filter { it.name == "start" && it.parameterTypes.firstOrNull() == Long::class.javaPrimitiveType }
            ?.forEach { method ->
                method.isAccessible = true
                HookHelper.deoptimize(method)
                runCatching { HookHelper.hookBefore(method) { param -> param.setArg(0, 0L) } }
                    .onFailure { HookHelper.log("$tag: hook start failed", it) }
            }
    }

    companion object {
        const val KEY = "device_center_fast_load"

        private const val MANAGER = "com.miui.circulate.device.api.DeviceControlManager"
    }
}
