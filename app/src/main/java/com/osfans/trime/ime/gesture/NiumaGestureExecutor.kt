/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.gesture

/** Only the first three global candidates; never editor text, persisted or logged. */
data class GestureEngineState(val raw: String, val schemaId: String, val asciiMode: Boolean, val candidates: List<String>, val caretPos: Int = raw.length)

interface GestureEngine {
    suspend fun snapshot(): GestureEngineState
    suspend fun selectGlobal(index: Int): Boolean
}

/** Caller runs this entire operation in the existing postRimeJob queue, including fallback. */
object NiumaGestureExecutor {
    enum class Outcome { SELECTED, FALLBACK, DROPPED, SELECTION_REJECTED }

    suspend fun execute(
        engine: GestureEngine,
        key: Char,
        gesture: GestureKind,
        modifiers: Int,
        codeIndex: NiumaCodeIndex?,
        stillCurrent: () -> Boolean,
        fallback: suspend () -> Unit,
    ): Outcome {
        if (!stillCurrent()) return Outcome.DROPPED
        val observed = engine.snapshot()
        if (!stillCurrent()) return Outcome.DROPPED
        val index = if (observed.caretPos != observed.raw.length) null else NiumaGestureRules.globalSelectionIndex(key, gesture, observed.raw,
            observed.schemaId, observed.asciiMode, modifiers, observed.candidates.size, codeIndex)
        // Another native producer may bypass the keyboard queue. Never select cached text or
        // reinterpret a changed context, even though ordinary rapid keys stay FIFO in this queue.
        val fresh = engine.snapshot()
        if (!stillCurrent() || fresh != observed) return Outcome.DROPPED
        if (index != null) {
            // A failed selection must not additionally type a fallback letter/symbol.
            return if (engine.selectGlobal(index)) Outcome.SELECTED else Outcome.SELECTION_REJECTED
        }
        fallback()
        return Outcome.FALLBACK
    }
}
