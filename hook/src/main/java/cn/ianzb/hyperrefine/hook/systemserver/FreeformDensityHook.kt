package cn.ianzb.hyperrefine.hook.systemserver

import android.content.res.Configuration
import android.graphics.Rect
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.systemui.SplitScreenKeys
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.Collections
import java.util.WeakHashMap
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 无极小窗字号 / 界面随窗口等比缩放。
 *
 * 系统自带的几个小窗比例会在窗口配置里写入对应的 `densityDpi`，所以文字会随窗口缩放；
 * 无极小窗只改窗口边界，应用仍然按全屏密度渲染。这里在 `ActivityRecord` 把配置下发给
 * 应用之前，按窗口面积比例补一个 `densityDpi`（并同步 dp 尺寸），让无极小窗与系统
 * 固定比例小窗的表现一致。
 *
 * 只处理 `freeform`；系统固定档位自带 `densityDpi` 时跳过，本模块写入的密度会在每次
 * 缩放时重新计算，退出小窗时撤销。判断失败时直接跳过，保持官方行为。开关变更需重启生效。
 */
class FreeformDensityHook : BaseHook() {

    override val key: String = FreeformDensityKeys.DENSITY

    /** 记录本模块写入的密度，用于区分系统固定档位并支持窗口继续缩放。 */
    private val injectedDensity = Collections.synchronizedMap(WeakHashMap<Any, Int>())

    override fun init() {
        val clazz = Reflect.findClassIfExists(
            "com.android.server.wm.ActivityRecord",
            target.classLoader,
        )
        if (clazz == null) {
            HookHelper.log("$tag: ActivityRecord not found")
            return
        }
        val configurationClass = Configuration::class.java
        val method = clazz.declaredMethods.firstOrNull {
            it.name == "setLastReportedConfiguration" &&
                it.parameterCount == 2 &&
                it.parameterTypes[0] == configurationClass &&
                it.parameterTypes[1] == configurationClass
        }
        if (method == null) {
            HookHelper.log("$tag: setLastReportedConfiguration(Configuration, Configuration) not found")
            return
        }
        runCatching {
            method.isAccessible = true
            HookHelper.deoptimize(method)
            HookHelper.hookBefore(method) { param ->
                val record = param.thisObject ?: return@hookBefore
                val global = param.args.getOrNull(0) as? Configuration ?: return@hookBefore
                val override = param.args.getOrNull(1) as? Configuration ?: return@hookBefore
                runCatching {
                    scaleDensity(record, global, override)
                }.onFailure {
                    HookHelper.log("$tag: scale freeform density failed", it)
                }
            }
        }.onFailure {
            HookHelper.log("$tag: hook setLastReportedConfiguration failed", it)
            return
        }
        HookHelper.log("$tag: freeform density follows window size")
    }

    private fun scaleDensity(record: Any, global: Configuration, override: Configuration) {
        // 字号缩放是“无极小窗”的子选项，主开关关闭时不参与任何配置。
        if (!HookPrefs.getBoolean(SplitScreenKeys.FREEFORM_FREE_RATIO, false)) {
            clearInjectedDensity(record, override)
            return
        }
        // `getMergedOverrideConfiguration()` 返回的是 ActivityRecord 持有的同一个对象，
        // 本模块写入的密度会保留到下一次下发；只有“非本模块”的非零密度才是系统固定档位。
        val previous = injectedDensity[record]
        if (override.densityDpi != 0 && override.densityDpi != previous) return

        val freeform = Reflect.callMethod(record, "inFreeformWindowingMode") as? Boolean ?: false
        if (!freeform) {
            clearInjectedDensity(record, override)
            return
        }
        val bounds = Reflect.callMethod(record, "getBounds") as? Rect
        if (bounds == null || bounds.width() <= 0 || bounds.height() <= 0) {
            clearInjectedDensity(record, override)
            return
        }
        // `Configuration.windowConfiguration` 在编译 SDK 里是隐藏字段，运行时反射读取。
        val windowConfiguration = Reflect.getObjectField(global, "windowConfiguration")
        val maxBounds = windowConfiguration?.let {
            Reflect.getObjectField(it, "maxBounds") as? Rect
        }
        if (maxBounds == null || maxBounds.width() <= 0 || maxBounds.height() <= 0) {
            clearInjectedDensity(record, override)
            return
        }

        val widthRatio = (bounds.width().toFloat() / maxBounds.width()).coerceIn(0f, 1f)
        val heightRatio = (bounds.height().toFloat() / maxBounds.height()).coerceIn(0f, 1f)
        val scale = sqrt(widthRatio * heightRatio).coerceIn(0.6f, 1f)
        if (scale >= 0.99f) {
            clearInjectedDensity(record, override)
            return
        }

        val targetDpi = (global.densityDpi * scale).roundToInt().coerceAtLeast(120)
        if (targetDpi >= global.densityDpi) {
            clearInjectedDensity(record, override)
            return
        }
        override.densityDpi = targetDpi
        val widthDp = (bounds.width() * 160f / targetDpi).roundToInt()
        val heightDp = (bounds.height() * 160f / targetDpi).roundToInt()
        override.screenWidthDp = widthDp
        override.screenHeightDp = heightDp
        override.smallestScreenWidthDp = min(widthDp, heightDp)
        injectedDensity[record] = targetDpi
    }

    /** 退出小窗或恢复全屏尺寸时撤销本模块写入的密度，避免遗留缩放。 */
    private fun clearInjectedDensity(record: Any, override: Configuration) {
        if (injectedDensity.remove(record) == null) return
        override.densityDpi = 0
        override.screenWidthDp = 0
        override.screenHeightDp = 0
        override.smallestScreenWidthDp = 0
    }
}
