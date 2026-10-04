package cn.ianzb.hyperrefine.hook.connect.mirror

import android.content.Intent
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.connect.MirrorKeys
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.rule.HookSkippedException
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.HookParam
import cn.ianzb.hyperrefine.hook.xposed.Reflect

/**
 * 自由浮窗 / 下拉通知栏最小化（接收端）。
 *
 * 移植自「妙享桌面增强」（酷安 @ayyya）的 `HookEntry.hookFloatingWindow` 与 `r3.q`：
 * - 接管 `SinkWindow` 的原生窗口尺寸，加入拖动条与收起气泡；
 * - 截获原生窗口参数回调，保持浮窗位置 / 尺寸；
 * - 通知栏下拉时按开关收起投屏。
 *
 * 目标进程：`com.xiaomi.mirror`（接收端 / 平板）。
 */
class MirrorFloatingWindowHook : BaseHook() {

    override val key: String = MirrorKeys.FLOATING_WINDOW

    override val deviceScope: Set<DeviceType> = setOf(DeviceType.PAD, DeviceType.FOLD)

    private lateinit var classLoader: ClassLoader
    private var modern: Boolean = false

    override fun init() {
        classLoader = target.classLoader ?: throw IllegalStateException("$tag: classLoader unavailable")
        if (Reflect.findClassIfExists(SINK_WINDOW, classLoader) == null &&
            Reflect.findClassIfExists(MIRROR_CONTROL_SINK, classLoader) == null
        ) {
            throw HookSkippedException("receiver classes not found")
        }
        modern = MirrorCompat.isModern(classLoader, target.appVersionCode)
        installFloatingWindow()
    }

    private fun installFloatingWindow() {
        val sinkWindow = Reflect.findClassIfExists(SINK_WINDOW, classLoader) ?: return
        val mirrorControlSink = Reflect.findClassIfExists(MIRROR_CONTROL_SINK, classLoader)

        if (modern) {
            hookAfterAll(sinkWindow, "V1") { configureFloatingWindow(it, it.args.toTypedArray()) }
        } else {
            hookAfterAll(sinkWindow, "P1") { configureFloatingWindow(it, it.args.toTypedArray()) }
        }

        Reflect.findClassIfExists(SINK_WINDOW_RECEIVER, classLoader)?.let { receiver ->
            hookBeforeAll(receiver, "onReceive") { param -> onShadeReceive(param) }
        }

        for (name in arrayOf("j1", "k1", "k2")) {
            hookAfterAll(sinkWindow, name) { restoreFloatingPosition(it) }
        }

        hookBeforeAll(sinkWindow, "h1") { param ->
            if (modern && param.args.isEmpty()) return@hookBeforeAll
            val state = MirrorFloatStore.floatingStates[param.thisObject as? View] ?: return@hookBeforeAll
            if (!MirrorConfig.floatingWindow()) return@hookBeforeAll
            state.updateWindowFocusState()
            state.collapse()
        }

        if (modern) {
            hookBeforeAll(sinkWindow, "l1") { param ->
                val state = MirrorFloatStore.floatingStates[param.thisObject as? View]
                if (state != null && MirrorConfig.floatingWindow() && state.mode == 0 && !state.pendingRestore) {
                    state.collapse()
                }
            }
        }

        if (mirrorControlSink != null) {
            hookBeforeAll(mirrorControlSink, "updateMirrorSurface") { param ->
                val surface = param.args.getOrNull(0) as? android.view.Surface
                if (surface != null && MirrorConfig.refreshRateEnabled()) {
                    MirrorCompat.requestSurfaceFrameRate(surface, MirrorConfig.fps())
                }
            }
        }

        // 实际画面区域圆角：SinkView / SinkTrustAuthView 通过 setRoundCorner(宽 * 比例) 设置，
        // 统一替换为用户选择的圆角（dp），并覆盖原生后续的重算。
        for (name in arrayOf(SINK_VIEW, SINK_TRUST_AUTH_VIEW)) {
            Reflect.findClassIfExists(name, classLoader)?.let { clazz ->
                hookBeforeAll(clazz, "setRoundCorner") { param ->
                    if (!MirrorConfig.floatingWindow()) return@hookBeforeAll
                    val view = param.thisObject as? View ?: return@hookBeforeAll
                    if (param.args.isEmpty()) return@hookBeforeAll
                    param.setArg(0, MirrorCompat.dp(view.context, MirrorConfig.floatingRadius()).toFloat())
                }
            }
        }

        for (name in arrayOf("com.xiaomi.mirror.pole.ViewOnTouchListenerC3392a", "com.xiaomi.mirror.pole.a")) {
            Reflect.findClassIfExists(name, classLoader)?.let { hookBubbleControllerClass(it) }
        }

        Reflect.findClassIfExists("android.view.WindowManagerImpl", null)?.let { wmImpl ->
            hookBeforeAll(wmImpl, "addView") { applyPreferredRefreshRate(it.args.toTypedArray()) }
            hookBeforeAll(wmImpl, "updateViewLayout") { onUpdateViewLayout(it) }
        }

        // 系统阴影圆角：SmoothFrameLayout2 调用 SurfaceControl.Transaction#setMiShadow 时圆角写死 20dp，
        // 这里替换为设置的圆角。参数顺序：SurfaceControl,int,float,float,float,float,float(半径),RectF。
        Reflect.findClassIfExists("android.view.SurfaceControl\$Transaction", null)?.let { transaction ->
            hookBeforeAll(transaction, "setMiShadow") { param ->
                if (!MirrorConfig.floatingWindow()) return@hookBeforeAll
                val args = param.args
                if (args.size >= 7 && args[6] is Float) {
                    val density = android.content.res.Resources.getSystem().displayMetrics.density
                    param.setArg(6, MirrorConfig.floatingRadius() * density)
                }
            }
        }

        val dispatchTouch = ViewGroup::class.java.getDeclaredMethod("dispatchTouchEvent", MotionEvent::class.java)
        HookHelper.hookBefore(dispatchTouch) { param ->
            val state = MirrorFloatStore.rootStates[param.thisObject] ?: return@hookBefore
            if (!MirrorConfig.floatingWindow()) return@hookBefore
            val event = param.args.getOrNull(0) as? MotionEvent ?: return@hookBefore
            if (state.onGesture(event)) param.setResultValue(true)
        }

        HookHelper.log("$tag: floating window hooks installed (modern=$modern)")
    }

