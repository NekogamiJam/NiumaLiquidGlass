/* SPDX-License-Identifier: GPL-3.0-or-later */
package com.osfans.trime.ime.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Explicit, RAM-only microphone sessions. CPU inference is serialized and never runs on the UI. */
internal class OfflineVoiceInput(context: Context, private val callback: VoiceInputCallback) {
    private val app = context.applicationContext
    private var active: Session? = null
    private var destroyed = false
    private class Session {
        val gate = Any()
        val cancelled = AtomicBoolean(false)
        val stopping = AtomicBoolean(false)
        val error = AtomicInteger(0)
        val recorder = AtomicReference<AudioRecord?>(null)
        val queue = ArrayBlockingQueue<FloatArray>(64) // At most 2.048 s queued PCM.
        val producerDone = CountDownLatch(1)
    }
    fun start(): VoiceStartResult {
        checkMain()
        if (destroyed) return VoiceStartResult(false, "Controller destroyed")
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return VoiceStartResult(false, "Microphone permission required")
        cancelSession(false)
        val session = Session()
        active = session
        callback.onEvent(VoiceEvent(VoiceState.LOADING))
        worker.execute { recognize(session) }
        return VoiceStartResult(true, "Local recognition queued")
    }
    fun stop() {
        checkMain()
        val session = active ?: return
        synchronized(session.gate) {
            if (session.cancelled.get() || !session.stopping.compareAndSet(false, true)) return
            stopRecorder(session)
        }
        callback.onEvent(VoiceEvent(VoiceState.STOPPING))
    }
    fun cancel() { checkMain(); cancelSession(true) }
    fun destroy() {
        checkMain()
        if (destroyed) return
        destroyed = true
        cancelSession(false)
    }
    private fun cancelSession(notify: Boolean) {
        val session = active ?: return
        active = null
        synchronized(session.gate) {
            session.cancelled.set(true)
            stopRecorder(session)
        }
        session.queue.clear()
        if (notify && !destroyed) callback.onEvent(VoiceEvent(VoiceState.CANCELLED))
    }
    private fun stopRecorder(session: Session) {
        synchronized(session.gate) {
            try { session.recorder.get()?.stop() } catch (_: Throwable) { }
        }
    }
    private fun publish(session: Session, event: VoiceEvent, terminal: Boolean = false) {
        main.post {
            if (!destroyed && active === session && !session.cancelled.get()) {
                if (terminal) active = null
                callback.onEvent(event)
            }
        }
    }
    private fun recognize(session: Session) {
        if (session.cancelled.get()) return
        var producer: Thread? = null
        var usedHandle = 0L
        cacheEpoch++ // Invalidate all earlier idle-release requests.
        try {
            if (handle == 0L) {
                val p = OfflineAsrModels.prepare(app)
                if (session.cancelled.get()) return
                handle = LocalAsrNative.create(p.model, p.tokens, p.vad)
                check(handle != 0L)
            }
            usedHandle = handle
            LocalAsrNative.reset(handle)
            if (session.cancelled.get()) return
            if (session.stopping.get()) {
                publish(session, VoiceEvent(VoiceState.FINISHED), true)
                return
            }
            producer = Thread({ capture(session) }, "niuma-offline-microphone").apply { isDaemon = true; start() }
            var text = ""
            fun append(parts: Array<String>) {
                if (session.cancelled.get()) return
                val before = text
                for (part in parts) text = OfflineTranscriptPolicy.append(text, part)
                if (text != before) publish(session, VoiceEvent(VoiceState.PARTIAL, listOf(text)))
            }
            while (!session.cancelled.get()) {
                val frame = session.queue.poll(100, TimeUnit.MILLISECONDS)
                if (frame != null) append(LocalAsrNative.accept(handle, frame))
                else if (session.producerDone.count == 0L && session.queue.isEmpty()) break
                if (session.error.get() != 0) throw CaptureFailure(session.error.get())
            }
            if (!session.cancelled.get()) {
                if (session.error.get() != 0) throw CaptureFailure(session.error.get())
                append(LocalAsrNative.finish(handle))
                if (!session.cancelled.get()) publish(session, VoiceEvent(VoiceState.FINISHED, listOf(text)), true)
            }
        } catch (t: Throwable) {
            if (!session.cancelled.get()) publish(session, VoiceEvent(VoiceState.ERROR, errorCode = if (t is CaptureFailure) t.code else if (usedHandle == 0L) 1101 else 1104), true)
        } finally {
            session.stopping.set(true)
            stopRecorder(session)
            producer?.join(1500)
            session.queue.clear()
            if (usedHandle != 0L) {
                try { LocalAsrNative.reset(usedHandle) } catch (_: Throwable) {
                    try { LocalAsrNative.destroy(usedHandle) } catch (_: Throwable) { }
                    handle = 0L
                }
            }
            val token = ++cacheEpoch
            main.postDelayed({ worker.execute {
                if (cacheEpoch == token && handle != 0L) {
                    try { LocalAsrNative.destroy(handle) } catch (_: Throwable) { }
                    handle = 0L
                }
            } }, IDLE_RELEASE_MS)
        }
    }
    private fun capture(session: Session) {
        var audio: AudioRecord? = null
        try {
            if (session.cancelled.get() || session.stopping.get()) return
            val min = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            check(min > 0)
            audio = AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(16_000).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(maxOf(min * 4, 64_000)).build()
            check(audio.state == AudioRecord.STATE_INITIALIZED)
            synchronized(session.gate) {
                session.recorder.set(audio)
                if (session.cancelled.get() || session.stopping.get()) return
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
            }
            if (session.cancelled.get() || session.stopping.get()) return
            publish(session, VoiceEvent(VoiceState.LISTENING))
            val shorts = ShortArray(512)
            var filled = 0
            while (!session.cancelled.get() && !session.stopping.get()) {
                val n = audio.read(shorts, filled, 512 - filled, AudioRecord.READ_BLOCKING)
                if (n < 0) {
                    if (!session.cancelled.get() && !session.stopping.get()) session.error.set(1102)
                    break
                }
                if (n == 0) continue
                filled += n
                if (filled == 512) {
                    if (!session.queue.offer(FloatArray(512) { shorts[it] / 32768f })) {
                        session.error.set(1103)
                        break
                    }
                    filled = 0
                }
            }
            if (!session.cancelled.get() && session.error.get() == 0 && filled > 0) {
                val tail = FloatArray(512) { if (it < filled) shorts[it] / 32768f else 0f }
                if (!session.queue.offer(tail)) session.error.set(1103)
            }
        } catch (_: Throwable) {
            if (!session.cancelled.get() && !session.stopping.get()) session.error.set(1102)
        } finally {
            if (audio != null) {
                try { audio.stop() } catch (_: Throwable) { }
                try { audio.release() } catch (_: Throwable) { }
            }
            session.recorder.set(null)
            session.producerDone.countDown()
        }
    }
    private class CaptureFailure(val code: Int) : RuntimeException()
    private fun checkMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    private companion object {
        val main = Handler(Looper.getMainLooper())
        val worker = Executors.newSingleThreadExecutor { Thread(it, "niuma-offline-inference").apply { isDaemon = true } }
        // Accessed on worker only; new sessions supersede idle release through cacheEpoch.
        var handle = 0L
        var cacheEpoch = 0L
        const val IDLE_RELEASE_MS = 30_000L
    }
}
