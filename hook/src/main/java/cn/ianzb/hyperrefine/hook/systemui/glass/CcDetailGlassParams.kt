package cn.ianzb.hyperrefine.hook.systemui.glass

import android.view.View
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import io.github.libxposed.api.XposedInterface
import java.util.WeakHashMap

/**
 * Share the effective glass between native surfaces in the same panel.
 */
internal class CcDetailGlassParams {
    private val panelParams = WeakHashMap<View, FloatArray>()
    private val activeWrite = ThreadLocal<MaterialWrite>()

    fun install() {
        runCatching {
            val setter = View::class.java.getMethod("setMiGlass", FloatArray::class.java)
            // Read the arguments after material customizers have applied their scene settings.
            HookHelper.intercept(setter, priority = XposedInterface.PRIORITY_LOWEST) { chain ->
                val write = activeWrite.get() ?: return@intercept chain.proceed()
                if (write.view !== chain.thisObject) return@intercept chain.proceed()
                val params = chain.args.firstOrNull() as? FloatArray ?: return@intercept chain.proceed()
                val effective = write.resolve(params)
                if (effective === params) chain.proceed() else chain.proceed(arrayOf(effective))
            }
        }.onFailure { HookHelper.log("CcGlass: detail glass parameter sharing unavailable", it) }
    }

    fun capture(
        panel: View,
        source: View,
        sourceDefaults: FloatArray,
        targetDefaults: FloatArray = sourceDefaults,
        apply: () -> Unit,
    ): Boolean {
        var sample: FloatArray? = null
        withWrite(source, { params ->
            if (params.size == sourceDefaults.size && params.size == targetDefaults.size && params.all(Float::isFinite)) {
                if (sourceDefaults === targetDefaults) {
                    if (!params.contentEquals(targetDefaults)) sample = params.copyOf()
                } else {
                    // A selected device supplies only values whose native defaults match the volume track.
                    val effective = FloatArray(params.size) { index ->
                        if (sourceDefaults[index] == targetDefaults[index]) params[index] else targetDefaults[index]
                    }
                    sample = effective.takeUnless { it.contentEquals(targetDefaults) }
                }
            }
            params
        }, apply)
        val previous = panelParams[panel]
        val current = sample
        if (current == null) return panelParams.remove(panel) != null
        if (previous != null && previous.contentEquals(current)) return false
        panelParams[panel] = current
        return true
    }

    fun applyToTarget(view: View, apply: () -> Boolean): Boolean {
        val params = generateSequence(view.parent as? View) { it.parent as? View }
            .firstNotNullOfOrNull { panelParams[it] } ?: return apply()
        return withWrite(view, { original ->
            if (original.size == params.size && !original.contentEquals(params)) params.copyOf() else original
        }, apply)
    }

    fun clear(panel: View) {
        panelParams.remove(panel)
    }

    private fun <T> withWrite(view: View, resolve: (FloatArray) -> FloatArray, apply: () -> T): T {
        val previous = activeWrite.get()
        activeWrite.set(MaterialWrite(view, resolve))
        try {
            return apply()
        } finally {
            if (previous == null) activeWrite.remove() else activeWrite.set(previous)
        }
    }

    private class MaterialWrite(val view: View, val resolve: (FloatArray) -> FloatArray)
}
