// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.glass

/** Offline-tunable material recipe; native blur/range are vendor raw units, NOT dp or Gaussian sigma. */
data class MaterialStyle(
    val blur: Float,
    val maskColor: Int,
    val maskOpacity: Float,
    val refractionIntensity: Float,
    val refractionRange: Float,
    val fallbackColor: Int,
)

/** Shared by panel, keycaps and offline tests, so light/dark never use contradictory recipes. */
object LiquidGlassStyle {
    const val CONFIG_ID = "niuma_glass.trime"
    const val NATIVE_KEY_MATERIALS = false
    const val PANEL_GLINTS_ENABLED = false
    const val PREEDIT_ROW_DP = 32
    const val NATIVE_INNER_STROKE_ENABLED = true
    // A bright label belongs on a dark glass surface. Read the active palette, not system uiMode:
    // manual palette overrides must keep glyphs, masks and keycaps on the same side.
    fun isDarkForKeyText(argb: Int): Boolean {
        val r = (argb ushr 16) and 255
        val g = (argb ushr 8) and 255
        val b = argb and 255
        return (299 * r + 587 * g + 114 * b) >= 128000
    }

    fun panel(dark: Boolean) = MaterialStyle(
        blur = 0.085f, // Stronger shared-panel smoothing; still only one native material.
        maskColor = if (dark) 0xff000000.toInt() else 0xffffffff.toInt(),
        maskOpacity = if (dark) 0.58f else 0.66f,
        refractionIntensity = 90f,
        refractionRange = 56f, // Vendor raw units, not dp or a Gaussian sigma.
        fallbackColor = if (dark) 0xff242426.toInt() else 0xffeeeef0.toInt(),
    )

    fun key(dark: Boolean) = MaterialStyle(
        blur = panel(dark).blur, // Shared panel blur only: no extra per-key blur passes.
        maskColor = if (dark) 0xff000000.toInt() else 0xffffffff.toInt(),
        maskOpacity = if (dark) 0.64f else 0.72f, // Low transparency, not near-clear keycaps.
        refractionIntensity = 0f, // Avoid lens warping 30+ times underneath small glyphs.
        refractionRange = 0f,
        fallbackColor = if (dark) 0xff424246.toInt() else 0xfff6f6f8.toInt(),
    )

    /** Smooth glint taper: both opacity AND width vanish at the ends; never a uniform outline. */
    fun glintEnvelope(fraction: Float): Float {
        val t = fraction.coerceIn(0f, 1f)
        val bell = kotlin.math.sin(Math.PI * t).toFloat().coerceAtLeast(0f)
        return bell * bell
    }
}
