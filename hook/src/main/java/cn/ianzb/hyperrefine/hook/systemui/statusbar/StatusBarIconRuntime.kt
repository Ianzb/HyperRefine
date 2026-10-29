package cn.ianzb.hyperrefine.hook.systemui.statusbar

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import cn.ianzb.hyperrefine.hook.device.DeviceContext
import cn.ianzb.hyperrefine.hook.device.DeviceType
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * 状态栏图标的运行时协调器。
 *
 * 只操作 SystemUI 已有的 native View / `MiuiStatusIconContainer` 的 ignoredSlots，
 * 不创建悬浮窗、不复制图标资源、不替换 SystemUI 的图标数据源。
 */
internal object StatusBarIconRuntime {

    private val hosts = WeakHashMap<ViewGroup, Host>()
    private val iconGroups = mutableListOf<WeakReference<ViewGroup>>()
    private val batteryViews = mutableListOf<WeakReference<View>>()
    private val groupHidden = WeakHashMap<View, Boolean>()
    /** 每个容器由模块临时加入 ignoredSlots 的 slot；只撤销这些，不碰原生归属。 */
    private val groupModuleIgnored = WeakHashMap<ViewGroup, MutableSet<String>>()
    /** IconManager 的原生 block list；模块只按显示条件过滤，配置变化时重新下发。 */
    private val iconBlockListBases = WeakHashMap<Any, List<String>>()

