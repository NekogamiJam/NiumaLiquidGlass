// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.glass

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.min

/** Two separated grazing glints. Intentionally NO closed path, bottom line or uniform ring. */
class PanelEdgeGlints(private val density: Float, dark: Boolean) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val measures = ArrayList<PathMeasure>(2)
    private val start = FloatArray(2)
    private val end = FloatArray(2)
    private var darkTone = dark
    private var opacity = 255

    fun setDark(dark: Boolean) {
        if (darkTone != dark) { darkTone = dark; invalidateSelf() }
    }

    override fun onBoundsChange(bounds: Rect) {
        measures.clear()
        val paths = ArrayList<Path>(2)
        if (bounds.width() <= 0 || bounds.height() <= 0) return
        val inset = 0.65f * density
        val l = bounds.left + inset
        val t = bounds.top + inset
        val r = bounds.right - inset
        val w = r - l
        val radius = min(18f * density, min(w / 2, bounds.height() / 2f))
        // Short left shoulder -> rounded corner -> a short stretch of the top edge.
        paths += Path().apply {
            moveTo(l, t + radius + 12f * density)
            lineTo(l, t + radius)
            arcTo(RectF(l, t, l + 2 * radius, t + 2 * radius), 180f, 90f)
            lineTo(l + maxOf(radius, w * 0.22f), t)
        }
        // A disjoint, weaker upper-right glint: big unlit gaps remain between/below both patches.
        paths += Path().apply {
            moveTo(r - maxOf(radius, w * 0.11f), t)
            lineTo(r - radius, t)
            arcTo(RectF(r - 2 * radius, t, r, t + 2 * radius), 270f, 90f)
            lineTo(r, t + radius + 20f * density)
        }
        paths.forEach { measures += PathMeasure(it, false) }
    }

    override fun draw(canvas: Canvas) {
        measures.forEachIndexed { index, measure ->
            val steps = 72
            for (i in 0 until steps) {
                val f = (i + 0.5f) / steps
                val envelope = LiquidGlassStyle.glintEnvelope(f)
                val peak = (if (darkTone) 0.25f else 0.39f) * (if (index == 0) 1f else 0.65f)
                paint.color = 0xffffffff.toInt()
                paint.alpha = (255f * peak * envelope * opacity / 255f).toInt().coerceIn(0, 255)
                paint.strokeWidth = density * (0.10f + 0.70f * envelope)
                measure.getPosTan(measure.length * i / steps, start, null)
                measure.getPosTan(measure.length * (i + 1) / steps, end, null)
                canvas.drawLine(start[0], start[1], end[0], end[1], paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) { opacity = alpha.coerceIn(0, 255); invalidateSelf() }
    override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
