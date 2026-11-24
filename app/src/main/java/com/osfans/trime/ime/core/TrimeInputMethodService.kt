/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.RectF
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.FrameLayout
import android.widget.Toast
import java.util.concurrent.atomic.AtomicLong
import com.osfans.trime.R
import com.osfans.trime.ime.english.EnglishSuggestionController
import com.osfans.trime.ime.association.NiumaAssociationController
import com.osfans.trime.ime.voice.NiumaVoiceController
import com.osfans.trime.ime.gesture.NiumaCodeIndex
import com.osfans.trime.ime.glass.LiquidGlassStyle
import org.json.JSONObject
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.KeyModifiers
import com.osfans.trime.core.KeyValue
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeKeyMapping
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeDaemon
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.prefs.PreferenceDelegate
import com.osfans.trime.data.prefs.PreferenceDelegateProvider
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.composition.CandidatesView
import com.osfans.trime.ime.keyboard.InputFeedbackManager
import com.osfans.trime.receiver.RimeIntentReceiver
import com.osfans.trime.util.any
import com.osfans.trime.util.findSectionFrom
import com.osfans.trime.util.forceShowSelf
import com.osfans.trime.util.monitorCursorAnchor
import com.osfans.trime.util.styledFloat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.launch
import splitties.systemservices.clipboardManager
import splitties.systemservices.inputMethodManager
import timber.log.Timber

/** [輸入法][InputMethodService]主程序  */

open class TrimeInputMethodService : LifecycleInputMethodService() {
    private lateinit var rime: RimeSession
    private val jobs = Channel<Job>(capacity = Channel.UNLIMITED)

    private val prefs = AppPrefs.defaultInstance()
    private lateinit var decorView: View
    private lateinit var contentView: FrameLayout
    private lateinit var lastKnownConfig: Configuration
    private var inputView: InputView? = null
    private var candidatesView: CandidatesView? = null
    private val navBarManager = NavigationBarManager()
    private val inputDeviceManager = InputDeviceManager { useVirtualKeyboard, useCandidatesView ->
        postRimeJob {
            setCandidatePagingMode(useCandidatesView)
        }
        currentInputConnection?.monitorCursorAnchor(useCandidatesView)
        val scope = themeScope ?: return@InputDeviceManager
        window.window?.let {
            navBarManager.evaluate(it, useVirtualKeyboard, scope.colors)
        }
    }
    private val rimeIntentReceiver = RimeIntentReceiver()

    private var lastCommittedText: String = ""

    private var composingText: String = ""

    private var cursorUpdateIndex = 0

    private val niumaInputEpoch = AtomicLong(0)
    @Volatile private var niumaGestureCodeIndex: NiumaCodeIndex? = null

    fun niumaGestureEpoch(): Long = niumaInputEpoch.get()

    /** Compatibility read only: never selects, changes or scans the document. */
    private fun readNiumaCursorWindow(
        connection: android.view.inputmethod.InputConnection, before: Int, after: Int,
    ): com.osfans.trime.ime.gesture.CursorWindow? {
        val source = object : com.osfans.trime.ime.gesture.CursorWindowSource {
            override fun surrounding(before: Int, after: Int) = connection.getSurroundingText(before, after, 0)?.let {
                com.osfans.trime.ime.gesture.CursorWindow(it.text.toString(), it.selectionStart, it.selectionEnd, it.offset)
            }
            override fun before(length: Int) = connection.getTextBeforeCursor(length, 0)?.toString()
            override fun after(length: Int) = connection.getTextAfterCursor(length, 0)?.toString()
            override fun selected() = connection.getSelectedText(0)?.toString()
        }
        return com.osfans.trime.ime.gesture.CursorWindowReader.read(source, before, after, niumaSelectionStart, niumaSelectionEnd)
    }

    /** Explicit upward-backspace only. One prefix delete, no selection/clipboard/text logs. */
    fun clearNiumaBeforeCursor(pressEpoch: Long) {
        val current = niumaGestureSessionGuard(pressEpoch) ?: return
        onNiumaInputAction(resetContext = true)
        cancelNiumaVoiceInput()
        postRimeJob {
            if (!current() || getRawInput().isNotEmpty()) return@postRimeJob
            kotlinx.coroutines.withContext(Dispatchers.Main.immediate) {
                if (!current() || composingText.isNotEmpty()) return@withContext
                val connection = currentInputConnection ?: return@withContext
                fun snapshot(): com.osfans.trime.ime.gesture.BeforeCursorSnapshot? =
                    readNiumaCursorWindow(connection, 2, 2)?.let {
                        com.osfans.trime.ime.gesture.BeforeCursorSnapshot(it.text, it.start, it.end, it.offset)
                    }
                val first = snapshot() ?: return@withContext
                val count = com.osfans.trime.ime.gesture.ClearBeforeCursorPolicy.deleteUtf16Before(
                    first, niumaSelectionStart, niumaSelectionEnd,
                ) ?: return@withContext
                // Re-observe before mutation; stale/moved/unknown editors fail closed.
                if (!current() || composingText.isNotEmpty() || snapshot() != first ||
                    com.osfans.trime.ime.gesture.ClearBeforeCursorPolicy.deleteUtf16Before(first, niumaSelectionStart, niumaSelectionEnd) != count
                ) return@withContext
                // The count is the exact absolute cursor in UTF-16 units, not MAX_VALUE.
                // All before, ZERO after; never retry a rejected/unknown mutation.
                runCatching { connection.deleteSurroundingText(count, 0) }
            }
        }
    }
    fun niumaGestureIndex(): NiumaCodeIndex? = niumaGestureCodeIndex

