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
    private val volumeParams = WeakHashMap<View, FloatArray>()
    private val activeWrite = ThreadLocal<MaterialWrite>()

    fun install() {
        runCatching {
            val setter = View::class.java.getMethod("setMiGlass", FloatArray::class.java)
            // Read the arguments after material customizers have applied their scene settings.
            HookHelper.intercept(setter, priority = XposedInterface.PRIORITY_LOWEST) { chain ->
                val view = chain.thisObject as? View ?: return@intercept chain.proceed()
                val params = chain.args.firstOrNull() as? FloatArray
                val write = activeWrite.get()
                val effective = when {
                    write?.view === view && params != null -> write.resolve(params)
                    else -> volumeParams[view]?.takeIf {
                        view.isAttachedToWindow && CcGlassKeys.isEnabled(CcGlassKeys.NOTIFICATION_MEDIA) &&
                            (params == null || it.size == params.size)
                    } ?: params
                }
                if (effective == null || (params != null && effective.contentEquals(params))) {
                    return@intercept chain.proceed()
                }
                chain.proceed(arrayOf(effective.copyOf()))
            }
        }.onFailure { HookHelper.log("CcGlass: detail glass parameter sharing unavailable", it) }
    }

    fun capture(
        panel: View,
        source: View,
        sourceDefaults: FloatArray,
        targetDefaults: FloatArray = sourceDefaults,
        copyCustomizedValues: Boolean = false,
        apply: () -> Unit,
    ): Boolean {
        var sample: FloatArray? = null
        withWrite(source, { params ->
            if (params.size == sourceDefaults.size && params.size == targetDefaults.size && params.all(Float::isFinite)) {
                if (sourceDefaults === targetDefaults) {
                    if (!params.contentEquals(targetDefaults)) sample = params.copyOf()
                } else {
                    val effective = FloatArray(params.size) { index ->
                        val sourceMatches = sourceDefaults[index] == targetDefaults[index]
                        val customized = copyCustomizedValues && params[index] != sourceDefaults[index]
                        if (sourceMatches || customized) params[index] else targetDefaults[index]
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

    fun applyToTarget(view: View, retainVolumeMaterial: Boolean = false, apply: () -> Boolean): Boolean {
        val params = generateSequence(view.parent as? View) { it.parent as? View }
            .firstNotNullOfOrNull { panelParams[it] }
        if (params == null && !retainVolumeMaterial) return apply()
        var appliedParams: FloatArray? = null
        val applied = withWrite(view, { original ->
            val effective = params?.takeIf { it.size == original.size } ?: original
            if (retainVolumeMaterial) appliedParams = effective.copyOf()
            effective
        }, apply)
        if (applied) appliedParams?.let { volumeParams[view] = it }
        return applied
    }

    fun clearVolumeMaterial(view: View) {
        volumeParams.remove(view)
    }

    fun hasVolumeMaterial(view: View): Boolean = volumeParams.containsKey(view)

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