    // ---------------- 回调 ----------------

    private fun onShadeReceive(param: HookParam) {
        if (MirrorConfig.minimizeOnShade()) return
        val intent = param.args.getOrNull(1) as? Intent ?: return
        if (intent.action == "com.android.systemui.fsgesture" && intent.getBooleanExtra("isEnter", true)) {
            param.setResultValue(null)
            HookHelper.log("$tag: ignored SystemUI shade collapse broadcast")
        }
    }

    private fun restoreFloatingPosition(param: HookParam) {
        if (!MirrorConfig.floatingWindow()) return
        val view = param.thisObject as? View ?: return
        val state = MirrorFloatStore.floatingStates[view] ?: return
        state.reconcile(param.args.toTypedArray())
        if (state.busy) return
        state.applyLayout(state.lp)
    }

    private fun applyPreferredRefreshRate(args: Array<out Any?>) {
        if (!MirrorConfig.refreshRateEnabled()) return
        if (args.size < 2) return
        val params = args[1] as? WindowManager.LayoutParams ?: return
        if (params.type == MirrorCompat.TYPE_MAGNIFICATION_OVERLAY) {
            params.preferredRefreshRate = MirrorConfig.fps().toFloat()
        }
    }

    private fun onUpdateViewLayout(param: HookParam) {
        applyPreferredRefreshRate(param.args.toTypedArray())
        val args = param.args
        if (args.size < 2) return
        val root = args[0] as? View ?: return
        val params = args[1] as? WindowManager.LayoutParams ?: return
        val state = MirrorFloatStore.rootStates[root] ?: return
        if (!MirrorConfig.floatingWindow()) return
        if (!state.busy) {
            state.applyLayout(params)
            return
        }
        val content = state.view
        params.alpha = 0f
        if (MirrorConfig.refreshRateEnabled()) params.preferredRefreshRate = MirrorConfig.fps().toFloat()
        if (state.pendingRestore) {
            params.x = state.expandedX
            params.y = state.expandedY
            params.width = state.winW
            params.height = state.winH
            val contentLp = content.layoutParams
            if (contentLp != null && (contentLp.width != state.sinkW || contentLp.height != state.sinkH)) {
                contentLp.width = state.sinkW
                contentLp.height = state.sinkH
                content.layoutParams = contentLp
            }
        }
    }

    // ---------------- 浮窗构建 ----------------