    /** Capture on touch release; an old touch/queued task must never target a new editor/tree. */
    fun niumaGestureSessionGuard(pressEpoch: Long): (() -> Boolean)? {
        val info = currentInputEditorInfo ?: return null
        val connection = currentInputConnection ?: return null
        if (pressEpoch != niumaInputEpoch.get()) return null
        return {
            !niumaDestroyed && pressEpoch == niumaInputEpoch.get() && isInputViewShown &&
                currentInputEditorInfo === info && currentInputConnection === connection &&
                ThemeManager.prefs.selectedTheme.getValue() == LiquidGlassStyle.CONFIG_ID
        }
    }

    private fun loadNiumaGestureMetadata() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val codes = assets.open("niuma/niuma_code_paths.txt").bufferedReader(Charsets.UTF_8).use { it.readLines() }
                val doc = JSONObject(assets.open("niuma/niuma_z_transitions.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
                require(doc.getInt("format") == 1 && doc.getString("schema_id") == "niuma_glass")
                require(doc.getString("source_expected_sha256") == "73f77da4d14535405d020b769b5c6ffe527a4ef8a36afdf052ec02937aa1d44e")
                require(doc.getInt("code_count") == 170277 && codes.size == 170277)
                val zs = doc.getJSONArray("extend_z_prefixes")
                val prefixes = (0 until zs.length()).map { zs.getString(it) }.toSet()
                val index = NiumaCodeIndex.fromValidated(codes, prefixes)
                if (!niumaDestroyed) niumaGestureCodeIndex = index
            } catch (_: Exception) {
                // Fail closed: ordinary input and configured long actions remain available.
                Timber.w("Niuma gesture metadata unavailable; special selection disabled")
            }
        }
    }

    private var niumaEnglish: EnglishSuggestionController? = null
    private fun niumaEnglishIsSafe(): Boolean {
        val info = currentInputEditorInfo ?: return false
        return com.osfans.trime.ime.english.EnglishEditorPolicy.allows(info.inputType, info.imeOptions) &&
            ThemeManager.prefs.selectedTheme.getValue() == LiquidGlassStyle.CONFIG_ID &&
            currentInputConnection != null && isInputViewShown && niumaVoiceCodeIsEmpty() &&
            rime.run { schemaCached.schemaId == "niuma_glass" && statusCached.isAsciiMode }
    }
    private fun ensureNiumaEnglish() = niumaEnglish ?: EnglishSuggestionController(this, ::niumaEnglishIsSafe, ::commitNiumaEnglish)
        .also { niumaEnglish = it }
    fun niumaEnglishView(): View = ensureNiumaEnglish().view
    fun preserveNiumaEnglishKey(code: Int): Boolean = code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ||
        code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 || code in setOf(KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_CAPS_LOCK,
            KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD, KeyEvent.KEYCODE_APOSTROPHE,
            KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_SEMICOLON, KeyEvent.KEYCODE_SLASH)

    /** Complete by appending only the missing suffix: no prefix deletion, selection or editor-history scan. */
    private fun commitNiumaEnglish(word: String, prefix: String) {
        if (!word.startsWith(prefix, ignoreCase = true) || prefix.length > 40) return
        val info = currentInputEditorInfo ?: return
        val connection = currentInputConnection ?: return
        val epoch = niumaInputEpoch.get()
        val request = niumaAssociationGeneration.get()
        postRimeJob {
            if (getRawInput().isNotEmpty()) return@postRimeJob
            withContext(Dispatchers.Main.immediate) {
                fun current() = epoch == niumaInputEpoch.get() && request == niumaAssociationGeneration.get() &&
                    currentInputEditorInfo === info && currentInputConnection === connection && niumaEnglishIsSafe()
                if (!current()) return@withContext
                val a = readNiumaCursorWindow(connection, prefix.length.coerceAtLeast(1), 2) ?: return@withContext
                if (a.offset.toLong() + a.start != niumaSelectionStart.toLong() || niumaSelectionStart != niumaSelectionEnd ||
                    !a.text.take(a.start).endsWith(prefix)) return@withContext
                val b = readNiumaCursorWindow(connection, prefix.length.coerceAtLeast(1), 2) ?: return@withContext
                if (!current() || a != b) return@withContext
                val separator = if (prefix.isEmpty() && a.text.take(a.start).lastOrNull()?.isWhitespace() == false) " " else ""
                commitText(separator + word.substring(prefix.length) + " ")
            }
        }
    }

    private var niumaAssociation: NiumaAssociationController? = null
    private val niumaAssociationGeneration = AtomicLong(0)
    @Volatile private var niumaSelectionStart = -1
    @Volatile private var niumaSelectionEnd = -1
    @Volatile private var niumaExpectedSelection: Int? = null

    private fun niumaAssociationIsSafe(): Boolean {
        val info = currentInputEditorInfo ?: return false
        val type = info.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        if (type and InputType.TYPE_MASK_CLASS != InputType.TYPE_CLASS_TEXT ||
            variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
            info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
        ) return false
        return currentInputConnection != null && isInputViewShown && niumaVoiceCodeIsEmpty() &&
            rime.run { schemaCached.schemaId == "niuma_glass" && !statusCached.isAsciiMode }
    }

    private fun ensureNiumaAssociation(): NiumaAssociationController = niumaAssociation ?:
        NiumaAssociationController(this, ::niumaAssociationIsSafe, ::commitNiumaAssociation)
            .also { niumaAssociation = it }

    fun niumaAssociationView(): View = ensureNiumaAssociation().view

    /** Invalidate synchronously before dispatch; UI changes stay on the main thread. */
    fun onNiumaInputAction(resetContext: Boolean = false, preserveEnglish: Boolean = false) {
        val request = niumaAssociationGeneration.incrementAndGet()
        val update = Runnable {
            if (resetContext && !preserveEnglish) niumaEnglish?.reset() else niumaEnglish?.hide()
            if (resetContext) niumaAssociation?.reset()
            else if (request == niumaAssociationGeneration.get()) niumaAssociation?.hide()
        }
        if (Looper.myLooper() == Looper.getMainLooper()) update.run()
        else ContextCompat.getMainExecutor(this).execute(update)
    }

    private fun commitNiumaAssociation(text: String) {
        val request = niumaAssociationGeneration.get()
        val info = currentInputEditorInfo ?: return
        val connection = currentInputConnection ?: return
        postRimeJob {
            val empty = getRawInput().isEmpty()
            ContextCompat.getMainExecutor(this@TrimeInputMethodService).execute {
                if (empty && request == niumaAssociationGeneration.get() && currentInputEditorInfo === info &&
                    currentInputConnection === connection && niumaAssociationIsSafe()
                ) commitText(text)
            }
        }
    }

    private fun showNiumaAssociationAfterCommit(text: String, request: Long) {
        ContextCompat.getMainExecutor(this).execute {
            if (request != niumaAssociationGeneration.get()) return@execute
            val info = currentInputEditorInfo
            val connection = currentInputConnection
            postRimeJob {
                val empty = getRawInput().isEmpty()
                ContextCompat.getMainExecutor(this@TrimeInputMethodService).execute {
                    if (request == niumaAssociationGeneration.get() && currentInputEditorInfo === info &&
                        currentInputConnection === connection && empty
                    ) ensureNiumaAssociation().onCommitted(text)
                }
            }
        }
    }

    private var niumaVoice: NiumaVoiceController? = null
    private val niumaVoiceGeneration = AtomicLong(0)
    private var niumaVoiceSubmissionGeneration = -1L

    private fun niumaVoiceCodeIsEmpty(): Boolean =
        ::rime.isInitialized && composingText.isEmpty() && runCatching {
            rime.run { isReady && compositionCached.length == 0 }
        }.getOrDefault(false)

    private fun ensureNiumaVoice(): NiumaVoiceController = niumaVoice ?: NiumaVoiceController(
        service = this,
        canStartForComposition = { niumaVoiceCodeIsEmpty() },
        onFinalText = { text ->
            // Invalidated immediately even when a non-voice action originates off the UI thread.
            if (niumaVoiceSubmissionGeneration == niumaVoiceGeneration.get() && niumaVoiceCodeIsEmpty()) {
                commitText(text)
            }
        },
    ).also { niumaVoice = it }

    fun niumaVoiceStatusView(): View = ensureNiumaVoice().statusView

    /** Called only by an explicit microphone action, never by permission/lifecycle callbacks. */
    fun startNiumaVoiceInput() {
        onNiumaInputAction(resetContext = true)
        val request = niumaVoiceGeneration.incrementAndGet()
        val info = currentInputEditorInfo ?: return
        val connection = currentInputConnection ?: return
        // A queue barrier waits for preceding key actions; cached preedit alone could be stale.
        postRimeJob {
            val codeIsEmpty = getRawInput().isEmpty()
            ContextCompat.getMainExecutor(this@TrimeInputMethodService).execute {
                if (request != niumaVoiceGeneration.get() || currentInputEditorInfo !== info ||
                    currentInputConnection !== connection
                ) return@execute
                if (!codeIsEmpty) {
                    Toast.makeText(this@TrimeInputMethodService, "请先清空当前编码，再使用语音输入", Toast.LENGTH_SHORT).show()
                    return@execute
                }
                niumaVoiceSubmissionGeneration = request
                ensureNiumaVoice().start()
            }
        }
    }

    fun cancelNiumaVoiceInput() {
        val request = niumaVoiceGeneration.incrementAndGet()
        if (Looper.myLooper() == Looper.getMainLooper()) {
            niumaVoice?.cancel()
        } else {
            ContextCompat.getMainExecutor(this).execute {
                if (request == niumaVoiceGeneration.get()) niumaVoice?.cancel()
            }
        }
    }

    private fun invalidateNiumaInputSession() {
        niumaInputEpoch.incrementAndGet()
        onNiumaInputAction(resetContext = true)
        niumaExpectedSelection = null
        niumaVoiceGeneration.incrementAndGet()
        niumaVoice?.onInputSessionChanged()
    }

    private val recreateInputViewPrefs: Array<PreferenceDelegate<*>> = arrayOf(
        prefs.keyboard.expandKeypressArea,
        prefs.keyboard.hideKeySymbol,
        prefs.keyboard.hideKeyHint,
        prefs.keyboard.hideInputBar,
        prefs.advanced.ignoreSystemGestureInsets,
    )

    private val themeScope: ThemeScope?
        get() = ColorManager.currentScope()

    @Keep
    private val recreateInputViewListener =
        PreferenceDelegate.OnChangeListener<Any> { _, _ ->
            themeScope?.let { replaceInputView(it) }
        }

    @Keep
    private val recreateCandidatesViewListener =
        PreferenceDelegateProvider.OnChangeListener {
            themeScope?.let { replaceCandidateView(it) }
        }

    @Keep
    private val onThemeChangeListener =
        ThemeManager.OnThemeChangeListener {
            themeScope?.let { replaceInputViews(it) }
        }

    @Keep
    private val onColorChangeListener =
        ColorManager.OnColorChangeListener {
            ContextCompat.getMainExecutor(this).execute {
                // A scheme-only change restyles the tree in place; theme
                // switches rebuild it through onThemeChangeListener.
                val scope = themeScope ?: return@execute
                inputView?.refreshColors()
                candidatesView?.refreshColors()
                window.window?.let {
                    navBarManager.evaluate(it, inputDeviceManager.useVirtualKeyboard, scope.colors)
                }
            }
        }

    private fun postJob(
        scope: CoroutineScope,
        block: suspend () -> Unit,
    ): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) { block() }
        jobs.trySend(job)
        return job
    }

    /**
     * Post a rime operation to [jobs] to be executed
     *
     * Unlike `rime.runOnReady` or `rime.launchOnReady` where
     * subsequent operations can start if the prior operation is not finished (suspended),
     * [postRimeJob] ensures that operations are executed sequentially.
     */
    fun postRimeJob(block: suspend RimeApi.() -> Unit) = postJob(rime.lifecycleScope) { rime.runOnReady(block) }

    private suspend fun updateRimeOption(api: RimeApi) {
        try {
            api.setRuntimeOption("soft_cursor", prefs.keyboard.useSoftCursor.getValue()) // 軟光標
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun registerReceiver() {
        val intentFilter =
            IntentFilter().apply {
                addAction(RimeIntentReceiver.ACTION_DEPLOY)
                addAction(RimeIntentReceiver.ACTION_SYNC_USER_DATA)
            }
        ContextCompat.registerReceiver(
            this,
            rimeIntentReceiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    @Volatile private var niumaDestroyed = false

    override fun onCreate() {
        // IME-only translucent theme; do not change the application/settings Activity theme.
        setTheme(R.style.NiumaGlassIme)
        rime = RimeDaemon.createSession(javaClass.name)
        lifecycleScope.launch {
            jobs.consumeEach { it.join() }
        }
        lifecycleScope.launch {
            rime.run { messageFlow }.collect {
                handleRimeMessage(it)
            }
        }
        recreateInputViewPrefs.forEach {
            it.registerOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.registerOnChangeListener(recreateCandidatesViewListener)
        // ensure theme and color managers are initialized after rime is ready
        lifecycleScope.launch {
            rime.runOnReady {
                withContext(Dispatchers.Main.immediate) {
                    if (niumaDestroyed) return@withContext
                    // Keep theme/scope publication and all View callbacks on the UI thread.
                    ThemeManager.init(resources.configuration)
                    val bootstrap = getSharedPreferences("niuma_bootstrap", MODE_PRIVATE)
                    if (!bootstrap.getBoolean("liquid_skin_config_v2", false)) {
                        val applied = ThemeManager.selectTheme("niuma_glass.trime")
                        if (applied == "niuma_glass.trime") {
                            bootstrap.edit().putBoolean("liquid_skin_config_v2", true).apply()
                        }
                    }
                    if (!niumaDestroyed) {
                        ThemeManager.addOnChangedListener(onThemeChangeListener)
                        ColorManager.addOnChangedListener(onColorChangeListener)
                        // Bootstrap may replace an already visible stock first-run scope.
                        themeScope?.let { replaceInputViews(it) }
                    }
                }
            }
        }
        InputFeedbackManager.init(this)
        registerReceiver()
        super.onCreate()
        loadNiumaGestureMetadata()
        Timber.d("onCreate")
        decorView = window.window!!.decorView
        contentView = decorView.findViewById(android.R.id.content)
        lastKnownConfig = Configuration(resources.configuration)
    }

    private fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.CommitTextMessage -> {
                if (!it.data.text.isNullOrEmpty()) {
                    commitText(it.data.text)
                }
            }

            is RimeMessage.InlinePreeditMessage -> {
                updateComposingText(it.data)
            }

            is RimeMessage.KeyMessage ->
                it.data.let msg@{
                    if (it.isVirtual && it.value.value in 32..126 && !it.modifiers.ctrl && !it.modifiers.alt &&
                        !it.modifiers.meta && !it.modifiers.release && niumaEnglishIsSafe()) {
                        // Printable English soft keys used to bypass commitText via synthetic editor key events.
                        // Route only this guarded ASCII case through the existing commit funnel, once.
                        commitText(Character.toString(it.value.value))
                        return@msg
                    }
                    if (it.isVirtual) {
                        when (it.value.value) {
                            RimeKeyMapping.RimeKey_Return ->
                                if (it.modifiers.isReturnChord()) {
                                    sendDownUpKeyEvent(
                                        it.value.keyCode,
                                        it.modifiers.metaState or meta(
                                            alt = it.modifiers.alt,
                                            shift = it.modifiers.shift,
                                            ctrl = it.modifiers.ctrl,
                                            meta = it.modifiers.meta,
                                        ),
                                    )
                                } else {
                                    handleReturnKey()
                                }

                            else -> {
                                val keyCode = it.value.keyCode
                                if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                                    // recognized keyCode
                                    sendDownUpKeyEvent(
                                        keyCode,
                                        it.modifiers.metaState or meta(
                                            alt = it.modifiers.alt,
                                            shift = it.modifiers.shift,
                                            ctrl = it.modifiers.ctrl,
                                            meta = it.modifiers.meta,
                                        ),
                                    )
                                    if (it.modifiers.ctrl && keyCode == KeyEvent.KEYCODE_C) clearTextSelection()
                                } else {
                                    if (it.value.value > 0) {
                                        runCatching {
                                            commitText(Character.toString(it.value.value))
                                        }.getOrElse { t -> Timber.w(t, "Unhandled Virtual KeyEvent: $it") }
                                    } else {
                                        Timber.w("Unhandled Virtual KeyEvent: $it")
                                    }
                                }
                            }
                        }
                    } else {
                        val keyCode = it.value.keyCode
                        if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                            // recognized keyCode
                            val eventTime = SystemClock.uptimeMillis()
                            if (it.modifiers.release) {
                                sendUpKeyEvent(eventTime, keyCode, it.modifiers.metaState)
                            } else {
                                sendDownKeyEvent(eventTime, keyCode, it.modifiers.metaState)
                            }
                        } else {
                            if (!it.modifiers.release && it.value.value > 0) {
                                runCatching {
                                    commitText(Character.toString(it.value.value))
                                }.getOrElse { t -> Timber.w(t, "Unhandled Rime KeyEvent: $it") }
                            } else {
                                Timber.w("Unhandled Rime KeyEvent: $it")
                            }
                        }
                    }
                }

            is RimeMessage.DeployMessage -> {
                if (it.data == RimeMessage.DeployMessage.State.Success) {
                    // The deployment may have refreshed the current theme's artifact.
                    val themeId = ThemeManager.prefs.selectedTheme.getValue()
                    lifecycleScope.launch { ThemeManager.selectTheme(themeId) }
                }
            }

            else -> {}
        }
    }

    private fun replaceInputView(scope: ThemeScope): InputView {
        niumaInputEpoch.incrementAndGet()
        val newInputView = InputView(this, rime, scope)
        setInputView(newInputView)
        inputDeviceManager.setInputView(newInputView)
        inputView = newInputView
        return newInputView
    }

    private fun replaceCandidateView(scope: ThemeScope): CandidatesView {
        val newCandidatesView = CandidatesView(this, rime, scope)
        contentView.removeView(candidatesView)
        contentView.addView(newCandidatesView)
        inputDeviceManager.setCandidatesView(newCandidatesView)
        candidatesView = newCandidatesView
        if (decorLocationUpdated) {
            candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
        } else {
            candidatesView?.updateCursorAnchor(contentSize)
        }
        return newCandidatesView
    }

    private fun replaceInputViews(scope: ThemeScope) {
        navBarManager.evaluate(window.window!!, inputDeviceManager.useVirtualKeyboard, scope.colors)
        replaceInputView(scope)
        replaceCandidateView(scope)
        // Initial theme readiness can precede onStartInput: there is no editor yet.
        // onStartInputView updates the enter label once a real EditorInfo is available.
        currentInputEditorInfo?.let { inputView?.updateEnterKeyLabel(it) }
    }

    override fun onDestroy() {
        niumaInputEpoch.incrementAndGet()
        niumaDestroyed = true
        niumaGestureCodeIndex = null
        niumaAssociationGeneration.incrementAndGet()
        niumaAssociation?.destroy()
        niumaEnglish?.destroy()
        niumaEnglish = null
        niumaAssociation = null
        niumaVoiceGeneration.incrementAndGet()
        niumaVoice?.destroy()
        niumaVoice = null
        InputFeedbackManager.destroy()
        inputView = null
        recreateInputViewPrefs.forEach {
            it.unregisterOnChangeListener(recreateInputViewListener)
        }
        prefs.candidates.unregisterOnChangeListener(recreateCandidatesViewListener)
        ThemeManager.removeOnChangedListener(onThemeChangeListener)
        ColorManager.removeOnChangedListener(onColorChangeListener)
        super.onDestroy()
        unregisterReceiver(rimeIntentReceiver)
        RimeDaemon.destroySession(javaClass.name)
    }

    private fun handleReturnKey() {
        val info = currentInputEditorInfo
        val action = EditorEnterAction.resolve(info.inputType, info.imeOptions, info.actionId, info.actionLabel)
        if (action == null) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        } else {
            currentInputConnection.performEditorAction(action)
        }
    }

    /**
     * https://github.com/fcitx5-android/fcitx5-android/blob/fe3a618c8fd18842305d2f8ec2880fcc67ec1679/app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt#L523-#L547
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        niumaInputEpoch.incrementAndGet()
        onNiumaInputAction(resetContext = true)
        cancelNiumaVoiceInput()
        postRimeJob { clearComposition() }
        val keyboardUiModeMask = ActivityInfo.CONFIG_KEYBOARD or
            ActivityInfo.CONFIG_KEYBOARD_HIDDEN or
            ActivityInfo.CONFIG_UI_MODE
        val diff = lastKnownConfig.diff(newConfig)
        Timber.d("onConfigurationChanged diff=$diff")
        if (diff and keyboardUiModeMask != diff) {
            super.onConfigurationChanged(newConfig)
        }
        lastKnownConfig.setTo(newConfig)
    }

    private val contentSize = floatArrayOf(0f, 0f)
    private val decorLocation = floatArrayOf(0f, 0f)
    private val decorLocationInt = intArrayOf(0, 0)
    private var decorLocationUpdated = false

    private fun updateDecorLocation() {
        contentSize[0] = contentView.width.toFloat()
        contentSize[1] =
            if (inputDeviceManager.useVirtualKeyboard) {
                inputViewLocation[1].toFloat()
            } else {
                contentView.height.toFloat()
            }
        decorView.getLocationOnScreen(decorLocationInt)
        decorLocation[0] = decorLocationInt[0].toFloat()
        decorLocation[1] = decorLocationInt[1].toFloat()
        // contentSize and decorLocation can be completely wrong,
        // when measuring right after the very first onStartInputView() of an IMS' lifecycle
        if (contentSize[0] > 0 && contentSize[1] > 0) {
            decorLocationUpdated = true
        }
    }

    private val anchorPosition = RectF()

    override fun onUpdateCursorAnchorInfo(info: CursorAnchorInfo) {
        val bounds = info.getCharacterBounds(0)
        // update anchorPosition
        if (bounds == null) {
            // composing is disabled in target app or trime settings
            // use the position of the insertion marker instead
            anchorPosition.top = info.insertionMarkerTop
            anchorPosition.left = info.insertionMarkerHorizontal
            anchorPosition.bottom = info.insertionMarkerBottom
            anchorPosition.right = info.insertionMarkerHorizontal
        } else {
            // for different writing system (e.g. right to left languages),
            // we have to calculate the correct RectF
            val horizontal = if (candidatesView?.layoutDirection == View.LAYOUT_DIRECTION_RTL) bounds.right else bounds.left
            anchorPosition.top = bounds.top
            anchorPosition.left = horizontal
            anchorPosition.bottom = bounds.bottom
            anchorPosition.right = horizontal
        }
        if (!decorLocationUpdated) {
            updateDecorLocation()
        }
        if (anchorPosition.any(Float::isNaN)) {
            candidatesView?.updateCursorAnchor(contentSize)
            return
        }
        info.matrix.mapRect(anchorPosition)
        val (dX, dY) = decorLocation
        anchorPosition.offset(-dX, -dY)
        candidatesView?.updateCursorAnchor(anchorPosition, contentSize)
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        if (newSelStart == newSelEnd && newSelEnd == niumaExpectedSelection) {
            niumaExpectedSelection = null
        } else if ((newSelStart != oldSelStart || newSelEnd != oldSelEnd) && composingText.isEmpty()) {
            onNiumaInputAction(resetContext = true)
            niumaExpectedSelection = null
        }
        niumaSelectionStart = newSelStart
        niumaSelectionEnd = newSelEnd
        cursorUpdateIndex += 1
        handleCursorUpdate(newSelStart, newSelEnd, candidatesStart, candidatesEnd, cursorUpdateIndex)
        inputView?.updateSelection(newSelStart, newSelEnd)
    }

    private fun handleCursorUpdate(
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
        updateIndex: Int,
    ) {
        if (newSelStart != newSelEnd) return
        if (candidatesStart == candidatesEnd) return
        if (newSelStart in candidatesStart..candidatesEnd) {
            val position = newSelStart - candidatesStart
            if (position != composingText.length) {
                niumaInputEpoch.incrementAndGet()
                postRimeJob {
                    if (updateIndex != cursorUpdateIndex) return@postRimeJob
                    Timber.d("handleCursorUpdate: move rime cursor to $position")
                    moveCursorPos(position)
                }
            }
        } else {
            niumaInputEpoch.incrementAndGet()
            Timber.d("handleCursorUpdate: clear composition")
            postRimeJob {
                clearComposition()
            }
        }
    }

    private val inputViewLocation = intArrayOf(0, 0)

    override fun onComputeInsets(outInsets: Insets) {
        if (inputDeviceManager.useVirtualKeyboard) {
            inputView?.keyboardView?.getLocationInWindow(inputViewLocation)
            outInsets.apply {
                contentTopInsets = inputViewLocation[1]
                visibleTopInsets = inputViewLocation[1]
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        } else {
            val n = decorView.findViewById<View>(android.R.id.navigationBarBackground)?.height ?: 0
            val h = decorView.height - n
            outInsets.apply {
                contentTopInsets = h
                visibleTopInsets = h
                touchableInsets = Insets.TOUCHABLE_INSETS_VISIBLE
            }
        }
    }

    // always show InputView since we delegate CandidatesView's visibility to it
    @SuppressLint("MissingSuperCall")
    override fun onEvaluateInputViewShown() = true

    fun superEvaluateInputViewShown() = super.onEvaluateInputViewShown()

    override fun onCreateInputView(): View? {
        Timber.d("onCreateInputView")
        val scope = themeScope
        if (scope == null) {
            Timber.w("ThemeScope is not ready yet; deferring input view creation")
            return null
        }
        replaceInputViews(scope)
        // We will call `setInputView` by ourselves. This is fine.
        return null
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        val inputArea = contentView.findViewById<FrameLayout>(android.R.id.inputArea)
        inputArea.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
        view.updateLayoutParams<ViewGroup.LayoutParams> {
            height = ViewGroup.LayoutParams.MATCH_PARENT
        }
    }

    override fun onConfigureWindow(
        win: Window,
        isFullscreen: Boolean,
        isCandidatesOnly: Boolean,
    ) {
        win.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onStartInput(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        invalidateNiumaInputSession()
        niumaSelectionStart = attribute.initialSelStart
        niumaSelectionEnd = attribute.initialSelEnd
        composingText = ""
        Timber.d("onStartInput: restarting=$restarting")
        val isNullType = attribute.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL
        postRimeJob {
            if (restarting) {
                // when input restarts in the same editor, clear previous composition
                clearComposition()
            }
            setNullInputType(isNullType)
        }
    }

    private val inlineSuggestions by prefs.general.inlineSuggestions

    @RequiresApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        if (!inlineSuggestions || !inputDeviceManager.useVirtualKeyboard) return null
        val colors = themeScope?.colors ?: return null
        return InlineSuggestions.createRequest(this, colors)
    }

    @SuppressLint("NewApi")
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        if (!inputDeviceManager.useVirtualKeyboard) return false
        return inputView?.handleInlineSuggestions(response) == true
    }

    override fun onStartInputView(
        attribute: EditorInfo,
        restarting: Boolean,
    ) {
        niumaInputEpoch.incrementAndGet()
        Timber.d("onStartInputView: restarting=$restarting")
        InputFeedbackManager.startInput()
        postRimeJob {
            updateRimeOption(this)
        }
        val (useVirtualKeyboard, useCandidatesView) =
            inputDeviceManager.evaluateOnStartInputView(attribute, this)
        if (useVirtualKeyboard) {
            inputView?.startInput(attribute, restarting)
        }
        if (useCandidatesView) {
            if (currentInputConnection?.monitorCursorAnchor() != true) {
                if (!decorLocationUpdated) {
                    updateDecorLocation()
                }
                // anchor CandidatesView to bottom-left corner in case InputConnection does not
                // support monitoring CursorAnchorInfo
                candidatesView?.updateCursorAnchor(contentSize)
            }
        }
    }

    override fun onFinishInput() {
        invalidateNiumaInputSession()
        super.onFinishInput()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        invalidateNiumaInputSession()
        Timber.d("onFinishInputView: finishingInput=$finishingInput")
        decorLocationUpdated = false
        inputView?.dismissCandidateActionMenu()
        candidatesView?.dismissCandidateActionMenu()
        inputDeviceManager.onFinishInputView()
        currentInputConnection?.apply {
            finishComposingText()
            monitorCursorAnchor(false)
        }
        composingText = ""
        postRimeJob {
            clearComposition()
        }
        InputFeedbackManager.finishInput()
    }

    fun commitText(text: String) {
        cancelNiumaVoiceInput()
        val ic = currentInputConnection ?: return
        val associationRequest = niumaAssociationGeneration.incrementAndGet()
        niumaExpectedSelection = if (niumaSelectionStart >= 0 && niumaSelectionEnd >= 0) {
            if (composingText.isNotEmpty()) niumaSelectionEnd - composingText.length + text.length
            else minOf(niumaSelectionStart, niumaSelectionEnd) + text.length
        } else null

        // when composing text equals commit content, finish composing text as-is
        if (composingText.isNotEmpty() && composingText == text) {
            ic.finishComposingText()
        } else {
            ic.commitText(text, 1)
        }
        lastCommittedText = text
        composingText = ""
        InputFeedbackManager.textCommitSpeak(text)
        val englishEpoch = niumaInputEpoch.get()
        ContextCompat.getMainExecutor(this).execute {
            if (englishEpoch == niumaInputEpoch.get() && currentInputConnection === ic) {
                if (niumaEnglishIsSafe()) ensureNiumaEnglish().committed(text) else niumaEnglish?.reset()
            }
        }
        showNiumaAssociationAfterCommit(text, associationRequest)
    }

    /**
     * Constructs a meta state integer flag which can be used for setting the `metaState` field when sending a KeyEvent
     * to the input connection. If this method is called without a meta modifier set to true, the default value `0` is
     * returned.
     *
     * @param ctrl Set to true to enable the CTRL meta modifier. Defaults to false.
     * @param alt Set to true to enable the ALT meta modifier. Defaults to false.
     * @param shift Set to true to enable the SHIFT meta modifier. Defaults to false.
     *
     * @return An integer containing all meta flags passed and formatted for use in a [KeyEvent].
     */
    fun meta(
        alt: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
        sym: Boolean = false,
    ): Int {
        var metaState = 0
        if (alt) metaState = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (ctrl) metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (shift) metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (meta) metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        if (sym) metaState = metaState or KeyEvent.META_SYM_ON
        return metaState
    }

    private fun sendDownKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    private fun sendUpKeyEvent(
        eventTime: Long,
        keyEventCode: Int,
        metaState: Int = 0,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD,
            ),
        )
    }

    /**
     * Same as [InputMethodService.sendDownUpKeyEvents] but also allows to set meta state.
     *
     * @param keyEventCode The key code to send, use a key code defined in Android's [KeyEvent].
     * @param metaState Flags indicating which meta keys are currently pressed.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun sendDownUpKeyEvent(
        keyEventCode: Int,
        metaState: Int = meta(),
    ): Boolean {
        val eventTime = SystemClock.uptimeMillis()
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        }
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        if (metaState and KeyEvent.META_META_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT)
        }
        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SYM)
        }
        sendDownKeyEvent(eventTime, keyEventCode, metaState)
        sendUpKeyEvent(eventTime, keyEventCode, metaState)
        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SYM)
        }
        if (metaState and KeyEvent.META_META_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT)
        }
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT)
        }
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT)
        }
        return true
    }

    private fun forwardKeyEvent(event: KeyEvent): Boolean {
        val keyVal = KeyValue.fromKeyEvent(event)
        if (keyVal.value != RimeKeyMapping.RimeKey_VoidSymbol) {
            val modifiers = KeyModifiers.fromKeyEvent(event)
            postRimeJob {
                processKey(keyVal, modifiers, isVirtual = false)
            }
            return true
        }
        Timber.d("Skipped KeyEvent: $event")
        return false
    }

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        cancelNiumaVoiceInput()
        onNiumaInputAction(resetContext = keyCode !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z, preserveEnglish = preserveNiumaEnglishKey(keyCode))
        if (inputDeviceManager.evaluateOnKeyDown(event, this)) {
            decorLocationUpdated = false
            forceShowSelf()
        }
        return forwardKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = forwardKeyEvent(event) || super.onKeyUp(keyCode, event)

    // Added in API level 14, deprecated in 29
    // it's needed because editors still use it even on API 36
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onViewClicked(focusChanged: Boolean) {
        super.onViewClicked(focusChanged)
        onNiumaInputAction(resetContext = true)
        inputDeviceManager.evaluateOnViewClicked(this)
    }

    @RequiresApi(34)
    override fun onUpdateEditorToolType(toolType: Int) {
        super.onUpdateEditorToolType(toolType)
        inputDeviceManager.evaluateOnUpdateEditorToolType(toolType, this)
    }

    fun switchToPrevIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToPreviousInputMethod()
            } else {
                @Suppress("DEPRECATION")
                inputMethodManager.switchToLastInputMethod(window.window!!.attributes.token)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the previous IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun switchToNextIme() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                switchToNextInputMethod(false)
            } else {
                @Suppress("DEPRECATION")
                inputMethodManager.switchToNextInputMethod(window.window!!.attributes.token, false)
            }
        } catch (e: Exception) {
            Timber.e(e, "Unable to switch to the next IME.")
            inputMethodManager.showInputMethodPicker()
        }
    }

    fun shareText(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val ic = currentInputConnection ?: return false
            val cs = ic.getSelectedText(0)
            if (cs == null) ic.performContextMenuAction(android.R.id.selectAll)
            return ic.performContextMenuAction(android.R.id.shareText)
        }
        return false
    }

    /** 編輯操作 */
    fun hookKeyboard(
        code: Int,
        mask: Int,
    ): Boolean {
        val ic = currentInputConnection ?: return false
        // 没按下 Ctrl 键
        if (mask != KeyEvent.META_CTRL_ON) {
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (prefs.keyboard.hookCtrlZY.getValue()) {
                when (code) {
                    KeyEvent.KEYCODE_Y -> return ic.performContextMenuAction(android.R.id.redo)
                    KeyEvent.KEYCODE_Z -> return ic.performContextMenuAction(android.R.id.undo)
                }
            }
        }

        when (code) {
            KeyEvent.KEYCODE_A -> {
                // 全选
                return if (prefs.keyboard.hookCtrlA.getValue()) {
                    ic.performContextMenuAction(android.R.id.selectAll)
                } else {
                    false
                }
            }

            KeyEvent.KEYCODE_X -> {
                // 剪切
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionStart != et.selectionEnd) return ic.performContextMenuAction(android.R.id.cut)
                    }
                }
                Timber.w("hookKeyboard cut fail")
                return false
            }

            KeyEvent.KEYCODE_C -> {
                // 复制
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        if (et.selectionStart != et.selectionEnd) {
                            ic.performContextMenuAction(android.R.id.copy).also { result ->
                                if (result) {
                                    clearTextSelection()
                                }
                                return result
                            }
                        }
                    }
                }
                Timber.w("hookKeyboard copy fail")
                return false
            }

            KeyEvent.KEYCODE_V -> {
                // 粘贴
                if (prefs.keyboard.hookCtrlCV.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et == null) {
                        Timber.d("hookKeyboard paste, et == null, try commitText")
                        val clipboardText = clipboardManager.primaryClip?.getItemAt(0)?.coerceToText(this)
                        if (ic.commitText(clipboardText, 1)) {
                            return true
                        }
                    } else if (ic.performContextMenuAction(android.R.id.paste)) {
                        return true
                    }
                    Timber.w("hookKeyboard paste fail")
                }
                return false
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (prefs.keyboard.hookCtrlLR.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = et.text.findSectionFrom(et.startOffset + et.selectionEnd)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT ->
                if (prefs.keyboard.hookCtrlLR.getValue()) {
                    val etr = ExtractedTextRequest()
                    etr.token = 0
                    val et = ic.getExtractedText(etr, 0)
                    if (et != null) {
                        val moveTo = et.text.findSectionFrom(et.startOffset + et.selectionStart, true)
                        ic.setSelection(moveTo, moveTo)
                        return true
                    }
                }
        }
        return false
    }

    fun clearTextSelection() {
        val ic = currentInputConnection ?: return
        val etr = ExtractedTextRequest().apply { token = 0 }
        val et = currentInputConnection.getExtractedText(etr, 0)
        et?.let {
            if (it.selectionStart != it.selectionEnd) {
                ic.setSelection(it.selectionEnd, it.selectionEnd)
            }
        }
    }

    internal fun updateComposingText(text: String) {
        if (text.isNotEmpty()) onNiumaInputAction()
        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        if (composingText.isNotEmpty() || text.isNotEmpty()) {
            if (!ic.getSelectedText(0).isNullOrEmpty()) {
                ic.deleteSurroundingText(1, 0)
            }
            ic.setComposingText(text, 1)
            if (text.isEmpty()) {
                ic.finishComposingText()
            }
        }
        composingText = text
        ic.endBatchEdit()
    }

    fun getActiveText(type: Int): String {
        val rimeComposition = rime.run { compositionCached }
        val selected = currentInputConnection?.getSelectedText(0)?.toString()
        val commitPreview = rimeComposition.commitTextPreview
        val preedit = rimeComposition.preedit ?: ""
        val beforeCursor = getTextAroundCursor(1024, before = true) ?: ""
        val afterCursor = getTextAroundCursor(before = false) ?: ""
        val lastCommitted = lastCommittedText

        return sequenceOf(
            when (type) {
                2 -> preedit
                3 -> selected
                4 -> beforeCursor
                1 -> lastCommitted
                else -> null
            },
            commitPreview,
            selected,
            lastCommitted,
            beforeCursor,
            afterCursor,
        )
            .firstOrNull { it?.isNotEmpty() == true } ?: ""
    }

    private fun getTextAroundCursor(
        initialStep: Int = 1024,
        before: Boolean,
    ): String? {
        val ic = currentInputConnection ?: return null
        var step = initialStep
        while (true) {
            val text = (if (before) ic.getTextBeforeCursor(step, 0) else ic.getTextAfterCursor(step, 0)) ?: return ""
            if (text.length < step) return text.toString()
            step *= 2
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    private var showingDialog: Dialog? = null

    fun showDialog(dialog: Dialog) {
        showingDialog?.dismiss()
        dialog.window?.also {
            it.attributes.apply {
                token = decorView.windowToken
                type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            }
            it.addFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            )
            it.setDimAmount(styledFloat(android.R.attr.backgroundDimAmount))
        }
        dialog.setOnDismissListener {
            showingDialog = null
        }
        dialog.show()
        showingDialog = dialog
    }
}

/**
 * A virtual Return carrying shift, control, alt, or meta is a key chord and is
 * forwarded with the modifier keys. Caps Lock or Num Lock alone is not.
 */
internal fun KeyModifiers.isReturnChord(): Boolean = shift || ctrl || alt || meta
