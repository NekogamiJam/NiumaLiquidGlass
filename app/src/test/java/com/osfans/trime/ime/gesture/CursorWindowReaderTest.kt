package com.osfans.trime.ime.gesture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

private class FakeCursorSource(
    var modern: CursorWindow? = null, var left: String? = "ab", var right: String? = "cd", var selection: String? = null,
    var unsupported: Boolean = false,
) : CursorWindowSource {
    var legacyReads = 0
    override fun surrounding(before: Int, after: Int): CursorWindow? {
        if (unsupported) throw AbstractMethodError("unsupported")
        return modern
    }
    override fun before(length: Int): String? { legacyReads++; return left }
    override fun after(length: Int): String? { legacyReads++; return right }
    override fun selected(): String? { legacyReads++; return selection }
}
class CursorWindowReaderTest : FunSpec({
    test("supported modern window remains preferred") {
        val s=FakeCursorSource(CursorWindow("abcd",2,2,48))
        CursorWindowReader.read(s,2,2,50,50) shouldBe s.modern
        s.legacyReads shouldBe 0
    }
    test("null modern API falls back to exact bounded known selection") {
        val s=FakeCursorSource()
        CursorWindowReader.read(s,2,2,50,50) shouldBe CursorWindow("abcd",2,2,48)
        s.legacyReads shouldBe 3
    }
    test("unsupported modern method and negative offset can use legacy reads") {
        val a=FakeCursorSource(unsupported=true)
        CursorWindowReader.read(a,2,2,50,50) shouldBe CursorWindow("abcd",2,2,48)
        val b=FakeCursorSource(CursorWindow("abcd",2,2,-1))
        CursorWindowReader.read(b,2,2,50,50) shouldBe CursorWindow("abcd",2,2,48)
    }
    test("contradictory modern absolute offset cannot be bypassed") {
        val s=FakeCursorSource(CursorWindow("abcd",2,2,47))
        CursorWindowReader.read(s,2,2,50,50) shouldBe null
        s.legacyReads shouldBe 0
    }
    test("unknown selected and invalid selection are rejected") {
        for ((a,b) in listOf(-1 to -1, 2 to 3)) {
            val s=FakeCursorSource()
            CursorWindowReader.read(s,2,2,a,b) shouldBe null
            s.legacyReads shouldBe 0
        }
        CursorWindowReader.read(FakeCursorSource(selection="x"),2,2,50,50) shouldBe null
    }
    test("null short oversized or failed legacy data cannot cause edits") {
        for (s in listOf(FakeCursorSource(left=null),FakeCursorSource(right=null),FakeCursorSource(left="a"),
            FakeCursorSource(left="abc"),FakeCursorSource(right="cde"))) {
            CursorWindowReader.read(s,2,2,50,50) shouldBe null
        }
    }
    test("surrogate cut is refused and intact emoji before cursor is allowed") {
        CursorWindowReader.read(FakeCursorSource(left="a\uD83D",right="\uDE00b"),2,2,50,50) shouldBe null
        CursorWindowReader.read(FakeCursorSource(left="😀",right="bc"),2,2,50,50) shouldBe CursorWindow("😀bc",2,2,48)
    }
    test("clear all before uses absolute callback position not tiny window length") {
        val w=CursorWindowReader.read(FakeCursorSource(),2,2,5000,5000)!!
        ClearBeforeCursorPolicy.deleteUtf16Before(BeforeCursorSnapshot(w.text,w.start,w.end,w.offset),5000,5000) shouldBe 5000
    }
    test("English suffix remains available with legacy-only editor") {
        val w=CursorWindowReader.read(FakeCursorSource(left="hell",right=""),4,2,4,4)!!
        w.text.take(w.start).endsWith("hell") shouldBe true
        ("hello".substring("hell".length)+" ") shouldBe "o "
        CursorWindowReader.read(FakeCursorSource(left=" ",right=""),1,2,6,6) shouldBe CursorWindow(" ",1,1,5)
    }
    test("document start and empty after text are valid but selection movement changes snapshots") {
        val s=FakeCursorSource(left="",right="ab")
        CursorWindowReader.read(s,2,2,0,0) shouldBe CursorWindow("ab",0,0,0)
        val a=CursorWindowReader.read(FakeCursorSource(),2,2,50,50)
        val b=CursorWindowReader.read(FakeCursorSource(),2,2,51,51)
        (a==b) shouldBe false
    }
    test("request sizes and malformed modern snapshots fail closed") {
        for (n in listOf(0,41,Int.MAX_VALUE)) CursorWindowReader.read(FakeCursorSource(),n,2,50,50) shouldBe null
        CursorWindowReader.read(FakeCursorSource(CursorWindow("abcd",1,2,-1)),2,2,50,50) shouldBe null
        CursorWindowReader.read(FakeCursorSource(CursorWindow("",0,0,50)),2,2,50,50) shouldBe null
    }
})
