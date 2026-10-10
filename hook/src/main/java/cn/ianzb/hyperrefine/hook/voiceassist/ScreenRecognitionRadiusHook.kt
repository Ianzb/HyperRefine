package cn.ianzb.hyperrefine.hook.voiceassist

import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import kotlin.math.min

/**
 * 超级小爱「识屏」开启动画的圆角适配。
 *
 * 目标进程：`com.miui.voiceassist`。
 *
 * 打开识屏时，`ScreenRecognitionView` 会把当前截图交给
 * `ClipImageView#startAnimation(onStart, onEnd, config)` 做「屏幕内容缩小成卡片」的动画，参数
 * `es1.a(targetWidth, targetHeight, topCropPx, bottomCropPx, topMarginPx, startCornerDp, endCornerDp, duration)`：
 * `endCornerDp` = 卡片圆角（`screen_recognition_container_radius` 24dp），`startCornerDp` 被写死成
 * `px2dp(5f)`（≈直角）。
 *
 * 处理分两步：
 * 1. 起始圆角改用屏幕真实圆角（`Display.getRoundedCorner(0).radius`）；
 * 2. 应用侧对圆角有「不超过 endCorner」的上限裁剪，起始圆角大于卡片圆角时会被裁成常量（无过渡）。
 *    因此在动画插值回调 `ClipImageView$b#onUpdate` 之后，按进度重新写入未裁剪的圆角，
 *    使圆角能从「屏幕圆角」平滑过渡到「卡片圆角」。
 *
 * 注意屏幕圆角**不保证**大于卡片圆角：平板上常见「屏幕圆角 < 卡片圆角」（Xiaomi Pad 7 Ultra：
 * 圆角 54px、密度 440dpi ≈ 19.6dp，而卡片圆角 `screen_recognition_container_radius` 为 24dp）。
 * 这种设备上应用自身「取小」的裁剪不会出问题，但起始圆角仍应按屏幕圆角过渡（19.6dp → 24dp），
 * 因此是否改写只与「应用写死的起始圆角」比较，不能与卡片圆角比较。
 */
class ScreenRecognitionRadiusHook : BaseHook() {

    override val key: String = KEY

    override fun init() {
        hookStartConfig()
        hookCornerInterpolation()
        hookExitCorner()
    }

    /** 起始圆角 = 屏幕真实圆角。 */
    private fun hookStartConfig() {
        val loader = target.classLoader ?: return
        val cls = Reflect.findClassIfExists(CLIP_IMAGE_VIEW, loader) ?: run {
            HookHelper.log("$tag: $CLIP_IMAGE_VIEW not found")
            return
        }
        val method = cls.declaredMethods.firstOrNull {
            it.name == "startAnimation" && it.parameterCount == 3
        } ?: run {
            HookHelper.log("$tag: startAnimation not found")
            return
        }
        method.isAccessible = true
        runCatching {
            HookHelper.hookBefore(method) { param ->
                if (!HookPrefs.getBoolean(KEY, false)) return@hookBefore
                val config = param.args.getOrNull(2) ?: return@hookBefore
                val view = param.thisObject as? View ?: return@hookBefore
                val end = cornerOf(config, "getEndCornerDp") ?: return@hookBefore
                // 应用写死的起始圆角：约 5px（未乘密度）≈ 1.8dp，观感为直角。
                val original = cornerOf(config, "getStartCornerDp") ?: return@hookBefore
                val cornerPx = screenCornerPx(view)
                if (cornerPx <= 0) return@hookBefore
                val density = view.resources.displayMetrics.density
                if (density <= 0f) return@hookBefore
                val startDp = cornerPx / density
                // 仅当屏幕圆角比原起始圆角更大时才需要改写；与卡片圆角 [end] 无关：
                // 大屏 / 高密度设备上屏幕圆角可能小于卡片圆角，此时动画同样应从屏幕圆角开始过渡。
                if (startDp <= original) return@hookBefore
                val rebuilt = rebuild(config, startDp, end) ?: return@hookBefore
                HookHelper.log("$tag: corner ${startDp}dp (screen ${cornerPx}px) -> card ${end}dp")
                param.setArg(2, rebuilt)
            }
        }.onFailure { HookHelper.log("$tag: hook startAnimation failed", it) }
    }

