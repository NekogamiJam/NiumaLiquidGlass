/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.broadcast

import android.view.inputmethod.EditorInfo
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.ime.core.EditorEnterAction
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance

internal fun resolveEnterKeyGlyph(action: Int?): String = when (action) {
    EditorInfo.IME_ACTION_SEARCH -> "vec@search"
    EditorInfo.IME_ACTION_SEND -> "vec@send"
    EditorInfo.IME_ACTION_DONE -> "vec@done"
    EditorInfo.IME_ACTION_GO -> "vec@go"
    EditorInfo.IME_ACTION_NEXT -> "vec@next"
    EditorInfo.IME_ACTION_PREVIOUS -> "vec@previous"
    else -> "vec@return"
}

internal fun shouldNotifyEnterKeyUpdate(oldLabel: String, newLabel: String, oldGlyph: String, newGlyph: String): Boolean =
    oldLabel != newLabel || oldGlyph != newGlyph

class EnterKeyDisplayDelegate(override val di: DI) : DIAware {
    private val broadcaster: InputBroadcaster by instance()
    private val theme: Theme by instance()

    companion object {
        const val DEFAULT_LABEL = "Enter"
    }

    enum class Mode {
        ACTION_LABEL_NEVER,
        ACTION_LABEL_ONLY,
        ACTION_LABEL_PREFERRED,
        CUSTOM_PREFERRED,
    }

    val mode: Mode = runCatching { Mode.entries[theme.style.enterLabelMode] }.getOrDefault(Mode.ACTION_LABEL_NEVER)

    var keyLabel: String = DEFAULT_LABEL
        private set

    /** Matching vector glyph for the editor action; presentation only. */
    var keyGlyph: String = "vec@return"
        private set

    fun updateLabelOnEditorInfo(info: EditorInfo) {
        updateLabel(info.inputType, info.imeOptions, info.actionId, info.actionLabel)
    }

    /** Updates the visible text before notifying listeners, without repeating unchanged labels. */
    internal fun updateLabel(inputType: Int, imeOptions: Int, actionId: Int, actionLabel: CharSequence?) {
        val action = EditorEnterAction.resolve(inputType, imeOptions, actionId, actionLabel)
        val glyph = resolveEnterKeyGlyph(action)
        val oldGlyph = keyGlyph
        keyGlyph = glyph
        val label = EditorEnterAction.label(action, actionLabel, mode.ordinal, theme.style.enterLabels.default) { themeAction ->
            with(theme.style.enterLabels) {
                when (themeAction) {
                    EditorInfo.IME_ACTION_DONE -> done
                    EditorInfo.IME_ACTION_GO -> go
                    EditorInfo.IME_ACTION_NEXT -> next
                    EditorInfo.IME_ACTION_PREVIOUS -> pre
                    EditorInfo.IME_ACTION_SEARCH -> search
                    EditorInfo.IME_ACTION_SEND -> send
                    else -> null
                }
            }
        }
        if (!shouldNotifyEnterKeyUpdate(keyLabel, label, oldGlyph, glyph)) return
        keyLabel = label
        broadcaster.onEnterKeyLabelUpdate(keyLabel)
    }
}
