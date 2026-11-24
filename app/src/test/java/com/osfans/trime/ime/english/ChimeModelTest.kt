package com.osfans.trime.ime.english

import io.kotest.core.spec.style.FunSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.math.abs

private fun chimeFixtureRoot(): File {
    val cwd = File(System.getProperty("user.dir"))
    return listOf(File(cwd, "../chime-check"), File(cwd, "../../chime-check"), File(cwd, "chime-check"))
        .firstOrNull { File(it, "upstream/cifg_int8.bin").isFile && File(it, "reference-fixtures.json").isFile }
        ?: error("Could not locate chime-check fixtures from ${cwd.absolutePath}")
}

class ChimeModelTest : FunSpec({
    test("matches Python reference top six, prefixes and selected logits for all thirteen fixtures") {
        val root = chimeFixtureRoot()
        val model = ChimeModel.load(File(root, "upstream/cifg_int8.bin").inputStream())
        val fixture = Json.parseToJsonElement(File(root, "reference-fixtures.json").readText()).jsonObject
        val cases = fixture.getValue("cases").jsonArray
        assertEquals(13, cases.size)
        for (case in cases) {
            val obj = case.jsonObject
            val text = obj.getValue("text").jsonPrimitive.content
            val prefix = obj.getValue("prefix").jsonPrimitive.content
            val expectedWords = obj.getValue("top6").jsonArray.map { it.jsonPrimitive.content }
            assertEquals("prefix for ${text.take(40)}", prefix, model.prefixForTest(text))
            assertEquals("top6 for ${text.take(40)}", expectedWords, model.predict(text, 6))
            val ids = obj.getValue("logitIds").jsonArray.map { it.jsonPrimitive.int }
            val logits = obj.getValue("logits").jsonArray.map { it.jsonPrimitive.float }
            val actual = model.logitsForTest(text)
            for (i in ids.indices) {
                assertTrue("logit mismatch for '$text' token id ${ids[i]}", abs(actual[ids[i]] - logits[i]) <= 0.002f)
            }
        }
    }
    test("rejects bad version and corrupted payload CRC") {
        val original = File(chimeFixtureRoot(), "upstream/cifg_int8.bin").readBytes()
        val badVersion = original.copyOf().also { it[4] = 2 }
        assertThrows(IllegalArgumentException::class.java) { ChimeModel.load(ByteArrayInputStream(badVersion)) }
        val badCrc = original.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        val error = assertThrows(IllegalArgumentException::class.java) { ChimeModel.load(ByteArrayInputStream(badCrc)) }
        assertEquals("CRC mismatch", error.message)
    }
})