    /**
     * `ClipImageView$b#onUpdate` 里会把圆角裁剪到不超过结束值；当起始圆角大于卡片圆角时，
     * 结果恒为卡片圆角（没有过渡）。这里在其之后按进度重写圆角，恢复平滑过渡。
     */
    private fun hookCornerInterpolation() {
        val loader = target.classLoader ?: return
        val cls = Reflect.findClassIfExists(LISTENER, loader) ?: run {
            HookHelper.log("$tag: $LISTENER not found")
            return
        }
        val method = cls.declaredMethods.firstOrNull {
            it.name == "onUpdate" && it.parameterCount == 2
        } ?: run {
            HookHelper.log("$tag: onUpdate not found")
            return
        }
        method.isAccessible = true
        runCatching {
            HookHelper.hookAfter(method) { param ->
                if (!HookPrefs.getBoolean(KEY, false)) return@hookAfter
                val listener = param.thisObject ?: return@hookAfter
                val view = findView(listener) ?: return@hookAfter
                val start = f(view, "r") ?: return@hookAfter
                val end = f(view, "s") ?: return@hookAfter
                // 仅处理「起始圆角 > 结束圆角」的下降过渡；上升情况应用侧已正确。
                if (start <= end) return@hookAfter
                val original = i(view, "l") ?: return@hookAfter
                val scaleT = f(view, "t") ?: return@hookAfter
                val params = view.layoutParams ?: return@hookAfter
                val w = params.width
                val h = params.height
                if (w <= 0 || h <= 0) return@hookAfter
                val progress = progress(original, scaleT, h)
                val cropScale = i(view, "m")?.toFloat() ?: return@hookAfter
                val cropBottomScale = i(view, "n")?.toFloat() ?: return@hookAfter
                val targetW = i(view, "p") ?: 0
                val targetH = i(view, "q") ?: 0
                val factor = min(
                    if (targetW > 0) w.toFloat() / targetW else 1f,
                    if (targetH > 0) h.toFloat() / targetH else 1f,
                )
                val corner = (start + (end - start) * progress) * factor
                Reflect.callMethod(view, "setCrop", cropScale * progress, cropBottomScale * progress, corner, w, h)
            }
        }.onFailure { HookHelper.log("$tag: hook corner interpolation failed", it) }
    }

    /**
     * 退出（关闭）动画：`ClipImageView$c#onUpdate` 用 `s-(s-r)*progress` 计算圆角，
     * 并在 `起始圆角>卡片圆角` 时把尺寸传成 -1（用 `getWidth/getHeight`，可能滞后于动画尺寸）。
     * 这里在它之后按进度重写圆角（卡片圆角 → 屏幕圆角）并传入当前动画尺寸。
     */
    private fun hookExitCorner() {
        val loader = target.classLoader ?: return
        val cls = Reflect.findClassIfExists(EXIT_LISTENER, loader) ?: run {
            HookHelper.log("$tag: $EXIT_LISTENER not found")
            return
        }
        val method = cls.declaredMethods.firstOrNull {
            it.name == "onUpdate" && it.parameterCount == 2
        } ?: run {
            HookHelper.log("$tag: exit onUpdate not found")
            return
        }
        method.isAccessible = true
        runCatching {
            HookHelper.hookAfter(method) { param ->
                if (!HookPrefs.getBoolean(KEY, false)) return@hookAfter
                val listener = param.thisObject ?: return@hookAfter
                val view = findView(listener) ?: return@hookAfter
                val screen = f(view, "r") ?: return@hookAfter
                val card = f(view, "s") ?: return@hookAfter
                if (screen <= card) return@hookAfter
                val targetH = i(view, "q") ?: return@hookAfter
                val scaleT = f(view, "t") ?: return@hookAfter
                val params = view.layoutParams ?: return@hookAfter
                val w = params.width
                val h = params.height
                if (w <= 0 || h <= 0) return@hookAfter
                val p = exitProgress(targetH, scaleT, h)
                val remain = 1f - p
                val cropScale = i(view, "m")?.toFloat() ?: return@hookAfter
                val cropBottomScale = i(view, "n")?.toFloat() ?: return@hookAfter
                val corner = card + (screen - card) * p
                Reflect.callMethod(view, "setCrop", cropScale * remain, cropBottomScale * remain, corner, w, h)
            }
        }.onFailure { HookHelper.log("$tag: hook exit corner failed", it) }
    }

