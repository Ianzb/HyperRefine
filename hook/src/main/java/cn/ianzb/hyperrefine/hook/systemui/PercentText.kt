package cn.ianzb.hyperrefine.hook.systemui

import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 百分比数值文本的格式化与样式应用（字号 / 字重 / 字体颜色）。
 *
 * 字体颜色规则：
 * - 关闭「跟随图标」：固定灰色（[GRAY_COLOR]，即图标低值 / 静音时的灰色）；
 * - 开启「跟随图标」：优先取图标当前颜色（如侧边音量条的颜色资源），取不到时按进度判断——
 *   低于 [HIGHLIGHT_RATIO] 用灰色，否则用图标高值彩色（`highlightColor`）。
 */
object PercentText {

    private const val DEFAULT_SIZE = 13f

    /** 图标低值 / 静音时的灰色（在插件 `color/toggle_slider_icon_color` #959595 基础上调亮）。 */
    private const val GRAY_COLOR = 0xFFBFBFBF.toInt()

    /** 图标由灰变彩的进度阈值（插件为 `0.12f`）。 */
    private const val HIGHLIGHT_RATIO = 0.12f

    /** 按字重缓存 Typeface，避免高频回调里反复 `Typeface.create`。 */
    private val typefaceCache = ConcurrentHashMap<Int, Typeface>()

    /** 垂直位置重试计数（布局未就绪时逐帧重试，带上限）。 */
    private val verticalRetry = java.util.WeakHashMap<TextView, Int>()

    /** 垂直位置最大重试帧数。 */
    private const val MAX_VERTICAL_RETRIES = 30

    fun percentText(value: Int, max: Int): String {
        if (max <= 0) return "0%"
        val percent = (value.toFloat() * 100f / max).roundToInt()
        return "$percent%"
    }

    /** 从 SliderViewHolder 之类的宿主取 `top_text` 文本视图。 */
    fun findTopText(holder: Any?): TextView? {
        if (holder == null) return null
        runCatching { Reflect.callMethod(holder, "getTopText") }.getOrNull()
            ?.let { if (it is TextView) return it }
        val item = runCatching { Reflect.getObjectField(holder, "itemView") }.getOrNull() as? View
            ?: return null
        return findViewByName(item, "top_text")
    }

