package cn.ianzb.hyperrefine.hook.systemui.wallpaper

import android.app.Application
import android.app.KeyguardManager
import android.content.ComponentCallbacks
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.reflect.Method
import java.util.ArrayList
import java.util.WeakHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * 让「取壁纸做模糊」的界面跟随壁纸压暗。
 *
 * 压暗壁纸走 `WallpaperManager#setWallpaperDimAmount`：它调低壁纸图层的 alpha、露出黑底，只在
 * **合成**阶段生效。系统取壁纸做模糊的链路读的是壁纸**纹理**本身（壁纸图层快照、壁纸预览位图），
 * 看不到这层 alpha，于是控制中心、负一屏的模糊背景会停在压暗前的亮度。
 *
 * 官方模糊的叠色层（`View#setMiBackgroundBlendColors`，画在模糊之上）正好可以补这一层：在最前面插入
 * 同浓度的黑色。SystemUI 使用通知栏 / 控制中心共用的 `blur_blend_container`。
 * 锁屏时取锁屏压暗值，解锁后仅在桌面或负一屏上方应用桌面值。
 *
 * 只调用系统自身的接口，不复制任何系统代码。
 */
internal object WallpaperDimBlurDim {

    private const val TAG = "WallpaperDimBlurDim"
    private const val SYSTEM_UI = "com.android.systemui"
    private const val SET_BLEND = "setMiBackgroundBlendColors"
    private const val ADD_BLEND = "addMiBackgroundBlendColor"
    private const val CLEAR_BLEND = "clearMiBackgroundBlendColor"
    private const val SET_RADIUS = "setMiBackgroundBlurRadius"
    private const val SET_MODE = "setMiBackgroundBlurMode"
    private const val SET_GLASS_RADIUS = "setMiGlassBlurRadius"

    /** android.graphics.BlendMode 常量：SRC_OVER。 */
    private const val SRC_OVER = 3

    /** 背景就是壁纸的界面（桌面 / 负一屏）。 */
    private val WALLPAPER_PACKAGES = setOf("com.miui.home", "com.miui.personalassistant")

    private const val TOP_QUERY_INTERVAL_MS = 800L

    @Volatile
    private var dim = 0f
    @Volatile
    private var lockDim = 0f
    @Volatile
    private var keyguardShowing = false

    /** 查询不可用时保留上一次判断。 */
    private val wallpaperBehind = AtomicBoolean(false)