    /** 注册所有场景的 MiuiClock；1s ticker 直接调用官方 updateTime，让当前场景自行格式化。 */
    private val clockViews = WeakHashMap<TextView, Boolean>()
    private val clockHandler = Handler(Looper.getMainLooper())
    private var clockTickerRunning = false
    private val clockTicker = object : Runnable {
        override fun run() {
            if (!clockTickerRunning) return
            if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) {
                clockTickerRunning = false
                return
            }
            val force = HookPrefs.getBoolean(StatusBarIconKeys.CLOCK_REFRESH, false)
            clockViews.keys.toList().forEach { view ->
                if (!view.isAttachedToWindow) return@forEach
                val pattern = clockPattern(view.context, view.id)
                val needsSeconds = pattern?.any { it == 's' || it == 'S' } == true
                if (force || needsSeconds) {
                    val updated = runCatching {
                        Reflect.callMethod(view, "updateTime")
                        true
                    }.getOrDefault(false)
                    if (!updated) {
                        clockText(view.context, view.id)?.let { text ->
                            if (!TextUtils.equals(view.text, text)) view.text = text
                        }
                        applyClockWeight(view)
                        applyClockLockWidth(view)
                    }
                }
            }
            clockHandler.postDelayed(this, 1000L)
        }
    }

    /** 字重覆盖前的原始 Typeface；只在当前进程内保留弱引用。 */
    private val originalTypefaces = WeakHashMap<TextView, Typeface?>()
    private val originalVariations = WeakHashMap<TextView, String?>()
    private val originalTextSizes = WeakHashMap<TextView, Float>()
    private val originalClockTexts = WeakHashMap<TextView, CharSequence?>()
    private val speedStyles = WeakHashMap<View, SpeedStyleState>()
    private val speedSlowStates = WeakHashMap<View, Boolean>()
    private val speedTraffic = WeakHashMap<View, TrafficSample>()
    private val gapStates = WeakHashMap<View, GapState>()
    private val gapBaseWidths = WeakHashMap<View, Int>()
    private val gapDeferred = WeakHashMap<View, Boolean>()
    private val notificationDotStates = WeakHashMap<View, DotDrawState>()
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var notificationDotParentClass: Class<*>? = null
    @Volatile private var dotMasterEnabled = false
    @Volatile private var dotMultiEnabled = false
    @Volatile private var dotFlagsReady = false
    private val notificationMaxBases = WeakHashMap<View, Int>()
    private val clockWidths = WeakHashMap<TextView, Int>()
    private val idCache = HashMap<String, Int>()
    private var restoringClock = false
    private var notificationMaxCache = -1
    private var slotRankCacheKey = ""
    private var slotRankCache: Map<String, Int> = emptyMap()

    init {
        refreshNotificationDotFlags()
        HookPrefs.registerListener { refreshNotificationDotFlags() }
    }

    private fun refreshNotificationDotFlags() {
        dotMasterEnabled = HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)
        dotMultiEnabled = HookPrefs.getBoolean(
            StatusBarIconKeys.notificationMultiDot(StatusBarIconRegistry.NOTIFICATIONS),
            false,
        )
        dotFlagsReady = true
    }

    /** 最近一次 Controller；用于配置变化后不重启 SystemUI 直接重排视图。 */
    private var controllerRef: WeakReference<Any>? = null
    private var nativeSlotOrder: List<String>? = null
    // 这些基线必须全局唯一；多个 Host（状态栏、锁屏、CC）各自保存一份会把
    // 别的 Host 已经改过的列表当成原生基线，导致列表在两次刷新之间来回翻转。
    private val orderHandler = Handler(Looper.getMainLooper())
    private var lastLiveOrder: String? = null
    private var rebuildingGroups = false
    private var refreshPending = false
    private val liveOrderRunnable = Runnable { reorderLive() }
    private val refreshRunnable = Runnable {
        refreshPending = false
        refreshIconBlockLists()
        refreshAll()
    }

    fun attach(root: ViewGroup) {
        synchronized(hosts) {
            hosts[root]?.refresh()
                ?: runCatching { Host(root).also { hosts[root] = it } }
                    .onFailure { android.util.Log.w("HyperRefineStatusBar", "attach failed", it) }
        }
    }

    /** 注册控制器创建的每个图标组；控制中心 fake 组也必须能按自定义条件直接隐藏。 */
    fun registerIconGroup(group: ViewGroup?) {
        group ?: return
        iconGroups.removeAll { it.get() == null || it.get() === group }
        iconGroups += WeakReference(group)
        // 重建/首次添加后宿主可能还没重新测量子 View；MiuiStatusIconContainer 的
        // onLayout 依赖子 View 实际宽度，必须显式请求一次布局，否则会沿用 0 宽度位置。
        group.requestLayout()
        group.post { group.requestLayout() }
        // 首次启动时 Host 的 refresh 往往早于 controller 的 IconManager 注册；
        // 只改静态 block list 不会更新已经创建的 mBlockList/ignoredSlots。
        // 等新组注册后再刷一次，避免必须手动改一次设置才生效。
        refreshSoon()
    }

    /**
     * 原生 onLayout 会按自己的 layoutStates 重写 translationX。
     * 自定义间距只改子 View 的绘制原点（scrollX），布局前先还原，
     * 布局后再叠加，避免和原生位移互相覆盖。
     */
    fun prepareIconLayout(group: ViewGroup?) {
        group ?: return
        if (gapStates.isEmpty()) return
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            gapStates[child]?.let { state ->
                if (child.scrollX != state.baseScrollX) {
                    child.scrollTo(state.baseScrollX, child.scrollY)
                }
            }
        }
    }

    fun applyIconGapTranslations(group: ViewGroup?) {
        group ?: return
        if (gapStates.isEmpty()) return
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i) ?: continue
            gapStates[child]?.let { state ->
                val contentOffset = state.offset.roundToInt()
                val target = state.baseScrollX - contentOffset
                if (child.scrollX != target) {
                    child.scrollTo(target, child.scrollY)
                }
            }
        }
    }

    /** 电池在状态栏与控制中心可能由不同 View 承载，单独登记以便统一切换可见性。 */
    fun registerBatteryView(view: View?) {
        view ?: return
        batteryViews.removeAll { it.get() == null || it.get() === view }
        batteryViews += WeakReference(view)
    }

    /**
     * 过滤 IconManager 的原生 block list。
     *
     * 蓝牙设备电量等“系统默认仅控制中心显示”的 slot 写在 `RIGHT_BLOCK_LIST` 里，
     * 控制中心过渡使用的 `ControlCenterFakeStatusIcons` 会把同一份 block list
     * 交给临时 `IconManager`。仅在 ignoredSlots/View 可见性上补救没有用：图标
     * 在 IconManager 里根本没有被创建。这里对 ALWAYS 的 slot 从 block list 中
     * 剔除，配置变化时再用原生列表重新下发，避免影响其它模式。
     */
    fun filterIconBlockList(manager: Any, slots: List<*>): List<String> {
        val names = slots.mapNotNull { it as? String }
        if (names.isEmpty()) return names
        iconBlockListBases.putIfAbsent(manager, names)
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return names
        val snapshot = StatusBarIconConfigReader.load()
        return names.filterNot { slot ->
            val component = StatusBarIconRegistry.right.firstOrNull { it.slot == slot }
                ?: return@filterNot false
            snapshot.config(component.id).display == StatusBarIconMode.ALWAYS.value
        }
    }

    /** 配置变化后重新应用 block list；关闭 ALWAYS 时恢复原生列表。 */
    private fun refreshIconBlockLists() {
        if (iconBlockListBases.isEmpty()) return
        iconBlockListBases.entries.toList().forEach { (manager, base) ->
            runCatching { Reflect.callMethod(manager, "setBlockList", base) }
        }
    }

    /**
     * 新补进临时 IconManager 的图标不会自动收到之前的 onDarkChanged，
     * 会保留 drawable 默认白色。这里让控制中心头部重新下发当前明暗色。
     */
    fun refreshIconTint(manager: Any) {
        val group = runCatching {
            Reflect.getObjectField(manager, "mGroup") as? ViewGroup
        }.getOrNull() ?: return
        var parent: View? = group.parent as? View
        var delegate: Any? = null
        while (parent != null) {
            if (parent.javaClass.simpleName == "ControlCenterFakeStatusIcons" ||
                parent.javaClass.simpleName == "ControlCenterStatusBarIcon"
            ) {
                delegate = runCatching { Reflect.getObjectField(parent, "delegate") }.getOrNull()
                break
            }
            parent = parent.parent as? View
        }
        delegate ?: return
        // MiuiLightDarkIconManager.setLight 在 mLight/mColor 未变化时会直接 return，
        // 新增的 child 永远收不到 onDarkChanged。先把 mColor 改成临时值，强制它
        // 重新遍历整组 child，再恢复为当前颜色。
        val color = runCatching { Reflect.getObjectField(manager, "mColor") as? Int }
            .getOrNull() ?: return
        val light = runCatching { Reflect.getObjectField(manager, "mLight") as? Boolean }
            .getOrNull() ?: return
        val useTint = runCatching { Reflect.getObjectField(delegate, "mUseTint") as? Boolean }
            .getOrNull() ?: true
        runCatching {
            Reflect.setObjectField(manager, "mColor", color xor 1)
            Reflect.callMethod(manager, "setLight", color, light, useTint)
        }
    }

    fun onPreferencesChanged() {
        notificationMaxCache = -1
        slotRankCacheKey = ""
        // 滑块/输入框会连续写偏好；合并成一次刷新，避免拖动时反复全量遍历宿主。
        if (!refreshPending) {
            refreshPending = true
            orderHandler.postDelayed(refreshRunnable, 60L)
        }
        scheduleLiveOrder()
    }

    private fun scheduleLiveOrder() {
        if (controllerRef?.get() == null) return
        orderHandler.removeCallbacks(liveOrderRunnable)
        orderHandler.postDelayed(liveOrderRunnable, 120L)
    }

    fun refreshAll() {
        ensureClockTicker()
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) {
            restoreClocks()
        } else {
            refreshRegisteredClocks()
        }
        val snapshot = synchronized(hosts) { hosts.values.toList() }
        snapshot.forEach { it.postRefresh() }
    }

    fun refreshSoon(root: ViewGroup? = null) {
        if (root == null) {
            // 通知图标在展开面板时会连续增删，立即 refreshAll 会把每次增删都放大成
            // 一轮全量重排。合并到下一帧统一刷新，避免动画期间反复 requestLayout。
            if (!refreshPending) {
                refreshPending = true
                orderHandler.post(refreshRunnable)
            }
        } else {
            root.post { hosts[root]?.refresh() }
        }
    }

    /** 配置变化时让已注册的场景时钟重跑官方 updateTime；清空格式后能立即回退原生。 */
    private fun refreshRegisteredClocks() {
        clockViews.keys.toList().forEach { view ->
            if (view.isAttachedToWindow) {
                runCatching { Reflect.callMethod(view, "updateTime") }
            }
        }
    }

    /** 在 Controller 首次 addIconGroup 前排序 slot，保证 IconManager 的索引与视图顺序一致。 */
    fun sortSlots(controller: Any) {
        controllerRef = WeakReference(controller)
        captureNativeOrder(controller)
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return
        val snapshot = StatusBarIconConfigReader.load()
        if (!snapshot.enabled || snapshot.order.isEmpty()) return
        val componentOrder = snapshot.reorderableOrder()
        val ordered = componentOrder.map(::slotNameForId)
        if (ordered.isEmpty()) return
        runCatching {
            val list = Reflect.getObjectField(controller, "mStatusBarIconList") ?: return
            // mViewOnlySlots 可能是 Collections.unmodifiableList，不能 clear/add；
            // 直接整体替换成新的 ArrayList。mSlots 也必须一起重排，
            // 因为 addIconGroup 用 getViewIndex() 从 mSlots 计算 onIconAdded 的插入索引。
            val slotsBefore = (Reflect.getObjectField(list, "mSlots") as? List<Any>)?.toList()
            val viewsBefore = (Reflect.getObjectField(list, "mViewOnlySlots") as? List<Any>)?.toList()
            var changed = reorderAndReplace(list, "mSlots", ordered)
            changed = reorderAndReplace(list, "mViewOnlySlots", ordered) || changed
            if (!listMatches(list, "mSlots", ordered) ||
                !listMatches(list, "mViewOnlySlots", ordered)
            ) {
                // 任一列表未写入成功都会让 addIconGroup 的索引与 View 顺序错位，
                // 直接恢复原生顺序，宁可不排序也不能把图标挤在一起。
                slotsBefore?.let { restoreList(list, "mSlots", it) }
                viewsBefore?.let { restoreList(list, "mViewOnlySlots", it) }
                return
            }
            if (changed) {
                lastLiveOrder = componentOrder.joinToString(",")
                // 仅当 slot 顺序确实变化且已有图标组时重建；首次排序发生在建组前，
                // 无条件重建会造成启动阶段图标闪空。
                rebuildGroups(controller)
            }
        }.onFailure {
            android.util.Log.w("HyperRefineStatusBar", "sort slots failed", it)
        }
    }

    private fun captureNativeOrder(controller: Any) {
        if (nativeSlotOrder != null) return
        runCatching {
            val list = Reflect.getObjectField(controller, "mStatusBarIconList") ?: return
            val raw = Reflect.getObjectField(list, "mViewOnlySlots") as? List<*> ?: return
            nativeSlotOrder = raw.mapNotNull { slot -> slot?.let(::slotName) }
        }
    }

    private fun slotName(slot: Any): String? =
        runCatching { Reflect.getObjectField(slot, "mName") as? String }.getOrNull()
            ?: runCatching { Reflect.getObjectField(slot, "name") as? String }.getOrNull()

    /** 组件 ID 不一定等于原生 slot，例如 alarm 的 slot 是 alarm_clock。 */
    private fun slotNameForId(id: String): String =
        StatusBarIconRegistry.byId(id)?.slot ?: id

    /**
     * 图标组按 [StatusBarIconControllerImpl.addIconGroup] 的顺序逐个 addView。
     * 某些 ROM 在图标动态出现时会重新生成 mViewOnlySlots，只在建组前排序会漏掉
     * VPN/静音/勿扰这类后插入的 slot。这里按“目标完整 slot 顺序”做插入排序：
     * 每个图标加入时，计算它应排在已存在子 View 的哪个位置，保证最终子 View
     * 顺序始终与设置一致，同时保留 handle/data_saver 等不可管理 slot 的原位置。
     */
    fun adjustIconAddedIndex(manager: Any?, slot: String?, fallback: Int): Int {
        if (slot.isNullOrEmpty()) return fallback
        if (nativeSlotOrder.isNullOrEmpty()) return fallback
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return fallback
        val desiredIds = StatusBarIconConfigReader.loadOrderedIds()
        if (desiredIds.isEmpty()) return fallback
        val desiredSlots = desiredIds.map(::slotNameForId)
        val ranks = slotRanks(desiredSlots)
        val newRank = ranks[slot] ?: return fallback
        manager ?: return fallback
        val group = runCatching {
            Reflect.getObjectField(manager, "mGroup") as? ViewGroup
        }.getOrNull() ?: return fallback
        var target = 0
        for (i in 0 until group.childCount) {
            val childSlot = runCatching {
                Reflect.callMethod(group.getChildAt(i), "getSlot") as? String
            }.getOrNull() ?: continue
            // 同一 slot 已经存在时绝不改写插入下标：控制器重刷会在旧 View
            // 尚未移除时再次走 onIconAdded，改写下标会把新 View 插到旧 View
            // 后面，形成两个蓝牙等重复图标。
            if (childSlot == slot) return fallback
            val rank = ranks[childSlot] ?: continue
            // 双卡等同一 slot 的多个 holder 保持原有先后顺序。
            if (rank < newRank) target++
        }
        return target.coerceIn(0, group.childCount)
    }

    private fun slotRanks(desiredSlots: List<String>): Map<String, Int> {
        val key = desiredSlots.joinToString(",")
        if (key == slotRankCacheKey) return slotRankCache
        val base = nativeSlotOrder
        val result = if (base.isNullOrEmpty()) {
            desiredSlots.withIndex().associate { it.value to it.index }
        } else {
            val index = desiredSlots.withIndex().associate { it.value to it.index }
            val expose = desiredSlots.toSet()
            val positions = base.indices.filter { base[it] in expose }
            val sorted = positions.map { base[it] }
                .sortedBy { index[it] ?: Int.MAX_VALUE }
            val full = base.toMutableList()
            positions.forEachIndexed { i, position -> full[position] = sorted[i] }
            full.withIndex().associate { it.value to it.index }
        }
        slotRankCacheKey = key
        slotRankCache = result
        return result
    }

    /**
     * 只交换命中顺序的 slot 在数组中的彼此位置，未暴露的 slot 保持原索引；
     * 这样实时重排不会把 volume / sound_box 等未管理图标整体推走。
     */
    private fun reorderList(source: List<Any>, ordered: List<String>): List<Any> {
        val result = source.toMutableList()
        val index = ordered.withIndex().associate { it.value to it.index }
        val expose = ordered.toSet()
        val positions = source.indices.filter { slotName(source[it]) in expose }
        val sorted = positions.map { source[it] }.sortedBy { index[slotName(it)] ?: Int.MAX_VALUE }
        positions.forEachIndexed { i, position -> result[position] = sorted[i] }
        return result
    }

    private fun restoreList(target: Any, fieldName: String, values: List<Any>) {
        runCatching {
            Reflect.findField(target.javaClass, fieldName).set(target, ArrayList(values))
        }
    }

    private fun reorderAndReplace(target: Any, fieldName: String, ordered: List<String>): Boolean {
        val source = Reflect.getObjectField(target, fieldName) as? List<Any>
            ?: throw IllegalStateException("$fieldName is not a list")
        val after = reorderList(source, ordered)
        if (source.mapNotNull(::slotName) == after.mapNotNull(::slotName)) return false
        val field = Reflect.findField(target.javaClass, fieldName)
        // mSlots 是宿主持有的 ArrayList，原地重排可保留引用；mViewOnlySlots 可能是
        // 过滤/不可变视图，清空它可能连带清空 mSlots，因此只做整体替换。
        val mutable = source as? MutableList<Any>
        if (fieldName == "mSlots" && mutable != null) {
            runCatching {
                mutable.clear()
                mutable.addAll(after)
            }
        }
        if (Reflect.getObjectField(target, fieldName)
                .let { (it as? List<Any>)?.mapNotNull(::slotName) } != after.mapNotNull(::slotName)
        ) {
            field.set(target, ArrayList(after))
        }
        return true
    }

    private fun reorderLive() {
        val controller = controllerRef?.get() ?: return
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) {
            // 关闭总开关时把已经实时重排过的视图恢复到启动时捕获的原生顺序。
            if (lastLiveOrder != null) {
                val native = nativeSlotOrder.orEmpty()
                if (native.isNotEmpty()) {
                    runCatching { reorderControllerViews(controller, native) }
                    lastLiveOrder = null
                }
            }
            return
        }
        val snapshot = StatusBarIconConfigReader.load()
        val desired = if (snapshot.order.isEmpty()) {
            nativeSlotOrder.orEmpty()
        } else {
            snapshot.reorderableOrder()
        }
        if (desired.isEmpty()) return
        val key = desired.joinToString(",")
        if (key == lastLiveOrder) return
        runCatching {
            if (reorderControllerViews(controller, desired)) lastLiveOrder = key
        }.onFailure {
            android.util.Log.w("HyperRefineStatusBar", "live order failed", it)
        }
    }

    /**
     * 已存在的图标组必须按新 slot 顺序重建，否则列表索引和 View 顺序会错位。
     * 走官方 remove/add，避免直接搬 View 触发退场动画。
     */
    private fun rebuildGroups(controller: Any) {
        if (rebuildingGroups) return
        val groups = Reflect.getObjectField(controller, "mIconGroups") as? List<*> ?: return
        if (groups.isEmpty()) return
        rebuildingGroups = true
        try {
            groups.toList().forEach { manager ->
                if (manager == null) return@forEach
                Reflect.callMethod(controller, "removeIconGroup", manager)
                Reflect.callMethod(controller, "addIconGroup", manager)
            }
        } finally {
            rebuildingGroups = false
        }
    }

    private fun reorderControllerViews(controller: Any, desired: List<String>): Boolean {
        val list = Reflect.getObjectField(controller, "mStatusBarIconList") ?: return false
        val desiredSlots = desired.map(::slotNameForId)
        val slotsBefore = (Reflect.getObjectField(list, "mSlots") as? List<Any>)?.toList()
        val viewsBefore = (Reflect.getObjectField(list, "mViewOnlySlots") as? List<Any>)?.toList()
        // 索引和视图顺序必须同时成功；字段不可写时失败关闭，绝不能只挪 View。
        reorderAndReplace(list, "mViewOnlySlots", desiredSlots)
        reorderAndReplace(list, "mSlots", desiredSlots)
        if (!listMatches(list, "mViewOnlySlots", desiredSlots) ||
            !listMatches(list, "mSlots", desiredSlots)
        ) {
            slotsBefore?.let { restoreList(list, "mSlots", it) }
            viewsBefore?.let { restoreList(list, "mViewOnlySlots", it) }
            return false
        }
        rebuildGroups(controller)
        return true
    }

    private fun listMatches(target: Any, fieldName: String, desired: List<String>): Boolean {
        val names = (Reflect.getObjectField(target, fieldName) as? List<Any>)
            ?.mapNotNull(::slotName) ?: return false
        val expose = desired.toSet()
        return names.filter { it in expose } == desired.filter { it in names }
    }

    /**
     * 通知图标上限必须在新容器构造、以及宿主调用 setMaxIconsAmount 后立即重放。
     * 只等下一次全量 refresh 会出现约 1 秒的系统三图标回退。
     */
    fun applyNotificationLimit(container: View?) {
        container ?: return
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) {
            notificationMaxBases.remove(container)?.let { base ->
                setNotificationMax(container, base)
            }
            return
        }
        val current = runCatching {
            Reflect.getObjectField(container, "mMaxIcons") as? Int
        }.getOrNull() ?: return
        val max = notificationMaxCache.takeIf { it > 0 } ?: StatusBarIconConfigReader
            .loadComponent(StatusBarIconRegistry.NOTIFICATIONS)
            .maxNotifications
            .coerceIn(1, 16)
            .also { notificationMaxCache = it }
        if (current == max) return
        // 原生初始值可能是 Integer.MAX_VALUE；关闭模块时应回到系统三图标，
        // 而不是把这个哨兵值写回宿主。
        if (!notificationMaxBases.containsKey(container)) {
            notificationMaxBases[container] = current.takeIf { it in 1..64 } ?: 3
        }
        setNotificationMax(container, max)
        // 缩到 3 个以下时宿主可能不会主动重算 mFirstOverflowIndex；圆点仍停在
        // 旧图标上。这里在下一帧按新上限重算可见状态，再重绘圆点。
        refreshNotificationDots(container)
    }

    fun refreshNotificationDots(container: View?) {
        val group = container as? ViewGroup ?: return
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return
        if (!HookPrefs.getBoolean(
                StatusBarIconKeys.notificationMultiDot(StatusBarIconRegistry.NOTIFICATIONS),
                false,
            )
        ) {
            return
        }
        group.post {
            runCatching { Reflect.callMethod(group, "calculateIconXTranslations") }
            runCatching { Reflect.callMethod(group, "applyIconStates") }
            for (i in 0 until group.childCount) group.getChildAt(i)?.invalidate()
            group.invalidate()
        }
    }

    private fun setNotificationMax(container: View, max: Int) {
        runCatching {
            Reflect.callMethod(container, "setMaxIconsAmount", max)
        }
    }

    /** 刷新间隔只改控制器下一次采样 delay；显示层不再二次节流。 */
    fun applyNetworkSpeed(view: View) {
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return
        applyNetworkSpeed(view, StatusBarIconConfigReader.loadComponent(StatusBarIconRegistry.NETWORK_SPEED))
    }

    fun applyNetworkSpeed(view: View, config: StatusBarIconComponentConfig) {
        applySpeedContent(view, config)
        applySpeedFontSize(view, config)
        applySpeedLayout(view, config)
        val slow = isSlowSpeed(view, config)
        if (speedSlowStates[view] != slow) {
            speedSlowStates[view] = slow
            // 跨过阈值时让宿主重新走一遍显隐和 ignored 状态。
            if (view.isAttachedToWindow) refreshSoon()
        }
    }

    private fun applySpeedContent(view: View, config: StatusBarIconComponentConfig) {
        runCatching {
            val numberText = Reflect.getObjectField(view, "mNetworkSpeedNumberText") as? TextView
                ?: return
            val unitText = Reflect.getObjectField(view, "mNetworkSpeedUnitText") as? TextView
                ?: return
            val number = Reflect.getObjectField(view, "mNetworkSpeedNumber")?.toString().orEmpty().trim()
            val unit = Reflect.getObjectField(view, "mNetworkSpeedUnit")?.toString().orEmpty().trim()
            val showUnit = config.speedShowUnit
            when (config.speedStyle) {
                "single_line" -> {
                    val (down, _) = sampleTraffic(view)
                    numberText.text = formatSpeed(down, showUnit, config.speedUnitStyle)
                    unitText.text = ""
                }
                "dual_line" -> {
                    val (down, up) = sampleTraffic(view)
                    val downText = formatSpeed(down, showUnit, config.speedUnitStyle)
                    val upText = formatSpeed(up, showUnit, config.speedUnitStyle)
                    val downLine = decorateSpeed(downText, config, upload = false)
                    val upLine = decorateSpeed(upText, config, upload = true)
                    numberText.text = if (config.speedDualUploadFirst) upLine else downLine
                    unitText.text = if (config.speedDualUploadFirst) downLine else upLine
                }
                "number_only" -> {
                    numberText.text = number
                    unitText.text = ""
                }
                else -> {
                    numberText.text = if (number.isEmpty()) "" else "$number "
                    // 跟随系统样式仍由原生提供数值和当前量级；单位写法按同一份
                    // K/M/G 映射改写，保证不改变原生字号、行距和采样结果。
                    unitText.text = if (showUnit && number.isNotEmpty() && unit.isNotEmpty()) {
                        "${speedUnitLabel(config.speedUnitStyle, speedUnitScale(unit))} "
                    } else {
                        ""
                    }
                }
            }
        }.onFailure {
            android.util.Log.w("HyperRefineStatusBar", "speed content failed", it)
        }
    }

    private fun applySpeedFontSize(view: View, config: StatusBarIconComponentConfig) {
        val state = speedStyles[view] ?: captureSpeedStyle(view)?.also { speedStyles[view] = it } ?: return
        val numberText = state.numberText
        val unitText = state.unitText
        val size = config.speedFontSize
        val dual = config.speedStyle == "dual_line"
        val single = config.speedStyle == "single_line"
        if (single && (!config.fontSizeEnabled || size <= 0f)) {
            // 单行样式默认用普通数值字号的两倍；用户显式设置字号时才尊重手动值。
            applySingleLineFontSize(view, state)
            return
        }
        if (!config.fontSizeEnabled || size <= 0f) {
            numberText.setTextSize(TypedValue.COMPLEX_UNIT_PX, state.numberTextSize)
            // 双排两行必须同字号；系统原生单位字号更小，这里统一成数值字号。
            unitText.setTextSize(
                TypedValue.COMPLEX_UNIT_PX,
                if (dual) state.numberTextSize else state.unitTextSize,
            )
            return
        }
        val ratio = if (state.numberTextSize > 0f) {
            state.unitTextSize / state.numberTextSize
        } else {
            0.91f
        }
        numberText.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        unitText.setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            if (dual) size else (size * ratio).coerceAtLeast(4f),
        )
    }

    /** 单行网速只放大到普通数值字号的 2 倍，避免占满整行高度后过大。 */
    private fun applySingleLineFontSize(view: View, state: SpeedStyleState) {
        val base = state.numberTextSize.takeIf { it > 0f }
            ?: TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14f,
                view.resources.displayMetrics,
            )
        state.numberText.setTextSize(TypedValue.COMPLEX_UNIT_PX, base * 2f)
    }

    private fun applySpeedLayout(view: View, config: StatusBarIconComponentConfig) {
        val state = speedStyles[view] ?: captureSpeedStyle(view)?.also { speedStyles[view] = it } ?: return
        val container = state.container ?: return
        val numberText = state.numberText
        val unitText = state.unitText
        val showUnit = config.speedShowUnit
        val single = config.speedStyle == "single_line"
        val numberOnly = config.speedStyle == "number_only" ||
            single ||
            (!showUnit && config.speedStyle == "system")
        val dual = config.speedStyle == "dual_line"

        numberText.visibility = View.VISIBLE
        unitText.visibility = if (numberOnly) View.GONE else View.VISIBLE

        var numberGravity = state.numberGravity
        var unitGravity = state.unitGravity
        var numberTranslation = state.numberTranslation
        var unitTranslation = state.unitTranslation
        if (numberOnly) {
            numberGravity = Gravity.CENTER
            unitGravity = Gravity.CENTER
            numberTranslation = 0f
            unitTranslation = 0f
        } else if (dual) {
            // 双排不依赖 TOP/BOTTOM gravity；两行都以中心为基准，再叠加系统原始
            // 行距与用户偏移，这样默认效果和系统两行网速一致。
            val rowGap = config.speedRowGap * view.resources.displayMetrics.density
            numberGravity = Gravity.CENTER
            unitGravity = Gravity.CENTER
            numberTranslation = state.numberTranslation - rowGap / 2f
            unitTranslation = state.unitTranslation + rowGap / 2f
        }

        setFrameGravity(numberText, numberGravity)
        setFrameGravity(unitText, unitGravity)
        numberText.translationX = state.numberTranslationX
        unitText.translationX = state.unitTranslationX
        numberText.translationY = numberTranslation
        unitText.translationY = unitTranslation

        // 样式/字号变化后清掉宿主缓存的空宽度，避免网速仍按旧尺寸占位。
        val cachedEmptyWidth = runCatching {
            Reflect.getObjectField(view, "mEmptyWidth") as? Int
        }.getOrNull()
        if (cachedEmptyWidth != -1) {
            runCatching { Reflect.setObjectField(view, "mEmptyWidth", -1) }
        }
        container.requestLayout()
        view.requestLayout()
    }

    private fun sampleTraffic(view: View): Pair<Long, Long> {
        val now = SystemClock.elapsedRealtime()
        val tx = trafficBytes { TrafficStats.getTotalTxBytes() to TrafficStats.getTxBytes("lo") }
        val rx = trafficBytes { TrafficStats.getTotalRxBytes() to TrafficStats.getRxBytes("lo") }
        val previous = speedTraffic[view]
        var down = previous?.down ?: 0L
        var up = previous?.up ?: 0L
        if (previous != null) {
            val elapsed = now - previous.time
            if (elapsed >= 350L) {
                down = ((rx - previous.rx).coerceAtLeast(0L) * 1000L / elapsed)
                up = ((tx - previous.tx).coerceAtLeast(0L) * 1000L / elapsed)
            }
        }
        speedTraffic[view] = TrafficSample(now, tx, rx, down, up)
        return down to up
    }

    private fun trafficBytes(read: () -> Pair<Long, Long>): Long = runCatching {
        val (total, loopback) = read()
        if (total <= 0L) 0L else (total - loopback.coerceAtLeast(0L)).coerceAtLeast(0L)
    }.getOrDefault(0L)

    private fun formatSpeed(
        bytesPerSecond: Long,
        showUnit: Boolean,
        unitStyle: String,
    ): String {
        var value = bytesPerSecond / 1024f
        var scale = 1
        while (value > 999f && scale < 3) {
            value /= 1024f
            scale++
        }
        val number = when {
            value < 10f -> String.format(Locale.getDefault(), "%.2f", value)
            value < 100f -> String.format(Locale.getDefault(), "%.1f", value)
            else -> String.format(Locale.getDefault(), "%.0f", value)
        }
        return if (showUnit) "$number ${speedUnitLabel(unitStyle, scale)}" else number
    }

    /** 单位标签随数值自动换 K/M/G；只改标签写法，不改变 1024 进制换算。 */
    private fun speedUnitLabel(style: String, scale: Int): String = when (style) {
        "k" -> when (scale) {
            0 -> "B"
            1 -> "K"
            2 -> "M"
            else -> "G"
        }
        "kb" -> when (scale) {
            0 -> "B"
            1 -> "KB"
            2 -> "MB"
            else -> "GB"
        }
        "kbit" -> when (scale) {
            0 -> "b"
            1 -> "Kb"
            2 -> "Mb"
            else -> "Gb"
        }
        "kbit_s" -> when (scale) {
            0 -> "b/s"
            1 -> "Kb/s"
            2 -> "Mb/s"
            else -> "Gb/s"
        }
        else -> when (scale) {
            0 -> "B/s"
            1 -> "KB/s"
            2 -> "MB/s"
            else -> "GB/s"
        }
    }

    /** 从原生单位标签读取当前量级，只用于跟随系统样式的单位改写。 */
    private fun speedUnitScale(unit: String): Int = when {
        unit.startsWith("G") -> 3
        unit.startsWith("M") -> 2
        unit.startsWith("K") -> 1
        else -> 0
    }

    /** 箭头与数字只留一个 thin space，避免默认的整格空格把双排撑得过宽。 */
    private fun decorateSpeed(
        text: String,
        config: StatusBarIconComponentConfig,
        upload: Boolean,
    ): String {
        val marker = speedArrowMark(config.speedDualArrowStyle, upload)
        if (marker.isEmpty()) return text
        val gap = "\u2009"
        return if (config.speedDualArrowPosition == "right") {
            "$text$gap$marker"
        } else {
            "$marker$gap$text"
        }
    }

    private fun speedArrowMark(style: String, upload: Boolean): String = when (style) {
        "none" -> ""
        "triangle" -> if (upload) "▲" else "▼"
        else -> if (upload) "↑" else "↓"
    }

    private fun setFrameGravity(view: TextView, gravity: Int) {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        if (params.gravity != gravity) {
            params.gravity = gravity
            view.layoutParams = params
        }
    }

    private fun captureSpeedStyle(view: View): SpeedStyleState? {
        val container = runCatching {
            Reflect.getObjectField(view, "mContainer") as? FrameLayout
        }.getOrNull() ?: return null
        val numberText = runCatching {
            Reflect.getObjectField(view, "mNetworkSpeedNumberText") as? TextView
        }.getOrNull() ?: return null
        val unitText = runCatching {
            Reflect.getObjectField(view, "mNetworkSpeedUnitText") as? TextView
        }.getOrNull() ?: return null
        return SpeedStyleState(
            container = container,
            numberText = numberText,
            unitText = unitText,
            numberGravity = (numberText.layoutParams as? FrameLayout.LayoutParams)?.gravity ?: Gravity.CENTER,
            unitGravity = (unitText.layoutParams as? FrameLayout.LayoutParams)?.gravity ?: Gravity.CENTER,
            numberTranslationX = numberText.translationX,
            unitTranslationX = unitText.translationX,
            numberTranslation = numberText.translationY,
            unitTranslation = unitText.translationY,
            numberTextSize = numberText.textSize,
            unitTextSize = unitText.textSize,
        )
    }

    private fun isSlowSpeed(view: View, config: StatusBarIconComponentConfig): Boolean {
        if (!config.speedSlowHide || config.speedSlowThreshold <= 0) return false
        val number = runCatching {
            Reflect.getObjectField(view, "mNetworkSpeedNumber")?.toString()
        }.getOrNull()?.trim()?.toFloatOrNull() ?: return false
        val unit = runCatching {
            Reflect.getObjectField(view, "mNetworkSpeedUnit")?.toString()
        }.getOrNull().orEmpty()
        val multiplier = when {
            unit.contains("MB", ignoreCase = true) ||
                unit.contains("M/s", ignoreCase = true) -> 1024f * 1024f
            unit.contains("KB", ignoreCase = true) ||
                unit.contains("K/s", ignoreCase = true) -> 1024f
            else -> 1f
        }
        val kbPerSecond = number * multiplier / 1024f
        return kbPerSecond < config.speedSlowThreshold
    }

    private fun restoreSpeedStyles() {
        speedStyles.forEach { (view, state) ->
            runCatching {
                state.numberText.setTextSize(TypedValue.COMPLEX_UNIT_PX, state.numberTextSize)
                state.unitText.setTextSize(TypedValue.COMPLEX_UNIT_PX, state.unitTextSize)
                val number = Reflect.getObjectField(view, "mNetworkSpeedNumber")?.toString().orEmpty()
                val unit = Reflect.getObjectField(view, "mNetworkSpeedUnit")?.toString().orEmpty()
                state.numberText.text = if (number.isEmpty()) "" else "$number "
                state.unitText.text = if (unit.isEmpty()) "" else "$unit "
                state.numberText.visibility = View.VISIBLE
                state.unitText.visibility = View.VISIBLE
                state.numberText.translationX = state.numberTranslationX
                state.unitText.translationX = state.unitTranslationX
                state.numberText.translationY = state.numberTranslation
                state.unitText.translationY = state.unitTranslation
                (state.numberText.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.gravity = state.numberGravity
                    state.numberText.layoutParams = it
                }
                (state.unitText.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.gravity = state.unitGravity
                    state.unitText.layoutParams = it
                }
                state.container?.minimumWidth = 0
                Reflect.setObjectField(view, "mEmptyWidth", -1)
                view.requestLayout()
            }
        }
        speedStyles.clear()
        speedSlowStates.clear()
        speedTraffic.clear()
    }

    /**
     * 原生 `StatusBarIconView` 的溢出点固定画三个圆。开启逐图标圆点后，
     * 在 before 中临时关掉原生绘制，after 再按隐藏图标数量补画 1..3 个。
     * 不新增 View / 悬浮窗，仍使用原生溢出点的位置、颜色与动画进度。
     */
    fun beforeNotificationDotDraw(view: View) {
        if (!dotFlagsReady) refreshNotificationDotFlags()
        if (!dotMasterEnabled || !dotMultiEnabled) return
        val state = notificationDotState(view) ?: return
        notificationDotStates[view] = state
        runCatching { Reflect.setObjectField(view, "mDotAppearAmount", 0f) }
    }

    fun afterNotificationDotDraw(view: View, canvas: Canvas?) {
        val state = notificationDotStates.remove(view) ?: return
        runCatching {
            Reflect.setObjectField(view, "mDotAppearAmount", state.progress)
            if (canvas == null) return@runCatching
            val alpha = (Color.alpha(state.color) / 255f *
                if (state.progress <= 1f) 1f else (2f - state.progress).coerceIn(0f, 1f))
            if (alpha <= 0f) return@runCatching
            val radius = if (state.progress <= 1f) {
                state.radius * state.progress
            } else {
                state.radius + (view.width / 4f - state.radius) * (state.progress - 1f)
            }
            if (radius <= 0.1f) return@runCatching
            dotPaint.color = state.color
            dotPaint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
            val centerX = view.width / 2f
            val centerY = view.height / 2f
            val step = radius * 2f + state.padding
            val firstOffset = -((state.count - 1) / 2f)
            for (i in 0 until state.count) {
                canvas.drawCircle(
                    centerX + (firstOffset + i) * step,
                    centerY,
                    radius,
                    dotPaint,
                )
            }
        }
    }

    /**
     * 通知增删后强制重绘溢出点。原生只在图标动画时才 invalidate，
     * 隐藏数量变化但图标位置不变时，最后一次绘制会停留在旧圆点数量。
     */
    fun invalidateNotificationDots(container: View?) {
        val group = container as? ViewGroup ?: return
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return
        if (!HookPrefs.getBoolean(
                StatusBarIconKeys.notificationMultiDot(StatusBarIconRegistry.NOTIFICATIONS),
                false,
            )
        ) {
            return
        }
        group.post {
            for (i in 0 until group.childCount) group.getChildAt(i)?.invalidate()
            group.invalidate()
        }
    }

    private fun notificationDotState(view: View): DotDrawState? {
        // 绝大多数图标没有圆点，先看出现进度，避免每帧继续走父容器/反射。
        val progress = runCatching {
            Reflect.getObjectField(view, "mDotAppearAmount") as? Float
        }.getOrNull() ?: return null
        if (progress <= 0f) return null
        val parent = view.parent as? ViewGroup ?: return null
        val parentClass = parent.javaClass
        val cachedClass = notificationDotParentClass
        if (cachedClass == null) {
            if (parentClass.simpleName != "NotificationIconContainer") return null
            notificationDotParentClass = parentClass
        } else if (parentClass !== cachedClass) {
            return null
        }
        val first = runCatching {
            Reflect.getObjectField(parent, "mFirstOverflowIndex") as? Int
        }.getOrNull() ?: return null
        if (first <= 0 || parent.indexOfChild(view) < first) return null
        val visibleState = runCatching {
            Reflect.getObjectField(view, "mVisibleState") as? Int
        }.getOrNull() ?: return null
        if (visibleState != 1) return null
        val radius = runCatching {
            Reflect.getObjectField(view, "mDotRadius") as? Float
        }.getOrNull() ?: return null
        val color = runCatching {
            Reflect.getObjectField(view, "mDecorColor") as? Int
        }.getOrNull() ?: return null
        val padding = runCatching {
            val ex = Reflect.getObjectField(view, "mExParams") ?: return@runCatching 0f
            Reflect.getObjectField(ex, "dotPadding") as? Float ?: 0f
        }.getOrDefault(0f)
        // mFirstOverflowIndex 在部分 ROM 上要等下一帧才更新；用自定义上限做
        // 下界兜底，避免新增通知后仍按旧 first 画出偏少的圆点。
        val maxIcons = runCatching {
            Reflect.getObjectField(parent, "mMaxIcons") as? Int
        }.getOrNull() ?: Int.MAX_VALUE
        val effectiveFirst = if (maxIcons in 1 until parent.childCount) {
            minOf(first, maxIcons)
        } else {
            first
        }
        val count = (parent.childCount - effectiveFirst).coerceIn(1, 3)
        return DotDrawState(count, progress, radius, color, padding)
    }

    /**
     * 只对已知系统场景应用自定义格式；未知 MiuiClock 返回 null，避免把通知中心/控制中心
     * 的时钟误改成状态栏样式。对应场景未开启自定义开关时也返回 null，保持宿主原生格式。
     */
    fun clockText(context: Context, viewId: Int): CharSequence? {
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return null
        val pattern = clockPattern(context, viewId) ?: return null
        return withClockAmPm(
            runCatching {
                SimpleDateFormat(normalizeClockPattern(pattern), Locale.getDefault()).format(Date())
            }.getOrNull(),
        )
    }

    /**
     * 中文时段是宿主 DateFormat 的输出，无法只靠 SimpleDateFormat 格式控制。
     * 自定义格式和原生格式最终都经过这里，保证两种路径行为一致。
     */
    fun withClockAmPm(text: CharSequence?): CharSequence? {
        if (!HookPrefs.getBoolean(StatusBarIconKeys.CLOCK_AM_PM, false)) return text
        return ClockAmPm.replace(text)
    }

    fun applyClockAmPm(view: TextView) {
        val replaced = withClockAmPm(view.text) ?: return
        if (!TextUtils.equals(view.text, replaced)) view.text = replaced
    }

    private fun clockPattern(context: Context, viewId: Int): String? {
        val scene = clockScene(context, viewId) ?: return null
        val patternKey = clockPatternKey(scene)
        if (!HookPrefs.getBoolean(StatusBarIconKeys.clockCustom(patternKey), false)) return null
        val stored = HookPrefs.getString(patternKey, "").orEmpty().trim()
        if (stored.isNotEmpty() && stored != StatusBarIconKeys.LEGACY_HORIZONTAL_CLOCK_PATTERN) {
            return stored
        }
        // 打开自定义但还没写入格式时，默认值就是当前系统格式；横屏额外带上
        // 日期与星期之间的空格，避免生成“M月d日周四”这种连在一起的样式。
        return systemClockPattern(context, scene)
    }

    private fun systemClockPattern(context: Context, scene: String): String {
        val time = runCatching {
            (android.text.format.DateFormat.getTimeFormat(context) as? SimpleDateFormat)?.toPattern()
        }.getOrNull().orEmpty().ifBlank { "HH:mm" }
        val date = runCatching {
            android.text.format.DateFormat.getBestDateTimePattern(
                Locale.getDefault(),
                "EMMMd",
            )
        }.getOrNull().orEmpty().ifBlank { "E, MMM d" }
        return when (scene) {
            "notification_small", "control_center", "pad_clock" -> date
            "horizontal" -> "$time ${spaceBeforeWeekday(date)}"
            else -> time
        }
    }

    /** 日期与星期之间补一个空格；星期在最前或已有分隔符时保持原样。 */
    private fun spaceBeforeWeekday(pattern: String): String {
        val index = pattern.indexOf("EEE")
        if (index <= 0) return pattern
        val previous = pattern[index - 1]
        if (previous.isWhitespace() ||
            previous == '(' || previous == '（' ||
            previous == ',' || previous == '，'
        ) {
            return pattern
        }
        return pattern.substring(0, index) + " " + pattern.substring(index)
    }

    /**
     * 用户常按 HyperHelper 示例填写 HH:MM:SS；在纯时间格式里把大写 M/S 视为分/秒。
     * 含日期（yyyy/dd/MMM）时保持 SimpleDateFormat 原语义，避免把月份改成分钟。
     */
    private fun normalizeClockPattern(pattern: String): String {
        if (pattern.any { it == 'm' || it == 's' }) return pattern
        if (pattern.contains("yy") || pattern.contains("dd") || pattern.contains("MMM")) return pattern
        return pattern.replace("MM", "mm").replace("SS", "ss")
    }

    fun registerClock(view: TextView) {
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return
        if (clockScene(view.context, view.id) == null) return
        clockViews[view] = true
        ensureClockTicker()
    }

    fun rememberClockText(view: TextView) {
        if (!originalClockTexts.containsKey(view)) {
            originalClockTexts[view] = view.text
        }
    }

    /** 只对状态栏时钟应用字重；通知中心/控制中心时钟不被组件字重影响。 */
    fun applyClockWeight(view: TextView) {
        if (restoringClock) return
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return
        if (clockScene(view.context, view.id) != "status") return
        val enabled = HookPrefs.getBoolean(
            StatusBarIconKeys.fontWeightEnabled(StatusBarIconRegistry.CLOCK),
            false,
        )
        val weight = if (enabled) {
            HookPrefs.getIntFlexible(
                StatusBarIconKeys.fontWeight(StatusBarIconRegistry.CLOCK),
                StatusBarIconRegistry.recommendedFontWeight(StatusBarIconRegistry.CLOCK),
            ).coerceIn(100, 900)
        } else {
            0
        }
        applyTextWeight(view, weight)
    }

    /** 状态栏时钟固定宽度；开关关闭时恢复宿主原布局宽度。 */
    fun applyClockLockWidth(view: TextView) {
        if (restoringClock) return
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) {
            if (clockScene(view.context, view.id) == "status") restoreClock(view)
            return
        }
        if (clockScene(view.context, view.id) != "status") return
        val params = view.layoutParams ?: return
        if (!clockWidths.containsKey(view)) {
            clockWidths[view] = params.width
        }
        val base = clockWidths[view] ?: params.width
        if (!HookPrefs.getBoolean(StatusBarIconKeys.CLOCK_LOCK_WIDTH, false)) {
            restoreClockWidth(view, base)
            return
        }
        val widthDp = HookPrefs.getIntFlexible(StatusBarIconKeys.CLOCK_WIDTH, 60)
            .coerceIn(20, 240)
        val target = (widthDp * view.resources.displayMetrics.density).roundToInt()
        if (params.width != target) {
            params.width = target
            view.layoutParams = params
            view.requestLayout()
        }
    }

    private fun restoreClockWidth(view: TextView, base: Int) {
        val params = view.layoutParams ?: return
        if (params.width != base) {
            params.width = base
            view.layoutParams = params
            view.requestLayout()
        }
    }

    fun restoreClock(view: TextView) {
        clockWidths.remove(view)?.let { restoreClockWidth(view, it) }
        if (originalClockTexts.containsKey(view)) {
            val original = originalClockTexts.remove(view)
            if (view.text != original) view.text = original
        }
        restoreTextWeight(view)
        clockViews.remove(view)
        if (!restoringClock) {
            restoringClock = true
            runCatching { Reflect.callMethod(view, "updateTime") }
                .onFailure {
                    // Clock 没有 updateTime 时只能恢复字重/宽度，文本由原生下一次刷新恢复。
                }
            restoringClock = false
        }
    }

    fun restoreClocks() {
        clockViews.keys.toList().forEach(::restoreClock)
        clockViews.clear()
    }

    fun applyTextWeight(root: View, weight: Int) {
        val views = ArrayList<TextView>()
        collectTextViews(root, views)
        views.forEach { view ->
            if (!originalTypefaces.containsKey(view)) {
                originalTypefaces[view] = view.typeface
            }
            val base = originalTypefaces[view]
            val desiredWeight = weight.coerceIn(100, 900)
            val desired = if (weight > 0) {
                Typeface.create(base ?: Typeface.DEFAULT, desiredWeight, base?.isItalic == true)
            } else {
                base
            }
            if (view.typeface != desired) view.typeface = desired
            // 静态字体只能就近取字重；可变字体通过 wght 轴拿到 100..900 的连续字重，
            // 否则细体/中等字重会统一回落到 Regular，看起来只有“更粗”有效。
            runCatching {
                if (!originalVariations.containsKey(view)) {
                    originalVariations[view] = view.paint.fontVariationSettings
                }
                val variation = if (weight > 0) {
                    "'wght' $desiredWeight"
                } else {
                    originalVariations[view]
                }
                if (view.paint.fontVariationSettings != variation) {
                    view.paint.fontVariationSettings = variation
                    view.invalidate()
                }
            }
        }
    }

    fun applyTextSize(root: View, sizeSp: Float) {
        val views = ArrayList<TextView>()
        collectTextViews(root, views)
        views.forEach { view ->
            if (!originalTextSizes.containsKey(view)) {
                originalTextSizes[view] = view.textSize
            }
            if (sizeSp > 0f) {
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp.coerceIn(8f, 32f))
            } else {
                originalTextSizes.remove(view)?.let { base ->
                    view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base)
                }
            }
        }
    }

    fun restoreTextWeight(root: View) {
        val views = ArrayList<TextView>()
        collectTextViews(root, views)
        views.forEach { view ->
            if (originalTypefaces.containsKey(view)) {
                val base = originalTypefaces.remove(view)
                if (view.typeface != base) view.typeface = base
            }
            runCatching {
                if (originalVariations.containsKey(view)) {
                    val base = originalVariations.remove(view)
                    if (view.paint.fontVariationSettings != base) {
                        view.paint.fontVariationSettings = base
                        view.invalidate()
                    }
                }
            }
            originalTextSizes.remove(view)?.let { base ->
                view.setTextSize(TypedValue.COMPLEX_UNIT_PX, base)
            }
        }
    }

    private fun ensureClockTicker() {
        if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) {
            if (clockTickerRunning) {
                clockTickerRunning = false
                clockHandler.removeCallbacks(clockTicker)
            }
            return
        }
        // 格式含秒时自动按 1s 刷新，避免用户忘记打开开关时秒数停在 00；
        // 开关本身仍可用于强制刷新不含秒的格式。
        val patternHasSeconds = listOf(
            StatusBarIconKeys.CLOCK_PATTERN_STATUS,
            StatusBarIconKeys.CLOCK_PATTERN_NOTIFICATION_BIG,
            StatusBarIconKeys.CLOCK_PATTERN_NOTIFICATION_SMALL,
            StatusBarIconKeys.CLOCK_PATTERN_CONTROL_CENTER,
            StatusBarIconKeys.CLOCK_PATTERN_HORIZONTAL,
            StatusBarIconKeys.CLOCK_PATTERN_PAD,
        ).any { key ->
            HookPrefs.getBoolean(StatusBarIconKeys.clockCustom(key), false) &&
                HookPrefs.getString(key, "").orEmpty().any { it == 's' || it == 'S' }
        }
        val shouldRun = patternHasSeconds ||
            HookPrefs.getBoolean(StatusBarIconKeys.CLOCK_REFRESH, false)
        if (shouldRun == clockTickerRunning) return
        clockTickerRunning = shouldRun
        if (shouldRun) clockHandler.post(clockTicker) else clockHandler.removeCallbacks(clockTicker)
    }

    private fun clockScene(context: Context, viewId: Int): String? {
        if (viewId == 0) return null
        return when (viewId) {
            id(context, "clock") -> "status"
            id(context, "big_time") -> "notification_big"
            id(context, "date_time") -> "notification_small"
            id(context, "normal_control_center_date_view") -> "control_center"
            // 横屏时钟格式只对手机开放；折叠屏/平板走系统默认格式。
            id(context, "horizontal_time") ->
                if (DeviceContext.current.type == DeviceType.PHONE) "horizontal" else null
            // pad_clock 只在平板由 ViewStub 展开，独立显示“月份 日期 星期”。
            id(context, "pad_clock") -> if (isTablet(context)) "pad_clock" else null
            else -> null
        }
    }

    private fun clockPatternKey(scene: String): String = when (scene) {
        "notification_big" -> StatusBarIconKeys.CLOCK_PATTERN_NOTIFICATION_BIG
        "notification_small" -> StatusBarIconKeys.CLOCK_PATTERN_NOTIFICATION_SMALL
        "control_center" -> StatusBarIconKeys.CLOCK_PATTERN_CONTROL_CENTER
        "horizontal" -> StatusBarIconKeys.CLOCK_PATTERN_HORIZONTAL
        "pad_clock" -> StatusBarIconKeys.CLOCK_PATTERN_PAD
        else -> StatusBarIconKeys.CLOCK_PATTERN_STATUS
    }

    private fun isTablet(context: Context): Boolean =
        context.resources.configuration.smallestScreenWidthDp >= 600

    private fun id(context: Context, name: String): Int = synchronized(idCache) {
        idCache.getOrPut(name) {
            context.resources.getIdentifier(name, "id", StatusBarIconKeys.TARGET)
        }
    }

    private fun collectTextViews(view: View, out: MutableList<TextView>) {
        if (view is TextView) out.add(view)
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collectTextViews(view.getChildAt(i), out)
        }
    }

    private class Host(root: ViewGroup) {
        private val rootRef = WeakReference(root)
        private val context: Context = root.context
        private val density = context.resources.displayMetrics.density
        private val base = IdentityHashMap<View, BaseView>()
        private val trackedIds = IdentityHashMap<View, String>()
        private val lastHidden = IdentityHashMap<View, Boolean>()
        private val forcedVisible = IdentityHashMap<View, Boolean>()
        /** 强制取消原生 blocked 前的状态；离开强制可见后必须恢复，避免留下空槽位。 */
        private val originalBlocked = IdentityHashMap<View, Boolean>()
        /** 首次接管前 MiuiStatusIconContainer 的真实 ignoredSlots；RIGHT_BLOCK_LIST 并不完整。 */
        private var capturedNativeIgnored: List<String>? = null
        /** 每个 slot 在模块首次接管前的 ignored 归属；用于从自定义模式回到 SYSTEM 时还原。 */
        private val nativeIgnoredBySlot = HashMap<String, Boolean>()
        /** 只记录模块自己加进 ignoredSlots 的 slot，SYSTEM 时只撤销这些，绝不误删原生忽略。 */
        private val moduleIgnored = HashSet<String>()
        /** 控制器级 mIconHideList 的原生归属；它同时作用于状态栏和 CC fake 图标组。 */
        private val nativeHideBySlot = HashMap<String, Boolean>()
        /** 只记录模块自己加进 mIconHideList 的 slot；SYSTEM 时只撤销这些。 */
        private val moduleHidden = HashSet<String>()
        /** 原生状态栏/控制中心各自使用的 slot 黑名单；这是系统自己控制显隐的入口。 */
        /** 每个容器首次接管前的 ignoredSlots，保留 mini 模式等原生额外项。 */
        private val transformed = IdentityHashMap<View, Boolean>()
        private val spaced = IdentityHashMap<View, Boolean>()
        private val aligned = IdentityHashMap<View, String>()
        private val attached = AtomicBoolean(false)
        private var lastIgnored: List<String>? = null
        private var lastCc = false
        private var lastMaxNotifications = -1
        private var lastNotificationCount = -1
        private var lastAlarmRefresh = 0L
        private var currentSnapshot: StatusBarIconSnapshot? = null
        private var preDraw: ViewTreeObserver.OnPreDrawListener? = null
        private var refreshPosted = false

        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refresh()
            }
        }

        private val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(android.app.AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }

        init {
            register()
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = register()
                override fun onViewDetachedFromWindow(v: View) = unregister()
            })
            preDraw = ViewTreeObserver.OnPreDrawListener {
                val cc = controlCenterExpanded()
                if (cc != lastCc) refresh()
                reconcileSpecialVisibility()
                true
            }.also { root.viewTreeObserver.addOnPreDrawListener(it) }
            refresh()
        }

        fun postRefresh() {
            if (refreshPosted) return
            val root = rootRef.get() ?: return
            refreshPosted = true
            root.post {
                refreshPosted = false
                refresh()
            }
        }

        fun refresh() {
            val root = rootRef.get() ?: return
            val snapshot = StatusBarIconConfigReader.load()
            ensureClockTicker()
            if (!snapshot.enabled) {
                restore(root)
                return
            }
            val cc = controlCenterExpanded()
            lastCc = cc
            currentSnapshot = snapshot
            val statusIcons = find(root, "statusIcons") as? ViewGroup
            val notificationContainer = notificationArea(root)?.let {
                find(it, "notificationIcons") ?: firstChildOfType(it, "NotificationIconContainer")
            } as? ViewGroup
            val evaluator = StatusBarIconConditionEvaluator(
                context = context,
                notificationCount = {
                    notificationContainer?.let { container ->
                        (0 until container.childCount).count {
                            container.getChildAt(it).visibility == View.VISIBLE
                        }
                    } ?: 0
                },
                speedText = {
                    statusIcons?.let { icons ->
                        findSpeedView(icons)?.let { speed ->
                            val n = runCatching {
                                Reflect.getObjectField(speed, "mNetworkSpeedNumber")?.toString()
                            }.getOrNull().orEmpty()
                            val u = runCatching {
                                Reflect.getObjectField(speed, "mNetworkSpeedUnit")?.toString()
                            }.getOrNull().orEmpty()
                            n + u
                        }
                    }.orEmpty()
                },
            )

            // 回退到上次提交的显示逻辑：控制器 hide list + 按容器逐 View 同步。
            applyGlobalHidden(snapshot, cc, evaluator)
            applySystemIcons(statusIcons, snapshot, cc, evaluator)
            applyGroupVisibility(snapshot, cc, evaluator)
            applySpecial(root, snapshot, cc, evaluator)
            applyClockText()
            applyNotificationMax(notificationContainer, snapshot)
            statusIcons?.let { icons ->
                findSpeedView(icons)?.let {
                    applyNetworkSpeed(it, snapshot.config(StatusBarIconRegistry.NETWORK_SPEED))
                }
            }
            ensureClockTicker()
        }

        /**
         * 同步 StatusBarIconControllerImpl.mIconHideList。
         *
         * 状态栏和展开的控制中心各有一组 MiuiStatusIconContainer/IconManager；
         * 只改 statusIcons 的 ignoredSlots 时，控制中心 fake 组仍会显示 VPN。
         * 控制器级 hide list 由 refreshIconGroup 应用到所有组。
         */
        private fun applyGlobalHidden(
            snapshot: StatusBarIconSnapshot,
            cc: Boolean,
            evaluator: StatusBarIconConditionEvaluator,
        ) {
            val controller = controllerRef?.get() ?: return
            val hideList = runCatching {
                Reflect.getObjectField(controller, "mIconHideList")
            }.getOrNull() ?: return
            var changed = false
            StatusBarIconRegistry.right.forEach { component ->
                val slot = component.slot ?: component.hideSlot ?: return@forEach
                val config = snapshot.config(component.id)
                val customDisplay = config.display.isNotBlank() &&
                    config.display != StatusBarIconMode.SYSTEM.value ||
                    config.mode != StatusBarIconMode.SYSTEM ||
                    config.logic != StatusBarIconLogic.DEFAULT
                val nativeHidden = nativeHideBySlot.getOrPut(slot) {
                    hideListContains(hideList, slot)
                }
                val current = hideListContains(hideList, slot)
                if (customDisplay) {
                    val display = displayOf(config)
                    val surfaceMode = display == StatusBarIconMode.ALWAYS.value ||
                        display == StatusBarIconMode.CC_ONLY_SHOW.value ||
                        display == StatusBarIconMode.CC_ONLY_HIDE.value
                    // 这三个模式按状态栏/CC 宿主分别显隐；绝不能写控制器级
                    // hide list，否则会把另一个宿主也一起隐藏。
                    val desired = if (surfaceMode) {
                        false
                    } else {
                        display == StatusBarIconMode.HIDDEN.value ||
                            (display.startsWith("condition:") &&
                                hidden(component, config, cc, evaluator))
                    }
                    if (desired) {
                        moduleHidden += slot
                        if (!current) {
                            runCatching { Reflect.callMethod(hideList, "add", slot) }
                            changed = true
                        }
                    } else {
                        moduleHidden.remove(slot)
                        if (current) {
                            runCatching { Reflect.callMethod(hideList, "remove", slot) }
                            changed = true
                        }
                    }
                } else if (nativeHidden) {
                    // SYSTEM 下恢复原生隐藏；原生后续动态加入的隐藏项保持不动。
                    if (!current) {
                        runCatching { Reflect.callMethod(hideList, "add", slot) }
                        changed = true
                    }
                } else if (slot in moduleHidden) {
                    moduleHidden.remove(slot)
                    if (current) {
                        runCatching { Reflect.callMethod(hideList, "remove", slot) }
                        changed = true
                    }
                }
            }
            if (!changed) return
            refreshIconGroups(controller)
        }

        private fun hideListContains(hideList: Any, slot: String): Boolean = runCatching {
            Reflect.callMethod(hideList, "contains", slot) as? Boolean ?: false
        }.getOrDefault(false)

        /**
         * `ModernStatusBarWifiView` 等新图标把 `setBlocked` 实现成空方法，控制器 hide list
         * 对它们无效。这里直接遍历控制器注册的每个图标组（包括控制中心 fake 组），
         * 只对显式自定义显示模式的组件按当前展开状态设置 View 可见性。
         */
        private fun applyGroupVisibility(
            snapshot: StatusBarIconSnapshot,
            cc: Boolean,
            evaluator: StatusBarIconConditionEvaluator,
        ) {
            val liveGroups = iconGroups.mapNotNull { it.get() }.filter { it.isAttachedToWindow }
            if (liveGroups.isEmpty()) return
            liveGroups.forEach { group ->
                val currentIgnored = currentIgnoredSlots(group).toMutableList()
                val localIgnored = groupModuleIgnored.getOrPut(group) { HashSet() }
                var ignoredChanged = false
                // 过渡 fake 组跟随普通状态栏，而不是控制中心；正常 CC 头才用 CC 语义。
                val fake = isFakeControlCenterGroup(group)
                val center = isControlCenterGroup(group) && !fake
                val surfaceCc = if (fake) false else cc
                for (i in 0 until group.childCount) {
                    val child = group.getChildAt(i)
                    val slot = runCatching {
                        Reflect.callMethod(child, "getSlot") as? String
                    }.getOrNull() ?: continue
                    val component = StatusBarIconRegistry.right.firstOrNull { it.slot == slot }
                        ?: continue
                    val config = snapshot.config(component.id)
                    val display = displayOf(config)
                    // 仅控制中心显示/隐藏时，单靠 View.GONE 仍会在容器里留下
                    // 一个 iconSize 宽的占位；必须把 slot 加进该容器的
                    // ignoredSlots，onLayout 才会真正跳过它。
                    val surfaceHide = when (display) {
                        StatusBarIconMode.CC_ONLY_SHOW.value -> !center
                        StatusBarIconMode.CC_ONLY_HIDE.value -> center
                        else -> null
                    }
                    if (surfaceHide != null) {
                        if (surfaceHide) {
                            localIgnored += slot
                            if (slot !in currentIgnored) {
                                currentIgnored += slot
                                ignoredChanged = true
                            }
                        } else if (localIgnored.remove(slot)) {
                            currentIgnored.remove(slot)
                            ignoredChanged = true
                        }
                    } else if (localIgnored.remove(slot)) {
                        currentIgnored.remove(slot)
                        ignoredChanged = true
                    }
                    // 控制中心 fake 组与状态栏组共用同一份几何/字重配置。
                    applyGeometry(child, config)
                    if (component.textual) {
                        applyTextWeight(child, if (config.fontWeightEnabled) config.fontWeight else 0)
                        if (component.id != StatusBarIconRegistry.NETWORK_SPEED) {
                            applyTextSize(child, if (config.fontSizeEnabled) config.fontSize else 0f)
                        }
                    }
                    if (!hasCustomDisplay(config)) {
                        if (groupHidden.remove(child) == true) {
                            child.visibility = if (nativeIconVisible(child)) {
                                View.VISIBLE
                            } else {
                                View.GONE
                            }
                        }
                        continue
                    }
                    val hide = hidden(component, config, surfaceCc, evaluator)
                    if (hide) {
                        groupHidden[child] = true
                        if (child.visibility != View.GONE) child.visibility = View.GONE
                    } else {
                        val forceVisible = config.mode == StatusBarIconMode.ALWAYS ||
                            (config.mode == StatusBarIconMode.CC_ONLY_SHOW && surfaceCc) ||
                            (config.mode == StatusBarIconMode.CC_ONLY_HIDE && !surfaceCc) ||
                            config.display.startsWith("condition:")
                        if ((groupHidden.remove(child) == true || forceVisible) &&
                            nativeIconVisible(child)
                        ) {
                            if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
                        }
                    }
                }
                // 图标暂时消失（VPN 断开等）时清掉模块残留的 ignored，避免下次
                // 重新出现时占位仍在。
                localIgnored.toList().forEach { slot ->
                    if (findChildBySlot(group, slot) == null) {
                        localIgnored.remove(slot)
                        currentIgnored.remove(slot)
                        ignoredChanged = true
                    }
                }
                if (ignoredChanged) {
                    runCatching {
                        Reflect.callMethod(group, "setIgnoredSlots", currentIgnored.toList())
                        group.requestLayout()
                    }
                }
                if (localIgnored.isEmpty()) groupModuleIgnored.remove(group)
            }
        }

        private fun hasCustomDisplay(config: StatusBarIconComponentConfig): Boolean =
            (config.display.isNotBlank() && config.display != StatusBarIconMode.SYSTEM.value) ||
                config.mode != StatusBarIconMode.SYSTEM ||
                config.logic != StatusBarIconLogic.DEFAULT

        private fun displayOf(config: StatusBarIconComponentConfig): String = when {
            config.display.isNotBlank() -> config.display
            config.logic == StatusBarIconLogic.CUSTOM &&
                config.condition != StatusBarIconCondition.NONE ->
                "condition:${config.condition.value}"
            else -> config.mode.value
        }

        private fun isInsideControlCenter(view: View): Boolean {
            var parent = view.parent
            while (parent is View) {
                if (parent.javaClass.simpleName.contains("ControlCenter")) return true
                parent = parent.parent
            }
            return false
        }

        private fun isControlCenterGroup(group: ViewGroup): Boolean {
            var parent = group.parent
            while (parent is View) {
                if (parent.javaClass.simpleName.contains("ControlCenter")) return true
                parent = parent.parent
            }
            return false
        }

        private fun isFakeControlCenterGroup(group: ViewGroup): Boolean {
            var parent = group.parent
            while (parent is View) {
                if (parent.javaClass.simpleName == "ControlCenterFakeStatusIcons") return true
                parent = parent.parent
            }
            return false
        }

        /**
         * 权限指示在状态栏和 CC 各有一份宿主。前者是 `privacy_area`；后者是
         * `MiuiPrivacyContainerView` / `MiuiFakePrivacyContainerView`，不在状态栏 root 下，
         * 只能从各个 iconGroup 所在窗口的 rootView 去找。CC 展开后容器会重建，所以每次 refresh 重扫。
         */
        private fun privacyContainers(root: View): List<View> {
            val result = ArrayList<View>()
            val seen = Collections.newSetFromMap(IdentityHashMap<View, Boolean>())

            fun scan(view: View?) {
                view ?: return
                if (!seen.add(view)) return
                if (view.javaClass.simpleName == "MiuiPrivacyContainerView" ||
                    view.javaClass.simpleName == "MiuiFakePrivacyContainerView"
                ) {
                    result += view
                }
                if (view is ViewGroup) {
                    for (index in 0 until view.childCount) scan(view.getChildAt(index))
                }
            }

            scan(root)
            iconGroups.mapNotNull { it.get() }.forEach { group -> scan(group.rootView) }
            return result
        }



        private fun restoreGlobalHidden() {
            if (nativeHideBySlot.isEmpty()) return
            val controller = controllerRef?.get() ?: return
            val hideList = runCatching {
                Reflect.getObjectField(controller, "mIconHideList")
            }.getOrNull() ?: return
            var changed = false
            nativeHideBySlot.forEach { (slot, nativeHidden) ->
                val current = hideListContains(hideList, slot)
                if (nativeHidden != current) {
                    runCatching {
                        Reflect.callMethod(
                            hideList,
                            if (nativeHidden) "add" else "remove",
                            slot,
                        )
                    }
                    changed = true
                }
            }
            if (changed) refreshIconGroups(controller)
        }

        private fun refreshIconGroups(controller: Any) {
            val groups = runCatching {
                Reflect.getObjectField(controller, "mIconGroups") as? List<*>
            }.getOrNull().orEmpty()
            groups.forEach { manager ->
                runCatching { Reflect.callMethod(controller, "refreshIconGroup", manager) }
            }
        }

        /**
         * 采用系统自己的 slot 黑名单，而不是逐个 View 强制 VISIBLE/GONE。
         *
         * MiuiIconManagerUtils.RIGHT_BLOCK_LIST / CONTROL_CENTER_BLOCK_LIST 是
         * 状态栏和 CC 两套容器各自读取的原生列表；系统默认路径也走它们。
         * 只改这两个列表，CC 正常头/fake 组由原生选择，模块不重复点亮图标。
         */
        private fun applySystemIcons(
            group: ViewGroup?,
            snapshot: StatusBarIconSnapshot,
            cc: Boolean,
            evaluator: StatusBarIconConditionEvaluator,
        ) {
            group ?: return
            val currentIgnored = currentIgnoredSlots(group)
            if (capturedNativeIgnored == null && currentIgnored.isNotEmpty()) {
                capturedNativeIgnored = currentIgnored
            }
            val ignored = currentIgnored.toMutableList()
            StatusBarIconRegistry.right.forEach { component ->
                val slot = component.slot ?: return@forEach
                // 只对已经存在的原生 View 记录归属；vpn 等 View 可能在连接后才出现。
                val child = findChildBySlot(group, slot) ?: return@forEach
                val config = snapshot.config(component.id)
                val nativeWasIgnored = nativeIgnoredBySlot.getOrPut(slot) {
                    slot in currentIgnored
                }
                val display = displayOf(config)
                val customDisplay = hasCustomDisplay(config)
                val isCondition = display.startsWith("condition:")
                // ignoredSlots 是控制器级、状态栏与 CC fake 组共用的。CC_ONLY_* 和
                // ALWAYS 不能把 slot 加进去，否则 CC 里的图标也会一起被忽略。
                val globalHide = display == StatusBarIconMode.HIDDEN.value ||
                    (isCondition && hidden(component, config, cc, evaluator))
                val surfaceOnly = display == StatusBarIconMode.CC_ONLY_SHOW.value ||
                    display == StatusBarIconMode.CC_ONLY_HIDE.value
                if (surfaceOnly) {
                    // ignoredSlots 由 applyGroupVisibility 按状态栏/CC 宿主分别维护；
                    // 这里再改会把另一个宿主的占位状态覆盖掉。
                } else when {
                    globalHide -> {
                        if (slot !in ignored) ignored += slot
                        moduleIgnored += slot
                    }
                    !customDisplay -> {
                        // SYSTEM 只撤销模块自己加的 ignored，绝不误删原生忽略（例如 zen）。
                        if (nativeWasIgnored) {
                            if (slot !in ignored) ignored += slot
                        } else if (slot in moduleIgnored) {
                            ignored.remove(slot)
                            moduleIgnored.remove(slot)
                        }
                    }
                    else -> {
                        // 原生没有图标内容时保留 ignored，避免“有占位但不可见”。
                        if (isIconVisible(child)) {
                            ignored.remove(slot)
                            moduleIgnored.remove(slot)
                        } else if (nativeWasIgnored) {
                            if (slot !in ignored) ignored += slot
                        }
                    }
                }
            }
            if (ignored != lastIgnored) {
                runCatching {
                    Reflect.callMethod(group, "setIgnoredSlots", ignored.toList())
                    lastIgnored = ignored.toList()
                    // MiuiStatusIconContainer.setIgnoredSlots 只改 List，不主动 requestLayout。
                    group.requestLayout()
                }
            }
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) ?: continue
                val slot = runCatching { Reflect.callMethod(child, "getSlot") as? String }.getOrNull()
                    ?: continue
                val component = StatusBarIconRegistry.right.firstOrNull { it.slot == slot } ?: continue
                val config = snapshot.config(component.id)
                trackedIds[child] = component.id
                val wasHidden = lastHidden[child] == true
                val display = displayOf(config)
                val isCondition = display.startsWith("condition:")
                val conditionHidden = isCondition && hidden(component, config, cc, evaluator)
                // applySystemIcons 只处理真实状态栏组；CC 组由 applyGroupVisibility
                // 按宿主分别处理。条件图标不能在 CC 展开时收掉真实状态栏这一份：
                // CC 过渡使用临时容器，那一份不一定已经就绪，收掉就会整段消失。
                val hide = when {
                    display == StatusBarIconMode.HIDDEN.value || conditionHidden -> true
                    display == StatusBarIconMode.CC_ONLY_SHOW.value -> true
                    display == StatusBarIconMode.CC_ONLY_HIDE.value -> false
                    // 始终显示：状态栏和 CC 头部都要保留。CC 过渡使用临时容器，
                    // 这里不能按 cc 把真实状态栏的图标收掉，否则下拉瞬间会淡出。
                    display == StatusBarIconMode.ALWAYS.value -> false
                    isCondition -> false
                    else -> false
                } || (component.id == StatusBarIconRegistry.NETWORK_SPEED && isSlowSpeed(child, config))
                lastHidden[child] = hide
                val iconVisible = isIconVisible(child)
                val forceVisible = !hide && iconVisible && when {
                    display == StatusBarIconMode.CC_ONLY_HIDE.value -> true
                    display == StatusBarIconMode.ALWAYS.value -> true
                    isCondition -> true
                    else -> false
                }
                val wasForced = forcedVisible[child] == true
                forcedVisible[child] = forceVisible
                if (forceVisible) {
                    if (!originalBlocked.containsKey(child)) {
                        originalBlocked[child] = runCatching {
                            Reflect.getObjectField(child, "mBlocked") as? Boolean ?: false
                        }.getOrDefault(false)
                    }
                    // always / cc_only / condition 的可见态由本模块接管；解除原生 blocked。
                    runCatching { Reflect.callMethod(child, "setBlocked", false) }
                }
                var blockedRestored = false
                if (!forceVisible && wasForced) {
                    originalBlocked.remove(child)?.let { blocked ->
                        runCatching {
                            Reflect.callMethod(child, "setBlocked", blocked)
                            blockedRestored = blocked
                        }
                    }
                }
                when {
                    hide -> if (child.visibility != View.GONE) child.visibility = View.GONE
                    forceVisible -> if (child.visibility != View.VISIBLE) child.visibility = View.VISIBLE
                    wasForced -> {
                        // 原生 blocked=true 时 setBlocked 已经会切到 GONE，不能再按 base 强行显示。
                        if (!blockedRestored) {
                            child.visibility = if (nativeIconVisible(child)) {
                                capture(child).visibility
                            } else {
                                View.GONE
                            }
                        }
                    }
                    wasHidden -> {
                        child.visibility = if (nativeIconVisible(child)) {
                            capture(child).visibility
                        } else {
                            View.GONE
                        }
                    }
                }
                applyGeometry(child, config)
                if (component.textual) {
                    applyTextWeight(
                        child,
                        if (config.fontWeightEnabled) config.fontWeight else 0,
                    )
                    if (component.id != StatusBarIconRegistry.NETWORK_SPEED) {
                        applyTextSize(child, if (config.fontSizeEnabled) config.fontSize else 0f)
                    }
                }
            }
            // NetworkSpeedView 不是 StatusIconDisplayable，没有 getSlot；仍需进入
            // trackedIds，OnPreDraw 才能应用偏移、缩放、透明度和文字对齐。
            findSpeedView(group)?.let { speed ->
                if (trackedIds[speed] == null) {
                    val config = snapshot.config(StatusBarIconRegistry.NETWORK_SPEED)
                    trackedIds[speed] = StatusBarIconRegistry.NETWORK_SPEED
                    applyGeometry(speed, config)
                    applyTextWeight(speed, if (config.fontWeightEnabled) config.fontWeight else 0)
                }
            }
        }

        private fun applySpecial(
            root: ViewGroup,
            snapshot: StatusBarIconSnapshot,
            cc: Boolean,
            evaluator: StatusBarIconConditionEvaluator,
        ) {
            val clock = find(root, "clock")
            val padClock = find(root, "pad_clock")
            val battery = find(root, "battery")
            val batteryIndicator = find(root, "battery_indicator")
            val notifications = find(root, "notification_icon_area")
            val fullscreenNotifications = find(root, "fullscreen_notification_icon_area")
            val privacy = find(root, "privacy_area")
            val operator = find(root, "operator_name")

            applySpecialView(clock, StatusBarIconRegistry.CLOCK, snapshot, cc, evaluator)
            applySpecialView(padClock, StatusBarIconRegistry.PAD_CLOCK, snapshot, cc, evaluator)
            applySpecialView(battery, StatusBarIconRegistry.BATTERY, snapshot, cc, evaluator)
            batteryViews.mapNotNull { it.get() }.forEach { extra ->
                if (extra !== battery) {
                    applySpecialView(extra, StatusBarIconRegistry.BATTERY, snapshot, cc, evaluator)
                }
            }
            if (batteryIndicator !== battery) {
                applySpecialView(batteryIndicator, StatusBarIconRegistry.BATTERY, snapshot, cc, evaluator)
            }
            applySpecialView(notifications, StatusBarIconRegistry.NOTIFICATIONS, snapshot, cc, evaluator)
            if (fullscreenNotifications !== notifications) {
                applySpecialView(
                    fullscreenNotifications,
                    StatusBarIconRegistry.NOTIFICATIONS,
                    snapshot,
                    cc,
                    evaluator,
                )
            }
            applySpecialView(privacy, StatusBarIconRegistry.PRIVACY, snapshot, cc, evaluator)
            privacyContainers(root).forEach { container ->
                if (container !== privacy) {
                    applySpecialView(
                        container,
                        StatusBarIconRegistry.PRIVACY,
                        snapshot,
                        cc,
                        evaluator,
                    )
                }
            }
            applySpecialView(operator, StatusBarIconRegistry.OPERATOR, snapshot, cc, evaluator)

            listOfNotNull(clock, padClock).forEach { clockView ->
                val config = snapshot.config(
                    if (clockView === padClock) {
                        StatusBarIconRegistry.PAD_CLOCK
                    } else {
                        StatusBarIconRegistry.CLOCK
                    },
                )
                val previous = aligned[clockView]
                if (config.align != "system" || previous != null) {
                    val baseView = capture(clockView)
                    (clockView as? TextView)?.gravity = when (config.align) {
                        "start" -> Gravity.START or Gravity.CENTER_VERTICAL
                        "end" -> Gravity.END or Gravity.CENTER_VERTICAL
                        "center" -> Gravity.CENTER
                        else -> baseView.gravity
                    }
                    (clockView as? TextView)?.textAlignment = when (config.align) {
                        "start" -> View.TEXT_ALIGNMENT_VIEW_START
                        "end" -> View.TEXT_ALIGNMENT_VIEW_END
                        "center" -> View.TEXT_ALIGNMENT_CENTER
                        else -> baseView.textAlignment
                    }
                    aligned[clockView] = config.align
                }
                (clockView as? TextView)?.let(::applyClockLockWidth)
            }
        }

        private fun applySpecialView(
            view: View?,
            id: String,
            snapshot: StatusBarIconSnapshot,
            cc: Boolean,
            evaluator: StatusBarIconConditionEvaluator,
        ) {
            view ?: return
            val config = snapshot.config(id)
            trackedIds[view] = id
            val baseView = capture(view)
            val display = displayOf(config)
            val isCondition = display.startsWith("condition:")
            // 电池 / 权限指示在状态栏和 CC 各有一套宿主；特殊宿主显隐必须按所在宿主判断。
            val centerHost =
                (id == StatusBarIconRegistry.BATTERY || id == StatusBarIconRegistry.PRIVACY) &&
                    isInsideControlCenter(view)
            val conditionHidden = isCondition && hidden(
                StatusBarIconRegistry.byId(id)!!,
                config,
                if (centerHost) false else cc,
                evaluator,
            )
            // 这些是真实状态栏里的独立宿主；只有明确的“仅控制中心显示/隐藏”
            // 才按控制中心切换。条件图标不能在过渡期间收掉，否则整段消失。
            val hide = when {
                display == StatusBarIconMode.HIDDEN.value || conditionHidden -> true
                display == StatusBarIconMode.CC_ONLY_SHOW.value -> !centerHost
                display == StatusBarIconMode.CC_ONLY_HIDE.value -> centerHost
                // 始终显示同样不能被 CC 展开状态收掉；临时 CC 容器会单独绘制一份。
                display == StatusBarIconMode.ALWAYS.value -> false
                isCondition -> false
                else -> false
            }
            val wasHidden = lastHidden[view] == true
            lastHidden[view] = hide
            // 控制中心内的权限指示由原生 HeaderController 用 alpha / visibility 编排：
            // 展开时显示真容器，过渡动画时显示 fake 容器，两者不会同时出现。
            // 模块若在这里强行 VISIBLE，会把原生已淡出的真容器重新点亮，导致过渡期间
            // 控制中心圆点与动画圆点重叠。CC 宿主只允许模块“隐藏”，其余交给原生。
            val nativePrivacyHost = id == StatusBarIconRegistry.PRIVACY && centerHost
            val forceVisible = !hide && !nativePrivacyHost && hasVisibleContent(view, id) && when {
                display == StatusBarIconMode.CC_ONLY_SHOW.value -> centerHost
                display == StatusBarIconMode.CC_ONLY_HIDE.value -> !centerHost
                display == StatusBarIconMode.ALWAYS.value -> true
                isCondition -> true
                else -> false
            }
            val wasForced = forcedVisible[view] == true
            forcedVisible[view] = forceVisible
            when {
                hide -> {
                    if (view.visibility != View.GONE) view.visibility = View.GONE
                }
                nativePrivacyHost ->
                    if (wasHidden || wasForced) view.visibility = baseView.visibility
                forceVisible -> {
                    if (view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
                }
                wasForced -> view.visibility = baseView.visibility
                wasHidden -> view.visibility = baseView.visibility
                config.mode == StatusBarIconMode.SYSTEM &&
                    config.logic == StatusBarIconLogic.DEFAULT -> Unit
                else -> view.visibility = baseView.visibility
            }
            applyGeometry(view, config)
            val component = StatusBarIconRegistry.byId(id)
            if (component?.textual == true) {
                applyTextWeight(view, if (config.fontWeightEnabled) config.fontWeight else 0)
                applyTextSize(view, if (config.fontSizeEnabled) config.fontSize else 0f)
            }
        }

        private fun applyGeometry(view: View, config: StatusBarIconComponentConfig) {
            applyTransforms(view, config)
            val baseView = capture(view)
            val gapStart = (config.gapStart.coerceIn(-16f, 32f) * density).toInt()
            val gapEnd = (config.gapEnd.coerceIn(-16f, 32f) * density).toInt()
            val gapCustom = gapStart != 0 || gapEnd != 0
            val wasSpaced = spaced[view] == true
            if (!gapCustom) {
                if (wasSpaced) {
                    view.minimumWidth = baseView.minimumWidth
                    (view as? LinearLayout)?.gravity = baseView.layoutGravity
                    view.scrollTo(baseView.scrollX, view.scrollY)
                    view.requestLayout()
                }
                spaced[view] = false
                gapStates.remove(view)
                gapBaseWidths.remove(view)
                gapDeferred.remove(view)
                return
            }
            // 不能用 padding 做左右间距：MiuiStatusIconContainer.onLayout 会把
            // padding 再加一次到子 View 宽度上，导致左右间距被重复计算。
            // 也不能用 translationX：原生 onLayout 会用 layoutStates 重写它。
            // 改为“最小宽度占位 + scrollX 绘制位移”：
            //   占位宽 = 图标原始宽 + gapStart + gapEnd
            //   内容左移/右移 = (gapStart - gapEnd) / 2
            // scrollX 只移动绘制内容，不参与原生 layoutStates 计算。
            val baseWidth = gapBaseWidths[view] ?: run {
                val measured = view.measuredWidth.takeIf { it > 0 } ?: view.width
                if (measured <= 0) {
                    if (gapDeferred.put(view, true) == null) {
                        view.post {
                            gapDeferred.remove(view)
                            refreshAll()
                        }
                    }
                    return
                }
                gapBaseWidths[view] = measured
                measured
            }
            val target = (baseWidth + gapStart + gapEnd).coerceAtLeast(baseWidth)
            if (view.minimumWidth != target) {
                view.minimumWidth = target
                view.requestLayout()
            }
            if (view is LinearLayout) {
                // NetworkSpeedView 这类横向 LinearLayout 的子内容默认从 START 排，
                // 仅放大 minimumWidth 只会增加右侧空白；把它改为水平居中，
                // 才能让“内容位移”同时作用于左右两侧。
                val centered = (baseView.layoutGravity and Gravity.VERTICAL_GRAVITY_MASK) or
                    Gravity.CENTER_HORIZONTAL
                if (view.gravity != centered) view.gravity = centered
            }
            val offset = (gapStart - gapEnd) / 2f
            val state = gapStates[view] ?: GapState(baseView.scrollX, offset)
            gapStates[view] = state.copy(offset = offset)
            val targetScroll = state.baseScrollX - offset.roundToInt()
            if (view.scrollX != targetScroll) {
                view.scrollTo(targetScroll, view.scrollY)
            }
            spaced[view] = true
        }

        private fun applyTransforms(view: View, config: StatusBarIconComponentConfig) {
            val baseView = capture(view)
            val offset = config.offsetY.coerceIn(-24f, 24f) * density
            val scale = config.scale.coerceIn(0.3f, 3f)
            val alpha = config.alpha.coerceIn(0f, 1f)
            val custom = offset != 0f || scale != 1f || alpha != 1f
            val wasCustom = transformed[view] == true
            if (!custom && !wasCustom) return
            transformed[view] = custom
            if (view.translationY != baseView.translationY + offset) {
                view.translationY = baseView.translationY + offset
            }
            if (view.scaleX != baseView.scaleX * scale) view.scaleX = baseView.scaleX * scale
            if (view.scaleY != baseView.scaleY * scale) view.scaleY = baseView.scaleY * scale
            if (view.alpha != baseView.alpha * alpha) view.alpha = baseView.alpha * alpha
        }

        private fun capture(view: View): BaseView = base.getOrPut(view) {
            val params = view.layoutParams as? ViewGroup.MarginLayoutParams
            BaseView(
                visibility = view.visibility,
                translationX = view.translationX,
                translationY = view.translationY,
                scrollX = view.scrollX,
                scaleX = view.scaleX,
                scaleY = view.scaleY,
                alpha = view.alpha,
                marginStart = params?.marginStart ?: 0,
                marginEnd = params?.marginEnd ?: 0,
                paddingStart = view.paddingStart,
                paddingEnd = view.paddingEnd,
                minimumWidth = view.minimumWidth,
                gravity = (view as? TextView)?.gravity ?: 0,
                layoutGravity = (view as? LinearLayout)?.gravity ?: 0,
                textAlignment = (view as? TextView)?.textAlignment ?: View.TEXT_ALIGNMENT_GRAVITY,
            )
        }

        private fun restore(root: ViewGroup) {
            refreshIconBlockLists()
            restoreSpeedStyles()
            base.forEach { (view, value) ->
                runCatching {
                    view.visibility = value.visibility
                    view.translationX = value.translationX
                    view.translationY = value.translationY
                    view.scaleX = value.scaleX
                    view.scaleY = value.scaleY
                    view.alpha = value.alpha
                    if (view.layoutParams is ViewGroup.MarginLayoutParams) {
                        val params = view.layoutParams as ViewGroup.MarginLayoutParams
                        params.marginStart = value.marginStart
                        params.marginEnd = value.marginEnd
                        view.layoutParams = params
                    }
                    view.setPaddingRelative(
                        value.paddingStart,
                        view.paddingTop,
                        value.paddingEnd,
                        view.paddingBottom,
                    )
                    view.minimumWidth = value.minimumWidth
                    (view as? LinearLayout)?.gravity = value.layoutGravity
                    if (view is TextView) {
                        view.gravity = value.gravity
                        view.textAlignment = value.textAlignment
                    }
                }
            }
            base.keys.toList().forEach { view ->
                if (view is TextView && clockScene(view.context, view.id) != null) {
                    restoreClock(view)
                } else {
                    restoreTextWeight(view)
                }
            }
            restoreClocks()
            // 恢复被强制解除的原生 blocked，避免模块关闭后留下永久空槽位。
            originalBlocked.forEach { (view, blocked) ->
                runCatching { Reflect.callMethod(view, "setBlocked", blocked) }
            }
            originalBlocked.clear()
            restoreGlobalHidden()
            notificationMaxBases.keys.toList().forEach(::applyNotificationLimit)
            groupHidden.forEach { (view, _) ->
                runCatching {
                    view.visibility = if (nativeIconVisible(view)) View.VISIBLE else View.GONE
                }
            }
            groupHidden.clear()
            groupModuleIgnored.forEach { (group, slots) ->
                val current = currentIgnoredSlots(group).toMutableList()
                if (current.removeAll(slots)) {
                    runCatching {
                        Reflect.callMethod(group, "setIgnoredSlots", current.toList())
                        group.requestLayout()
                    }
                }
            }
            groupModuleIgnored.clear()
            nativeHideBySlot.clear()
            nativeIgnoredBySlot.clear()
            moduleIgnored.clear()
            moduleHidden.clear()
            aligned.clear()
            transformed.clear()
            spaced.clear()
            gapStates.clear()
            gapBaseWidths.clear()
            gapDeferred.clear()
            forcedVisible.clear()
            lastHidden.clear()
            val native = nativeIgnoredSlots()
            (find(root, "statusIcons") as? ViewGroup)?.let { group ->
                runCatching {
                    Reflect.callMethod(group, "setIgnoredSlots", native)
                    group.requestLayout()
                }
            }
            lastIgnored = native
            val group = find(root, "statusIcons") as? ViewGroup
            group?.let {
                val native = nativeIgnoredSlots()
                if (native != lastIgnored) {
                    runCatching { Reflect.callMethod(it, "setIgnoredSlots", native) }
                    lastIgnored = native
                }
            }
        }

        private fun reconcileSpecialVisibility() {
            val root = rootRef.get() ?: return
            if (!HookPrefs.getBoolean(StatusBarIconKeys.ENABLED, false)) return
            if (!attached.get()) return
            // 只在控制中心状态 / 通知数量变化时做完整刷新；不逐帧重写，避免与原生动画冲突。
            if (controlCenterExpanded() != lastCc) refresh()
            val area = notificationArea(root)
            val count = area?.let { outer ->
                val container = find(outer, "notificationIcons") ?: firstChildOfType(outer, "NotificationIconContainer")
                (container as? ViewGroup)?.let { inner ->
                    (0 until inner.childCount).count { inner.getChildAt(it).visibility == View.VISIBLE }
                }
            } ?: 0
            if (count != lastNotificationCount) {
                lastNotificationCount = count
                refresh()
            }
            lastHidden.forEach { (view, hidden) ->
                if (hidden && view.visibility != View.GONE) view.visibility = View.GONE
            }
            forcedVisible.forEach { (view, force) ->
                if (force && view.visibility != View.VISIBLE) view.visibility = View.VISIBLE
            }
            val snapshot = currentSnapshot ?: return
            // 闹钟条件依赖“距离下次响铃还有多久”，系统广播偶发丢失时不能一直用
            // 旧结果。仅在确实启用该条件时低频重算，避免无意义的全量刷新。
            val expectsAlarm = StatusBarIconRegistry.right.any { component ->
                component.conditions.contains(StatusBarIconCondition.ALARM_WITHIN) &&
                    snapshot.display(component.id) ==
                    "condition:${StatusBarIconCondition.ALARM_WITHIN.value}"
            }
            if (!expectsAlarm) {
                lastAlarmRefresh = 0L
            } else {
                val now = SystemClock.elapsedRealtime()
                if (now - lastAlarmRefresh >= 15_000L) {
                    lastAlarmRefresh = now
                    refresh()
                    return
                }
            }
            trackedIds.forEach { (view, id) ->
                applyTransforms(view, snapshot.config(id))
                applyAlignmentOffset(view, id, snapshot.config(id))
            }
        }

        /** 组件宽度通常由内容决定；用水平位移把文字真正放到组件内部的对齐位置。 */
        private fun applyAlignmentOffset(view: View, id: String, config: StatusBarIconComponentConfig) {
            // 只有时间/网速支持“组件内对齐”。右侧 slot 图标的 translationX 由
            // MiuiStatusIconContainer/动画控制器负责，若这里按 base 重置会把新布局
            // 拉回旧位置，多个图标就会叠在一起。
            if (id != StatusBarIconRegistry.CLOCK &&
                id != StatusBarIconRegistry.PAD_CLOCK &&
                id != StatusBarIconRegistry.NETWORK_SPEED
            ) {
                return
            }
            if (id == StatusBarIconRegistry.NETWORK_SPEED) {
                // 网速的对齐只改容器内两行文字，不能重置整个 NetworkSpeedView 的
                // translationX；否则重排后它仍停在旧槽位，压到蓝牙/信号上。
                if (config.align == "system") {
                    speedStyles[view]?.let { state ->
                        state.numberText.translationX = state.numberTranslationX
                        state.unitText.translationX = state.unitTranslationX
                    }
                    return
                }
                val sign = when (config.align) {
                    "start" -> -1f
                    "end" -> 1f
                    else -> return
                }
                val state = speedStyles[view] ?: return
                val container = state.container ?: return
                if (container.width <= 0) return
                for (text in listOf(state.numberText, state.unitText)) {
                    if (text.visibility != View.VISIBLE) continue
                    val spare = (container.width -
                        text.paint.measureText(text.text.toString())).coerceAtLeast(0f)
                    text.translationX = sign * spare / 2f
                }
                return
            }
            if (config.align == "system") {
                val baseView = base[view]
                if (baseView != null && view.translationX != baseView.translationX) {
                    view.translationX = baseView.translationX
                }
                return
            }
            val baseView = base[view] ?: return
            val sign = when (config.align) {
                "start" -> -1f
                "end" -> 1f
                else -> 0f
            }
            if (sign == 0f) {
                view.translationX = baseView.translationX
                return
            }
            if (id == StatusBarIconRegistry.CLOCK || id == StatusBarIconRegistry.PAD_CLOCK) {
                val text = view as? TextView ?: return
                val spare = (view.width - text.paint.measureText(text.text.toString())).coerceAtLeast(0f)
                view.translationX = baseView.translationX + sign * spare / 2f
                return
            }
        }

        private fun hidden(
            component: StatusBarIconComponent,
            config: StatusBarIconComponentConfig,
            cc: Boolean,
            evaluator: StatusBarIconConditionEvaluator,
        ): Boolean {
            val display = when {
                config.display.isNotBlank() -> config.display
                config.logic == StatusBarIconLogic.CUSTOM &&
                    config.condition != StatusBarIconCondition.NONE ->
                    "condition:${config.condition.value}"
                else -> config.mode.value
            }
            return when {
                display == StatusBarIconMode.HIDDEN.value -> true
                display == StatusBarIconMode.ALWAYS.value -> false
                display == StatusBarIconMode.CC_ONLY_SHOW.value -> !cc
                display == StatusBarIconMode.CC_ONLY_HIDE.value -> cc
                display.startsWith("condition:") -> {
                    val condition = StatusBarIconCondition.from(display.removePrefix("condition:"))
                    if (condition == StatusBarIconCondition.NONE) {
                        false
                    } else {
                        evaluator.matches(component, condition, config.alarmWithin) == false
                    }
                }
                else -> false
            }
        }

        private fun applyClockText() {
            val root = rootRef.get() ?: return
            val clock = find(root, "clock") as? TextView ?: return
            registerClock(clock)
            rememberClockText(clock)
            val text = clockText(context, clock.id)
            if (text != null) {
                if (!TextUtils.equals(clock.text, text)) clock.text = text
            } else {
                // 从自定义格式切回系统格式时，先恢复进入自定义前的文本，再让宿主重算。
                if (originalClockTexts.containsKey(clock)) {
                    val original = originalClockTexts[clock]
                    if (!TextUtils.equals(clock.text, original)) clock.text = original
                }
                runCatching { Reflect.callMethod(clock, "updateTime") }
                applyClockAmPm(clock)
            }
            applyClockLockWidth(clock)
        }

        private fun nativeIconVisible(view: View): Boolean = runCatching {
            Reflect.callMethod(view, "isIconVisible") as? Boolean
        }.getOrNull() ?: (view.visibility == View.VISIBLE)

        private fun applyNotificationMax(container: ViewGroup?, snapshot: StatusBarIconSnapshot) {
            container ?: return
            lastMaxNotifications =
                snapshot.config(StatusBarIconRegistry.NOTIFICATIONS).maxNotifications
            StatusBarIconRuntime.applyNotificationLimit(container)
        }

        private fun controlCenterExpanded(): Boolean {
            val root = rootRef.get() ?: return false
            // NotificationIconContainerInject 在 bind/状态回调中维护控制中心展开状态；
            // animatorController.controlPanelExpand 仅作为老版本回退。
            val area = notificationArea(root)
            val container = area?.let {
                find(it, "notificationIcons") ?: firstChildOfType(it, "NotificationIconContainer")
            }
            if (container != null) {
                runCatching {
                    val inject = Reflect.callMethod(container, "getInject") ?: return@runCatching
                    val controlCenter = Reflect.getObjectField(
                        inject,
                        "controlCenterPanelExpanded",
                    ) as? Boolean
                    if (controlCenter == true) return true
                    // 部分 HyperOS 版本只维护通知面板展开标记；控制中心属于通知面板。
                    val notificationPanel = Reflect.getObjectField(
                        inject,
                        "notificationPanelExpanded",
                    ) as? Boolean
                    if (notificationPanel == true) return true
                    if (controlCenter != null) return false
                }
            }
            val group = find(root, "statusIcons") ?: return false
            return runCatching {
                val controller = Reflect.getObjectField(group, "animatorController") ?: return false
                Reflect.getObjectField(controller, "controlPanelExpand") as? Boolean ?: false
            }.getOrDefault(false)
        }

        private fun nativeIgnoredSlots(group: ViewGroup? = null): List<String> {
            if (capturedNativeIgnored == null) {
                val current = group?.let(::currentIgnoredSlots).orEmpty()
                val blocked = runCatching {
                    val clazz = Reflect.findClass(
                        "com.android.systemui.statusbar.phone.MiuiIconManagerUtils",
                        context.classLoader,
                    )
                    @Suppress("UNCHECKED_CAST")
                    (Reflect.getStaticObjectField(clazz, "RIGHT_BLOCK_LIST") as? List<String>).orEmpty()
                }.getOrDefault(emptyList())
                // 当前容器列表才包含 vpn 等运行时动态项；静态表只作为兜底。
                capturedNativeIgnored = (current + blocked).distinct()
            }
            return capturedNativeIgnored.orEmpty()
        }

        private fun currentIgnoredSlots(group: ViewGroup): List<String> =
            runCatching {
                @Suppress("UNCHECKED_CAST")
                (Reflect.getObjectField(group, "ignoredSlots") as? List<String>).orEmpty()
            }.getOrDefault(emptyList())

        private fun findChildBySlot(group: ViewGroup, slot: String): View? {
            for (i in 0 until group.childCount) {
                val child = group.getChildAt(i) ?: continue
                val childSlot = runCatching {
                    Reflect.callMethod(child, "getSlot") as? String
                }.getOrNull()
                if (childSlot == slot) return child
            }
            return null
        }

        private fun isIconVisible(view: View): Boolean = runCatching {
            Reflect.callMethod(view, "isIconVisible") as? Boolean ?: true
        }.getOrDefault(true)

        /** 特殊宿主没有 isIconVisible；空 TextView/空容器强制显示只会留下空位。 */
        private fun hasVisibleContent(view: View, id: String): Boolean = when {
            id == StatusBarIconRegistry.BATTERY -> true
            // 权限指示的子 View 才是真实内容；原生可能先收起容器再更新子 View，
            // 因此不能要求容器自身当前可见，否则“始终显示”会被原生 GONE 挡住。
            id == StatusBarIconRegistry.PRIVACY -> when (view) {
                is TextView -> !view.text.isNullOrEmpty()
                is ViewGroup -> (0 until view.childCount).any {
                    view.getChildAt(it).visibility == View.VISIBLE
                }
                else -> true
            }
            view is TextView -> !view.text.isNullOrEmpty()
            view is ViewGroup -> view.childCount > 0
            else -> view.visibility == View.VISIBLE
        }

        /**
         * 普通状态栏把 NotificationIconContainer 放在 fullscreen_notification_icon_area，
         * 刘海/挖孔形态才放到 notification_icon_area；两处都按实际承载容器选择。
         */
        private fun notificationArea(root: ViewGroup): ViewGroup? {
            val drip = find(root, "notification_icon_area") as? ViewGroup
            val fullscreen = find(root, "fullscreen_notification_icon_area") as? ViewGroup
            fun hasContainer(area: ViewGroup?): Boolean {
                area ?: return false
                return find(area, "notificationIcons") != null ||
                    firstChildOfType(area, "NotificationIconContainer") != null
            }
            return when {
                hasContainer(fullscreen) -> fullscreen
                hasContainer(drip) -> drip
                fullscreen != null -> fullscreen
                else -> drip
            }
        }

        private fun find(root: View, name: String): View? {
            val id = idCache.getOrPut(name) {
                root.resources.getIdentifier(name, "id", StatusBarIconKeys.TARGET)
            }
            return if (id == 0) null else root.findViewById(id)
        }

        private fun firstChildOfType(root: ViewGroup, simpleName: String): View? {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child.javaClass.simpleName == simpleName) return child
                if (child is ViewGroup) firstChildOfType(child, simpleName)?.let { return it }
            }
            return null
        }

        private fun findSpeedView(root: ViewGroup): View? {
            for (i in 0 until root.childCount) {
                val child = root.getChildAt(i)
                if (child.javaClass.simpleName == "NetworkSpeedView") return child
            }
            return null
        }

        private fun register() {
            if (!attached.compareAndSet(false, true)) return
            runCatching {
                context.registerReceiver(receiver, filter)
            }.recoverCatching {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            }.onFailure {
                android.util.Log.w("HyperRefineStatusBar", "receiver register failed", it)
            }
        }

        private fun unregister() {
            if (!attached.compareAndSet(true, false)) return
            runCatching { context.unregisterReceiver(receiver) }
        }

        private fun recyclePreDraw() {
            preDraw?.let { rootRef.get()?.viewTreeObserver?.removeOnPreDrawListener(it) }
            preDraw = null
        }
    }

    private data class TrafficSample(
        val time: Long,
        val tx: Long,
        val rx: Long,
        val down: Long,
        val up: Long,
    )

    private data class SpeedStyleState(
        val container: FrameLayout?,
        val numberText: TextView,
        val unitText: TextView,
        val numberGravity: Int,
        val unitGravity: Int,
        val numberTranslationX: Float,
        val unitTranslationX: Float,
        val numberTranslation: Float,
        val unitTranslation: Float,
        val numberTextSize: Float,
        val unitTextSize: Float,
    )

    private data class DotDrawState(
        val count: Int,
        val progress: Float,
        val radius: Float,
        val color: Int,
        val padding: Float,
    )

    private data class BaseView(
        val visibility: Int,
        val translationX: Float,
        val translationY: Float,
        val scrollX: Int,
        val scaleX: Float,
        val scaleY: Float,
        val alpha: Float,
        val marginStart: Int,
        val marginEnd: Int,
        val paddingStart: Int,
        val paddingEnd: Int,
        val minimumWidth: Int,
        val gravity: Int,
        val layoutGravity: Int,
        val textAlignment: Int,
    )

    private data class GapState(
        val baseScrollX: Int,
        val offset: Float,
    )
}