    fun findViewByName(root: View, name: String): TextView? {
        if (root is TextView && entryName(root) == name) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findViewByName(root.getChildAt(i), name)?.let { return it }
            }
        }
        return null
    }

    /** 取视图 id 的资源名（避免使用已弃用的 `Resources.getIdentifier`）。 */
    private fun entryName(view: View): String? =
        runCatching { view.resources.getResourceEntryName(view.id) }.getOrNull()

    /** SeekBar 的当前值与最大值。 */
    fun progressOf(slider: Any?): Pair<Int, Int>? {
        val bar = slider as? SeekBar ?: return null
        val max = bar.max
        if (max <= 0) return null
        return bar.progress to max
    }

    /** StreamState 的 level / levelMax（侧边音量条兜底）。 */
    fun levelOf(streamState: Any?): Pair<Int, Int>? {
        if (streamState == null) return null
        val level = runCatching { Reflect.getObjectField(streamState, "level") }.getOrNull() as? Int
            ?: return null
        val levelMax = runCatching { Reflect.getObjectField(streamState, "levelMax") }.getOrNull() as? Int
            ?: return null
        if (levelMax <= 0) return null
        return level to levelMax
    }

    /**
     * 写入百分比文本并应用样式。
     *
     * @param pref 样式配置键前缀（如 `cc_volume`），读取 `*_font_size` / `*_font_weight` / `*_follow_icon`
     * @param icon 图标视图（用于解析颜色资源 id）
     * @param iconColorRes 图标颜色（颜色资源 id 或字面量，可空）；提供时以它为准
     * @param highlightColor 图标高值（彩色）颜色；无法获知图标颜色时按进度使用
     */
    fun show(
        tv: TextView,
        value: Int,
        max: Int,
        pref: String,
        icon: View?,
        iconColorRes: Int? = null,
        highlightColor: Int,
    ) {
        val text = percentText(value, max)
        if (tv.text.toString() != text) tv.text = text
        if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE

        val size = HookPrefs.getFloat("${pref}_font_size", DEFAULT_SIZE).coerceIn(4f, 48f)
        val expectedPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, size, tv.resources.displayMetrics)
        // 与视图当前值比较后再写入，避免拖动逐帧触发 measure/layout。
        if (abs(tv.textSize - expectedPx) > 0.5f) tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        val typeface = typefaceFor(weightOf(HookPrefs.getString("${pref}_font_weight", "normal")))
        if (tv.typeface !== typeface) tv.typeface = typeface

        val color = textColor(pref, value, max, icon, iconColorRes, highlightColor)
        if (tv.currentTextColor != color) tv.setTextColor(color)
    }

    /**
     * 按配置的上下位置百分比在同级容器内平移百分比文本。
     *
     * - `100%`：保持原位（顶部）；
     * - `0%`：移动到容器底部。
     *
     * @param pref 样式配置键前缀（读取 `${pref}_position`，0–100）
     */
    fun applyVerticalPosition(tv: TextView, pref: String) {
        val percent = HookPrefs.getFloat("${pref}_position", 100f)
        if (percent >= 100f) {
            verticalRetry.remove(tv)
            if (tv.translationY != 0f) tv.translationY = 0f
            return
        }
        val parent = tv.parent as? View
        if (parent == null || parent.height <= 0 || tv.height <= 0) {
            // 首次打开时视图尚未布局（高度为 0），等下一帧布局完成后再套用，
            // 否则要等用户改动数值触发下一次回调才会移动。
            scheduleVerticalRetry(tv, pref)
            return
        }
        val available = parent.height - tv.height - tv.top
        if (available < 0) {
            scheduleVerticalRetry(tv, pref)
            return
        }
        verticalRetry.remove(tv)
        val ty = (1f - percent.coerceIn(0f, 100f) / 100f) * available
        if (abs(tv.translationY - ty) > 0.5f) tv.translationY = ty
    }

    /** 布局未就绪时重试（带上限，避免视图长期不可见时无限循环）。 */
    private fun scheduleVerticalRetry(tv: TextView, pref: String) {
        val attempts = verticalRetry[tv] ?: 0
        if (attempts >= MAX_VERTICAL_RETRIES) return
        verticalRetry[tv] = attempts + 1
        tv.post {
            if (tv.isAttachedToWindow) applyVerticalPosition(tv, pref) else verticalRetry.remove(tv)
        }
    }

    /** 计算文本颜色：关闭跟随固定灰色；开启跟随图标（低值灰色 / 高值彩色）。 */
    private fun textColor(
        pref: String,
        value: Int,
        max: Int,
        icon: View?,
        iconColorRes: Int?,
        highlightColor: Int,
    ): Int {
        if (!HookPrefs.getBoolean("${pref}_follow_icon", true)) return GRAY_COLOR
        resolveIconColor(icon, iconColorRes)?.let { return it }
        val ratio = if (max > 0) value.toFloat() / max else 0f
        return if (ratio < HIGHLIGHT_RATIO) GRAY_COLOR else highlightColor
    }

    /**
     * 解析图标颜色：`iconColorRes` 为颜色资源 id 时解析为颜色，为字面量时直接使用；取不到返回 null。
     */
    private fun resolveIconColor(icon: View?, iconColorRes: Int?): Int? {
        if (icon == null || iconColorRes == null || iconColorRes == 0) return null
        val color = runCatching { icon.resources.getColor(iconColorRes, icon.context.theme) }.getOrNull()
            ?: iconColorRes
        return color.takeIf { Color.alpha(it) != 0 }
    }

    fun weightOf(weight: String?): Int = when (weight) {
        "light" -> 300
        "medium" -> 500
        "semibold" -> 600
        "bold" -> 700
        "black" -> 900
        else -> 400
    }

    private fun typefaceFor(weight: Int): Typeface =
        typefaceCache.getOrPut(weight) { Typeface.create(Typeface.DEFAULT, weight, false) }
}
