package cn.ianzb.hyperrefine.hook.connect.mirror

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * 气泡上的「妙享投屏」图标（移植自「妙享桌面增强」的 `r3.s`）。
 */
internal class BubbleGlyphDrawable(private val density: Float) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val bounds = RectF(bounds)
        val width = bounds.width() * 0.46f
        val height = bounds.height() * 0.9f
        val left = bounds.centerX() - width / 2f
        val top = bounds.centerY() - height / 2f
        val radius = Math.min(width, height) * 0.18f
        val rect = RectF(left, top, left + width, top + height)

        paint.style = Paint.Style.FILL
        paint.shader = null
        paint.color = -526345
        canvas.drawRoundRect(rect, radius, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Math.max(1f, density * 0.75f)
        paint.color = -3092272
        canvas.drawRoundRect(rect, radius, radius, paint)

        val insetX = width * 0.1f
        val insetY = height * 0.075f
        val inner = RectF(rect.left + insetX, rect.top + insetY, rect.right - insetX, rect.bottom - insetY)
        paint.style = Paint.Style.FILL
        paint.shader = LinearGradient(
            inner.left, inner.top, inner.left, inner.bottom,
            -4200449, -4399, Shader.TileMode.CLAMP
        )
        val innerRadius = radius * 0.62f
        canvas.drawRoundRect(inner, innerRadius, innerRadius, paint)
        paint.shader = null
    }

    override fun getIntrinsicHeight(): Int = Math.round(density * 48f)

    override fun getIntrinsicWidth(): Int = Math.round(density * 48f)

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
    }
}