    private fun exitProgress(target: Int, scale: Float, current: Int): Float {
        val p = if (target == 0 || scale == 0f || scale == 1f) {
            if (target == 0) 0f else 1f
        } else {
            (1f - current.toFloat() / target) / (1f - 1f / scale)
        }
        return p.coerceIn(0f, 1f)
    }

    private fun progress(original: Int, scale: Float, current: Int): Float {
        val p = if (original == 0 || scale == 1f) {
            if (original == 0) 0f else 1f
        } else {
            (1f - current.toFloat() / original) / (1f - scale)
        }
        return p.coerceIn(0f, 1f)
    }

    /** 从监听器里找它持有的 `ClipImageView`（字段名可能被混淆，故按类型扫描）。 */
    private fun findView(listener: Any): View? {
        listener.javaClass.declaredFields.forEach { field ->
            runCatching {
                field.isAccessible = true
                val value = field.get(listener)
                if (value is View) return value
            }
        }
        return null
    }

    private fun f(view: Any, name: String): Float? =
        runCatching { Reflect.getObjectField(view, name) }.getOrNull() as? Float

    private fun i(view: Any, name: String): Int? =
        runCatching { Reflect.getObjectField(view, name) }.getOrNull() as? Int

    private fun cornerOf(config: Any, getter: String): Float? =
        runCatching { Reflect.callMethod(config, getter) }.getOrNull() as? Float

    /** 读取屏幕物理圆角（像素）。失败 / 不可用时返回 0。 */
    private fun screenCornerPx(view: View): Int = runCatching {
        view.display?.getRoundedCorner(0)?.radius ?: 0
    }.getOrDefault(0)

    /** 用相同字段重建动画配置，仅替换起始圆角。 */
    private fun rebuild(config: Any, startDp: Float, endDp: Float): Any? = runCatching {
        val ctor = config.javaClass.getConstructor(
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            Integer.TYPE,
            java.lang.Float.TYPE,
            java.lang.Float.TYPE,
            java.lang.Long.TYPE,
        )
        ctor.newInstance(
            Reflect.callMethod(config, "getTargetWidth"),
            Reflect.callMethod(config, "getTargetHeight"),
            Reflect.callMethod(config, "getTopCropPx"),
            Reflect.callMethod(config, "getBottomCropPx"),
            Reflect.callMethod(config, "getTopMarginPx"),
            startDp,
            endDp,
            Reflect.callMethod(config, "getDuration"),
        )
    }.getOrNull()

    companion object {
        const val KEY = "sr_animation_radius"

        private const val CLIP_IMAGE_VIEW =
            "com.xiaomi.voiceassistant.screenrecognition.view.ClipImageView"
        private const val LISTENER =
            "com.xiaomi.voiceassistant.screenrecognition.view.ClipImageView\$b"
        private const val EXIT_LISTENER =
            "com.xiaomi.voiceassistant.screenrecognition.view.ClipImageView\$c"
    }
}
