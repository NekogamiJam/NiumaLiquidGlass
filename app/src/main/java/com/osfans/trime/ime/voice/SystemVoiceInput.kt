/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original modification: standalone, callback-only Android speech recognition controller.
 */
package com.osfans.trime.ime.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/** Public lifecycle/status signals. Recognition text is only carried in the callback event. */
enum class VoiceState { LOADING, LISTENING, PARTIAL, STOPPING, FINISHED, CANCELLED, ERROR }

data class VoiceEvent(
    val state: VoiceState,
    /** Transient recognition candidates from the current callback; not retained by this controller. */
    val results: List<String> = emptyList(),
    /** Platform SpeechRecognizer error code, when state is ERROR. */
    val errorCode: Int? = null,
)

fun interface VoiceInputCallback {
    fun onEvent(event: VoiceEvent)
}

data class VoiceStartResult(val started: Boolean, val message: String)

/**
 * Callback-only wrapper around Android SpeechRecognizer. Construct and call on the main thread.
 * Call start() only in response to an explicit user action and after RECORD_AUDIO is granted.
 * EXTRA_PREFER_OFFLINE is a request preference, not an offline guarantee.
 * The caller owns permission UX, empty-input guards, microphone UI and input-view lifecycle.
 */
class SystemVoiceInput(context: Context, private val callback: VoiceInputCallback) {
    private val appContext = context.applicationContext
    private var active: Session? = null
    private var generation = 0L
    private var destroyed = false

    private data class Session(
        val id: Long,
        val recognizer: SpeechRecognizer,
        var terminal: Boolean = false,
        var stopping: Boolean = false,
    )

    /** Explicitly starts one recognition session; never prompts for permission itself. */
    fun start(languageTag: String? = null, partialResults: Boolean = true): VoiceStartResult {
        if (!onMainThread()) return VoiceStartResult(false, "Must be called on the main thread")
        if (destroyed) return VoiceStartResult(false, "Controller has been destroyed")
        if (appContext.checkCallingOrSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            return VoiceStartResult(false, "RECORD_AUDIO permission is not granted")
        val onDevice = try {
            Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
        } catch (_: Throwable) { false }
        val systemAvailable = try { SpeechRecognizer.isRecognitionAvailable(appContext) }
        catch (_: Throwable) { false }
        if (!onDevice && !systemAvailable)
            return VoiceStartResult(false, "No system speech recognition service is available")

        // Supersede an earlier session before creating a fresh listener, so late callbacks are stale.
        cancelActive(notify = false)
        val id = ++generation
        val recognizer = try {
            if (onDevice)
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            else
                SpeechRecognizer.createSpeechRecognizer(appContext)
        } catch (t: Throwable) {
            emit(VoiceEvent(VoiceState.ERROR))
            return VoiceStartResult(false, "Could not create system recognizer: ${t.javaClass.simpleName}")
        }
        val session = Session(id, recognizer)
        active = session
        recognizer.setRecognitionListener(listenerFor(session))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (!languageTag.isNullOrBlank()) putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
        }
        return try {
            recognizer.startListening(intent)
            emitIfCurrent(session, VoiceEvent(VoiceState.LISTENING))
            VoiceStartResult(true, "Recognition started")
        } catch (t: Throwable) {
            finish(session, VoiceEvent(VoiceState.ERROR))
            VoiceStartResult(false, "Could not start recognition: ${t.javaClass.simpleName}")
        }
    }

    /** Requests the service to finish the utterance and deliver final results. Idempotent. */
    fun stop() {
        requireMainThread()
        val session = active ?: return
        if (session.terminal || session.stopping) return
        session.stopping = true
        emitIfCurrent(session, VoiceEvent(VoiceState.STOPPING))
        try { session.recognizer.stopListening() }
        catch (_: Throwable) { finish(session, VoiceEvent(VoiceState.ERROR)) }
    }

    /** Aborts the current session. Late results/errors from it are discarded. Idempotent. */
    fun cancel() {
        requireMainThread()
        cancelActive(notify = true)
    }

    /** Cancels and releases resources. Safe to call more than once; no callbacks follow destruction. */
    fun destroy() {
        requireMainThread()
        if (destroyed) return
        destroyed = true
        cancelActive(notify = false)
    }

    private fun listenerFor(session: Session) = object : RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit

        override fun onPartialResults(partialResults: android.os.Bundle?) {
            if (!isCurrent(session)) return
            emitIfCurrent(session, VoiceEvent(VoiceState.PARTIAL, readCandidates(partialResults)))
        }

        override fun onResults(results: android.os.Bundle?) {
            if (!isCurrent(session)) return
            finish(session, VoiceEvent(VoiceState.FINISHED, readCandidates(results)))
        }

        override fun onError(error: Int) {
            if (!isCurrent(session)) return
            finish(session, VoiceEvent(VoiceState.ERROR, errorCode = error))
        }
    }

    private fun readCandidates(bundle: android.os.Bundle?): List<String> =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.toList().orEmpty()

    private fun isCurrent(session: Session): Boolean =
        !destroyed && active === session && session.id == generation && !session.terminal

    private fun emitIfCurrent(session: Session, event: VoiceEvent) {
        if (isCurrent(session)) emit(event)
    }

    private fun finish(session: Session, event: VoiceEvent) {
        if (!isCurrent(session)) return
        session.terminal = true
        active = null
        release(session.recognizer)
        emit(event)
    }

    private fun cancelActive(notify: Boolean) {
        val session = active ?: return
        active = null
        session.terminal = true
        generation++
        try { session.recognizer.cancel() } catch (_: Throwable) { }
        release(session.recognizer)
        if (notify && !destroyed) emit(VoiceEvent(VoiceState.CANCELLED))
    }

    private fun release(recognizer: SpeechRecognizer) {
        try { recognizer.setRecognitionListener(null) } catch (_: Throwable) { }
        try { recognizer.destroy() } catch (_: Throwable) { }
    }

    private fun emit(event: VoiceEvent) {
        try { callback.onEvent(event) } catch (_: Throwable) { /* Do not let client callbacks crash platform dispatch. */ }
    }

    private fun onMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
    private fun requireMainThread() {
        check(onMainThread()) { "SystemVoiceInput must be used on the main thread" }
    }
}
