// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.glass

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class LiquidGlassStyleTest : FunSpec({
    test("palette glyphs, not system mode, select the material tone") {
        LiquidGlassStyle.isDarkForKeyText(0xff151515.toInt()) shouldBe false
        LiquidGlassStyle.isDarkForKeyText(0xfff7f7f7.toInt()) shouldBe true
    }
    test("panel masks are neutral white and black with substantially stronger coverage") {
        LiquidGlassStyle.panel(false).maskColor shouldBe 0xffffffff.toInt()
        LiquidGlassStyle.panel(true).maskColor shouldBe 0xff000000.toInt()
        for (dark in listOf(false, true)) {
            val panel = LiquidGlassStyle.panel(dark)
            (panel.maskOpacity in 0.5f..0.75f) shouldBe true
            panel.blur shouldBe 0.085f
            panel.refractionRange shouldBe 56f
            (panel.fallbackColor ushr 24) shouldBe 255
        }
    }
    test("key surfaces share the panel blur with low transparency and no independent native layers") {
        for (dark in listOf(false, true)) {
            val key = LiquidGlassStyle.key(dark)
            key.blur shouldBe LiquidGlassStyle.panel(dark).blur
            (key.maskOpacity >= LiquidGlassStyle.panel(dark).maskOpacity) shouldBe true
            (key.maskOpacity in 0.6f..0.8f) shouldBe true
            key.refractionIntensity shouldBe 0f
        }
    }
    test("header reservation and optional single-panel native stroke are explicit") {
        LiquidGlassStyle.PREEDIT_ROW_DP shouldBe 32
        LiquidGlassStyle.NATIVE_INNER_STROKE_ENABLED shouldBe true
        val stroke = InnerStrokeLight()
        stroke.geometry0 shouldBe 0f
        stroke.geometry1 shouldBe 2f
        stroke.geometry2 shouldBe 0f
        stroke.widthRaw shouldBe 4
        (stroke.intensity in 0f..1f) shouldBe true
    }
    test("expensive per-key native materials and segmented panel glints are disabled") {
        LiquidGlassStyle.NATIVE_KEY_MATERIALS shouldBe false
        LiquidGlassStyle.PANEL_GLINTS_ENABLED shouldBe false
        LiquidGlassStyle.glintEnvelope(0f) shouldBe 0f
        (LiquidGlassStyle.glintEnvelope(1f) < 0.00001f) shouldBe true
        LiquidGlassStyle.glintEnvelope(0.5f) shouldBe 1f
        (LiquidGlassStyle.glintEnvelope(0.1f) < LiquidGlassStyle.glintEnvelope(0.4f)) shouldBe true
        (0..100).all { LiquidGlassStyle.glintEnvelope(it / 100f) in 0f..1f } shouldBe true
    }
})
