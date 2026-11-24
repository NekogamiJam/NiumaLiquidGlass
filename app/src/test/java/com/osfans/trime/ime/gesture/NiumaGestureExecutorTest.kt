// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.gesture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class TestGestureEngine(var state: GestureEngineState) : GestureEngine {
    val actions = mutableListOf<String>()
    var reads = 0
    var afterRead: (Int) -> Unit = {}
    var selectionSucceeds = true
    override suspend fun snapshot(): GestureEngineState {
        val result = state
        afterRead(++reads)
        return result
    }
    override suspend fun selectGlobal(index: Int): Boolean {
        actions += "select:$index"
        return selectionSucceeds
    }
}

class NiumaGestureExecutorTest : FunSpec({
    val index = NiumaCodeIndex.fromValidated(listOf("a", "az", "azz", "b", "zcza"), setOf("a", "az", "zc"))
    fun state(raw: String = "b", candidates: List<String> = listOf("一", "二", "三")) =
        GestureEngineState(raw, "niuma_glass", false, candidates)

    test("production executor selects global third without submitting cached strings") {
        val e = TestGestureEngine(state())
        NiumaGestureExecutor.execute(e, 'z', GestureKind.SWIPE_UP, 0, index, {true}) { e.actions += "fallback" } shouldBe NiumaGestureExecutor.Outcome.SELECTED
        e.actions shouldBe listOf("select:2")
    }
    test("tap z fallback and subsequent keys execute inline in original queue order") {
        val e = TestGestureEngine(state("a"))
        repeat(2) {
            NiumaGestureExecutor.execute(e, 'z', GestureKind.TAP, 0, index, {true}) {
                e.actions += "input:z"
                e.state = e.state.copy(raw = e.state.raw + "z", caretPos = e.state.raw.length + 1)
            } shouldBe NiumaGestureExecutor.Outcome.FALLBACK
        }
        NiumaGestureExecutor.execute(e, 'z', GestureKind.TAP, 0, index, {true}) { e.actions += "unexpected" } shouldBe NiumaGestureExecutor.Outcome.SELECTED
        e.actions shouldBe listOf("input:z", "input:z", "select:2")
    }
    test("old editor/session before dispatch causes no native read, selection or fallback") {
        val e = TestGestureEngine(state())
        NiumaGestureExecutor.execute(e, 'l', GestureKind.SWIPE_UP, 0, index, {false}) { e.actions += "fallback" } shouldBe NiumaGestureExecutor.Outcome.DROPPED
        e.reads shouldBe 0; e.actions shouldBe emptyList()
    }
    test("editor switch during suspended native reads discards both selection and fallback") {
        val e = TestGestureEngine(state()); var sameSession = true
        e.afterRead = { if(it == 2) sameSession = false }
        NiumaGestureExecutor.execute(e, 'z', GestureKind.TAP, 0, index, {sameSession}) { e.actions += "fallback" } shouldBe NiumaGestureExecutor.Outcome.DROPPED
        e.actions shouldBe emptyList()
    }
    test("changes to raw code or first three native candidates invalidate the observed action") {
        for (changed in listOf(state("a"), state(candidates=listOf("三", "二", "一")))) {
            val e = TestGestureEngine(state())
            e.afterRead = { if(it == 1) e.state = changed }
            NiumaGestureExecutor.execute(e, 'z', GestureKind.SWIPE_UP, 0, index, {true}) { e.actions += "fallback" } shouldBe NiumaGestureExecutor.Outcome.DROPPED
            e.actions shouldBe emptyList()
        }
    }
    test("failed native selection is not followed by an extra letter or symbol") {
        val e = TestGestureEngine(state()); e.selectionSucceeds = false
        NiumaGestureExecutor.execute(e, 'z', GestureKind.SWIPE_UP, 0, index, {true}) { e.actions += "fallback" } shouldBe NiumaGestureExecutor.Outcome.SELECTION_REJECTED
        e.actions shouldBe listOf("select:2")
    }
    test("candidate shortage and missing metadata retain the caller's exact fallback once") {
        for (metadata in listOf(index, null)) {
            val e = TestGestureEngine(state(candidates=listOf("一")))
            NiumaGestureExecutor.execute(e, 'z', GestureKind.SWIPE_UP, 0, metadata, {true}) { e.actions += "configured-long-action" } shouldBe NiumaGestureExecutor.Outcome.FALLBACK
            e.actions shouldBe listOf("configured-long-action")
        }
    }
    test("middle-of-code editing, ASCII and modifier chords do not intercept normal input") {
        for ((s, modifiers) in listOf(state().copy(caretPos=0) to 0, state().copy(asciiMode=true) to 0, state() to 1)) {
            val e = TestGestureEngine(s)
            NiumaGestureExecutor.execute(e, 'z', GestureKind.TAP, modifiers, index, {true}) { e.actions += "normal" } shouldBe NiumaGestureExecutor.Outcome.FALLBACK
            e.actions shouldBe listOf("normal")
        }
    }
})
