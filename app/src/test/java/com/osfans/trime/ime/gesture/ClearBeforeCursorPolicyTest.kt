package com.osfans.trime.ime.gesture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class ClearBeforeCursorPolicyTest : FunSpec({
    test("clear the whole prefix in UTF16 while preserving after") {
        ClearBeforeCursorPolicy.deleteUtf16Before(BeforeCursorSnapshot("abcd",2,2,0),2,2) shouldBe 2
    }
    test("supplementary character is removed completely not half a surrogate") {
        ClearBeforeCursorPolicy.deleteUtf16Before(BeforeCursorSnapshot("😀bc",2,2,1),3,3) shouldBe 3
    }
    test("long prefix requires only a tiny window and has no 4096 truncation") {
        ClearBeforeCursorPolicy.deleteUtf16Before(BeforeCursorSnapshot("aaTA",2,2,4998),5000,5000) shouldBe 5000
    }
    test("empty prefix is a no op") {
        ClearBeforeCursorPolicy.deleteUtf16Before(BeforeCursorSnapshot("ab",0,0,0),0,0) shouldBe null
    }
    test("selection and stale positions are refused") {
        ClearBeforeCursorPolicy.deleteUtf16Before(BeforeCursorSnapshot("abcd",1,3,0),1,3) shouldBe null
        ClearBeforeCursorPolicy.deleteUtf16Before(BeforeCursorSnapshot("abcd",2,2,0),3,3) shouldBe null
    }
    test("unknown offset null or impossible index cannot mutate") {
        for(s in listOf(BeforeCursorSnapshot(null,0,0,0), BeforeCursorSnapshot("a",1,1,-1), BeforeCursorSnapshot("a",2,2,0))) {
            ClearBeforeCursorPolicy.deleteUtf16Before(s,1,1) shouldBe null
        }
    }
    test("cursor inside surrogate pair is refused") {
        ClearBeforeCursorPolicy.deleteUtf16Before(BeforeCursorSnapshot("😀",1,1,0),1,1) shouldBe null
    }
    test("offset arithmetic cannot overflow") {
        ClearBeforeCursorPolicy.deleteUtf16Before(BeforeCursorSnapshot("ab",2,2,Int.MAX_VALUE),1,1) shouldBe null
    }
    test("multiline prefix keeps after length zero") {
        ClearBeforeCursorPolicy.deleteUtf16Before(BeforeCursorSnapshot("\n二三",2,2,1),3,3) shouldBe 3
    }
})
