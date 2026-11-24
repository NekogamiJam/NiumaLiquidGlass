package com.osfans.trime.ime.english

import android.text.InputType
import android.view.inputmethod.EditorInfo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class EnglishEditorPolicyTest : FunSpec({
    test("admits observed QQ flags without any package whitelist") {
        EnglishEditorPolicy.allows(0x280001, 0x4) shouldBe true
        EnglishEditorPolicy.allows(0x80001, 0) shouldBe true
    }
    test("NO_SUGGESTIONS cannot disable suggestions in ordinary text fields") {
        val variations = listOf(InputType.TYPE_TEXT_VARIATION_NORMAL, InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE,
            InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE, InputType.TYPE_TEXT_VARIATION_FILTER,
            InputType.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT, InputType.TYPE_TEXT_VARIATION_PERSON_NAME)
        for (v in variations) {
            EnglishEditorPolicy.allows(InputType.TYPE_CLASS_TEXT or v, 0) shouldBe true
            EnglishEditorPolicy.allows(InputType.TYPE_CLASS_TEXT or v or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0) shouldBe true
        }
    }
    test("keeps existing ordinary multiline app behavior") {
        EnglishEditorPolicy.allows(InputType.TYPE_CLASS_TEXT, 0) shouldBe true
        EnglishEditorPolicy.allows(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE, 0) shouldBe true
    }
    test("all password address and URI variants remain excluded with or without NO_SUGGESTIONS") {
        for (v in listOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD, InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_URI)) {
            for (flags in listOf(0, InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)) {
                EnglishEditorPolicy.allows(InputType.TYPE_CLASS_TEXT or v or flags, 0) shouldBe false
            }
        }
    }
    test("privacy flag remains an absolute exclusion") {
        for (type in listOf(InputType.TYPE_CLASS_TEXT, 0x280001, 0x80001)) {
            EnglishEditorPolicy.allows(type, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_ACTION_SEND) shouldBe false
        }
    }
    test("nontext and null input types remain excluded") {
        for (type in listOf(InputType.TYPE_NULL, InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE, InputType.TYPE_CLASS_DATETIME,
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)) {
            EnglishEditorPolicy.allows(type, 0) shouldBe false
            EnglishEditorPolicy.allows(type or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0) shouldBe false
        }
    }
})
