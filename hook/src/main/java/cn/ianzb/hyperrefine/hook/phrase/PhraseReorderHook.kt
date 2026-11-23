package cn.ianzb.hyperrefine.hook.phrase

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import org.json.JSONArray
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Reorders the official phrase popup's shared list without changing its provider. */
class PhraseReorderHook : BaseHook() {
    override val key: String = PhraseReorderKeys.ENABLED

    private val installed = mutableSetOf<Class<*>>()
    private val rows = WeakHashMap<View, WeakReference<Any>>()
    private val main = Handler(Looper.getMainLooper())

    override fun init() {
        val loader = target.classLoader ?: return
        val manager = loader.loadClass("android.inputmethodservice.InputMethodModuleManager")
        val loadDex = manager.getDeclaredMethod("loadDex", ClassLoader::class.java, String::class.java)
        HookHelper.hookAfter(loadDex) { param ->
            val moduleLoader = param.args.firstOrNull() as? ClassLoader ?: return@hookAfter
            installIfPresent(moduleLoader)
        }
        installIfPresent(loader)
    }

    private fun installIfPresent(loader: ClassLoader) {
        val adapter = runCatching { loader.loadClass(ADAPTER) }.getOrNull() ?: return
        synchronized(installed) {
            if (adapter in installed) return
            runCatching {
                val holder = loader.loadClass("$ADAPTER\$ViewHolder")
                val bind = adapter.getDeclaredMethod("onBindViewHolder", holder, Int::class.javaPrimitiveType)
                val setList = adapter.getDeclaredMethod("setPhraseList", ArrayList::class.java)
                val position = holder.getMethod("getBindingAdapterPosition")
                val rowField = holder.getField("itemView")
                val layoutField = holder.getDeclaredField("itemLayout").apply { isAccessible = true }
                val textField = holder.getDeclaredField("textItem").apply { isAccessible = true }
                val dataField = adapter.getDeclaredField("mPhraseList").apply { isAccessible = true }
                val closeRows = adapter.getMethod("closeAll")
                val move = adapter.getMethod("notifyItemMoved", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                val notifyChanged = adapter.getMethod("notifyDataSetChanged")
                val entity = loader.loadClass("com.miui.provider.PhraseEntityImpl")
                val id = entity.getMethod("getId")
                val words = entity.getMethod("getWords")

                fun keyOf(item: Any): String {
                    val localId = id.invoke(item) as Long
                    if (localId > 0) return "i:$localId"
                    val phrase = words.invoke(item) as? String ?: ""
                    val digest = MessageDigest.getInstance("SHA-256").digest(phrase.toByteArray(Charsets.UTF_8))
                    return "w:" + android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
                }

                HookHelper.hookBefore(setList) { param ->
                    if (!PhraseReorderKeys.isEnabled()) return@hookBefore
                    val list = param.args.firstOrNull() as? ArrayList<*> ?: return@hookBefore
                    if (!list.all(entity::isInstance)) return@hookBefore
                    @Suppress("UNCHECKED_CAST")
                    restoreOrder(list as ArrayList<Any>, ::keyOf)
                }

                HookHelper.hookAfter(bind) { param ->
                    val boundHolder = param.args.firstOrNull() ?: return@hookAfter
                    val row = rowField.get(boundHolder) as? View ?: return@hookAfter
                    val layout = layoutField.get(boundHolder) as? LinearLayout ?: return@hookAfter
                    val text = textField.get(boundHolder) as? TextView ?: return@hookAfter
                    val existing = layout.findViewWithTag<TextView>(HANDLE_TAG)
                    if (!PhraseReorderKeys.isEnabled()) {
                        existing?.visibility = View.GONE
                        return@hookAfter
                    }
                    val owner = param.thisObject ?: return@hookAfter
                    rows[row] = WeakReference(boundHolder)
                    val handle = existing ?: addHandle(layout, text) ?: return@hookAfter
                    handle.visibility = View.VISIBLE
                    handle.setTextColor(text.currentTextColor)
                    if (existing != null) return@hookAfter
                    handle.setOnTouchListener(Drag(
                        row, boundHolder, owner, position, closeRows, move, notifyChanged,
                        items = {
                            @Suppress("UNCHECKED_CAST")
                            dataField.get(owner) as? ArrayList<Any>
                        },
                        keyOf = ::keyOf,
                    ))
                }
                installed.add(adapter)
                HookHelper.log("$tag: official phrase popup hooks installed @ ${target.processName}")
            }.onFailure { HookHelper.log("$tag: unsupported official phrase popup", it) }
        }
    }

    private fun addHandle(layout: LinearLayout, text: TextView): TextView? {
        val original = text.layoutParams as? LinearLayout.LayoutParams ?: return null
        val index = layout.indexOfChild(text)
        if (index < 0) return null
        val row = LinearLayout(layout.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val handle = TextView(layout.context).apply {
            tag = HANDLE_TAG
            this.text = "≡"
            textSize = 21f
            gravity = Gravity.CENTER
            alpha = 0.65f
            isClickable = true
            contentDescription = if (resources.configuration.locales[0].language == "zh") "拖动排序" else "Drag to reorder"
        }
        layout.removeViewAt(index)
        val wrapperParams = LinearLayout.LayoutParams(original).apply {
            width = ViewGroup.LayoutParams.MATCH_PARENT
            height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(handle, LinearLayout.LayoutParams(
            dp(layout, 40), ViewGroup.LayoutParams.MATCH_PARENT,
        ))
        layout.addView(row, index, wrapperParams)
        return handle
    }

    private inner class Drag(
        private val row: View,
        private val holder: Any,
        private val adapter: Any,
        private val position: Method,
        private val closeRows: Method,
        private val notifyMove: Method,
        private val notifyChanged: Method,
        private val items: () -> ArrayList<Any>?,
        private val keyOf: (Any) -> String,
    ) : View.OnTouchListener {
        private var list: ViewGroup? = null
        private var session: ArrayList<Any>? = null
        private var original = emptyList<Any>()
        private var draggedItem: Any? = null
        private var moved = false
        private var pointerId = MotionEvent.INVALID_POINTER_ID
        private var pointerY = 0f
        private var grabOffset = 0f
        private var originalY = 0f
        private var originalZ = 0f
        private var initialPointerY = 0f
        private var edgeDirection = 0
        private var edgeEnteredAt = 0L
        private val touchSlop = ViewConfiguration.get(row.context).scaledTouchSlop
        private val location = IntArray(2)
        private val dragFrame = object : Runnable {
            override fun run() {
                val parent = list ?: return
                if (!parent.isAttachedToWindow) {
                    finish(save = false)
                    return
                }
                val direction = updateEdgeDirection(parent)
                if (direction != 0 && parent.canScrollVertically(direction) &&
                    SystemClock.uptimeMillis() - edgeEnteredAt >= EDGE_DWELL_MS
                ) {
                    parent.scrollBy(0, direction * dp(parent, 3))
                }
                runCatching { moveAt(pointerY) }.onFailure {
                    finish(save = false)
                    HookHelper.log("$tag: phrase drag frame failed", it)
                }
                if (list === parent) parent.postOnAnimation(this)
            }
        }

        override fun onTouch(handle: View, event: MotionEvent): Boolean = runCatching {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!PhraseReorderKeys.isEnabled()) return false
                    val parent = row.parent as? ViewGroup ?: return false
                    val data = items() ?: return false
                    val start = position.invoke(holder) as Int
                    if (data.size < 2 || start !in data.indices) return false
                    list = parent
                    session = data
                    original = data.toList()
                    draggedItem = data[start]
                    moved = false
                    pointerId = event.getPointerId(0)
                    pointerY = event.rawY
                    initialPointerY = pointerY
                    edgeDirection = 0
                    originalY = row.translationY
                    originalZ = row.translationZ
                    parent.getLocationOnScreen(location)
                    grabOffset = pointerY - (location[1] + row.top + row.height / 2f + originalY)
                    row.translationZ = originalZ + dp(row, 4)
                    closeRows.invoke(adapter)
                    handle.parent.requestDisallowInterceptTouchEvent(true)
                    parent.postOnAnimation(dragFrame)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val index = event.findPointerIndex(pointerId)
                    if (index >= 0) {
                        pointerY = event.getRawY(index)
                        moveAt(pointerY)
                        list?.let(::updateEdgeDirection)
                    }
                    session != null
                }
                MotionEvent.ACTION_POINTER_UP ->
                    if (event.getPointerId(event.actionIndex) == pointerId) finish(save = true) else session != null
                MotionEvent.ACTION_UP -> finish(save = true)
                MotionEvent.ACTION_CANCEL -> finish(save = false)
                else -> session != null
            }
        }.onFailure {
            finish(save = false)
            HookHelper.log("$tag: phrase drag failed", it)
        }.getOrDefault(false)

        private fun updateEdgeDirection(parent: View): Int {
            parent.getLocationOnScreen(location)
            val edge = dp(parent, 12).coerceAtMost(parent.height / 8)
            val top = location[1] + parent.paddingTop
            val bottom = location[1] + parent.height - parent.paddingBottom
            val travel = pointerY - initialPointerY
            val direction = when {
                travel < -touchSlop && pointerY < top + edge -> -1
                travel > touchSlop && pointerY > bottom - edge -> 1
                else -> 0
            }
            if (direction != edgeDirection) {
                edgeDirection = direction
                edgeEnteredAt = SystemClock.uptimeMillis()
            }
            return direction
        }

        private fun moveAt(y: Float) {
            val parent = list ?: return
            val data = session ?: return
            if (items() !== data || data.size != original.size) {
                finish(save = false)
                return
            }
            if (row.parent !== parent) return
            parent.getLocationOnScreen(location)
            val center = row.top + row.height / 2f
            val draggedCenter = (y - location[1] - grabOffset).coerceIn(0f, parent.height.toFloat())
            row.translationY = draggedCenter - center
            if (parent.isLayoutRequested) return
            val from = data.indexOfFirst { it === draggedItem }
            if (from < 0) return
            var to = from
            for (index in 0 until parent.childCount) {
                val child = parent.getChildAt(index)
                if (child === row) continue
                val target = rows[child]?.get() ?: continue
                val candidate = position.invoke(target) as Int
                if (candidate !in data.indices) continue
                val targetCenter = child.top + child.height / 2f
                if (draggedCenter > center && draggedCenter >= targetCenter && candidate > to) to = candidate
                if (draggedCenter < center && draggedCenter <= targetCenter && candidate < to) to = candidate
            }
            if (from == to) return
            data.add(to, data.removeAt(from))
            moved = true
            notifyMove.invoke(adapter, from, to)
        }

        private fun finish(save: Boolean): Boolean {
            val data = session ?: return false
            val parent = list
            session = null
            list = null
            draggedItem = null
            pointerId = MotionEvent.INVALID_POINTER_ID
            parent?.removeCallbacks(dragFrame)
            parent?.requestDisallowInterceptTouchEvent(false)
            row.translationY = originalY
            row.translationZ = originalZ
            if (moved && items() === data && data.size == original.size) {
                if (save) {
                    val order = JSONArray(data.map(keyOf)).toString()
                    preferences()?.edit()?.putString(ORDER_KEY, order)?.apply()
                } else {
                    data.clear()
                    data.addAll(original)
                    main.post { runCatching { if (items() === data) notifyChanged.invoke(adapter) } }
                }
            }
            original = emptyList()
            moved = false
            return true
        }
    }

