package cn.ianzb.hyperrefine.hook.systemui

import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.systemui.glass.CcGlassKeys
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Method

/**
 * 1b：把其它模块（如 HyperLight 的柔光玻璃）对官方**侧边一级音量列**实际调用的框架玻璃方法，
 * 原样镜像到多应用面板音量列（按子视图路径匹配），使面板条与侧边一级条的亮度 / 模糊一致。
 *
 * HyperLight 的柔光玻璃最终通过框架的 `View.setMiGlass(float[])` / `View.setMiGlassBlurRadius(int,int)`
 * （或静态 `MiGlassCompat.setMiGlassCompat(View, float[])`）把参数套到音量列子视图上。这里拦截这些调用，
 * 若目标是侧边列，则把**同样的方法 + 同样的参数**补到面板列对应子视图上；只做「侧边 → 面板」方向。
 */
class SideGlassMirrorHook : BaseHook() {

    override val key: String = CcGlassKeys.MASTER

    override fun init() {
        val cl = target.classLoader ?: return
        val viewCls = runCatching { Class.forName("android.view.View", false, cl) }.getOrNull()
        if (viewCls != null) {
            val intType = Int::class.javaPrimitiveType!!
            hookInstance(viewCls, "setMiGlass", FloatArray::class.java)
            hookInstance(viewCls, "setMiGlassBlurRadius", intType, intType)
        }
        for (name in MI_GLASS_COMPAT) {
            val cls = Reflect.findClassIfExists(name, cl) ?: continue
            cls.declaredMethods
                .filter {
                    it.name == "setMiGlassCompat" &&
                        it.parameterCount == 2 &&
                        it.parameterTypes[0] == View::class.java
                }
                .forEach { method ->
                    method.isAccessible = true
                    runCatching { HookHelper.hookBefore(method) { p -> doMirror(p.args.getOrNull(0) as? View, method, p.args) } }
                        .onFailure { HookHelper.log("$tag: hook MiGlassCompat failed", it) }
                }
            break
        }
        HookHelper.log("$tag: glass mirror hooked")
    }

    private fun hookInstance(cls: Class<*>, name: String, vararg params: Class<*>) {
        val method = runCatching { cls.getDeclaredMethod(name, *params) }.getOrNull() ?: return
        method.isAccessible = true
        runCatching {
            HookHelper.hookBefore(method) { p ->
                val self = p.thisObject as? View ?: return@hookBefore
                doMirror(self, method, p.args)
            }
        }.onFailure { HookHelper.log("$tag: hook $name failed", it) }
    }

    private fun doMirror(view: View?, method: Method, args: List<Any?>) {
        if (view == null) return
        AppVolumePanel.recordSideGlass(view, method.name, args.toTypedArray())
    }

    companion object {
        private val MI_GLASS_COMPAT = listOf(
            "com.miui.systemui.util.MiGlassCompat",
            "miui.systemui.util.MiGlassCompat",
        )
    }
}
