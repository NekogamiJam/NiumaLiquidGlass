/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import org.json.JSONObject

/** Signed APK assets -> app-private files; worker thread only, no downloads or external paths. */
internal object OfflineAsrModels {
    data class Paths(val model: String, val tokens: String, val vad: String)
    private val names = setOf("sensevoice.int8.onnx", "tokens.txt", "silero_vad.onnx")
    fun prepare(context: Context): Paths {
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        val dir = File(context.noBackupFilesDir, "offline-asr")
        check(dir.isDirectory || dir.mkdirs())
        return java.io.RandomAccessFile(File(dir, ".prepare.lock"), "rw").use { file ->
            file.channel.lock().use { prepareLocked(context) }
        }
    }
    private fun prepareLocked(context: Context): Paths {
        val manifest = context.assets.open("offline-asr/models.json").bufferedReader().use { JSONObject(it.readText()) }
        require(manifest.getInt("format") == 1 && manifest.getString("model_id") == "sensevoice-2024-07-17-int8")
        val files = manifest.getJSONArray("files")
        require(files.length() == names.size)
        val dir = File(context.noBackupFilesDir, "offline-asr")
        check(dir.isDirectory || dir.mkdirs())
        val seen = mutableSetOf<String>()
        for (i in 0 until files.length()) {
            val entry = files.getJSONObject(i)
            val name = entry.getString("filename")
            val length = entry.getLong("bytes")
            val digest = entry.getString("sha256")
            require(name in names && seen.add(name) && length in 1L..300_000_000L && digest.matches(Regex("[0-9a-f]{64}")))
            val target = File(dir, name)
            require(target.canonicalFile.parentFile == dir.canonicalFile)
            if (target.isFile && target.length() == length && hash(target) == digest) continue
            val atomic = AtomicFile(target)
            val output = atomic.startWrite()
            try {
                val md = MessageDigest.getInstance("SHA-256")
                var copied = 0L
                context.assets.open("offline-asr/$name").use { input ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        copied += n
                        check(copied <= length)
                        md.update(buffer, 0, n)
                        output.write(buffer, 0, n)
                    }
                }
                check(copied == length && hex(md.digest()) == digest)
                atomic.finishWrite(output)
            } catch (t: Throwable) {
                atomic.failWrite(output)
                throw t
            }
        }
        require(seen == names)
        return Paths(File(dir, "sensevoice.int8.onnx").absolutePath, File(dir, "tokens.txt").absolutePath, File(dir, "silero_vad.onnx").absolutePath)
    }
    private fun hash(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return hex(md.digest())
    }
    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
}
