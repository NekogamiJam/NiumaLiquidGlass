// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.glass

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Cheap keycap over the ONE shared native-blurred panel. Never creates a native material. */
class GlassKeySurface(context: Context) : View(context) {
    private var dark = false
    private var radius = 0f
    private var pressedTone = false
    private var activeTone = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var recipe = LiquidGlassStyle.key(false)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
        // No outline clipping, per-key material, attach callbacks, or offscreen blur layer.
    }

    fun updateAppearance(isDark: Boolean, radiusPx: Float, pressed: Boolean, active: Boolean = false) {
        val r = radiusPx.coerceAtLeast(0f)
        if (dark == isDark && radius == r && pressedTone == pressed && activeTone == active) return
        if (dark != isDark) recipe = LiquidGlassStyle.key(isDark)
        dark = isDark; radius = r; pressedTone = pressed; activeTone = active
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val density = resources.displayMetrics.density
        val stroke = density * 0.55f
        // Fill and stroke share exactly the same inset geometry; no independently clipped native edge.
        val inset = stroke / 2f + 0.25f * density
        rect.set(inset, inset, width - inset, height - inset)
        if (rect.width() <= 0f || rect.height() <= 0f) return
        val r = (radius - inset).coerceIn(0f, minOf(rect.width(), rect.height()) / 2f)
        paint.style = Paint.Style.FILL
        paint.color = recipe.maskColor
        paint.alpha = (255f * recipe.maskOpacity).toInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, r, r, paint)
        paint.alpha = 255
        if (activeTone) {
            paint.color = if (dark) 0x286EA6FF else 0x203566CC
            canvas.drawRoundRect(rect, r, r, paint)
        }
        if (pressedTone) {
            paint.color = if (dark) 0x22ffffff else 0x18000000
            canvas.drawRoundRect(rect, r, r, paint)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = if (activeTone) { if (dark) 0x996EA6FF.toInt() else 0x993566CC.toInt() }
            else if (dark) 0x35ffffff else 0x26000000
        canvas.drawRoundRect(rect, r, r, paint)
    }
}
