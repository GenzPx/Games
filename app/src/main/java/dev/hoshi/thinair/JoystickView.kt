package dev.hoshi.thinair

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

class JoystickView @JvmOverloads constructor(
    ctx: Context, attrs: AttributeSet? = null,
) : View(ctx, attrs) {
    var nx = 0f
    var ny = 0f
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 5f; color = 0x55E8E0D2
    }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0x99C9A227.toInt()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val r = min(cx, cy) - 8f
        c.drawCircle(cx, cy, r, ring)
        c.drawCircle(cx + nx * r * 0.62f, cy + ny * r * 0.62f, r * 0.32f, knob)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val cx = width / 2f; val cy = height / 2f
        val r = min(cx, cy) - 8f
        if (e.action == MotionEvent.ACTION_UP || e.action == MotionEvent.ACTION_CANCEL) {
            nx = 0f; ny = 0f; invalidate(); return true
        }
        var dx = e.x - cx; var dy = e.y - cy
        val len = hypot(dx, dy)
        if (len > r) { dx = dx / len * r; dy = dy / len * r }
        nx = (dx / r).coerceIn(-1f, 1f)
        ny = (dy / r).coerceIn(-1f, 1f)
        invalidate()
        return true
    }
}
