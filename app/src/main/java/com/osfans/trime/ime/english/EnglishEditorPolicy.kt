// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.english

import android.text.InputType
import android.view.inputmethod.EditorInfo

/**
 * Admission only; mode/session/raw-input checks remain in the service.
 * The user explicitly enabled local English suggestions across apps regardless
 * of NO_SUGGESTIONS. This does not override sensitive/private field exclusions;
 * never rely solely on an OEM secure keyboard to protect password input.
 */
object EnglishEditorPolicy {
    fun allows(inputType: Int, imeOptions: Int): Boolean {
        if (inputType and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT) return false
        val variation = inputType and InputType.TYPE_MASK_VARIATION
        if (variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_URI,
            )) return false
        return imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING == 0
    }
}
