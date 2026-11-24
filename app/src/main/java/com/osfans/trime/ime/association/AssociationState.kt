// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.association

/** Pure frontend state: no Rime API, candidate mutation, user dictionary, or persistent history. */
class AssociationState(private val maxCandidates: Int = 12) {
    private var index: Map<String, List<String>> = emptyMap()
    private var recent = ""
    var suggestions: List<String> = emptyList()
        private set

    fun installIndex(value: Map<String, List<String>>) { index = value }

    /** Called before any key/action dispatch, so even an untranslatable code cannot show stale suggestions. */
    fun onInputStarted() { suggestions = emptyList() }

    /** Input-session/cursor/selection/security changes must discard context, not read surrounding text. */
    fun reset() { recent = ""; suggestions = emptyList() }

    fun onCommitted(text: String) {
        suggestions = emptyList()
        if (!isHan(text)) { recent = ""; return }
        recent = suffix(recent + text, 16)
        for (length in minOf(4, recent.codePointCount(0, recent.length)) downTo 1) {
            val values = index[suffix(recent, length)].orEmpty()
            if (values.isNotEmpty()) {
                suggestions = values.asSequence().filter(::isHan).distinct().take(maxCandidates).toList()
                return
            }
        }
    }

    private fun suffix(text: String, count: Int): String {
        val size = text.codePointCount(0, text.length)
        return text.substring(text.offsetByCodePoints(text.length, -minOf(size, count)))
    }

    companion object {
        fun isHan(text: String): Boolean = text.isNotEmpty() && text.codePoints().allMatch {
            it in 0x3400..0x4dbf || it in 0x4e00..0x9fff || it in 0xf900..0xfaff ||
                it in 0x20000..0x2ffff || it in 0x30000..0x323af
        }
    }
}
