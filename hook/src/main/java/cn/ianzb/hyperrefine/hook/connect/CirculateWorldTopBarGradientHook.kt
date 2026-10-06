package cn.ianzb.hyperrefine.hook.connect

import android.graphics.Canvas
import cn.ianzb.hyperrefine.hook.miuix.TopBarGradientHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 融合设备中心（`CirculateWorldActivity`）的顶栏渐变模糊（目标进程 `com.milink.service:ui`）。
 *
 * 该界面内容由 Fragment / Compose 列表承载，不驱动 MIUIX `ActionBarContainer` 的滚动遮罩，
 * 故**常驻模糊**（[alwaysBlur]）。
 *
 * 另外，小米互联内置 MIUIX 的「自带渐变遮罩」不止在 `ActionBarContainer` 里画：
 * - `ActionBarContainer` 把遮罩拆成 `J()`/`K()`/`M()` 多个内部方法绘制；
 * - `ActionBarOverlayLayout.dispatchDraw` 还会在其子 View 之上再画一层顶部渐变
 *   （`P` content-header 背景 drawable + `X5` 驱动的 `p5` 线性渐变），这层会盖在模糊之上。
 *
 * 因此这里在启用时把这几处一并抑制，顶栏只剩我们加的模糊。
 */
class CirculateWorldTopBarGradientHook : TopBarGradientHook() {

    override val alwaysBlur: Boolean = true

    override fun init() {
        super.init()
        val loader = target.classLoader ?: return
        suppressBarContainerMask(loader)
        suppressOverlayContentMask(loader)
    }

    /** 跳过内置 MIUIX `ActionBarContainer` 里画「主背景 drawable / `d1` 渐变遮罩」的内部方法。 */
    private fun suppressBarContainerMask(loader: ClassLoader) {
        val barClass = Reflect.findClassIfExists(BAR, loader) ?: return
        for (name in BAR_MASK_PAINTERS) {
            val method = Reflect.findMethodIfExists(barClass, name, Canvas::class.java) ?: continue
            HookHelper.intercept(method) { chain ->
                if (enabled()) null else chain.proceed()
            }
        }
    }

    /** 抑制 `ActionBarOverlayLayout` 顶部那层自带渐变 / content-header 背景。 */
    private fun suppressOverlayContentMask(loader: ClassLoader) {
        val overlayClass = Reflect.findClassIfExists(OVERLAY_LAYOUT, loader) ?: return
        val dispatchDraw = Reflect.findMethodIfExists(overlayClass, "dispatchDraw", Canvas::class.java) ?: return
        val headerField = Reflect.findField(overlayClass, "P")
        val gradientAlphaField = Reflect.findField(overlayClass, "X5")

        HookHelper.intercept(dispatchDraw) { chain ->
            if (!enabled()) {
                chain.proceed()
            } else {
                val target = chain.thisObject
                val savedHeader = runCatching { headerField.get(target) }.getOrNull()
                val savedAlpha = runCatching { gradientAlphaField.getFloat(target) }.getOrDefault(0f)
                try {
                    headerField.set(target, null)
                    gradientAlphaField.setFloat(target, 0f)
                    chain.proceed()
                } finally {
                    runCatching { headerField.set(target, savedHeader) }
                    runCatching { gradientAlphaField.setFloat(target, savedAlpha) }
                }
            }
        }
    }

    private companion object {
        const val BAR = "miuix.appcompat.internal.app.widget.ActionBarContainer"
        const val OVERLAY_LAYOUT = "miuix.appcompat.internal.app.widget.ActionBarOverlayLayout"

        /** 画出「主背景 drawable / `d1` 渐变遮罩 / 二者组合」的私有方法。 */
        val BAR_MASK_PAINTERS = arrayOf("J", "K", "M")
    }
}
