package com.osfans.trime.ime.gesture

/** Only a tiny cursor window is inspected, never the document prefix or clipboard. */
data class BeforeCursorSnapshot(val text: String?, val start: Int, val end: Int, val offset: Int)

object ClearBeforeCursorPolicy {
    fun deleteUtf16Before(snapshot: BeforeCursorSnapshot, expectedStart: Int, expectedEnd: Int): Int? {
        val text = snapshot.text ?: return null
        val cursor = snapshot.start
        if (snapshot.offset < 0 || cursor < 0 || cursor > text.length || cursor != snapshot.end) return null
        val absolute = snapshot.offset.toLong() + cursor
        if (absolute <= 0 || absolute > Int.MAX_VALUE || expectedStart != expectedEnd || absolute != expectedStart.toLong()) return null
        // Deleting the ENTIRE prefix uses its absolute UTF-16 length. This is not
        // code-point counting; no arbitrary 4096-character truncation or full-text read.
        // Refuse a cursor inside a surrogate pair rather than cut the boundary.
        if (cursor > 0 && Character.isHighSurrogate(text[cursor - 1])) return null
        if (cursor < text.length && Character.isLowSurrogate(text[cursor])) return null
        return absolute.toInt()
    }
}
