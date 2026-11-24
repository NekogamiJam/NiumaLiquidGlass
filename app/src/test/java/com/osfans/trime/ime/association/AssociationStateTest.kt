// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.association

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class AssociationStateTest : FunSpec({
    fun state() = AssociationState().apply { installIndex(mapOf("中" to listOf("国", "华", "国"), "中国" to listOf("人民", "文化"), "国" to listOf("家"))) }
    test("suggestions are committed-text continuations, longest text suffix wins") {
        val s = state(); s.onCommitted("中"); s.suggestions shouldBe listOf("国", "华")
        s.onCommitted("国"); s.suggestions shouldBe listOf("人民", "文化")
    }
    test("beginning even an invalid/sparse code immediately hides suggestions") {
        val s = state(); s.onCommitted("中"); s.onInputStarted(); s.suggestions shouldBe emptyList()
    }
    test("selecting a continuation never changes the static index order") {
        val s = state(); repeat(50) { s.reset(); s.onCommitted("中"); s.suggestions shouldBe listOf("国", "华"); s.onCommitted("华") }
        s.reset(); s.onCommitted("中"); s.suggestions shouldBe listOf("国", "华")
    }
    test("punctuation and non-Han commits discard text context") {
        val s = state(); s.onCommitted("中"); s.onCommitted("!"); s.suggestions shouldBe emptyList()
        s.onCommitted("国"); s.suggestions shouldBe listOf("家")
        s.onCommitted("abc"); s.suggestions shouldBe emptyList()
    }
    test("session/cursor/security reset clears context and all visible suggestions") {
        val s = state(); s.onCommitted("中"); s.reset(); s.suggestions shouldBe emptyList()
        s.onCommitted("国"); s.suggestions shouldBe listOf("家")
    }
    test("non-Han/generated bad entries cannot enter suggestion strip") {
        val s = AssociationState(2); s.installIndex(mapOf("中" to listOf("a", "国", "国", "华", "文")))
        s.onCommitted("中"); s.suggestions shouldBe listOf("国", "华")
    }
})
