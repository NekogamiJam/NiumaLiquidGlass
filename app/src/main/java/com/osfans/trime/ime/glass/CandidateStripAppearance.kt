// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.glass

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable

/** Candidate-row appearance only. No recognizer, editor, engine or global clipboard access. */
data class CandidateStripAppearance(
    val textSizeSp: Float,
    val foreground: Int,
    val secondary: Int,
    val typeface: Typeface?,
    val rowHeightPx: Int,
    val itemPaddingPx: Int,
    val done: Drawable?,
    val close: Drawable?,
) {
    fun ripple(): Drawable = RippleDrawable(
        ColorStateList.valueOf(Color.argb(0x28, Color.red(foreground), Color.green(foreground), Color.blue(foreground))),
        null,
        GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = rowHeightPx / 6f },
    )
}

interface CandidateStripAppearanceTarget {
    fun applyAppearance(appearance: CandidateStripAppearance)
}
