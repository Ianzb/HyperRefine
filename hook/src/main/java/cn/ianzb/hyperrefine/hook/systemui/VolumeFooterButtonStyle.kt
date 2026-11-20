package cn.ianzb.hyperrefine.hook.systemui

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.InsetDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.LinearLayout
import cn.ianzb.hyperrefine.hook.prefs.HookPrefs
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Keeps each button’s stock horizontal slot while sizing and shaping its own material. */
internal class VolumeFooterButtonStyle {
    data class Style(val width: Int, val height: Int, val radius: Float)
    private data class Size(val width: Int, val height: Int)
    private data class OutlineState(
        val provider: ViewOutlineProvider?,
        val clipToOutline: Boolean,
        val custom: RoundedOutline,
    )

    private class RoundedOutline(var radius: Float) : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            if (view.width > 0 && view.height > 0) {
                outline.setRoundRect(0, 0, view.width, view.height, radius)
            }
        }
    }

    private val sizes = WeakHashMap<View, Pair<Size, Size>>()
    private val outlines = WeakHashMap<View, OutlineState>()
    private val alignments = WeakHashMap<View, Pair<Int, Int>>()

    private data class DrawableState(val original: Drawable, val styled: Drawable, val radius: Float)
    private val backgrounds = WeakHashMap<View, DrawableState>()
    private val foregrounds = WeakHashMap<View, DrawableState>()

    fun read(context: Context): Style? {
        if (!HookPrefs.getBoolean(VolumeBarKeys.BUTTON_STYLE, false)) return null
        val density = context.resources.displayMetrics.density
        fun dp(key: String, fallback: Float, range: ClosedFloatingPointRange<Float>): Float =
            (HookPrefs.getFloat(key, fallback).takeIf { it.isFinite() } ?: fallback).coerceIn(range) * density
        val width = dp(VolumeBarKeys.BUTTON_WIDTH, VolumeBarKeys.DEFAULT_BUTTON_WIDTH, 24f..140f).roundToInt()
        val height = dp(VolumeBarKeys.BUTTON_HEIGHT, VolumeBarKeys.DEFAULT_BUTTON_HEIGHT, 24f..100f).roundToInt()
        val radius = dp(VolumeBarKeys.BUTTON_RADIUS, VolumeBarKeys.DEFAULT_BUTTON_RADIUS, 0f..50f)
        return Style(width, height, radius.coerceAtMost(minOf(width, height) / 2f))
    }

    fun apply(root: View, background: View, content: View?, style: Style?) {
        val params = root.layoutParams
        val size = style?.let { Size(it.width, it.height) }
        // Preserve the stock horizontal measurement even if the button body is wider.
        // Its right edge stays at the stock edge; extra width extends into the screen.
        val stockWidth = sizes[background]?.first?.width
            ?: background.layoutParams?.width ?: 0
        val stockSlot = stockWidth.takeIf { it > 0 } ?: root.width.takeIf { it > 0 }
        if (params != null) {
            val slot = if (size != null && stockSlot != null) Size(stockSlot, params.height) else null
            resize(root, slot)
        }
        alignButton(root, background, size != null && stockSlot != null)
        resize(background, size)
        applyCorners(background, style?.radius)
        if (content != null) {
            resize(content, size)
            applyCorners(content, style?.radius)
        }
    }

    private fun alignButton(root: View, button: View, enabled: Boolean) {
        val params = button.layoutParams as? LinearLayout.LayoutParams ?: return
        val previous = alignments[button]
        val original = if (previous?.second == params.gravity) previous.first else params.gravity
        val target = if (enabled) {
            val location = IntArray(2)
            root.getLocationOnScreen(location)
            val screenWidth = root.resources.displayMetrics.widthPixels
            val onRight = root.width == 0 || location[0] + root.width / 2 >= screenWidth / 2
            val vertical = original.coerceAtLeast(0) and Gravity.VERTICAL_GRAVITY_MASK
            vertical or if (onRight) Gravity.RIGHT else Gravity.LEFT
        } else original
        if (enabled) alignments[button] = original to target else alignments.remove(button)
        if (params.gravity != target) {
            params.gravity = target
            button.layoutParams = params
        }
    }

    private fun applyCorners(view: View, radius: Float?) {
        val background = replaceDrawable(view, view.background, radius, backgrounds)
        if (background !== view.background) view.background = background
        val foreground = replaceDrawable(view, view.foreground, radius, foregrounds)
        if (foreground !== view.foreground) view.foreground = foreground
        updateOutline(view, radius)
    }

    private fun replaceDrawable(
        view: View,
        current: Drawable?,
        radius: Float?,
        states: MutableMap<View, DrawableState>,
    ): Drawable? {
        val previous = states[view]
        val owned = previous != null && current === previous.styled
        if (radius == null) {
            states.remove(view)
            return if (owned) previous.original else current
        }
        if (owned && previous.radius == radius) return current
        val original = (if (owned) previous.original else current) ?: return null
        val copy = original.constantState?.newDrawable(view.resources, view.context.theme)?.mutate()
        if (copy == null || !applyDrawableRadius(copy, radius)) {
            states.remove(view)
            return original
        }
        states[view] = DrawableState(original, copy, radius)
        return copy
    }

    private fun applyDrawableRadius(drawable: Drawable, radius: Float): Boolean = when (drawable) {
        is GradientDrawable -> {
            drawable.cornerRadii = null
            drawable.cornerRadius = radius
            true
        }
        is InsetDrawable -> drawable.drawable?.let { applyDrawableRadius(it, radius) } == true
        is LayerDrawable -> {
            var changed = false
            for (i in 0 until drawable.numberOfLayers) {
                if (applyDrawableRadius(drawable.getDrawable(i), radius)) changed = true
            }
            changed
        }
        else -> false
    }

    private fun resize(view: View, target: Size?) {
        val params = view.layoutParams ?: return
        val current = Size(params.width, params.height)
        val previous = sizes[view]
        val original = if (previous?.second == current) previous.first else current
        val desired = target ?: original
        if (target == null) sizes.remove(view) else sizes[view] = original to target
        if (current != desired) {
            params.width = desired.width
            params.height = desired.height
            view.layoutParams = params
        }
    }

    private fun updateOutline(view: View, radius: Float?) {
        val state = outlines[view]
        if (radius == null) {
            outlines.remove(view)
            if (state != null && view.outlineProvider === state.custom) {
                view.outlineProvider = state.provider
                view.clipToOutline = state.clipToOutline
            }
            return
        }
        if (state != null && view.outlineProvider === state.custom) {
            if (state.custom.radius != radius) {
                state.custom.radius = radius
                view.invalidateOutline()
            }
            return
        }
        val outline = RoundedOutline(radius)
        outlines[view] = OutlineState(view.outlineProvider, view.clipToOutline, outline)
        view.outlineProvider = outline
        view.clipToOutline = true
    }
}
