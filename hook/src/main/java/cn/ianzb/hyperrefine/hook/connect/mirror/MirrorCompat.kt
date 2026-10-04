package cn.ianzb.hyperrefine.hook.connect.mirror

import android.content.Context
import android.graphics.Outline
import android.graphics.Point
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 妙享桌面增强的通用工具（坐标换算、原生尺寸提取、Surface 帧率请求等）。
 */
internal object MirrorCompat {

    const val TYPE_MAGNIFICATION_OVERLAY = 2027

    /**
     * 妙享桌面新旧版本策略（与「妙享桌面增强」`r.a` 一致）。
     *
     * 优先依据目标版本号，未知版本时退回类特征探测。
     */
    fun isModern(classLoader: ClassLoader, versionCode: Long): Boolean {
        val modernClasses = Reflect.findClassIfExists("s3.I", classLoader) != null ||
            Reflect.findClassIfExists("u3.H", classLoader) != null
        val legacyClasses = Reflect.findClassIfExists("r3.J", classLoader) != null ||
            Reflect.findClassIfExists("t3.H", classLoader) != null
        return when {
            versionCode in 1..179_999 -> modernClasses && !legacyClasses
            versionCode >= 180_000 -> modernClasses || !legacyClasses
            else -> modernClasses
        }
    }

    fun dp(context: Context, value: Int): Int =
        Math.round(value * context.resources.displayMetrics.density)

    fun clamp(value: Int, min: Int, max: Int): Int = Math.max(min, Math.min(max, value))

    fun displaySize(context: Context): Point {
        val display = (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay
        val point = Point()
        display.getRealSize(point)
        if (context.resources.configuration.orientation == 2 && point.x < point.y) {
            val tmp = point.x
            point.x = point.y
            point.y = tmp
        }
        return point
    }

    /** 从原生回调参数中提取最后两个正整数作为宽高。 */
    fun extractNativeSize(args: Array<out Any?>?): IntArray? {
        if (args == null || args.isEmpty()) return null
        var last = -1
        var prev = -1
        for (i in args.indices) {
            val value = args[i]
            if (value is Int && value > 0) {
                prev = last
                last = i
            }
        }
        if (prev >= 0 && last >= 0) {
            val width = args[prev] as Int
            val height = args[last] as Int
            if (width > 0 && height > 0) return intArrayOf(width, height)
        }
        return null
    }

    fun scaledSize(width: Int, height: Int, scale: Float, minSide: Int): IntArray {
        val factor = Math.max(0.01f, scale)
        var w = Math.max(1, Math.round(width * factor))
        var h = Math.max(1, Math.round(height * factor))
        val min = Math.min(w, h)
        if (min < minSide) {
            val k = minSide.toFloat() / min
            w = Math.max(1, Math.round(w * k))
            h = Math.max(1, Math.round(h * k))
        }
        return intArrayOf(w, h)
    }

    fun updateWindow(context: Context, state: FloatState) {
        try {
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                .updateViewLayout(state.root, state.lp)
        } catch (t: Throwable) {
            HookHelper.log("MirrorCompat: updateViewLayout failed", t)
        }
    }

    fun requestSurfaceFrameRate(surface: Surface?, fps: Int) {
        if (surface == null || !surface.isValid) return
        runCatching {
            surface.setFrameRate(fps.toFloat(), 0)
            HookHelper.log("MirrorCompat: surface frame rate = $fps")
        }.onFailure { HookHelper.log("MirrorCompat: surface frame rate request failed", it) }
    }

    fun requestSurfaceFrameRate(view: View?, fps: Int) {
        if (view == null) return
        if (view is SurfaceView) {
            runCatching {
                val surface = view.holder.surface
                if (surface != null && surface.isValid) requestSurfaceFrameRate(surface, fps)
            }
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) requestSurfaceFrameRate(view.getChildAt(i), fps)
        }
    }

    /** 在随后的若干时间点反复请求帧率，覆盖 Surface 重建 / 首帧未就绪。 */
    fun scheduleSurfaceConfiguration(view: View?) {
        if (view == null || !MirrorConfig.refreshRateEnabled()) return
        val runnable = Runnable { requestSurfaceFrameRate(view, MirrorConfig.fps()) }
        view.post(runnable)
        for (delay in longArrayOf(16, 50, 100, 250, 500, 1000, 2000, 4000)) {
            view.postDelayed(runnable, delay)
        }
    }

    /** 给视图设置圆角轮廓并裁剪（用于各遮罩层圆角，避免依赖原生按 padding 计算的子轮廓）。 */
    fun applyRoundedOutline(view: View?, radiusPx: Float) {
        if (view == null) return
        runCatching {
            view.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, outline: Outline) {
                    outline.setRoundRect(0, 0, v.width, v.height, radiusPx)
                }
            }
            view.clipToOutline = true
            view.invalidateOutline()
        }.onFailure { HookHelper.log("MirrorCompat: rounded outline failed for ${view.javaClass.name}", it) }
    }

    /** 递归按类名查找视图。 */
    fun findViewByClass(view: View?, className: String): View? {
        if (view == null) return null
        if (view.javaClass.name == className) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findViewByClass(view.getChildAt(i), className)?.let { return it }
            }
        }
        return null
    }

    /** 递归查找原生 `ShadowView`（用于恢复后重新施加阴影）。 */
    fun findShadowView(view: View?): View? {
        if (view == null) return null
        if (view.javaClass.name == "com.xiaomi.mirror.sink.ShadowView") return view
        if (view !is ViewGroup) return null
        for (i in 0 until view.childCount) {
            val found = findShadowView(view.getChildAt(i))
            if (found != null) return found
        }
        return null
    }

    fun setIntFieldIfPresent(obj: Any?, name: String, value: Int) {
        if (obj == null) return
        runCatching { Reflect.findField(obj.javaClass, name).setInt(obj, value) }
            .onFailure { HookHelper.log("MirrorCompat: field $name update failed", it) }
    }

    /** 给视图中所有 [SurfaceView] 注册回调，Surface 创建 / 变化时重新请求帧率。 */
    fun installSurfaceRateCallbacks(view: View?) {
        if (view == null || !MirrorConfig.refreshRateEnabled()) return
        if (view is SurfaceView) {
            val added = synchronized(MirrorFloatStore.surfaceRateCallbacks) {
                MirrorFloatStore.surfaceRateCallbacks.add(view)
            }
            if (added) {
                view.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        requestSurfaceFrameRate(view, MirrorConfig.fps())
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        requestSurfaceFrameRate(view, MirrorConfig.fps())
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
                })
            }
            requestSurfaceFrameRate(view, MirrorConfig.fps())
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) installSurfaceRateCallbacks(view.getChildAt(i))
        }
    }
}
