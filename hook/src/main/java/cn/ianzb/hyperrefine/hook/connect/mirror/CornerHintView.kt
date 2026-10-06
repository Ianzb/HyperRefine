package cn.ianzb.hyperrefine.hook.connect.mirror

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 浮窗四角「拖拽缩放」提示：在每个角绘制一条弯曲的圆角圆弧（圆角条状）。
 *
 * 仅作视觉提示，不拦截触摸：本视图不接收点击，事件会继续下发给画面。
 */
internal class CornerHintView(context: Context) : View(context) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        stroke.color = if (night) COLOR_DARK else COLOR_LIGHT
        stroke.strokeWidth = STROKE_DP * density

        val inset = INSET_DP * density
        val radius = RADIUS_DP * density

        // 圆心落在各角「内侧」radius 处，圆弧朝角外凸、圆心朝窗口内部（即朝内弯曲），
        // 与窗口本身圆角的弯曲方向一致。
        // 左上：180°→270°
        drawCorner(canvas, inset + radius, inset + radius, radius, 180f)
        // 右上：270°→360°
        drawCorner(canvas, w - inset - radius, inset + radius, radius, 270f)
        // 右下：0°→90°
        drawCorner(canvas, w - inset - radius, h - inset - radius, radius, 0f)
        // 左下：90°→180°
        drawCorner(canvas, inset + radius, h - inset - radius, radius, 90f)
    }

    private fun drawCorner(canvas: Canvas, cx: Float, cy: Float, radius: Float, start: Float) {
        rect.set(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(rect, start, 90f, false, stroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    private companion object {
        /** 深色模式：黑色圆弧。 */
        const val COLOR_DARK = 0xE6000000.toInt()

        /** 浅色模式：白色圆弧。 */
        const val COLOR_LIGHT = 0xE6FFFFFF.toInt()

        const val STROKE_DP = 5f
        const val RADIUS_DP = 16f
        const val INSET_DP = 3f
    }
}
