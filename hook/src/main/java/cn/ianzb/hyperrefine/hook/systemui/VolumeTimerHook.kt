package cn.ianzb.hyperrefine.hook.systemui

import android.content.Context
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.graphics.drawable.DrawableWrapper
import android.graphics.drawable.LayerDrawable
import android.text.format.DateUtils
import android.util.AttributeSet
import android.view.View
import android.view.ViewTreeObserver
import android.widget.SeekBar
import cn.ianzb.hyperrefine.hook.base.BaseHook
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.HookHelper
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.WeakHashMap

class VolumeTimerHook : BaseHook() {
    override val key = KEY

    override fun init() {
        val loader = target.classLoader ?: return
        PluginLoader.register(key, loader, ::hookPlugin)
    }

    private fun hookPlugin(loader: ClassLoader) {
        val timer = Reflect.findClass("com.android.systemui.miui.volume.MiuiVolumeTimerSeekBar", loader)
        val base = requireNotNull(timer.superclass)
        val item = Reflect.findClass("com.android.systemui.miui.volume.TimerItem", loader)
        val helper = Reflect.findClass("com.android.systemui.miui.volume.MiuiVolumeTimerDrawableHelper", loader)
        val constructor = timer.getDeclaredConstructor(Context::class.java, AttributeSet::class.java, Int::class.javaPrimitiveType)
        val segments = Reflect.findField(base, "mTimeSegments")
        val motions = Reflect.findField(timer, "mMotions")
        val titles = Reflect.findField(helper, "mTimeSegmentTitle")
        val indicator = Reflect.findField(helper, "mBackgroundSegments")
        val layers = Reflect.findField(helper, "mBgLayer")
        val backgroundParts = Reflect.findField(helper, "mBgDrawable")
        val itemTimer = Reflect.findField(item, "mTimer")
        val itemText = Reflect.findField(item, "mTimerTime")
        val itemWidth = Reflect.findField(item, "mWidth")
        val itemIndex = Reflect.findField(item, "mDeterminedSegment")
        val position = Reflect.findMethod(item, "updateTimerTimePosition")
        val timeToProgress = Reflect.findMethod(base, "timeToProgress", Int::class.javaPrimitiveType!!)
        val configured = WeakHashMap<SeekBar, IntArray>()
        val pendingLabelPositions = WeakHashMap<View, Unit>()

        HookHelper.intercept(position) { chain ->
            val bar = itemTimer.get(chain.thisObject) as? SeekBar
            val values = configured[bar] ?: return@intercept chain.proceed()
            if (values.lastIndex == NATIVE_SEGMENT_COUNT) return@intercept chain.proceed()
            val text = itemText.get(chain.thisObject) as? View ?: return@intercept chain.proceed()
            val owner = chain.thisObject ?: return@intercept chain.proceed()
            val observer = text.viewTreeObserver
            if (!observer.isAlive) return@intercept chain.proceed()
            if (pendingLabelPositions.put(text, Unit) != null) return@intercept null
            observer.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (observer.isAlive) observer.removeOnGlobalLayoutListener(this)
                    else text.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalLayoutListener(this)
                    pendingLabelPositions.remove(text)
                    val width = (itemWidth.get(owner) as? Number)?.toFloat() ?: return
                    val index = itemIndex.getInt(owner).coerceIn(0, values.lastIndex)
                    text.translationX = width * index / values.lastIndex - text.width / 2f
                }
            })
            null
        }
        HookHelper.intercept(timeToProgress) { chain ->
            val bar = chain.thisObject as? SeekBar ?: return@intercept chain.proceed()
            val values = configured[bar] ?: return@intercept chain.proceed()
            val seconds = chain.args[0] as Int
            if (seconds <= 0) return@intercept 0
            val upper = values.indexOfFirst { it >= seconds }
            if (upper < 0) return@intercept bar.max
            val lower = values[upper - 1]
            val fraction = (seconds - lower).toFloat() / (values[upper] - lower)
            ((upper - 1 + fraction) * (bar.max / values.lastIndex)).toInt()
        }
        HookHelper.hookAfter(constructor) { param ->
            val bar = param.thisObject as? SeekBar ?: return@hookAfter
            // The vertical variant shares this constructor and retains its native timer.
            if (bar.javaClass !== timer) return@hookAfter
            val values = parse(HookPrefs.getString(MINUTES, DEFAULT) ?: DEFAULT) ?: return@hookAfter
            val native = segments.get(bar) as? IntArray ?: return@hookAfter
            if (values.contentEquals(native)) return@hookAfter
            val drawableHelper = motions.get(bar)?.takeIf(helper::isInstance) ?: return@hookAfter
            val nativeTitles = titles.get(drawableHelper) as? Array<*> ?: return@hookAfter
            val off = nativeTitles.firstOrNull() as? String ?: return@hookAfter
            val labels = Array(values.size) { index -> if (index == 0) off else durationLabel(bar, values[index]) }
            if (values.size != native.size) {
                val background = layers.get(drawableHelper) as? LayerDrawable
                val parts = backgroundParts.get(drawableHelper) as? Array<*>
                val old = indicator.get(drawableHelper) as? Drawable
                val track = parts?.firstOrNull() as? Drawable
                val marker = old?.constantState?.newDrawable(bar.resources)?.mutate()
                if (background != null && parts != null && track != null && marker != null &&
                    background.numberOfLayers == parts.size && parts.lastOrNull() === old
                ) {
                    marker.alpha = old.alpha
                    val ticks = TimerDividers(marker, track, values.lastIndex)
                    background.setDrawable(parts.lastIndex, ticks)
                    indicator.set(drawableHelper, ticks)
                }
            }
            titles.set(drawableHelper, labels)
            segments.set(bar, values)
            configured[bar] = values
            // Native segment math uses integer division of max by the number of intervals.
            val step = (bar.max / values.lastIndex).coerceAtLeast(1)
            bar.max = step * values.lastIndex
        }
    }

    private fun durationLabel(bar: SeekBar, seconds: Int): String {
        val minutes = seconds / 60
        val hours = minutes / 60
        val remainder = minutes % 60
        return runCatching {
            val res = bar.resources
            val packageName = res.getResourcePackageName(bar.id)
            fun format(name: String, count: Int): String =
                res.getString(res.getIdentifier(name, "string", packageName), count)
            buildList {
                if (hours > 0) add(format(if (hours == 1) "timer_1_hour" else "timer_2_hours", hours))
                if (remainder > 0) add(format("timer_30_minutes", remainder))
            }.joinToString(" ")
        }.getOrElse { DateUtils.formatElapsedTime(seconds.toLong()) }
    }

    private class TimerDividers(
        marker: Drawable,
        private val track: Drawable,
        private val segmentCount: Int,
    ) : DrawableWrapper(marker) {

        override fun draw(canvas: Canvas) {
            val marker = drawable ?: return
            val trackBounds = track.bounds
            if (trackBounds.isEmpty || bounds.isEmpty) return
            // The native nine-patch has three vertical marks. Repeat its center mark at each boundary.
            val center = bounds.exactCenterX()
            val halfClip = bounds.width() / 4f
            for (index in 1 until segmentCount) {
                val x = trackBounds.left + trackBounds.width() * index.toFloat() / segmentCount
                val save = canvas.save()
                canvas.translate(x - center, 0f)
                canvas.clipRect(center - halfClip, bounds.top.toFloat(), center + halfClip, bounds.bottom.toFloat())
                marker.draw(canvas)
                canvas.restoreToCount(save)
            }
        }
    }

    companion object {
        const val KEY = "volume_timer_custom"
        const val MINUTES = "volume_timer_minutes"
        const val DEFAULT = "30,60,120,480"
        private const val NATIVE_SEGMENT_COUNT = 4

        fun isValid(input: String): Boolean = parse(input) != null

        internal fun parse(input: String): IntArray? {
            val parts = input.split(',', '，')
            if (parts.size !in 1..8) return null
            val times = IntArray(parts.size + 1)
            parts.forEachIndexed { index, part ->
                val minutes = part.trim().toIntOrNull()?.takeIf { it in 1..1440 } ?: return null
                times[index + 1] = minutes * 60
                if (times[index + 1] <= times[index]) return null
            }
            return times
        }
    }
}
