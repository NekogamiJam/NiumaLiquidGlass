package com.osfans.trime.debug

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AtomicFile
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import com.osfans.trime.ime.english.ChimeModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Debug/DUMP-only public fixtures. No microphone, clipboard, surrounding text, or editor text logs. */
class EnglishProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getStringExtra("kind") == "editor") {
            val expected = intent.getStringExtra("expected").takeIf { it in setOf("thank you ", "thank ", "you ", "hello ", "工") }.orEmpty()
            val field = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                hint = "Public English candidate fixture"; minLines = 5
            }
            fun report() = publish("english-editor.json", JSONObject().put("length", field.length()).put("expected_match", field.text.toString() == expected))
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { report() }
                override fun afterTextChanged(s: Editable?) {}
            })
            setContentView(field); report(); field.requestFocus()
            field.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }, 500)
        } else {
            setContentView(TextView(this).apply { text = "Public Chime model check; no recording" })
            Thread {
                val result = runCatching {
                    val model = assets.open("chime/cifg_int8.bin").use { ChimeModel.load(it) }
                    val cases = listOf("thank " to "you", "good " to "morning", "please let me " to "know", "see you tom" to "tomorrow")
                    val rows = JSONArray()
                    for ((text, expected) in cases) {
                        val t = SystemClock.elapsedRealtimeNanos()
                        val words = model.predict(text, 6)
                        rows.put(JSONObject().put("fixture", text).put("words", JSONArray(words)).put("expected_top1", words.firstOrNull() == expected).put("milliseconds", (SystemClock.elapsedRealtimeNanos()-t)/1e6))
                    }
                    JSONObject().put("success", (0 until rows.length()).all { rows.getJSONObject(it).getBoolean("expected_top1") }).put("cases", rows)
                }.getOrElse { JSONObject().put("success", false).put("failure_type", it.javaClass.simpleName) }
                publish("english-native.json", result)
            }.start()
        }
    }
    private fun publish(name: String, data: JSONObject) {
        val file = AtomicFile(File(filesDir, name)); var stream: java.io.FileOutputStream? = null
        try { stream = file.startWrite(); stream.write(data.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (_: Exception) { file.failWrite(stream) }
    }
}
