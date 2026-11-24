// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.gesture

/** The physical interaction category relevant to static Niuma upper-symbol rules. */
enum class GestureKind { TAP, SWIPE_UP }

/**
 * Immutable, validated lookup over the complete sorted static Niuma code list.
 * Prefix tests are lower-bound searches and do not materialize all code prefixes.
 */
class NiumaCodeIndex private constructor(
    private val codes: List<String>,
    private val zPrefixes: Set<String>,
) {
    /** True when [raw] is a nonempty prefix of at least one valid source code. */
    fun isValidPrefix(raw: String): Boolean {
        if (raw.isEmpty() || raw.any { it !in 'a'..'z' } || raw.length > 5) return false
        val index = lowerBound(raw)
        return index < codes.size && codes[index].startsWith(raw)
    }

    /** Whether appending a noninitial z to this raw code remains a source-code prefix. */
    fun zCanExtend(raw: String): Boolean = isValidPrefix(raw) && raw in zPrefixes

    private fun lowerBound(prefix: String): Int {
        var low = 0
        var high = codes.size
        while (low < high) {
            val mid = low + (high - low) / 2
            if (codes[mid] < prefix) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        /** Validate static assets once before constructing the runtime index. */
        fun fromValidated(codes: List<String>, zPrefixes: Set<String>): NiumaCodeIndex {
            require(codes.isNotEmpty()) { "codes must not be empty" }
            var previous: String? = null
            for (code in codes) {
                require(code.length in 1..5 && code.all { it in 'a'..'z' }) {
                    "codes must contain lowercase ASCII letters of length 1..5"
                }
                require(previous == null || previous < code) { "codes must be strictly ascending and unique" }
                previous = code
            }
            val expectedZPrefixes = buildSet {
                for (code in codes) {
                    for (index in 1 until code.length) {
                        if (code[index] == 'z') add(code.substring(0, index))
                    }
                }
            }
            require(zPrefixes == expectedZPrefixes) { "zPrefixes must exactly match noninitial-z code paths" }
            return NiumaCodeIndex(codes.toList(), zPrefixes.toSet())
        }
    }
}

/** Pure decision rules; candidate indices are global, never current-page-relative. */
object NiumaGestureRules {
    fun globalSelectionIndex(
        key: Char,
        gesture: GestureKind,
        raw: String,
        schemaId: String,
        asciiMode: Boolean,
        modifiers: Int,
        candidateCount: Int,
        codeIndex: NiumaCodeIndex?,
    ): Int? {
        if (codeIndex == null || !codeIndex.isValidPrefix(raw)) return null
        if (schemaId != "niuma_glass" || asciiMode || modifiers != 0) return null
        val index = when (key) {
            'l' -> if (gesture == GestureKind.SWIPE_UP) 1 else return null
            'z' -> when (gesture) {
                GestureKind.SWIPE_UP -> 2
                // 2026-11-18 user change: with four valid letters a Z-tap now
                // selects the global third candidate as well (like Z-swipe);
                // only prefixes whose z-extension is still a source-code path
                // keep Z as an input letter.
                GestureKind.TAP -> if (codeIndex.zCanExtend(raw)) return null else 2
            }
            else -> return null
        }
        return index.takeIf { candidateCount > it }
    }
}
