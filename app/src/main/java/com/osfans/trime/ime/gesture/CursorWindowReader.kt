package com.osfans.trime.ime.gesture

/** Short-lived bounded cursor data. Never stores an editor/document history. */
data class CursorWindow(val text: String, val start: Int, val end: Int, val offset: Int)

interface CursorWindowSource {
    fun surrounding(before: Int, after: Int): CursorWindow?
    fun before(length: Int): String?
    fun after(length: Int): String?
    fun selected(): String?
}

object CursorWindowReader {
    fun read(source: CursorWindowSource, before: Int, after: Int, expectedStart: Int, expectedEnd: Int): CursorWindow? {
        if (before !in 1..40 || after !in 0..2 || expectedStart < 0 || expectedStart != expectedEnd) return null
        val modern = runCatching { source.surrounding(before, after) }.getOrNull()
        // A supported, absolute snapshot must agree with the current selection.
        // A contradiction is not permission to try a weaker read.
        if (modern != null && (modern.start != modern.end || modern.start !in 0..modern.text.length ||
                modern.start != minOf(before, expectedStart) || modern.text.length - modern.start > after || !validBoundary(modern))) return null
        if (modern != null && modern.offset >= 0) {
            if (modern.offset.toLong() + modern.start != expectedStart.toLong()) return null
            return modern
        }
        // Some custom/Web editors implement the older APIs but not API31
        // getSurroundingText, or cannot supply its absolute offset. Use only a
        // tiny window and the known, collapsed onUpdateSelection position.
        val left = runCatching { source.before(before) }.getOrNull() ?: return null
        val right = runCatching { source.after(after) }.getOrNull() ?: return null
        val selection = runCatching { source.selected() }.getOrElse { return null }
        if (!selection.isNullOrEmpty() || left.length != minOf(before, expectedStart) || right.length > after) return null
        val result = CursorWindow(left + right, left.length, left.length, expectedStart - left.length)
        return result.takeIf { validBoundary(it) }
    }

    private fun validBoundary(window: CursorWindow): Boolean =
        (window.start == 0 || !Character.isHighSurrogate(window.text[window.start - 1])) &&
            (window.start == window.text.length || !Character.isLowSurrogate(window.text[window.start]))
}
