package cn.ianzb.hyperrefine.hook.systemui.wallpaper

import android.app.KeyguardManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Color
import android.graphics.Point
import android.view.View
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * 让锁屏 / AOD 的玻璃模糊跟随壁纸压暗。
 *
 * 锁屏时钟和通知玻璃在同一个窗口内采样自己的背景 RenderNode，采样源是压暗前的壁纸；
 * `WallpaperManagerService` 的压暗只作用于壁纸图层，不会进入玻璃的采样子树。
 * 这里不复制模糊实现，而是在 MIUI 所有玻璃共同经过的
 * [View.addMiBackgroundBlendColor] / [View.setMiBackgroundBlendColors] 上补一层黑色混合色，
 * 其 alpha 等于锁屏压暗程度。这样时钟、通知以及后续新增的锁屏玻璃都会经过同一入口。
 *
 * 只在锁屏显示时注入；解锁后移除，避免控制中心等其它玻璃被误压暗。
 * 目标进程：com.android.systemui。hook 常驻安装，开关 / 滑条变化无需重启。
 */
class LockGlassDimHook : BaseHook() {

    override val key: String = HOOK_KEY

    override fun init() {
        val addMethod = runCatching {
            View::class.java.getDeclaredMethod(
                "addMiBackgroundBlendColor",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }
        }.onFailure {
            HookHelper.log("$tag: addMiBackgroundBlendColor not found", it)
        }.getOrNull()

        val setMethod = runCatching {
            View::class.java.getDeclaredMethod(
                "setMiBackgroundBlendColors",
                ArrayList::class.java,
            ).apply { isAccessible = true }
        }.onFailure {
            HookHelper.log("$tag: setMiBackgroundBlendColors not found", it)
        }.getOrNull()

        if (addMethod == null || setMethod == null) return

        runCatching {
            HookHelper.deoptimize(addMethod)
            HookHelper.hookAfter(addMethod) { param -> onAdd(param) }
            HookHelper.deoptimize(setMethod)
            HookHelper.hookBefore(setMethod) { param -> onSet(param) }
        }.onFailure {
            HookHelper.log("$tag: hook View blend methods failed", it)
            return
        }

        hookKeyguardState()
        runCatching {
            HookPrefs.registerListener { reapplyAll() }
        }.onFailure {
            HookHelper.log("$tag: register preference listener failed", it)
        }
        HookHelper.log("$tag: lock-screen glass dim installed")
    }

    private fun hookKeyguardState() {
        val cl = target.classLoader ?: return
        val controller = Reflect.findClassIfExists(KEYGUARD_CONTROLLER, cl)
        if (controller == null) {
            HookHelper.log("$tag: $KEYGUARD_CONTROLLER not found")
            return
        }
        val method = controller.declaredMethods.firstOrNull {
            it.name == "notifyKeyguardState" &&
                it.parameterTypes.contentEquals(
                    arrayOf(Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType),
                )
        }
        if (method == null) {
            HookHelper.log("$tag: notifyKeyguardState(boolean, boolean) not found")
            return
        }
        runCatching {
            method.isAccessible = true
            HookHelper.deoptimize(method)
            HookHelper.hookBefore(method) { param ->
                keyguardShowing = param.args.getOrNull(0) as? Boolean ?: keyguardShowing
                keyguardStateKnown = true
                reapplyAll()
            }
        }.onFailure {
            HookHelper.log("$tag: hook keyguard state failed", it)
        }
    }

    private fun onSet(param: HookParam) {
        val view = param.thisObject as? View ?: return
        val original = param.args.getOrNull(0) as? ArrayList<*> ?: return
        seen[view] = true
        val dim = dimColor(view)
        val old = applied[view]
        val next = ArrayList<Point>(original.size + 1)
        for (item in original) {
            if (item !is Point) continue
            if (old != null && item.x == old && item.y == BLEND_MODE) continue
            next.add(item)
        }
        if (dim != 0) {
            next.add(Point(dim, BLEND_MODE))
            applied[view] = dim
        } else {
            applied.remove(view)
        }
        if (next != original) {
            param.setArg(0, next)
        }
    }

