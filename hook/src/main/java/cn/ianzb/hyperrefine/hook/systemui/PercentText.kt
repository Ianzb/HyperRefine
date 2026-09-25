package cn.ianzb.hyperrefine.hook.systemui

import android.graphics.Color
import android.graphics.PorterDuffColorFilter
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.graphics.Typeface
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import cn.ianzb.hyperrefine.hook.xposed.Reflect
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * 百分比数值文本的格式化与样式应用（字号 / 字重 / 字体颜色）。
 *
 * 字体颜色支持「实时跟随图标颜色」：读取音量条喇叭图标 / 亮度条太阳图标的当前颜色
 * （tint / colorFilter / 颜色字段），跟随关闭时恢复文本默认颜色。
 */
object PercentText {

    private const val RES_PACKAGE = "miui.systemui.plugin"

    private const val DEFAULT_SIZE = 13f

    /** 各文本视图首次见到时的系统默认颜色，用于跟随关闭后恢复。 */
    private val originalColors = WeakHashMap<TextView, Int>()

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
        val id = root.resources.getIdentifier(name, "id", RES_PACKAGE)
        if (id != 0) return root.findViewById<TextView>(id)
        return null
    }

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
     * @param icon 跟随颜色的图标视图
     * @param iconColorRes 图标颜色（颜色值或资源 id，可空）
     * @param fallbackColor 跟随且取不到图标颜色时的兜底色
     */
    fun show(
        tv: TextView,
        value: Int,
        max: Int,
        pref: String,
        icon: View?,
        iconColorRes: Int? = null,
        fallbackColor: Int,
    ) {
        val text = percentText(value, max)
        if (tv.text.toString() != text) tv.text = text
        if (tv.visibility != View.VISIBLE) tv.visibility = View.VISIBLE

        val size = HookPrefs.getFloat("${pref}_font_size", DEFAULT_SIZE).coerceIn(4f, 48f)
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, size)
        tv.typeface = Typeface.create(Typeface.DEFAULT, weightOf(HookPrefs.getString("${pref}_font_weight", "normal")), false)

        if (!originalColors.containsKey(tv)) {
            originalColors[tv] = runCatching { tv.currentTextColor }.getOrDefault(Color.WHITE)
        }
        val follow = HookPrefs.getBoolean("${pref}_follow_icon", true)
        val color = if (follow) {
            readIconColor(icon, iconColorRes) ?: fallbackColor
        } else {
            originalColors[tv] ?: Color.WHITE
        }
        if (tv.currentTextColor != color) tv.setTextColor(color)
    }

    /** 从 ColorFilter 读取颜色（PorterDuffColorFilter.getColor，经反射以兼容编译 SDK）。 */
    private fun colorOfFilter(filter: android.graphics.ColorFilter?): Int? {
        if (filter == null) return null
        val color = runCatching { Reflect.callMethod(filter, "getColor") }.getOrNull() as? Int ?: return null
        return if (Color.alpha(color) != 0) color else null
    }

    fun weightOf(weight: String?): Int = when (weight) {
        "light" -> 300
        "medium" -> 500
        "bold" -> 700
        else -> 400
    }

    /** 读取图标当前颜色（实时跟随），取不到返回 null。 */
    fun readIconColor(icon: View?, iconColorRes: Int?): Int? {
        if (iconColorRes != null && iconColorRes != 0) {
            val color = if (iconColorRes ushr 24 == 0 && icon != null) {
                runCatching { icon.resources.getColor(iconColorRes, icon.context.theme) }.getOrNull()
            } else {
                iconColorRes
            }
            if (color != null && Color.alpha(color) != 0) return color
        }
        if (icon is ImageView) {
            icon.imageTintList?.defaultColor?.let { if (Color.alpha(it) != 0) return it }
            colorOfFilter(icon.colorFilter)?.let { return it }
            colorOfFilter(icon.drawable?.colorFilter)?.let { return it }
        }
        if (icon != null) {
            for (name in arrayOf("getCurrentColor", "getColor", "getIconColor")) {
                val value = runCatching { Reflect.callMethod(icon, name) }.getOrNull() as? Int ?: continue
                if (Color.alpha(value) != 0) return value
            }
        }
        return null
    }
}
