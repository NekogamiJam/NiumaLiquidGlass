// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.glass

import android.util.AtomicFile
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Only rendering metadata is accepted. Coalesced, serialized atomic publication OFF the UI thread. */
object GlassDiagnosticWriter {
    private data class Pending(val target: File, val json: String)
    private val pending = AtomicReference<Pending?>(null)
    private val scheduled = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "RimeGlassDiagnostic").apply { isDaemon = true }
    }

    fun publish(directory: File, renderingJson: String) {
        pending.set(Pending(File(directory, "niuma-glass-state.json"), renderingJson))
        schedule()
    }

    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        worker.execute {
            try {
                while (true) {
                    val item = pending.getAndSet(null) ?: break
                    val file = AtomicFile(item.target)
                    var stream: FileOutputStream? = null
                    try {
                        stream = file.startWrite()
                        stream.write(item.json.toByteArray(Charsets.UTF_8))
                        file.finishWrite(stream)
                        stream = null
                    } catch (_: Exception) {
                        file.failWrite(stream)
                        // No payload, editor content, or exception text is logged.
                        Log.w("RimeGlassDiagnostic", "Rendering diagnostic publication failed")
                    }
                }
            } finally {
                scheduled.set(false)
                if (pending.get() != null) schedule()
            }
        }
    }
}
