// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.glass

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MaterialApplyGateTest : FunSpec({
    test("repeated equal layout only applies a successful material once") {
        val gate = MaterialApplyGate<String>(); val window = Any(); var calls = 0
        repeat(100) {
            if (gate.needsApply("light", 100, 200, window)) {
                calls++; gate.applied("light", 100, 200, window)
            }
        }
        calls shouldBe 1
    }
    test("size or palette changes cannot reuse an incompatible material") {
        val gate = MaterialApplyGate<String>(); val window = Any()
        gate.applied("light", 100, 200, window)
        gate.needsApply("dark", 100, 200, window) shouldBe true
        gate.needsApply("light", 101, 200, window) shouldBe true
        gate.needsApply("light", 100, 201, window) shouldBe true
        gate.needsApply("light", 100, 200, window) shouldBe false
    }
    test("equal window values with distinct identities still require native reattachment") {
        data class Token(val value: Int)
        val first = Token(1); val second = Token(1); val gate = MaterialApplyGate<String>()
        gate.applied("light", 100, 200, first)
        gate.needsApply("light", 100, 200, second) shouldBe true
    }
    test("detach invalidation forces reapply even for the same window size and palette") {
        val gate = MaterialApplyGate<String>(); val window = Any()
        gate.applied("light", 100, 200, window); gate.invalidate()
        gate.needsApply("light", 100, 200, window) shouldBe true
    }
    test("an unsuccessful attempt never poisons the success cache") {
        val gate = MaterialApplyGate<String>(); val window = Any()
        repeat(3) { gate.needsApply("light", 100, 200, window) shouldBe true }
        gate.applied("light", 100, 200, window)
        gate.needsApply("light", 100, 200, window) shouldBe false
    }
})
