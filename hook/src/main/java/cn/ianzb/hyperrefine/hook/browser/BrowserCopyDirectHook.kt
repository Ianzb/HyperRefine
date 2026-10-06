package cn.ianzb.hyperrefine.hook.browser

import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * HyperAI 复制直达（目标 `com.xiaomi.aicr`，独立实现，思路参考 Fxxk-MiBrowser，MIT）。
 *
 * - `ActionCoreProvider.call` 返回后改写跳转 Intent 与元数据；
 * - `BubbleView.a(CueData)` 渲染前改写 CueData 的目标包名 / 文案；
 * - `TextView.setText` 后把气泡里的小米浏览器图标替换为默认浏览器图标。
 */
class BrowserCopyDirectHook : BaseHook() {

    override val key: String = BrowserKeys.COPY_DIRECT

    override fun init() {
        val loader = target.classLoader ?: return
        hookActionCoreProvider(loader)
        hookBubbleView(loader)
        hookRenderedIcon()
    }

    private fun hookActionCoreProvider(loader: ClassLoader) {
        val cls = Reflect.findClassIfExists(
            "com.xiaomi.aicr.hce_framework.protocol.action.com.xiaomi.aicr.actionprovider.ActionCoreProvider",
            loader,
        ) ?: return
        val method = Reflect.findMethodIfExists(
            cls, "call", String::class.java, String::class.java, android.os.Bundle::class.java
        ) ?: return
        HookHelper.hookAfter(method) { param ->
            val request = param.args.getOrNull(2) as? android.os.Bundle
            val response = param.result as? android.os.Bundle
            BrowserCopyDirect.rewriteActionResponse(request, response)
        }
    }

    private fun hookBubbleView(loader: ClassLoader) {
        val bubble = Reflect.findClassIfExists(
            "com.xiaomi.ai.bubble.core.bubbleview.view.BubbleView", loader
        ) ?: return
        val cueData = Reflect.findClassIfExists("com.xiaomi.ai.bubble.core.model.CueData", loader) ?: return
        val method = bubble.declaredMethods.firstOrNull {
            it.name == "a" && it.parameterTypes.size == 1 && it.parameterTypes[0] == cueData
        } ?: return
        method.isAccessible = true
        HookHelper.hookBefore(method) { param ->
            val value = param.args.getOrNull(0) ?: return@hookBefore
            val context = BrowserCompat.currentApplication() ?: return@hookBefore
            val browser = BrowserDefaultResolver.resolve(context) ?: return@hookBefore
            if (!browser.isDefault || XiaomiBrowserPackages.isBrowser(browser.packageName)) return@hookBefore
            val label = BrowserCompat.defaultBrowserLabel(context) ?: browser.packageName
            BrowserCopyDirect.rewriteCueData(value, browser.packageName, label)
        }
    }

    private fun hookRenderedIcon() {
        for (method in TextView::class.java.declaredMethods) {
            if (method.name != "setText") continue
            if (method.parameterTypes.none { CharSequence::class.java.isAssignableFrom(it) }) continue
            runCatching {
                HookHelper.hookAfter(method) { param ->
                    val textView = param.thisObject as? TextView ?: return@hookAfter
                    val text = param.args.firstOrNull { it is CharSequence }?.toString()
                        ?: textView.text?.toString()
                        ?: return@hookAfter
                    replaceBubbleIcon(textView, text)
                }
            }
        }
    }

    private fun replaceBubbleIcon(textView: TextView, text: String) {
        if (!text.contains("打开")) return
        val context = textView.context ?: return
        val browser = BrowserDefaultResolver.resolve(context) ?: return
        if (!browser.isDefault || XiaomiBrowserPackages.isBrowser(browser.packageName)) return
        val label = BrowserCompat.defaultBrowserLabel(context) ?: browser.packageName
        if (!text.contains(label, ignoreCase = true)) return

        val replacement = Runnable { replaceNearestImage(textView) }
        textView.post(replacement)
        textView.postDelayed(replacement, 120L)
    }

    private fun replaceNearestImage(textView: TextView) {
        if (!textView.isAttachedToWindow) return
        val drawable = defaultBrowserDrawable(textView) ?: return
        var ancestor = textView.parent as? ViewGroup
        var levels = 0
        while (ancestor != null && levels < 6) {
            val candidates = ArrayList<ImageView>()
            collectImageViews(ancestor, candidates)
            val target = candidates.filter { it.visibility == View.VISIBLE }
                .minByOrNull { distance(textView, it) }
            if (target != null) {
                target.setImageDrawable(drawable.constantState?.newDrawable()?.mutate() ?: drawable)
                return
            }
            ancestor = ancestor.parent as? ViewGroup
            levels++
        }
    }

    private fun defaultBrowserDrawable(textView: TextView): Drawable? {
        val context = textView.context ?: return null
        val browser = BrowserDefaultResolver.resolve(context) ?: return null
        return runCatching { context.packageManager.getApplicationIcon(browser.packageName) }.getOrNull()
    }

    private fun collectImageViews(root: View, output: MutableList<ImageView>) {
        if (root is ImageView) {
            output += root
            return
        }
        if (root !is ViewGroup) return
        for (index in 0 until root.childCount) collectImageViews(root.getChildAt(index), output)
    }

    private fun distance(textView: TextView, imageView: ImageView): Int {
        val textPos = IntArray(2)
        val imagePos = IntArray(2)
        runCatching { textView.getLocationOnScreen(textPos) }
        runCatching { imageView.getLocationOnScreen(imagePos) }
        return kotlin.math.abs(textPos[0] - imagePos[0]) + kotlin.math.abs(textPos[1] - imagePos[1])
    }
}
