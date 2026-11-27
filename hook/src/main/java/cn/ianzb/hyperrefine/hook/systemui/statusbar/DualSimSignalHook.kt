package cn.ianzb.hyperrefine.hook.systemui.statusbar

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import io.github.libxposed.api.XposedInterface.HookHandle
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Event-driven native-view composition; no polling, telephony changes, or copied signal assets. */
class DualSimSignalHook : BaseHook() {
    override val key = StatusBarKeys.DUAL_MODE
    override fun init() {
        installation.installOnce { installFor(target.classLoader) }
    }

    private fun installFor(loader: ClassLoader?) {
        val binder = Reflect.findClass(StatusBarViews.MOBILE_BINDER, loader)
        val binds = StatusBarMembers.methods(binder, "bind") {
            it.parameterCount == 4 && ViewGroup::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                it.parameterTypes[1].name == "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModel" &&
                it.parameterTypes[2].name == "com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MiuiMobileIconViewModel" &&
                it.parameterTypes[3].name == "com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger"
        }
        val adapter = Reflect.findClass("com.android.systemui.statusbar.pipeline.mobile.ui.MobileUiAdapter", loader)
        val starts = StatusBarMembers.methods(adapter, "start") { it.parameterCount == 0 }
        val collector = Reflect.findClass(StatusBarViews.MOBILE_BINDER + "\$bind\$1\$1\$4\$2", loader)
        val icon = StatusBarMembers.field(collector, "\$mobile")
        require(ImageView::class.java.isAssignableFrom(icon.type))
        val emits = StatusBarMembers.methods(collector, "emit") {
            it.parameterCount == 2 && it.parameterTypes[0] == Any::class.java &&
                it.parameterTypes[1].name == "kotlin.coroutines.Continuation"
        }
        val themeCollector = Reflect.findClass(StatusBarViews.MOBILE_BINDER + "\$bind\$1\$1\$26\$2", loader)
        val themeIcon = StatusBarMembers.field(themeCollector, "\$mobile")
        require(ImageView::class.java.isAssignableFrom(themeIcon.type))
        val themeEmits = StatusBarMembers.methods(themeCollector, "emit") {
            it.parameterCount == 2 && it.parameterTypes[0] == Any::class.java &&
                it.parameterTypes[1].name == "kotlin.coroutines.Continuation"
        }
        val tintCollector = Reflect.findClass(StatusBarViews.MOBILE_BINDER + "\$bind\$1\$1\$5\$1", loader)
        val tintViews = StatusBarMembers.field(tintCollector, "\$tintViewList")
        require(List::class.java.isAssignableFrom(tintViews.type))
        val tintUpdates = StatusBarMembers.methods(tintCollector, "invokeSuspend") {
            it.parameterTypes.contentEquals(arrayOf(Any::class.java)) && it.returnType == Any::class.java
        }
        val runtime = DualSignalRuntime(DualSimFlowObserver())
        val guard = StatusBarGuard(tag)
        val installs = mutableListOf<() -> HookHandle>()
        binds.forEach { method -> installs += { StatusBarMembers.after(method) { param -> guard.run {
            val root = param.args.firstOrNull() as? ViewGroup ?: return@run
            // bind(root, locationModel, miuiModel, logger): the location wrapper has no cell flows.
            val model = param.args.getOrNull(2) ?: return@run
            if (StatusBarViews.isStatusBarMobile(root, model)) runtime.bind(root, model)
        } } } }
        starts.forEach { method -> installs += { StatusBarMembers.after(method) { param -> guard.run {
            param.thisObject?.let(runtime::adapter)
        } } } }
        emits.forEach { method -> installs += { StatusBarMembers.after(method) { param -> guard.run {
            val instance = param.thisObject ?: return@run
            val image = icon.get(instance) as? ImageView ?: return@run
            runtime.emitted(image)
        } } } }
        themeEmits.forEach { method -> installs += { StatusBarMembers.after(method) { param -> guard.run {
            val instance = param.thisObject ?: return@run
            (themeIcon.get(instance) as? ImageView)?.let(runtime::emitted)
        } } } }
        tintUpdates.forEach { method -> installs += { StatusBarMembers.after(method) { param -> guard.run {
            val instance = param.thisObject ?: return@run
            (tintViews.get(instance) as? List<*>)?.filterIsInstance<ImageView>()?.forEach(runtime::emitted)
        } } } }
        StatusBarMembers.install(installs)
    }

    private companion object {
        val installation = DualSimInstallation()
    }
}

