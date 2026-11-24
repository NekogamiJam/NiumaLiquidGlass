/*
 * Chime-SMS-2M inference port, adapted for LocalType/Rime.
 * Copyright 2026 Luke Steuber. Original reference code: Apache-2.0. Weights: CC-BY-SA-4.0.
 * This adapter is distributed as part of the GPL-licensed project; the upstream
 * code retains Apache-2.0; model weights retain CC-BY-SA-4.0. No Android APIs are used here.
 */
package com.osfans.trime.ime.english

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.exp
import kotlin.math.tanh

/** Stateless CPU-only Chime CIFG model and the documented ASCII-English text adapter. */
class ChimeModel private constructor(
    private val tokens: Array<String>,
    private val embeddingQ: ByteArray,
    private val embeddingScale: FloatArray,
    private val wxQ: ByteArray,
    private val wxScale: FloatArray,
    private val wrQ: ByteArray,
    private val wrScale: FloatArray,
    private val projQ: ByteArray,
    private val projScale: FloatArray,
    private val bias: FloatArray,
) {
    private val tokenIds = tokens.withIndex().associate { it.value to it.index }
    private val offerIds = tokens.indices.filter { isOfferable(tokens[it]) }

    /** Return the k highest-ranked complete vocabulary words matching the active prefix. */
    fun predict(text: String, k: Int = 6): List<String> {
        require(k in 1..64) { "k must be in 1..64" }
        val (logits, prefix) = logitsAndPrefix(text)
        return offerIds.asSequence()
            .filter { tokens[it].startsWith(prefix) }
            .sortedWith(compareByDescending<Int> { logits[it] }.thenBy { it })
            .take(k)
            .map { tokens[it] }
            .toList()
    }

    /** Numerical hook for reference-fixture tests; values are ordered by vocabulary id. */
    fun logitsForTest(text: String): FloatArray = logitsAndPrefix(text).first

    /** Normalized prefix used for ranking, exposed only to keep reference tests precise. */
    fun prefixForTest(text: String): String = context(text).second

    private fun logitsAndPrefix(text: String): Pair<FloatArray, String> {
        val (ids, prefix) = context(text)
        val hSize = H
        val eSize = E
        var cell = FloatArray(hSize)
        var recurrent = FloatArray(eSize)
        for (id in ids) {
            val embOffset = id * eSize
            val g = bias.copyOf()
            for (e in 0 until eSize) {
                val x = embeddingQ[embOffset + e].toFloat() * embeddingScale[id]
                val wxBase = e * (3 * hSize)
                for (j in 0 until 3 * hSize) g[j] += x * wxQ[wxBase + j].toFloat() * wxScale[e]
                val r = recurrent[e]
                for (j in 0 until 3 * hSize) g[j] += r * wrQ[wxBase + j].toFloat() * wrScale[e]
            }
            val nextCell = FloatArray(hSize)
            for (j in 0 until hSize) {
                val f = sigmoid(g[j])
                val u = tanh(g[2 * hSize + j])
                nextCell[j] = f * cell[j] + (1f - f) * u
            }
            val activated = FloatArray(hSize)
            for (j in 0 until hSize) activated[j] = sigmoid(g[hSize + j]) * tanh(nextCell[j])
            val nextRecurrent = FloatArray(eSize)
            for (j in 0 until hSize) {
                val x = activated[j]
                val base = j * eSize
                for (e in 0 until eSize) nextRecurrent[e] += x * projQ[base + e].toFloat() * projScale[j]
            }
            cell = nextCell
            recurrent = nextRecurrent
        }
        val logits = FloatArray(tokens.size)
        for (id in tokens.indices) {
            val base = id * eSize
            var sum = 0f
            for (e in 0 until eSize) sum += embeddingQ[base + e].toFloat() * embeddingScale[id] * recurrent[e]
            logits[id] = sum
        }
        return logits to prefix
    }

    /** Build BOS + current-sentence tokens, applying the reference 63-token/32-hop window. */
    private fun context(text: String): Pair<IntArray, String> {
        val prefix = activePrefix(text)
        val before = if (prefix.isEmpty()) text else text.dropLast(prefix.length)
        val sentence = before.substringAfterLast('\n')
        val all = tokenize(sentence).toMutableList()
        val lastEnd = all.indexOfLast { it == "." || it == "!" || it == "?" || it == "…" }
        if (lastEnd >= 0) all.subList(0, lastEnd + 1).clear()
        val start = if (all.size <= CONTEXT_LIMIT) 0 else ((all.size - CONTEXT_LIMIT + HOP - 1) / HOP) * HOP
        val ids = IntArray(1 + all.size - start)
        ids[0] = BOS_ID
        for (i in start until all.size) ids[i - start + 1] = tokenIds[all[i]] ?: UNK_ID
        return ids to normalize(prefix)
    }

    private fun activePrefix(text: String): String {
        var i = text.length
        while (i > 0) {
            val c = text[i - 1]
            if (isAsciiLetter(c) || c == '\'' || c == '’' || c == '-') i-- else break
        }
        val run = text.substring(i)
        val firstLetter = run.indexOfFirst { isAsciiLetter(it) }
        return if (firstLetter < 0) "" else run.substring(firstLetter)
    }

    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                isAsciiLetter(c) -> {
                    val start = i++
                    while (i < text.length && (isAsciiLetter(text[i]) || text[i] == '\'' || text[i] == '’' || text[i] == '-')) i++
                    out += normalize(text.substring(start, i))
                }
                c in '0'..'9' -> {
                    i++
                    while (i < text.length) {
                        if (text[i] in '0'..'9') i++
                        else if (text[i] in ".,:/" && i + 1 < text.length && text[i + 1] in '0'..'9') i++
                        else break
                    }
                    out += NUM
                }
                else -> {
                    val canonical = canonicalPunctuation(c)
                    emitPunctuation(out, canonical)
                    i++
                }
            }
        }
        return out
    }

    private fun emitPunctuation(out: MutableList<String>, value: String) {
        if (value == ".") {
            when (out.lastOrNull()) {
                "." -> { out[out.lastIndex] = "…"; return }
                "…" -> return
            }
        } else if (out.lastOrNull() == value && value in PUNCTUATION) return
        out += if (value in PUNCTUATION) value else SYM
    }

    companion object {
        private const val V = 16384
        private const val E = 96
        private const val H = 670
        private const val BOS_ID = 1
        private const val UNK_ID = 3
        private const val CONTEXT_LIMIT = 63
        private const val HOP = 32
        private const val NUM = "<num>"
        private const val SYM = "<sym>"
        private val PUNCTUATION = setOf(".", ",", "?", "!", "…", "'", "\"", "-", ":", ";", "(", ")", "/", "&", "$", "%", "*", "+", "=", "@", "#", "~", "_", "[", "]", "<", ">", "^", "\\", "|", "°")
        private val SPECIALS = listOf("<pad>", "<s>", "</s>", "<unk>", "<num>", "<sym>")
        private val CANON = mapOf(
            '’' to "'", '‘' to "'", '‚' to "'", '`' to "'", '´' to "'",
            '“' to "\"", '”' to "\"", '„' to "\"", '«' to "\"", '»' to "\"",
            '–' to "-", '—' to "-", '‐' to "-", '‑' to "-", '−' to "-", '﹣' to "-", '－' to "-",
            '。' to ".", '．' to ".", '，' to ",", '、' to ",", '：' to ":", '；' to ";",
            '？' to "?", '！' to "!", '（' to "(", '）' to ")", '¿' to "?", '¡' to "!", '‥' to "…",
        )

        /** Read and validate a version-1 little-endian LTCF model from a stream. */
        @JvmStatic
        fun load(input: InputStream): ChimeModel {
            val bytes = input.use { it.readBytes() }
            require(bytes.size >= HEADER_SIZE + 4) { "truncated model" }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            require(bytes[0] == 'L'.code.toByte() && bytes[1] == 'T'.code.toByte() && bytes[2] == 'C'.code.toByte() && bytes[3] == 'F'.code.toByte()) { "not an LTCF file" }
            buffer.position(4)
            val version = buffer.int
            val v = buffer.int
            val e = buffer.int
            val h = buffer.int
            val vocabBytes = buffer.int
            require(version == 1) { "unsupported LTCF version: $version" }
            require(v == V && e == E && h == H) { "unexpected model shape: V=$v E=$e H=$h" }
            require(vocabBytes > 0 && vocabBytes <= bytes.size - HEADER_SIZE - 4) { "invalid vocabulary length" }
            val crcOffset = bytes.size - 4
            val expectedCrc = ByteBuffer.wrap(bytes, crcOffset, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xffffffffL
            val crc = CRC32().apply { update(bytes, 0, crcOffset) }.value
            require(crc == expectedCrc) { "CRC mismatch" }
            val cursor = Cursor(buffer, crcOffset)
            val (embScale, embQ) = cursor.matrix(V, E)
            val (wxScale, wxQ) = cursor.matrix(E, 3 * H)
            val (wrScale, wrQ) = cursor.matrix(E, 3 * H)
            val (projScale, projQ) = cursor.matrix(H, E)
            val bias = cursor.floats(3 * H)
            require(cursor.position + vocabBytes == crcOffset) { "vocabulary section does not match header" }
            val vocab = String(bytes, cursor.position, vocabBytes, Charsets.UTF_8)
            require(vocab.endsWith('\n')) { "vocabulary must end with newline" }
            val tokens = vocab.dropLast(1).split('\n').toTypedArray()
            require(tokens.size == V && tokens.take(SPECIALS.size) == SPECIALS && tokens.toSet().size == V) { "invalid vocabulary section" }
            return ChimeModel(tokens, embQ, embScale, wxQ, wxScale, wrQ, wrScale, projQ, projScale, bias)
        }

        private const val HEADER_SIZE = 24
        private fun normalize(s: String) = s.replace('’', '\'').lowercase()
        private fun isAsciiLetter(c: Char) = c in 'a'..'z' || c in 'A'..'Z'
        private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()
        private fun isOfferable(s: String): Boolean = s.isNotEmpty() && s[0] in 'a'..'z' &&
            s.all { it in 'a'..'z' || it == '\'' || it == '’' || it == '-' } && (s.length >= 2 || s == "a" || s == "i")

        private fun canonicalPunctuation(c: Char): String = CANON[c] ?: c.toString()
    }

    private class Cursor(private val buffer: ByteBuffer, private val end: Int) {
        val position: Int get() = buffer.position()
        fun matrix(rows: Int, cols: Int): Pair<FloatArray, ByteArray> {
            val count = rows.toLong() * cols
            require(count <= Int.MAX_VALUE) { "matrix too large" }
            val scales = floats(rows)
            require(scales.all { it.isFinite() && it >= 0f }) { "invalid matrix scale" }
            val q = ByteArray(count.toInt())
            require(buffer.position() + q.size.toLong() + padding(q.size) <= end) { "truncated matrix" }
            buffer.get(q)
            buffer.position(buffer.position() + padding(q.size))
            return scales to q
        }
        fun floats(count: Int): FloatArray {
            require(count >= 0 && buffer.position() + count.toLong() * 4 <= end) { "truncated float section" }
            return FloatArray(count) { buffer.float.also { v -> require(v.isFinite()) } }
        }
        private fun padding(size: Int) = (4 - size % 4) % 4
    }
}
