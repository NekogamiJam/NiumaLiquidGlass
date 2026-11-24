/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice

internal object OfflineTranscriptPolicy {
    const val MAX_CHARS = 4096
    /** Bounded RAM-only preview. Never writes to an editor, file or log. */
    fun append(previous: String, segment: String): String {
        val next = segment.filter { !it.isISOControl() || it == '\n' }.trim()
        if (next.isEmpty()) return previous
        val space = previous.isNotEmpty() && asciiWord(next.first()) &&
            (asciiWord(previous.last()) || previous.last() in ".?!,:;)")
        val combined = previous + (if (space) " " else "") + next
        require(combined.length <= MAX_CHARS) { "Offline transcript limit exceeded" }
        return combined
    }
    private fun asciiWord(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'
}
