package cn.ianzb.hyperrefine.hook.connect

import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 融合设备中心「流转卡片」补柔光玻璃。
 *
 * 点击融合设备中心里的设备后，`com.milink.service` 会弹出一张流转卡片
 * （窗口 `com.milink.card.frame.library.host.MLCard`）。可用设备的卡片内容由设备端
 * 远程视图自带柔光玻璃；而「设备不可用」时使用的是本地布局
 * `window_card_pin_device_offline`（根 `ml_pin_offline_root`，纯色底
 * `circulate_card_bg`），它只依赖宿主窗口模糊、自身没有玻璃层。
 *
 * 处理方式：hook `ViewStub.inflate()`，当 inflate 出的是离线卡片根时，调用应用自身的
 * `com.milink.util.MaterialUtils.a(view)` 套上系统材质玻璃（`setMiGlass` / 经典 blend），
 * 与其它卡片保持一致；不支持的机型上该方法内部会自行跳过。
 */
class MiLinkCardGlassHook : BaseHook() {

    override val key: String = ConnectKeys.CARD_GLASS

    override fun init() {
        val stubClass = Reflect.findClassIfExists(VIEW_STUB, target.classLoader) ?: return
        val inflate = stubClass.declaredMethods
            .firstOrNull { it.name == "inflate" && it.parameterCount == 0 } ?: return
        inflate.isAccessible = true
        runCatching {
            HookHelper.hookAfter(inflate) { param ->
                onInflate(param.thisObject as? View, param.result as? View)
            }
        }.onFailure { HookHelper.log("$tag: hook ViewStub.inflate failed", it) }
    }

    private fun onInflate(stub: View?, result: View?) {
        if (!HookPrefs.getBoolean(key, false)) return
        if (result == null) return
        if (idName(stub) != OFFLINE_STUB_ID && idName(result) != OFFLINE_ROOT_ID) return
        result.post { if (HookPrefs.getBoolean(key, false)) runCatching { applyGlass(result) } }
    }

    private fun applyGlass(view: View) {
        val cls = Reflect.findClassIfExists(MATERIAL_UTILS, view.context.classLoader) ?: return
        HookHelper.log("$tag: offline card inflated, applying material glass")
        runCatching { Reflect.callStaticMethod(cls, "a", view) }
            .onFailure { HookHelper.log("$tag: apply card glass failed", it) }
    }

    private fun idName(view: View?): String? {
        if (view == null || view.id == View.NO_ID) return null
        return runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()
    }

    companion object {
        private const val VIEW_STUB = "android.view.ViewStub"
        private const val OFFLINE_STUB_ID = "ml_pin_offline_vs"
        private const val OFFLINE_ROOT_ID = "ml_pin_offline_root"
        private const val MATERIAL_UTILS = "com.milink.util.MaterialUtils"
    }
}