    private fun restoreOrder(items: ArrayList<Any>, keyOf: (Any) -> String) {
        val raw = preferences()?.getString(ORDER_KEY, null) ?: return
        if (items.size < 2) return
        val ranks = runCatching {
            val saved = JSONArray(raw)
            buildMap { for (i in 0 until saved.length()) put(saved.getString(i), i) }
        }.getOrNull() ?: return
        val sorted = items.sortedBy { ranks[keyOf(it)] ?: Int.MAX_VALUE }
        if (items != sorted) {
            items.clear()
            items.addAll(sorted)
        }
    }

    private fun preferences() = runCatching {
        val app = Class.forName("android.app.ActivityThread")
            .getMethod("currentApplication").invoke(null) as? Application
        app?.getSharedPreferences("hyperrefine_phrase", Context.MODE_PRIVATE)
    }.getOrNull()

    private fun dp(view: View, value: Int): Int =
        (view.resources.displayMetrics.density * value).roundToInt()

    private companion object {
        const val ADAPTER = "com.miui.inputmethod.InputMethodPhraseAdapter"
        const val HANDLE_TAG = "hyperrefine_phrase_drag_handle"
        const val ORDER_KEY = "popup_phrase_order_v2"
        const val EDGE_DWELL_MS = 280L
    }
}