    private fun onAdd(param: HookParam) {
        val view = param.thisObject as? View ?: return
        seen[view] = true
        if (!view.isAttachedToWindow) {
            // 玻璃 View 往往在 attach 前就写入混色，等 attach 后再补一层。
            view.post { reapply(view) }
            return
        }
        reapply(view)
    }

    private fun reapplyAll() {
        val views = synchronized(applied) { seen.keys.toList() }
        views.forEach { reapply(it) }
    }

    private fun reapply(view: View) {
        if (!view.isAttachedToWindow) return
        val colors = readColors(view) ?: return
        val dim = dimColor(view)
        val old = applied[view]
        var changed = false
        if (old != null) {
            changed = colors.removeAll { it.x == old && it.y == BLEND_MODE }
        }
        if (dim != 0) {
            colors.add(Point(dim, BLEND_MODE))
            applied[view] = dim
            changed = true
        } else {
            applied.remove(view)
        }
        if (!changed) return
        runCatching { setMethod?.invoke(view, colors) }.onFailure {
            HookHelper.log("$tag: reapply blend failed", it)
        }
    }

    private fun readColors(view: View): ArrayList<Point>? = runCatching {
        @Suppress("UNCHECKED_CAST")
        (readMethod?.invoke(view) as? ArrayList<Point>)?.let { ArrayList(it) }
    }.onFailure {
        HookHelper.log("$tag: read blend colors failed", it)
    }.getOrNull()

    /** 当前是否应该压暗玻璃；不满足时返回 0。 */
    private fun dimColor(view: View? = null): Int {
        if (keyguardStateKnown) {
            if (!keyguardShowing) return 0
        } else if (!isKeyguardLocked(view)) {
            return 0
        }
        if (!HookPrefs.getBoolean(WallpaperDimKeys.ENABLED, false)) return 0
        if (!isNight()) return 0
        val amount = if (HookPrefs.contains(WallpaperDimKeys.LOCK_AMOUNT)) {
            amount(WallpaperDimKeys.LOCK_AMOUNT)
        } else {
            amount(WallpaperDimKeys.AMOUNT)
        }
        val alpha = (amount * 255f).roundToInt().coerceIn(0, 255)
        if (alpha == 0) return 0
        return Color.argb(alpha, 0, 0, 0)
    }

    private fun isKeyguardLocked(view: View?): Boolean {
        val context = view?.context ?: return false
        return runCatching {
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        }.getOrDefault(false)
    }

    private fun amount(key: String): Float =
        HookPrefs.getFloat(key, WallpaperDimKeys.DEFAULT_AMOUNT).coerceIn(0f, 100f) / 100f

    private fun isNight(): Boolean =
        (Resources.getSystem().configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val applied = WeakHashMap<View, Int>()
    private val seen = WeakHashMap<View, Boolean>()

    @Volatile
    private var keyguardShowing = false

    @Volatile
    private var keyguardStateKnown = false

    companion object {
        private const val HOOK_KEY = "wallpaper_dark_dim_glass"
        private const val KEYGUARD_CONTROLLER =
            "com.android.systemui.statusbar.policy.KeyguardStateControllerImpl"
        private const val BLEND_MODE = 3 // BlendModeToken.SRC_OVER

        private val readMethod by lazy {
            runCatching {
                View::class.java.getDeclaredMethod("getMiBackgroundBlendColors")
                    .apply { isAccessible = true }
            }.getOrNull()
        }

        private val setMethod by lazy {
            runCatching {
                View::class.java.getDeclaredMethod(
                    "setMiBackgroundBlendColors",
                    ArrayList::class.java,
                ).apply { isAccessible = true }
            }.getOrNull()
        }
    }
}