    private val installed = AtomicBoolean()
    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "HyperRefine-WallpaperDim").apply { isDaemon = true }
    }

    private val lastQuery = AtomicLong(0L)
    private val querying = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    /** 已接上深浅色回调的应用，避免重复注册。 */
    private val tracked =
        java.util.Collections.newSetFromMap(WeakHashMap<Application, Boolean>())

    /** 各模糊容器最近一次的「宿主叠色」（不含本类插入的压暗层）、已插入的压暗层与已写入结果。 */
    private val hostColors = WeakHashMap<View, ArrayList<Point>>()
    private val injected = WeakHashMap<View, Point>()
    private val applied = WeakHashMap<View, Float>()

    private val idNames = HashMap<String, String?>()
    private val writing = ThreadLocal<Boolean>()

    private var setBlendMethod: Method? = null

    /**
     * 安装框架 `View` 模糊接口的挂钩并跟随深浅色 / 滑条，同一进程只需一次。
     *
     * @param application 目标应用已创建的 Application；为 null 时挂钩 `Application#onCreate` 等待。
     */
    fun install(application: Application?) {
        if (application != null) {
            track(application)
            installHooks()
            return
        }
        runCatching {
            val cls = Class.forName("android.app.Application")
            val onCreate = cls.declaredMethods
                .firstOrNull { it.name == "onCreate" && it.parameterCount == 0 }
                ?: return@runCatching
            onCreate.isAccessible = true
            HookHelper.hookAfter(onCreate) { param ->
                (param.thisObject as? Application)?.let { app ->
                    track(app)
                    installHooks()
                }
            }
        }.onFailure { HookHelper.log("$TAG: hook Application#onCreate failed", it) }
    }

    /** 深浅色切换 / 滑条变化后按新值重写全部已知容器。 */
    private fun update(context: Context) {
        val home = currentDim(context, WallpaperDimKeys.AMOUNT)
        val lock = if (HookPrefs.contains(WallpaperDimKeys.LOCK_AMOUNT)) {
            currentDim(context, WallpaperDimKeys.LOCK_AMOUNT)
        } else home
        if (home == dim && lock == lockDim) return
        dim = home
        lockDim = lock
        main.post { refresh() }
    }

    internal fun onKeyguardChanged(showing: Boolean) {
        if (showing == keyguardShowing) return
        keyguardShowing = showing
        if (!showing) queryWallpaperBehind(force = true)
        main.post { refresh() }
    }

    private fun currentDim(context: Context, key: String): Float {
        if (!HookPrefs.getBoolean(WallpaperDimKeys.ENABLED, false)) return 0f
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (!night) return 0f
        return HookPrefs.getFloat(key, WallpaperDimKeys.DEFAULT_AMOUNT)
            .coerceIn(0f, 100f) / 100f
    }

    @Suppress("OVERRIDE_DEPRECATION")
    private fun track(app: Application) {
        appContext = app
        if (!tracked.add(app)) return
        if (app.packageName == SYSTEM_UI) {
            keyguardShowing = runCatching {
                app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            }.getOrDefault(false)
        }
        runCatching {
            app.registerComponentCallbacks(object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) = update(app)

                override fun onLowMemory() {}
            })
        }.onFailure { HookHelper.log("$TAG: registerComponentCallbacks failed", it) }
        runCatching { HookPrefs.registerListener { update(app) } }
            .onFailure { HookHelper.log("$TAG: prefs listener unavailable", it) }
        update(app)
        queryWallpaperBehind(force = true)
    }

    private fun installHooks() {
        if (!installed.compareAndSet(false, true)) return
        val view = View::class.java
        setBlendMethod = runCatching {
            view.getMethod(SET_BLEND, ArrayList::class.java).also { it.isAccessible = true }
        }.getOrNull()
        if (setBlendMethod == null) {
            HookHelper.log("$TAG: $SET_BLEND not found, blur dim disabled")
            return
        }
        hookSetBlend()
        hookClearBlend(view)
        hookAddBlend(view)
        // 宿主可能只开模糊、不写叠色：任一模糊参数生效时补上压暗层（按压暗结果去重，不逐帧写）。
        hookBlurSetter(view, SET_RADIUS) { it > 0 }
        hookBlurSetter(view, SET_MODE) { it != 0 }
        hookBlurSetter(view, SET_GLASS_RADIUS) { it > 0 }
    }

    private fun hookSetBlend() {
        val method = setBlendMethod ?: return
        runCatching {
            HookHelper.hookBefore(method) { param ->
                val target = param.thisObject as? View ?: return@hookBefore
                if (writing.get() == true || !isBlurContainer(target)) return@hookBefore
                val host = copyOf(param.args[0]) ?: return@hookBefore
                val stripped = record(target, host)
                queryWallpaperBehind()
                val effective = effectiveDim()
                synchronized(applied) { applied[target] = effective }
                if (effective <= 0f) {
                    synchronized(injected) { injected.remove(target) }
                    if (stripped) param.setArg(0, host)
                    return@hookBefore
                }
                param.setArg(0, withDim(target, host, effective))
            }
        }.onFailure { HookHelper.log("$TAG: hook $SET_BLEND failed", it) }
    }

    private fun hookClearBlend(view: Class<*>) {
        val method = runCatching { view.getMethod(CLEAR_BLEND).also { it.isAccessible = true } }
            .getOrNull() ?: return
        runCatching {
            HookHelper.hookAfter(method) { param ->
                val target = param.thisObject as? View ?: return@hookAfter
                if (writing.get() == true || !isBlurContainer(target)) return@hookAfter
                val host = ArrayList<Point>()
                record(target, host)
                if (effectiveDim() > 0f) write(target, host)
                else {
                    synchronized(applied) { applied.remove(target) }
                    synchronized(injected) { injected.remove(target) }
                }
            }
        }.onFailure { HookHelper.log("$TAG: hook $CLEAR_BLEND failed", it) }
    }

    private fun hookAddBlend(view: Class<*>) {
        val int = Int::class.javaPrimitiveType!!
        val method = Reflect.findMethodIfExists(view, ADD_BLEND, int, int) ?: return
        val read = Reflect.findMethodIfExists(view, "getMiBackgroundBlendColors") ?: return
        runCatching {
            HookHelper.hookAfter(method) { param ->
                val target = param.thisObject as? View ?: return@hookAfter
                if (writing.get() == true || !isBlurContainer(target)) return@hookAfter
                val colors = synchronized(hostColors) { hostColors[target]?.let(::ArrayList) }
                val host = if (colors != null) {
                    val color = param.args[0] as? Int ?: return@hookAfter
                    val mode = param.args[1] as? Int ?: return@hookAfter
                    colors.apply { add(Point(color, mode)) }
                } else {
                    copyOf(read.invoke(target)) ?: return@hookAfter
                }
                record(target, host)
                queryWallpaperBehind()
                if (effectiveDim() > 0f || (appliedOf(target) ?: 0f) > 0f) write(target, host)
            }
        }.onFailure { HookHelper.log("$TAG: hook $ADD_BLEND failed", it) }
    }

    /** 模糊参数（半径 / 模式）出现时补写压暗层；参数会随展开动画逐帧更新，故按结果去重。 */
    private fun hookBlurSetter(view: Class<*>, name: String, active: (Int) -> Boolean) {
        val method = runCatching { view.getMethod(name, Int::class.javaPrimitiveType) }
            .getOrNull() ?: return
        runCatching {
            HookHelper.hookAfter(method) { param ->
                val target = param.thisObject as? View ?: return@hookAfter
                if (!isBlurContainer(target) || !active(param.args[0] as? Int ?: 0)) return@hookAfter
                queryWallpaperBehind()
                val effective = effectiveDim()
                val previous = appliedOf(target) ?: 0f
                if (previous == effective) return@hookAfter
                val host = hostColorsOf(target)
                synchronized(hostColors) { hostColors.putIfAbsent(target, host) }
                write(target, host)
            }
        }.onFailure { HookHelper.log("$TAG: hook $name failed", it) }
    }

    private fun refresh() {
        val targets = synchronized(hostColors) { hostColors.keys.toList() }
        targets.forEach { view -> write(view, hostColorsOf(view)) }
    }

    /** 把容器当前的叠色写成「宿主叠色 + 压暗层」（压暗为 0 时恢复宿主原样）。 */
    private fun write(view: View, host: ArrayList<Point>) {
        val method = setBlendMethod ?: return
        val effective = effectiveDim()
        val value = if (effective <= 0f) {
            synchronized(injected) { injected.remove(view) }
            host
        } else withDim(view, host, effective)
        writing.set(true)
        try {
            method.invoke(view, value)
            synchronized(applied) { applied[view] = effective }
        } catch (error: Exception) {
            HookHelper.log("$TAG: apply dim failed", error)
        } finally {
            writing.remove()
        }
    }

    /** 生效压暗值：开关 / 深浅色给出的压暗值，只在「背后是壁纸」的界面上使用。 */
    private fun effectiveDim(): Float = when {
        keyguardShowing -> lockDim
        wallpaperBehind.get() -> dim
        else -> 0f
    }

    private fun dimPoint(value: Float): Point =
        Point(((value * 255f).roundToInt().coerceIn(1, 255)) shl 24, SRC_OVER)

    /** 在最前面插入压暗层，并记住这一层以便之后精确移除。 */
    private fun withDim(view: View, host: ArrayList<Point>, value: Float): ArrayList<Point> {
        val point = dimPoint(value)
        synchronized(injected) { injected[view] = point }
        val result = ArrayList<Point>(host.size + 1)
        result.add(point)
        result.addAll(host)
        return result
    }

    private fun copyOf(value: Any?): ArrayList<Point>? {
        @Suppress("UNCHECKED_CAST")
        val list = value as? ArrayList<Point> ?: return null
        return ArrayList(list)
    }

    private fun record(view: View, host: ArrayList<Point>): Boolean {
        val mine = synchronized(injected) { injected[view] }
        val stripped = mine != null && host.firstOrNull() === mine
        if (stripped) host.removeAt(0)
        synchronized(hostColors) { hostColors[view] = host }
        return stripped
    }

    private fun hostColorsOf(view: View): ArrayList<Point> =
        synchronized(hostColors) { hostColors[view]?.let { ArrayList(it) } ?: ArrayList() }

    private fun appliedOf(view: View): Float? = synchronized(applied) { applied[view] }

    /** 模糊容器：id 名含 `blur`（通知栏 / 控制中心为 `blur_blend_container`）。 */
    private fun isBlurContainer(view: View): Boolean {
        val id = view.id
        if (id == View.NO_ID) return false
        val resources = view.resources
        val key = runCatching { resources.getResourcePackageName(id) + "#" + id }.getOrNull()
            ?: return false
        val name = synchronized(idNames) {
            if (idNames.containsKey(key)) return@synchronized idNames[key]
            val resolved = runCatching { resources.getResourceEntryName(id) }.getOrNull()
            idNames[key] = resolved
            resolved
        }
        return if (appContext?.packageName == SYSTEM_UI) name == "blur_blend_container"
        else name?.contains("blur", ignoreCase = true) == true
    }

    internal fun managesSystemUiContainer(view: View): Boolean =
        setBlendMethod != null && appContext?.packageName == SYSTEM_UI && isBlurContainer(view)

    /**
     * 刷新「最上层是不是桌面 / 负一屏」的判断。
     *
     * 在应用之上打开控制中心 / 通知栏时，模糊的是应用内容而不是壁纸，此时不应压暗。
     * 查询在后台线程做并按间隔节流；查不到（接口不可用）时保持上一次判断。
     */
    private fun queryWallpaperBehind(force: Boolean = false) {
        val context = appContext ?: return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastQuery.get() < TOP_QUERY_INTERVAL_MS) return
        if (!querying.compareAndSet(false, true)) return
        lastQuery.set(now)
        executor.execute {
            val top = topPackage(context)
            querying.set(false)
            if (top == null) return@execute
            val next = top in WALLPAPER_PACKAGES
            if (wallpaperBehind.getAndSet(next) != next) main.post { refresh() }
        }
    }

    private fun topPackage(context: Context): String? {
        val info = runCatching {
            context.getSystemService(Context.ACTIVITY_SERVICE)?.let {
                Reflect.callMethod(it, "getFocusedRootTaskInfo")
            }
        }.getOrNull() ?: runCatching {
            val type = Reflect.findClass("android.app.ActivityTaskManager", context.classLoader)
            val service = Reflect.callStaticMethod(type, "getService") ?: return@runCatching null
            Reflect.callMethod(service, "getFocusedRootTaskInfo")
        }.getOrNull() ?: return null
        return runCatching { (Reflect.getObjectField(info, "topActivity") as? ComponentName)?.packageName }
            .getOrNull()
    }
}