private class DualSignalRuntime(private val flows: DualSimFlowObserver) {
    private class Row(root: ViewGroup, image: ImageView, val subId: Int) {
        val root = WeakReference(root)
        val image = WeakReference(image)
        var native: Drawable? = null
        var hiddenByModule = false
        var nativeVisibility = root.visibility
        var nativeColor = Color.WHITE
        var stacked: StackedSignalDrawable? = null
        val parent = WeakReference(image.parent as? ViewGroup)
        val originalClipChildren = (image.parent as? ViewGroup)?.clipChildren ?: true
        val originalClipPadding = (image.parent as? ViewGroup)?.clipToPadding ?: true
        val group = WeakReference(StatusBarViews.find(root, "mobile_group") as? ViewGroup)
        val originalLeft = group.get()?.paddingLeft ?: 0
        val originalRight = group.get()?.paddingRight ?: 0
        var styled = false
    }

    private val rows = WeakHashMap<ViewGroup, Row>() // Accessed on the UI thread only.
    private val observed = WeakHashMap<Any, Boolean>()
    private val signals = mutableMapOf<Int, StatusBarPolicy.Signal>()
    private val slotIndexes = mutableMapOf<Int, Int>()
    private val signalLevels = mutableMapOf<Int, Int?>()
    private var dataSubId = -1
    private val handler = Handler(Looper.getMainLooper())
    private val guard = StatusBarGuard("DualSimSignalHook")
    private val refreshGate = DualSimRefreshGate({ callback -> handler.post { callback() } }, {
        guard.run { refresh() }
    })

    fun adapter(adapter: Any) {
        val hdController = Reflect.getObjectField(adapter, "hdController") ?: error("No mobile HD controller")
        val javaAdapter = Reflect.getObjectField(hdController, "javaAdapter") ?: error("No mobile JavaAdapter")
        flows.attach(javaAdapter)
        val provider = Reflect.getObjectField(adapter, "mobileIconsViewModel") ?: error("No mobile model provider")
        val model = Reflect.callMethod(provider, "get") ?: error("No mobile icons model")
        val interactor = runCatching {
            Reflect.callMethod(Reflect.getObjectField(model, "miuiIntsLazy")!!, "get")
        }.getOrNull() ?: Reflect.getObjectField(model, "miuiInt") ?: error("No mobile interactor")
        val repository = Reflect.getObjectField(interactor, "mobileConnectionsRepo") ?: error("No mobile repository")
        val source = Reflect.getObjectField(repository, "activeMobileDataSubscriptionId") ?: error("No active SIM flow")
        val initial = flows.value(source) as? Int ?: -1
        synchronized(signals) { dataSubId = initial }
        flows.observe(source) { value ->
            (value as? Int)?.let { synchronized(signals) { dataSubId = it }; schedule() }
        }
    }

