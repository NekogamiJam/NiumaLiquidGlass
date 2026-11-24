// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.gesture

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.io.File

class NiumaGestureRulesTest : FunSpec({
    val smallIndex = NiumaCodeIndex.fromValidated(
        codes = listOf("a", "ab", "abc", "l", "la", "lb", "z", "za", "zc", "zcb", "zz", "zzb"),
        zPrefixes = setOf("z"),
    )

    test("tap-L never selects; swipe-L selects global second when present") {
        NiumaGestureRules.globalSelectionIndex('l', GestureKind.TAP, "la", "niuma_glass", false, 0, 4, smallIndex) shouldBe null
        NiumaGestureRules.globalSelectionIndex('l', GestureKind.SWIPE_UP, "la", "niuma_glass", false, 0, 4, smallIndex) shouldBe 1
    }

    test("swipe-Z selects global third regardless of z extension path") {
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.SWIPE_UP, "zz", "niuma_glass", false, 0, 4, smallIndex) shouldBe 2
    }

    test("tap-Z selects global third only when appending z is not a valid code path") {
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.TAP, "z", "niuma_glass", false, 0, 4, smallIndex) shouldBe null
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.TAP, "zc", "niuma_glass", false, 0, 4, smallIndex) shouldBe 2
    }

    test("invalid raw, unavailable index, wrong schema, ASCII and modifiers preserve normal behavior") {
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.SWIPE_UP, "q", "niuma_glass", false, 0, 4, smallIndex) shouldBe null
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.SWIPE_UP, "z", "niuma_glass", false, 0, 4, null) shouldBe null
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.SWIPE_UP, "z", "other", false, 0, 4, smallIndex) shouldBe null
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.SWIPE_UP, "z", "niuma_glass", true, 0, 4, smallIndex) shouldBe null
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.SWIPE_UP, "z", "niuma_glass", false, 1, 4, smallIndex) shouldBe null
    }

    test("candidate counts gate global indices without page-relative assumptions") {
        NiumaGestureRules.globalSelectionIndex('l', GestureKind.SWIPE_UP, "la", "niuma_glass", false, 0, 1, smallIndex) shouldBe null
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.TAP, "zc", "niuma_glass", false, 0, 2, smallIndex) shouldBe null
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.TAP, "zc", "niuma_glass", false, 0, 3, smallIndex) shouldBe 2
    }

    test("four-letter tap-Z selects the global third like Z-swipe (2026-11-18 user change)") {
        val index = NiumaCodeIndex.fromValidated(listOf("abcd", "zjia", "zjian"), emptySet())
        // No z-extension exists for these codes, so tap-Z is a third-candidate
        // selection in both zc-style (abcd) and fh-style (zjia) four-letter codes.
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.TAP, "abcd", "niuma_glass", false, 0, 3, index) shouldBe 2
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.SWIPE_UP, "abcd", "niuma_glass", false, 0, 3, index) shouldBe 2
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.TAP, "zjia", "niuma_glass", false, 0, 3, index) shouldBe 2
        // Without a third candidate the tap-Z keeps the old input-letter fallback.
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.TAP, "abcd", "niuma_glass", false, 0, 2, index) shouldBe null
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.TAP, "abcd", "niuma_glass", false, 0, 1, index) shouldBe null
    }

    test("index validation rejects empty, unsorted, duplicate, malformed codes and inaccurate z paths") {
        shouldThrow<IllegalArgumentException> { NiumaCodeIndex.fromValidated(emptyList(), emptySet()) }
        shouldThrow<IllegalArgumentException> { NiumaCodeIndex.fromValidated(listOf("b", "a"), emptySet()) }
        shouldThrow<IllegalArgumentException> { NiumaCodeIndex.fromValidated(listOf("a", "a"), emptySet()) }
        shouldThrow<IllegalArgumentException> { NiumaCodeIndex.fromValidated(listOf("A"), emptySet()) }
        shouldThrow<IllegalArgumentException> { NiumaCodeIndex.fromValidated(listOf("abcdef"), emptySet()) }
        shouldThrow<IllegalArgumentException> { NiumaCodeIndex.fromValidated(listOf("azz"), emptySet()) }
    }

    test("pinned complete code paths have 170277 entries and cover all 26 noninitial-z positions") {
        val codeFile = File("../../gesture-tools/generated/niuma_code_paths.txt")
        codeFile.isFile shouldBe true
        val codes = codeFile.readLines(Charsets.US_ASCII)
        codes.size shouldBe 170277
        codes.zipWithNext().all { (left, right) -> left < right } shouldBe true
        codes.all { it.length in 1..5 && it.all { ch -> ch in 'a'..'z' } } shouldBe true

        val expectedPrefixes = buildSet {
            for (code in codes) {
                for (index in 1 until code.length) {
                    if (code[index] == 'z') add(code.substring(0, index))
                }
            }
        }
        expectedPrefixes.size shouldBe 11
        val zPaths = codes.sumOf { code -> (1 until code.length).count { code[it] == 'z' } }
        zPaths shouldBe 26

        val jsonFile = File("../../gesture-tools/generated/niuma_z_transitions.json")
        jsonFile.isFile shouldBe true
        val json = jsonFile.readText()
        val array = Regex("\"extend_z_prefixes\"\\s*:\\s*\\[([^]]*)]").find(json)?.groupValues?.get(1)
        requireNotNull(array)
        val assetPrefixes = Regex("\"([a-z]+)\"").findAll(array).map { it.groupValues[1] }.toSet()
        assetPrefixes shouldBe expectedPrefixes

        val fullIndex = NiumaCodeIndex.fromValidated(codes, assetPrefixes)
        // Exercise production binary prefix search for every source path, not only a model.
        for (code in codes) {
            for (length in 1..code.length) {
                val prefix = code.take(length)
                fullIndex.isValidPrefix(prefix) shouldBe true
                if (length < code.length && code[length] == 'z') {
                    NiumaGestureRules.globalSelectionIndex('z', GestureKind.TAP, prefix, "niuma_glass", false, 0, 3, fullIndex) shouldBe null
                }
            }
        }
        fullIndex.isValidPrefix("zc") shouldBe true
        fullIndex.zCanExtend("zc") shouldBe true
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.TAP, "zc", "niuma_glass", false, 0, 4, fullIndex) shouldBe null
        fullIndex.zCanExtend("z") shouldBe true
        // 2026-11-18 user change: a four-letter code whose z-extension is not a
        // source-code path now selects the global third on Z-tap; without a
        // third candidate the tap keeps the input-letter fallback.
        fullIndex.zCanExtend("aaaa") shouldBe false
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.TAP, "aaaa", "niuma_glass", false, 0, 3, fullIndex) shouldBe 2
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.TAP, "aaaa", "niuma_glass", false, 0, 2, fullIndex) shouldBe null
        NiumaGestureRules.globalSelectionIndex('z', GestureKind.TAP, "aaaa", "niuma_glass", false, 0, 1, fullIndex) shouldBe null
    }
})