    private fun configureFloatingWindow(param: HookParam, args: Array<out Any?>) {
        if (!MirrorConfig.floatingWindow()) return
        val sink = param.thisObject as? View ?: return
        try {
            val content = Reflect.getObjectField(sink, "a") as? ViewGroup ?: return
            val lp = findWindowParams(sink) ?: return
            if (lp.type != MirrorCompat.TYPE_MAGNIFICATION_OVERLAY) return

            val existing = MirrorFloatStore.floatingStates[sink]
            if (existing != null) {
                existing.reconcile(args)
                existing.applyLayout(existing.lp)
                existing.applyWindowRadius()
                for (delay in RADIUS_REAPPLY_DELAYS) content.postDelayed({ existing.applyWindowRadius(false) }, delay)
                MirrorCompat.installSurfaceRateCallbacks(content)
                MirrorCompat.scheduleSurfaceConfiguration(content)
                return
            }

            val hover: View? = content.findViewById(
                content.resources.getIdentifier("btn_hover", "id", TARGET_PACKAGE)
            )
            if (hover == null) {
                HookHelper.log("$tag: drag handle not found")
                return
            }
            hover.visibility = View.VISIBLE

            val size = MirrorCompat.displaySize(sink.context)
            val viewLp = sink.layoutParams
            var nativeW = Math.max(1, viewLp?.width ?: 1)
            var nativeH = Math.max(1, viewLp?.height ?: 1)
            MirrorCompat.extractNativeSize(args)?.let {
                nativeW = it[0]
                nativeH = it[1]
            }
            val scaled = MirrorCompat.scaledSize(
                nativeW, nativeH,
                Math.min(0.7f, Math.min(size.x * 0.88f / nativeW, size.y * 0.88f / nativeH)),
                360,
            )
            val sinkW = scaled[0]
            val sinkH = scaled[1]
            val extraW = Math.max(0, lp.width - nativeW)
            val extraH = Math.max(0, lp.height - nativeH)
            viewLp?.let {
                it.width = sinkW
                it.height = sinkH
                sink.layoutParams = it
            }
            lp.width = extraW + sinkW
            lp.height = extraH + sinkH
            val x = Math.max(0, size.x - lp.width)
            val y = Math.round(Math.max(0, size.y - lp.height) * 0.5f)
            lp.x = x
            lp.y = y

            val state = FloatState(content, sink, lp, x, y, lp.width, lp.height, sinkW, sinkH, size)
            state.nativeW = nativeW
            state.nativeH = nativeH
            state.restoreState()

            MirrorFloatStore.floatingStates[sink] = state
            MirrorFloatStore.rootStates[content] = state
            MirrorFloatStore.activeFloatingState = state

            if (!state.listenersInstalled) {
                state.listenersInstalled = true
                content.viewTreeObserver.addOnGlobalLayoutListener {
                    state.updateWindowFocusState()
                    if (MirrorConfig.minimizeOnShade() && state.windowNotFocused && state.mode == 0 && !state.busy) {
                        state.collapseViaNative()
                    }
                }
                content.viewTreeObserver.addOnWindowFocusChangeListener { focused ->
                    if (focused) {
                        state.windowNotFocused = false
                    } else {
                        content.post { state.updateWindowFocusState() }
                    }
                }
            }

            hover.visibility = View.GONE
            state.applyWindowRadius()
            for (delay in RADIUS_REAPPLY_DELAYS) content.postDelayed({ state.applyWindowRadius(false) }, delay)
            state.dragHandle.setOnTouchListener { _, event -> state.handleTouch(event) }

            findNativeBubbleController(sink)?.let { controller ->
                hookBubbleControllerClass(controller.javaClass)
                runCatching { Reflect.callMethod(controller, "j") }
                MirrorFloatStore.bubbleControllerStates[controller] = state
                state.bubbleController = controller
            }

            MirrorCompat.updateWindow(sink.context, state)
            MirrorCompat.installSurfaceRateCallbacks(content)
            MirrorCompat.scheduleSurfaceConfiguration(content)
            HookHelper.log("$tag: floating window ${lp.width}x${lp.height} at $x,$y")
        } catch (t: Throwable) {
            HookHelper.log("$tag: failed to configure floating window", t)
        }
    }

