/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original modification: Trime-facing explicit voice-input session coordinator.
 */
package com.osfans.trime.ime.voice

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.ImageButton
import android.graphics.Color
import android.text.TextUtils
import com.osfans.trime.ime.glass.CandidateStripAppearance
import com.osfans.trime.ime.glass.CandidateStripAppearanceTarget
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.os.Handler
import android.os.Looper

/**
 * Coordinates an explicit microphone tap with current IME input-session safety.
 * The host must declare VoicePermissionActivity with exported=false and RECORD_AUDIO permission,
 * call onInputSessionChanged() at input start/finish, call cancel() before non-voice key actions,
 * and call destroy() when the owning IME component is permanently torn down. Provide
 * canStartForComposition as a fail-closed predicate that is true only when the encoding buffer is empty.
 * This class never writes to InputConnection; only onFinalText receives final text for caller-controlled handling.
 */
class NiumaVoiceController(
    private val service: InputMethodService,
    private val canStartForComposition: () -> Boolean,
    private val onFinalText: (String) -> Unit,
) : CandidateStripAppearanceTarget {
    private data class Session(
        val epoch: Long,
        val editorInfo: EditorInfo,
        val inputConnection: InputConnection,
        val recognizer: OfflineVoiceInput,
        var pendingText: String? = null,
        var submitted: Boolean = false,
        var terminal: Boolean = false,
    )

    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { timeoutSession() }
    private var active: Session? = null
    private var epoch = 0L
    private var destroyed = false

    private var appearance = CandidateStripAppearance(20f, Color.GRAY, Color.GRAY, null, dp(32), dp(12), null, null)
    private val statusText = TextView(service).apply {
        textSize = 20f
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
    }
    private val stopButton = ImageButton(service).apply {
        contentDescription = "完成听写"
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setOnClickListener { stop() }
    }
    private val cancelButton = ImageButton(service).apply {
        contentDescription = "取消听写"
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setOnClickListener { cancel() }
    }

    /** Material-local, fixed-height transparent candidate-like status strip. */
    val statusView: View = LinearLayout(service).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        tag = this@NiumaVoiceController
        addView(statusText, LinearLayout.LayoutParams(0, appearance.rowHeightPx, 1f))
        addView(stopButton, LinearLayout.LayoutParams(dp(44), appearance.rowHeightPx))
        addView(cancelButton, LinearLayout.LayoutParams(dp(44), appearance.rowHeightPx))
        visibility = View.GONE
    }

    override fun applyAppearance(appearance: CandidateStripAppearance) {
        this.appearance = appearance
        statusView.setBackgroundColor(Color.TRANSPARENT)
        statusText.textSize = appearance.textSizeSp
        statusText.typeface = appearance.typeface
        statusText.setTextColor(appearance.foreground)
        statusText.setPadding(appearance.itemPaddingPx, 0, appearance.itemPaddingPx, 0)
        stopButton.setImageDrawable(appearance.done)
        cancelButton.setImageDrawable(appearance.close)
        stopButton.background = appearance.ripple()
        cancelButton.background = appearance.ripple()
        val row = statusView as LinearLayout
        for (i in 0 until row.childCount) row.getChildAt(i).layoutParams = row.getChildAt(i).layoutParams.apply { height = appearance.rowHeightPx }
    }

    /** Call only from an explicit microphone click. Never starts recording after a permission screen returns. */
    fun start() {
        requireMainThread()
        if (destroyed) return
        // A new explicit request supersedes an older recording even if a later guard rejects it.
        cancelActive()
        if (!compositionIsEmpty()) {
            toast("请先清空当前编码，再使用语音输入")
            return
        }
        val editorInfo = service.currentInputEditorInfo
        if (editorInfo == null || isSensitiveField(editorInfo)) {
            toast("当前输入框不支持语音输入")
            return
        }
        val connection = service.currentInputConnection
        if (connection == null) {
            toast("当前输入连接不可用")
            return
        }
        if (!VoiceConsent.canStart(service)) {
            try {
                service.startActivity(
                    Intent(service, VoicePermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Throwable) {
                toast("无法打开语音权限说明")
            }
            return
        }

        val sessionEpoch = epoch
        var created: Session? = null
        stopButton.contentDescription = "结束听写，预览后确认"
        val recognizer = OfflineVoiceInput(service) { event ->
            val session = created
            if (session != null) handleEvent(session, event)
        }
        val session = Session(sessionEpoch, editorInfo, connection, recognizer)
        created = session
        active = session
        val startResult = try { recognizer.start() }
        catch (_: Throwable) { null }
        if (startResult?.started != true && active === session) {
            endSession(session)
            toast("无法启动离线听写，请检查模型与录音权限")
            return
        }
        if (active === session) {
            handler.removeCallbacks(timeout)
            handler.postDelayed(timeout, MAX_SESSION_MILLIS)
        }
    }

    /** Request final recognition results while keeping the strip visible. */
    fun stop() {
        requireMainThread()
        val session = active ?: return
        if (session.pendingText != null) confirm(session) else session.recognizer.stop()
    }

    /** Explicitly abort recognition and hide the status strip. */
    fun cancel() {
        requireMainThread()
        cancelActive()
    }

    /** Invoke at each onStartInput/onFinishInput boundary to invalidate all captured references. */
    fun onInputSessionChanged() {
        requireMainThread()
        epoch++
        cancelActive()
    }

    /** Permanently releases this controller; it cannot be restarted. */
    fun destroy() {
        requireMainThread()
        if (destroyed) return
        destroyed = true
        epoch++
        cancelActive()
        statusView.visibility = View.GONE
        statusText.text = ""
        stopButton.setOnClickListener(null)
        cancelButton.setOnClickListener(null)
    }

    private fun handleEvent(session: Session, event: VoiceEvent) {
        if (destroyed || active !== session || session.terminal || session.epoch != epoch) return
        when (event.state) {
            VoiceState.LOADING -> showStatus("正在加载离线模型…")
            VoiceState.LISTENING -> showStatus("本地听写中…")
            VoiceState.PARTIAL -> showStatus(event.results.firstOrNull()?.takeIf { it.isNotBlank() }?.let { "听写：$it" } ?: "本地听写中…")
            VoiceState.STOPPING -> showStatus("正在结束听写…")
            VoiceState.CANCELLED -> endSession(session)
            VoiceState.ERROR -> {
                endSession(session)
                toast(errorMessage(event.errorCode))
            }
            VoiceState.FINISHED -> {
                val finalText = event.results.firstOrNull()
                handler.removeCallbacks(timeout)
                if (finalText.isNullOrBlank()) {
                    endSession(session)
                    toast("未检测到有效语音")
                } else if (sessionStillMatches(session) && compositionIsEmpty()) {
                    session.pendingText = finalText
                    stopButton.contentDescription = "确认听写结果并上屏"
                    showStatus("确认：$finalText")
                } else endSession(session)
            }
        }
    }

    private fun confirm(session: Session) {
        val text = session.pendingText ?: return
        val allowed = !session.submitted && sessionStillMatches(session) && compositionIsEmpty() &&
            VoiceConsent.canStart(service) && !isSensitiveField(session.editorInfo)
        session.pendingText = null
        endSession(session)
        if (allowed) {
            session.submitted = true
            try { onFinalText(text) } catch (_: Throwable) { toast("听写结果处理失败") }
        }
    }

    private fun sessionStillMatches(session: Session): Boolean =
        !destroyed && session.epoch == epoch && active === session &&
            service.currentInputEditorInfo === session.editorInfo &&
            service.currentInputConnection === session.inputConnection

    private fun compositionIsEmpty(): Boolean = try { canStartForComposition() }
    catch (_: Throwable) { false }

    private fun isSensitiveField(info: EditorInfo): Boolean {
        if ((info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return true
        val inputClass = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return (inputClass == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )) || (inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun timeoutSession() {
        val session = active ?: return
        if (destroyed || session.epoch != epoch) return
        session.recognizer.stop()
        toast("已达到单次听写时限，请确认结果或取消")
    }

    private fun cancelActive() {
        handler.removeCallbacks(timeout)
        val session = active
        active = null
        if (session != null) {
            session.pendingText = null
            session.terminal = true;
            session.recognizer.cancel()
            session.recognizer.destroy()
        }
        statusView.visibility = View.GONE
        statusText.text = ""
    }

    private fun endSession(session: Session) {
        if (session.terminal) return
        session.terminal = true
        session.pendingText = null
        if (active === session) active = null
        handler.removeCallbacks(timeout)
        session.recognizer.destroy()
        statusView.visibility = View.GONE
        statusText.text = ""
    }

    private fun showStatus(text: String) {
        // Already styled from the active candidate palette; never paints an independent rectangle.
        statusText.text = text
        statusView.visibility = View.VISIBLE
    }

    private fun errorMessage(code: Int?): String = when (code) {
        1101 -> "离线模型装载失败，请检查存储空间或重新安装"
        1102 -> "无法录音，请检查麦克风权限及系统隐私开关"
        1103 -> "录音处理不及时，已停止，请缩短单次听写"
        1104 -> "离线识别失败，已停止且未提交文字"
        6 -> "未检测到语音，请重试"
        else -> "离线听写暂不可用，请重试"
    }

    private fun toast(message: String) {
        Toast.makeText(service, message, Toast.LENGTH_SHORT).show()
    }

    private fun dp(value: Int): Int = (value * service.resources.displayMetrics.density + 0.5f).toInt()

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "NiumaVoiceController must be used on the main thread"
        }
    }

    private companion object {
        const val MAX_SESSION_MILLIS = 60_000L
    }
}
