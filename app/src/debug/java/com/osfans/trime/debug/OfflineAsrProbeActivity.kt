package com.osfans.trime.debug

import android.app.Activity
import android.os.Bundle
import android.util.AtomicFile
import com.osfans.trime.ime.voice.LocalAsrNative
import com.osfans.trime.ime.voice.OfflineAsrModels
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Debug/DUMP-only fixed-public-file JNI smoke. NEVER opens a microphone/editor. */
class OfflineAsrProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread({
            val report = JSONObject().put("microphone_opened", false).put("editor_accessed", false)
            var handle = 0L
            try {
                val paths = OfflineAsrModels.prepare(applicationContext)
                val start = System.nanoTime()
                handle = LocalAsrNative.create(paths.model, paths.tokens, paths.vad)
                report.put("model_loaded", handle != 0L).put("load_ms", (System.nanoTime() - start) / 1e6)
                val cases = JSONArray()
                for (name in listOf("silence", "zh", "silence")) {
                    LocalAsrNative.reset(handle)
                    val bytes = assets.open("offline-asr-fixtures/$name.f32").use { it.readBytes() }
                    val floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    val results = JSONArray()
                    val t = System.nanoTime()
                    while (floats.hasRemaining()) {
                        val frame = FloatArray(512)
                        floats.get(frame, 0, minOf(512, floats.remaining()))
                        LocalAsrNative.accept(handle, frame).forEach { results.put(it) }
                        frame.fill(0f)
                    }
                    LocalAsrNative.finish(handle).forEach { results.put(it) }
                    cases.put(JSONObject().put("sample", name).put("segments", results).put("seconds", (System.nanoTime() - t) / 1e9))
                }
                report.put("cases", cases)
                var badLength = false
                try { LocalAsrNative.accept(handle, FloatArray(1)) } catch (_: IllegalStateException) { badLength = true }
                var badFloat = false
                try { LocalAsrNative.accept(handle, FloatArray(512) { Float.NaN }) } catch (_: IllegalStateException) { badFloat = true }
                LocalAsrNative.reset(handle)
                report.put("bad_frame_refused", badLength).put("nonfinite_refused", badFloat)
                LocalAsrNative.destroy(handle)
                val stale = handle
                handle = 0L
                var staleRefused = false
                try { LocalAsrNative.finish(stale) } catch (_: IllegalStateException) { staleRefused = true }
                report.put("stale_handle_refused", staleRefused).put("status", "completed")
            } catch (t: Throwable) {
                report.put("status", "failed").put("error_type", t.javaClass.simpleName)
            } finally {
                if (handle != 0L) try { LocalAsrNative.destroy(handle) } catch (_: Throwable) { }
                val file = AtomicFile(File(filesDir, "offline-asr-probe.json"))
                val stream = file.startWrite()
                try { stream.write(report.toString(2).toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
                catch (t: Throwable) { file.failWrite(stream) }
                runOnUiThread { finish() }
            }
        }, "offline-asr-public-file-check").start()
    }
}
