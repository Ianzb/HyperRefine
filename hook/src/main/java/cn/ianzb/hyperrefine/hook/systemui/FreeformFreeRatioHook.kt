package cn.ianzb.hyperrefine.hook.systemui

import android.graphics.Rect
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 无极小窗四角拖动时保留自由宽高比（仅平板 / 折叠屏）。
 *
 * 拖动过程中 `MiuiInfinityModeRatioPositioner` 的 `current` 记录自由比例，
 * 但 `executeWindowTransaction()` 与 `onGestureDragEnd()` 会按 `guess` 的最近
 * 比例档位写入 destination，导致窗口、遮罩、阴影和抬手坐标都被吸附到系统档位。
 *
 * 开启后在每次拖动后把 destination bounds / visual bounds 覆盖为
 * `mLastMovingBounds`，并在 `updateDestinationAttributes()` 之后再次覆盖，
 * 从而让整条链路都使用自由比例。
 */
class FreeformFreeRatioHook : BaseHook() {

    override val key: String = SplitScreenKeys.FREEFORM_FREE_RATIO
    override val deviceScope: Set<DeviceType> = setOf(DeviceType.PAD, DeviceType.FOLD)

    private var lastBounds: Rect? = null
    private var lastWrapper: Any? = null

    override fun init() {
        val loader = target.classLoader ?: throw HookSkippedException("no classloader")
        val positionerCls = findClass(INFINITY_POSITIONER, loader)
            ?: throw HookSkippedException("$INFINITY_POSITIONER not found")
        val wrapperCls = findClass(INFINITY_WRAPPER, loader)
            ?: throw HookSkippedException("$INFINITY_WRAPPER not found")
        val move = positionerCls.declaredMethods.firstOrNull {
            it.name == "onGestureDragMove" && it.parameterCount == 2
        } ?: throw HookSkippedException("$INFINITY_POSITIONER#onGestureDragMove not found")
        val end = positionerCls.declaredMethods.firstOrNull {
            it.name == "onGestureDragEnd" && it.parameterCount == 2
        } ?: throw HookSkippedException("$INFINITY_POSITIONER#onGestureDragEnd not found")
        val reset = positionerCls.declaredMethods.firstOrNull {
            it.name == "reset" && it.parameterCount == 0
        } ?: throw HookSkippedException("$INFINITY_POSITIONER#reset not found")
        val update = wrapperCls.declaredMethods.firstOrNull {
            it.name == "updateDestinationAttributes" && it.parameterCount == 4
        } ?: throw HookSkippedException("$INFINITY_WRAPPER#updateDestinationAttributes not found")

        listOf(move, end, reset, update).forEach {
            it.isAccessible = true
            HookHelper.deoptimize(it)
        }
        HookHelper.hookAfter(move) { param -> runCatching { rememberBounds(param) } }
        HookHelper.hookAfter(end) { clear() }
        HookHelper.hookAfter(reset) { clear() }
        HookHelper.hookAfter(update) { param -> runCatching { overrideDestination(param) } }
    }

    private fun rememberBounds(param: HookParam) {
        val positioner = param.thisObject ?: return
        val bounds = Reflect.getObjectField(positioner, "mLastMovingBounds") as? Rect
            ?: return
        if (bounds.isEmpty) return
        val wrapper = Reflect.getObjectField(positioner, "mTaskWrapper") ?: return
        val current = Reflect.getObjectField(positioner, "current") ?: return
        val guess = Reflect.getObjectField(positioner, "guess") ?: return
        if ((Reflect.getObjectField(guess, "mode") as? Int) != 1) return
        val finalBounds = if (HookPrefs.getBoolean(SplitScreenKeys.FREEFORM_FREE_RATIO_SNAP, true)) {
            snapToOfficialRatio(positioner, bounds, current, guess)
        } else {
            Rect(bounds)
        }
        lastBounds = finalBounds
        lastWrapper = wrapper
        applyFreeBounds(wrapper, finalBounds)
    }

    /** 自由比例离官方档位足够近时，按官方比例重算宽高并保持窗口中心不动。 */
    private fun snapToOfficialRatio(
        positioner: Any,
        bounds: Rect,
        current: Any,
        guess: Any,
    ): Rect {
        val currentRatio = Reflect.getObjectField(current, "aspectRatio") as? Float ?: return Rect(bounds)
        val officialRatio = Reflect.getObjectField(guess, "aspectRatio") as? Float ?: return Rect(bounds)
        if (currentRatio <= 0f || officialRatio <= 0f) return Rect(bounds)
        val delta = abs(currentRatio - officialRatio) / officialRatio
        if (delta > SNAP_THRESHOLD) return Rect(bounds)
        val factor = sqrt(officialRatio / currentRatio)
        if (!factor.isFinite() || factor <= 0f) return Rect(bounds)
        val screenWidth = Reflect.getObjectField(positioner, "mScreenWidth") as? Int ?: 0
        val screenHeight = Reflect.getObjectField(positioner, "mScreenHeight") as? Int ?: 0
        val width = (bounds.width() * factor).roundToInt()
            .coerceAtLeast(1)
            .let { if (screenWidth > 0) min(it, screenWidth) else it }
        val height = (bounds.height() / factor).roundToInt()
            .coerceAtLeast(1)
            .let { if (screenHeight > 0) min(it, screenHeight) else it }
        val maxLeft = if (screenWidth > 0) (screenWidth - width).coerceAtLeast(0) else Int.MAX_VALUE
        val maxTop = if (screenHeight > 0) (screenHeight - height).coerceAtLeast(0) else Int.MAX_VALUE
        val left = (bounds.centerX() - width / 2).coerceIn(0, maxLeft)
        val top = (bounds.centerY() - height / 2).coerceIn(0, maxTop)
        return Rect(left, top, left + width, top + height)
    }

    private fun overrideDestination(param: HookParam) {
        val bounds = lastBounds ?: return
        if ((param.args.getOrNull(1) as? Int) != 1) return
        val wrapper = param.thisObject ?: return
        if (wrapper !== lastWrapper) return
        applyFreeBounds(wrapper, bounds)
    }

    private fun applyFreeBounds(wrapper: Any, bounds: Rect) {
        val rect = Rect(bounds)
        Reflect.callMethod(wrapper, "setDestinationBounds", rect)
        Reflect.callMethod(wrapper, "setDestinationVisualBounds", Rect(rect))
        Reflect.callMethod(wrapper, "setDestinationNormalScale", 1.0f)
    }

    private fun clear() {
        lastBounds = null
        lastWrapper = null
    }

    private fun findClass(name: String, loader: ClassLoader): Class<*>? =
        Reflect.findClassIfExists(name, loader)
            ?: Reflect.findClassIfExists(name)
            ?: Reflect.findClassIfExists(name, loader.parent)

    companion object {
        private const val SNAP_THRESHOLD = 0.06f
        private const val INFINITY_POSITIONER =
            "com.android.wm.shell.multitasking.miuiinfinitymode.positiner.MiuiInfinityModeRatioPositioner"
        private const val INFINITY_WRAPPER =
            "com.android.wm.shell.multitasking.miuiinfinitymode.MiuiInfinityModeTaskWrapper"
    }
}
