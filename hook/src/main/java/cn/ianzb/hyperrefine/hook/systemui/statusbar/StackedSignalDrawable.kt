package cn.ianzb.hyperrefine.hook.systemui.statusbar

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import kotlin.math.roundToInt

/** Four independently drawn cells per SIM; SystemUI supplies the current light/dark tint. */
internal class StackedSignalDrawable(
    private val density: Float,
    private var topLevel: Int,
    private var bottomLevel: Int,
    private var baseColor: Int,
) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tint: ColorStateList? = null
    private var foreground = baseColor
    private var opacity = 255
    private var geometry: List<DualSimArtworkPolicy.Cell> = emptyList()
    private var geometryWidth = -1
    private var geometryHeight = -1
    private var artworkScale = 1f
    private var verticalOffset = 0f

    /** Update the same drawable: a tint or signal event must not swap its shape or intrinsic size. */
    fun update(top: Int, bottom: Int, color: Int, scale: Float, offset: Float) {
        if (topLevel == top && bottomLevel == bottom && baseColor == color &&
            artworkScale == scale && verticalOffset == offset) return
        topLevel = top; bottomLevel = bottom; baseColor = color
        artworkScale = scale; verticalOffset = offset
        foreground = tint?.getColorForState(state, tint!!.defaultColor) ?: color
        invalidateSelf()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.width() != geometryWidth || bounds.height() != geometryHeight) {
            geometryWidth = bounds.width(); geometryHeight = bounds.height()
            geometry = DualSimArtworkPolicy.cells(geometryWidth.toFloat(), geometryHeight.toFloat())
        }
        val saved = canvas.save()
        canvas.translate(bounds.left.toFloat(), bounds.top + verticalOffset)
        // User sizing stays inside the drawable; do not overwrite native ImageView animations.
        canvas.scale(artworkScale, artworkScale, bounds.width() / 2f, bounds.height() / 2f)
        geometry.forEach { cell ->
            val level = if (cell.row == 0) topLevel else bottomLevel
            paint.color = DualSimArtworkPolicy.cellColor(foreground, opacity, cell.column < level)
            canvas.drawRoundRect(cell.left, cell.top, cell.right, cell.bottom, cell.radius, cell.radius, paint)
        }
        canvas.restoreToCount(saved)
    }

    override fun setAlpha(alpha: Int) {
        opacity = alpha.coerceIn(0, 255); invalidateSelf()
    }
    override fun getAlpha() = opacity
    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter; invalidateSelf()
    }
    override fun setTintList(tint: ColorStateList?) {
        this.tint = tint
        foreground = tint?.getColorForState(state, tint.defaultColor) ?: baseColor
        invalidateSelf()
    }
    override fun isStateful() = tint?.isStateful == true
    override fun onStateChange(state: IntArray): Boolean {
        val next = tint?.let { it.getColorForState(state, it.defaultColor) } ?: baseColor
        if (foreground == next) return false
        foreground = next; invalidateSelf(); return true
    }
    @Deprecated("Drawable opacity is retained for Android compatibility")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth() = (DualSimArtworkPolicy.VIEWPORT_WIDTH * density).roundToInt().coerceAtLeast(1)
    override fun getIntrinsicHeight() = (DualSimArtworkPolicy.VIEWPORT_HEIGHT * density).roundToInt().coerceAtLeast(1)
}
