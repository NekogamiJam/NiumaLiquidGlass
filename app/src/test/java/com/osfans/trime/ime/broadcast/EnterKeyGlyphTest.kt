package com.osfans.trime.ime.broadcast

import android.view.inputmethod.EditorInfo
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class EnterKeyGlyphTest :
    StringSpec({
        "editor action changes map to their own visual glyph" {
            resolveEnterKeyGlyph(EditorInfo.IME_ACTION_SEARCH) shouldBe "vec@search"
            resolveEnterKeyGlyph(EditorInfo.IME_ACTION_SEND) shouldBe "vec@send"
            resolveEnterKeyGlyph(EditorInfo.IME_ACTION_DONE) shouldBe "vec@done"
            resolveEnterKeyGlyph(EditorInfo.IME_ACTION_GO) shouldBe "vec@go"
            resolveEnterKeyGlyph(EditorInfo.IME_ACTION_NEXT) shouldBe "vec@next"
            resolveEnterKeyGlyph(EditorInfo.IME_ACTION_PREVIOUS) shouldBe "vec@previous"
            resolveEnterKeyGlyph(null) shouldBe "vec@return"
        }

        "different actions remain distinct even when the editor reuses one label" {
            val searchGlyph = resolveEnterKeyGlyph(EditorInfo.IME_ACTION_SEARCH)
            val sendGlyph = resolveEnterKeyGlyph(EditorInfo.IME_ACTION_SEND)
            searchGlyph shouldBe "vec@search"
            sendGlyph shouldBe "vec@send"
            (searchGlyph == sendGlyph) shouldBe false
            shouldNotifyEnterKeyUpdate("Go", "Go", searchGlyph, sendGlyph) shouldBe true
            shouldNotifyEnterKeyUpdate("Go", "Go", sendGlyph, sendGlyph) shouldBe false
        }
    })
