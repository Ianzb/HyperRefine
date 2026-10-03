package cn.ianzb.hyperrefine.hook.systemui

import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 融合设备中心（控制中心）：缩小设备按钮的点击判定范围。
 *
 * `DeviceItemViewHolder` 的根视图是 `CustomRootView`，其 `onMeasure` 在 AT_MOST（`wrap_content`）下
 * 把宽度强制为可用宽度的 1/4，即**整格宽度**；而可见按钮只是其中居中的方形 `container`。
 * 官方把点击监听挂在整格的 `itemView` 上，于是点击按钮四周的空白也会落到设备上（打开设备界面）。
 *
 * 处理方式：
 * - 把点击判定移到居中的方形 `container` 上（点击它仍打开设备）；
 * - `itemView` 保持可点击（否则官方 Folme 按压动画只收到 DOWN、收不到 UP 会卡在缩小状态），
 *   但把它的点击改为触发 `DeviceCenterEntryRecyclerView` 的点击（`performClick`），
 *   由于 `container` 会消费自身范围内的点击，`itemView` 的点击只会在格子空白处触发，
 *   从而打开**融合设备中心**。
 *
 * 目标类位于 MIUISystemUIPlugin 插件（`miui.systemui.plugin`）。
 */
class DeviceCenterHitAreaHook : BaseHook() {

    override val key: String = KEY

    override fun init() {
        val systemCl = target.classLoader ?: return
        PluginLoader.register(key, systemCl) { pluginCl -> install(pluginCl) }
    }

    private fun install(pluginCl: ClassLoader) {
        val holderClass = Reflect.findClassIfExists(HOLDER, pluginCl) ?: return
        holderClass.declaredConstructors.forEach { ctor ->
            ctor.isAccessible = true
            runCatching { HookHelper.hookAfter(ctor) { param -> shrink(param) } }
                .onFailure { HookHelper.log("$tag: hook ctor failed", it) }
        }
    }

    private fun shrink(param: HookParam) {
        if (!HookPrefs.getBoolean(KEY, false)) return
        val holder = param.thisObject ?: return
        val itemView = runCatching { Reflect.getObjectField(holder, "itemView") as? View }
            .getOrNull() ?: return
        val binding = runCatching { Reflect.getObjectField(holder, "binding") }.getOrNull() ?: return
        val container = runCatching { Reflect.getObjectField(binding, "container") as? View }
            .getOrNull() ?: return
        // itemView 保持可点击以让官方 Folme 按压动画正常收放；其点击只在格子空白处触发
        // （container 会消费按钮范围内的点击），改为触发列表点击 → 打开融合设备中心。
        itemView.setOnClickListener { v -> (v.parent as? View)?.performClick() }
        // container（可见按钮）改为点击时转发到官方 clickAction
        // （该 lambda 内部固定使用 binding.container，与传入的 view 无关）。
        container.setOnClickListener { v ->
            val action = runCatching { Reflect.getObjectField(holder, "clickAction") }.getOrNull()
                ?: return@setOnClickListener
            runCatching { Reflect.callMethod(action, "invoke", v) }
                .onFailure { HookHelper.log("$tag: invoke clickAction failed", it) }
        }
    }

    companion object {
        const val KEY = "device_center_shrink_hit_area"

        private const val HOLDER =
            "miui.systemui.controlcenter.panel.main.devicecenter.devices.DeviceItemViewHolder"
    }
}
