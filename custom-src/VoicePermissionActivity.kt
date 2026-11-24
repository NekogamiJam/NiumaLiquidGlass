/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original modification: standalone first-use voice disclosure and runtime-permission gate.
 * Manifest integration note: declare this Activity with android:exported="false" from the app.
 */
package com.osfans.trime.ime.voice

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** App-private notice/permission state shared with the voice integration caller. */
object VoiceConsent {
    private const val PREFS_NAME = "niuma_voice"
    private const val KEY_CONSENT = "consent"
    private const val KEY_VERSION = "version"
    const val CURRENT_VERSION: Int = 2

    /** True only when this version of the disclosure was explicitly accepted. */
    @JvmStatic
    fun hasConsent(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CONSENT, false) &&
            prefs.getInt(KEY_VERSION, 0) == CURRENT_VERSION
    }

    /** True only when both the disclosure and Android runtime permission are currently satisfied. */
    @JvmStatic
    fun canStart(context: Context): Boolean = hasConsent(context) &&
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    internal fun saveConsent(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_CONSENT, true)
            .putInt(KEY_VERSION, CURRENT_VERSION)
            .apply()
    }
}

/**
 * First-use disclosure shown after the user explicitly selects the keyboard's voice entry.
 * This Activity only asks for RECORD_AUDIO permission; it never creates a recognizer or records.
 * After permission grant it finishes and requires a second explicit microphone tap from the caller.
 * Caller must add this Activity to the app manifest with exported=false.
 */
class VoicePermissionActivity : Activity() {
    companion object {
        private const val REQUEST_RECORD_AUDIO = 41
    }

    private var permissionRequestPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showDisclosure()
    }

    private fun showDisclosure() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "语音输入说明"
            textSize = 20f
        }
        val details = TextView(this).apply {
            text = "本键盘使用手机本地 SenseVoice 2024 int8 与 VAD 进行听写，不把音频发送到系统或云端识别服务。首次使用会在后台准备内置模型。\n\n录音与待确认文字只暂存在内存，不保存录音或识别日志。点勾结束录音，预览后再点勾确认上屏；取消或切换输入框会丢弃本次结果。单次最长约60秒，空闲后释放模型。\n\n点击“同意并继续”后申请正常录音权限。授权完成后请返回输入框，再次点击麦克风或长按空格才会开始听写。"
            textSize = 16f
            setPadding(0, dp(16), 0, dp(24))
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancel = Button(this).apply {
            text = "取消"
            setOnClickListener { finish() }
        }
        val agree = Button(this).apply {
            text = "同意并继续"
            setOnClickListener {
                if (permissionRequestPending) return@setOnClickListener
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    acceptAndReturn()
                } else {
                    permissionRequestPending = true
                    isEnabled = false
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                }
            }
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(agree, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        content.addView(title, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        content.addView(details, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        content.addView(actions, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setContentView(content)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        permissionRequestPending = false
        val granted = permissions.indices.any { index ->
            permissions[index] == Manifest.permission.RECORD_AUDIO &&
                grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED
        }
        if (granted && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            acceptAndReturn()
        } else {
            Toast.makeText(
                this,
                "未获得录音权限，语音输入未启用。如系统不再显示权限请求，可在系统设置中手动开启；本组件不会自动打开设置。",
                Toast.LENGTH_LONG,
            ).show()
            finish()
        }
    }

    private fun acceptAndReturn() {
        VoiceConsent.saveConsent(this)
        Toast.makeText(this, "设置完成。返回输入框后，请再次点击麦克风开始识别。", Toast.LENGTH_LONG).show()
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