    fun bind(root: ViewGroup, model: Any) {
        if (rows.containsKey(root)) return
        val image = StatusBarViews.find(root, "mobile_signal") as? ImageView ?: error("No native signal image")
        val subId = Reflect.getObjectField(root, "subId") as? Int ?: error("No SIM subscription id")
        val cell = runCatching { Reflect.callMethod(model, "getCellProvider") }.getOrNull() ?: model
        val signalFlow = Reflect.getObjectField(cell, "signalIconId") ?: error("No native signal flow")
        val visibleFlow = runCatching { Reflect.getObjectField(model, "isVisible") }.getOrNull()
            ?: Reflect.getObjectField(cell, "isVisible") ?: error("No native signal visibility")
        val providerFlow = Reflect.getObjectField(model, "vmProvider") ?: error("No active mobile model flow")
        val satelliteFlow = Reflect.getObjectField(cell, "satelliteVisible") ?: error("No satellite visibility flow")
        val interactor = Reflect.getObjectField(model, "iconInteractor") ?: error("No SIM slot interactor")
        val slotFlow = Reflect.getObjectField(interactor, "phoneId") ?: error("No SIM slot flow")
        synchronized(signals) { slotIndexes.putIfAbsent(subId, flows.value(slotFlow) as? Int ?: -1) }
        val resource = flows.value(signalFlow) as? Int ?: error("Unexpected signal flow value")
        val visibility = StatusBarMembers.mobileVisibility(flows.value(visibleFlow)) ?: error("Unexpected visibility flow value")
        val initial = StatusBarPolicy.Signal(subId, resource, visibility.signal, visibility.root,
            flows.value(providerFlow) === cell,
            flows.value(satelliteFlow) as? Boolean ?: error("Unexpected satellite visibility"))
        val row = Row(root, image, subId)
        capture(row, image)
        if (!observed.containsKey(model)) {
            flows.observe(signalFlow) { value -> guard.run {
                val res = value as? Int ?: return@run
                synchronized(signals) {
                    signals[subId] = (signals[subId] ?: initial).copy(resourceId = res)
                }
                schedule()
            } }
            flows.observe(visibleFlow) { value -> guard.run {
                val shown = StatusBarMembers.mobileVisibility(value) ?: return@run
                synchronized(signals) {
                    signals[subId] = (signals[subId] ?: initial).copy(visible = shown.signal, rootVisible = shown.root)
                }
                schedule()
            } }
            flows.observe(providerFlow) { value -> guard.run {
                synchronized(signals) { signals[subId] = (signals[subId] ?: initial).copy(cellularMode = value === cell) }
                schedule()
            } }
            flows.observe(satelliteFlow) { value -> guard.run {
                val satellite = value as? Boolean ?: return@run
                synchronized(signals) { signals[subId] = (signals[subId] ?: initial).copy(satellite = satellite) }
                schedule()
            } }
            // A subscription ID is not a SIM slot; observe the host's actual slot mapping once per model.
            flows.observe(slotFlow) { value ->
                synchronized(signals) { slotIndexes[subId] = value as? Int ?: -1 }
                schedule()
            }
            observed[model] = true
        }
        synchronized(signals) { signals.putIfAbsent(subId, initial) }
        rows[root] = row
        val listener = android.view.ViewTreeObserver.OnPreDrawListener {
            // Final barrier: native visibility/resource updates must not leak an intermediate frame.
            if (row.styled && image.drawable !is StackedSignalDrawable) {
                capture(row, image)
                schedule()
            }
            if (row.hiddenByModule && root.visibility != View.GONE) schedule()
            flushPending()
            true
        }
        var listening = false
        fun attachListener() {
            if (!listening && root.viewTreeObserver.isAlive) {
                root.viewTreeObserver.addOnPreDrawListener(listener); listening = true
            }
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { attachListener(); schedule() }
            override fun onViewDetachedFromWindow(v: View) {
                if (v.viewTreeObserver.isAlive) v.viewTreeObserver.removeOnPreDrawListener(listener)
                listening = false
                schedule()
            }
        })
        if (root.isAttachedToWindow) attachListener()
        schedule()
    }

    fun emitted(image: ImageView) {
        val affected = rows.values.filter { it.image.get() === image }
        if (affected.isEmpty()) return
        affected.forEach { row -> capture(row, image) }
        schedule()
        // Native collectors run on the UI thread. Restore composition before returning to drawing.
        if (Looper.myLooper() == handler.looper) flushPending()
    }

    private fun capture(row: Row, image: ImageView) {
        val native = image.drawable ?: return
        if (native is StackedSignalDrawable) return
        row.native = native.constantState?.newDrawable(image.resources)?.mutate()?.also {
            it.colorFilter = native.colorFilter; it.state = native.state
        } ?: native
        row.native?.let { drawable -> row.nativeColor = sampleColor(drawable) }
    }

    /** Read a tiny in-memory rendering, including the native tint, when a signal emission changes. */
    private fun sampleColor(drawable: Drawable): Int {
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        val original = Rect(drawable.bounds)
        try {
            drawable.setBounds(0, 0, 24, 24)
            drawable.draw(Canvas(bitmap))
            var best = -1f
            var color = Color.WHITE
            for (y in 0..23) for (x in 0..23) {
                val pixel = bitmap.getPixel(x, y)
                val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3f
                val score = Color.alpha(pixel) * kotlin.math.abs(brightness - 127.5f)
                if (Color.alpha(pixel) > 0 && score > best) { best = score; color = pixel or (0xff shl 24) }
            }
            return color
        } finally { drawable.bounds = original; bitmap.recycle() }
    }

    private fun schedule() {
        refreshGate.request()
    }

    private fun flushPending() {
        refreshGate.flush()
    }

    private fun refresh() {
        val mode = StatusBarKeys.dualMode()
        val snapshot = synchronized(signals) { Triple(signals.values.toList(), dataSubId, slotIndexes.toMap()) }
        // Separate hosts (home / lock screen / another display) never share a composed view.
        rows.values.toList().filter { it.root.get()?.isAttachedToWindow == true }
            .groupBy { it.root.get()?.parent }.values.forEach { group ->
                // Host icon/dot/alpha transitions are presentation, not a SIM disappearing.
                // Real visibility, provider and satellite state still come from the model below.
                val eligible = group.map { it.subId }.toSet()
                val candidates = snapshot.first.filter { it.subId in eligible }
                val pairing = StatusBarPolicy.pair(candidates, snapshot.second)
                val primary = pairing?.primary ?: if (mode == StatusBarKeys.DUAL_ALWAYS) {
                    candidates.firstOrNull { it.visible && it.cellularMode && !it.satellite && it.resourceId > 0 }
                } else null
                val first = primary?.let { signal -> group.firstOrNull { it.subId == signal.subId } }
                val second = pairing?.let { pair -> group.firstOrNull { it.subId == pair.secondary.subId } }
                fun nativeLevel(row: Row, resource: Int): Int? {
                    if (!signalLevels.containsKey(resource)) signalLevels[resource] = runCatching {
                        StatusBarPolicy.signalLevel(row.image.get()!!.resources.getResourceEntryName(resource))
                    }.getOrNull()
                    return signalLevels[resource]
                }
                val levels = if (mode == StatusBarKeys.DUAL_ALWAYS && first != null && primary != null) {
                    DualSimArtworkPolicy.fourCellLevel(nativeLevel(first, primary.resourceId))
                        ?.let { DualSimArtworkPolicy.Levels(it, it) }
                } else if (mode == StatusBarKeys.DUAL_AUTO && pairing != null && first != null && second?.native != null) {
                    DualSimArtworkPolicy.levels(snapshot.third[first.subId] ?: -1, snapshot.third[second.subId] ?: -1,
                        nativeLevel(first, pairing.primary.resourceId), nativeLevel(second, pairing.secondary.resourceId))
                } else null
                if (first?.native == null || levels == null) {
                    group.forEach { restore(it, snapshot.first.firstOrNull { state -> state.subId == it.subId }?.rootVisible) }
                    return@forEach
                }
                group.forEach { row ->
                    val root = row.root.get() ?: return@forEach
                    if (row === second) {
                        if (!row.hiddenByModule) { row.nativeVisibility = root.visibility; row.hiddenByModule = true }
                        if (root.visibility != View.GONE) root.visibility = View.GONE
                    } else if (row === first) {
                        restoreVisibility(row, true)
                        render(row, levels)
                    } else restore(row, snapshot.first.firstOrNull { it.subId == row.subId }?.rootVisible)
                }
            }
    }

    private fun restoreVisibility(row: Row, visible: Boolean?) {
        if (row.hiddenByModule) {
            row.root.get()?.visibility = when (visible) {
                true -> View.VISIBLE
                false -> View.GONE
                null -> row.nativeVisibility
            }
            row.hiddenByModule = false
        }
    }

    private fun restore(row: Row, visible: Boolean?) {
        restoreVisibility(row, visible)
        row.image.get()?.let { image ->
            if (image.drawable is StackedSignalDrawable) row.native?.let(image::setImageDrawable)
        }
        if (row.styled) {
            row.parent.get()?.let { parent ->
                parent.clipChildren = row.originalClipChildren; parent.clipToPadding = row.originalClipPadding
            }
            row.group.get()?.let { it.setPadding(row.originalLeft, it.paddingTop, row.originalRight, it.paddingBottom) }
            row.styled = false
        }
    }

    private fun render(first: Row, levels: DualSimArtworkPolicy.Levels) {
        val image = first.image.get() ?: return
        val root = first.root.get() ?: return
        first.styled = true
        fun setting(key: String, default: Float, min: Float, max: Float) = StatusBarPolicy.bounded(HookPrefs.getFloat(key, default), default, min, max)
        val scale = setting(StatusBarKeys.DUAL_SCALE, 1f, 0.5f, 2f)
        val color = image.imageTintList?.getColorForState(image.drawableState, first.nativeColor) ?: first.nativeColor
        val density = image.resources.displayMetrics.density
        val offset = setting(StatusBarKeys.DUAL_OFFSET, 0f, -4f, 4f) * density
        val drawable = first.stacked ?: StackedSignalDrawable(density, levels.top, levels.bottom, color)
            .also { first.stacked = it }
        drawable.update(levels.top, levels.bottom, color, scale, offset)
        if (image.drawable !== drawable) image.setImageDrawable(drawable)
        (image.parent as? ViewGroup)?.let { parent ->
            parent.clipChildren = if (scale > 1f || offset != 0f) false else first.originalClipChildren
            parent.clipToPadding = if (scale > 1f || offset != 0f) false else first.originalClipPadding
        }
        val group = StatusBarViews.find(root, "mobile_group") as? ViewGroup ?: return
        val left = first.originalLeft + (setting(StatusBarKeys.DUAL_LEFT_GAP, 0f, 0f, 8f) * density).roundToInt()
        val right = first.originalRight + (setting(StatusBarKeys.DUAL_RIGHT_GAP, 0f, 0f, 8f) * density).roundToInt()
        if (group.paddingLeft != left || group.paddingRight != right) group.setPadding(left, group.paddingTop, right, group.paddingBottom)
    }
}
