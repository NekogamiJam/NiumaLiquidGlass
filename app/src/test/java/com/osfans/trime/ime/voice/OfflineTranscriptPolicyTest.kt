package com.osfans.trime.ime.voice

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow

class OfflineTranscriptPolicyTest : FunSpec({
    test("blank and controls cannot invent a transcript") {
        OfflineTranscriptPolicy.append("", " \u0000\t\r ") shouldBe ""
    }
    test("Chinese segments join without an English space") {
        OfflineTranscriptPolicy.append("今天", "你好。") shouldBe "今天你好。"
    }
    test("ASCII words receive a single separating space") {
        OfflineTranscriptPolicy.append("hello", "world") shouldBe "hello world"
        OfflineTranscriptPolicy.append("hello.", "World") shouldBe "hello. World"
    }
    test("punctuation is not detached by a synthetic space") {
        OfflineTranscriptPolicy.append("hello", ", world") shouldBe "hello, world"
    }
    test("supplementary text is retained") {
        OfflineTranscriptPolicy.append("你好", "😀") shouldBe "你好😀"
    }
    test("a bounded preview refuses rather than truncates silently") {
        OfflineTranscriptPolicy.append("a".repeat(4095), "!").length shouldBe 4096
        shouldThrow<IllegalArgumentException> { OfflineTranscriptPolicy.append("a".repeat(4096), "!") }
    }
})