    private fun findWindowParams(obj: Any): WindowManager.LayoutParams? {
        for (name in arrayOf("x1", "y1")) {
            val value = runCatching { Reflect.getObjectField(obj, name) }.getOrNull()
            if (value is WindowManager.LayoutParams) return value
        }
        var cls: Class<*>? = obj.javaClass
        while (cls != null) {
            for (field in cls.declaredFields) {
                if (WindowManager.LayoutParams::class.java.isAssignableFrom(field.type)) {
                    field.isAccessible = true
                    val value = runCatching { field.get(obj) }.getOrNull()
                    if (value is WindowManager.LayoutParams) return value
                }
            }
            cls = cls.superclass
        }
        return null
    }

    private fun findNativeBubbleController(obj: Any): Any? {
        for (name in arrayOf("V2", "y2")) {
            val field = runCatching { Reflect.getObjectField(obj, name) }.getOrNull() ?: continue
            val methods = field.javaClass.declaredMethods.map { it.name }.toHashSet()
            if (("p" in methods || "q" in methods) && "j" in methods && "l" in methods) {
                HookHelper.log("$tag: native bubble controller field = $name")
                return field
            }
        }
        HookHelper.log("$tag: native bubble controller not found")
        return null
    }

    private fun hookBubbleControllerClass(cls: Class<*>?) {
        if (cls == null) return
        val added = synchronized(MirrorFloatStore.bubbleControllerHooked) {
            MirrorFloatStore.bubbleControllerHooked.add(cls)
        }
        if (!added) return
        for (method in cls.declaredMethods) {
            when (method.name) {
                "q", "p" -> HookHelper.hookBefore(method) { bubbleTouch(it) }
                "j" -> HookHelper.hookBefore(method) { bubbleCommitRestore(it) }
            }
        }
    }

    private fun bubbleTouch(param: HookParam) {
        var state = MirrorFloatStore.bubbleControllerStates[param.thisObject]
        if (state == null) state = MirrorFloatStore.pendingCollapseState()
        if (state == null) state = MirrorFloatStore.stateForBubbleController(param.thisObject)
        val name = (param.executable as? java.lang.reflect.Method)?.name ?: "?"
        if (name == "p" && param.args.isNotEmpty()) return
        if (state == null || !MirrorConfig.floatingWindow()) return
        when {
            state.mode == 0 && !state.pendingCollapse && SystemClock.uptimeMillis() < state.restoreGraceUntil ->
                param.setResultValue(null)
            !state.pendingRestore -> {
                if (!state.pendingCollapse) {
                    if (state.mode != 1) {
                        state.pendingCollapse = true
                        state.completeNativeCollapse()
                    }
                    param.setResultValue(null)
                } else {
                    state.completeNativeCollapse()
                    param.setResultValue(null)
                }
            }
            else -> {
                state.pendingCollapse = false
                param.setResultValue(null)
            }
        }
    }

    private fun bubbleCommitRestore(param: HookParam) {
        val state = MirrorFloatStore.bubbleControllerStates[param.thisObject]
            ?: MirrorFloatStore.activeFloatingState
            ?: return
        if (MirrorConfig.floatingWindow() && state.pendingRestore && !state.suppress) {
            state.completeRestore(false)
        }
    }

    private fun hookAfterAll(clazz: Class<*>, name: String, callback: (HookParam) -> Unit) {
        clazz.declaredMethods.filter { it.name == name }.forEach { HookHelper.hookAfter(it, callback = callback) }
    }

    private fun hookBeforeAll(clazz: Class<*>, name: String, callback: (HookParam) -> Unit) {
        clazz.declaredMethods.filter { it.name == name }.forEach { HookHelper.hookBefore(it, callback = callback) }
    }

    private companion object {
        const val TARGET_PACKAGE = "com.xiaomi.mirror"
        const val SINK_WINDOW = "com.xiaomi.mirror.sink.SinkWindow"
        const val SINK_WINDOW_RECEIVER = "com.xiaomi.mirror.sink.SinkWindow\$t"
        const val SINK_VIEW = "com.xiaomi.mirror.sink.SinkView"
        const val SINK_TRUST_AUTH_VIEW = "com.xiaomi.mirror.trust.widget.SinkTrustAuthView"
        const val MIRROR_CONTROL_SINK = "com.xiaomi.mirrorcontrol.MirrorControlSink"

        /** 圆角在布局 / 原生重算后再补几次，避免被覆盖。 */
        val RADIUS_REAPPLY_DELAYS = longArrayOf(0, 150, 400, 900, 1600)
    }
}
