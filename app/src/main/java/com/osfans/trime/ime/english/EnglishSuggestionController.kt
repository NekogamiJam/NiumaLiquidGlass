// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.english

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import com.osfans.trime.ime.glass.CandidateStripAppearance
import com.osfans.trime.ime.glass.CandidateStripAppearanceTarget
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Local English only. Context is exclusively this IME's committed ASCII, never editor history. */
class EnglishSuggestionController(
    private val context: Context,
    private val canShow: () -> Boolean,
    private val onPick: (String, String) -> Unit,
) : CandidateStripAppearanceTarget {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var model: ChimeModel? = null // worker-confined
    private var failed = false // worker-confined
    private var destroyed = false
    private var generation = 0L
    private var recent = ""
    private var current: List<String> = emptyList()
    private val latest = AtomicReference<Request?>(null)
    private val scheduled = AtomicBoolean(false)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private var appearance = CandidateStripAppearance(20f, Color.WHITE, Color.GRAY, null, dp(32), dp(12), null, null)
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val close = ImageButton(context).apply { contentDescription = "关闭英文候选"; setOnClickListener { reset() } }
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.TRANSPARENT); visibility = View.GONE; tag = this@EnglishSuggestionController
        addView(TextView(context).apply { text = "EN"; textSize = 11f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(36), dp(32)))
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(0, dp(32), 1f))
        addView(close, LinearLayout.LayoutParams(dp(44), dp(32)))
    }
    private data class Request(val generation: Long, val text: String, val prefix: String)
    private fun onMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    fun hide() { onMain(); generation++; latest.set(null); current = emptyList(); row.removeAllViews(); view.visibility = View.GONE }
    fun reset() { onMain(); recent = ""; hide() }
    fun committed(text: String) {
        onMain()
        if (destroyed || !canShow() || text.isEmpty() || text.length > 64 || text.any { it.code !in 9..126 }) { reset(); return }
        recent = (recent + text).takeLast(512)
        hide()
        val prefix = Regex("[A-Za-z][A-Za-z'-]*$").find(recent)?.value.orEmpty()
        if (prefix.length > 40) return
        val request = Request(generation, recent, prefix)
        // At most one pending runnable: replace the request, never enqueue every keystroke.
        latest.set(request)
        schedule()
    }
    private fun schedule() {
        if (!scheduled.compareAndSet(false, true)) return
        worker.execute {
            try { while (true) {
                val next = latest.getAndSet(null) ?: break
                val words = runCatching {
                    if (failed) return@runCatching emptyList<String>()
                    val predictor = model ?: context.assets.open("chime/cifg_int8.bin").use { input ->
                        val bytes = input.readBytes()
                        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                        require(sha == "3255dbeaf6e7fc0922afdca42ef751a22e3ad28903ab4acc9eb0022b9a704318")
                        ChimeModel.load(bytes.inputStream()).also { model = it }
                    }
                    predictor.predict(next.text, 6).filter { it.matches(Regex("[a-z]+(?:['-][a-z]+)*")) && it.startsWith(next.prefix.lowercase(java.util.Locale.ROOT)) }
                }.getOrElse { failed = true; emptyList() }
                main.post {
                    if (!destroyed && next.generation == generation && next.text == recent && canShow()) render(next, words)
                }
            } } finally {
                scheduled.set(false)
                if (latest.get() != null) schedule()
            }
        }
    }
    private fun render(request: Request, words: List<String>) {
        current = words
        row.removeAllViews()
        words.forEach { word ->
            val shown = when {
                request.prefix.length > 1 && request.prefix.all { it.isUpperCase() } -> word.uppercase(java.util.Locale.ROOT)
                request.prefix.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercaseChar() }
                else -> word
            }
            row.addView(TextView(context).apply {
                text = shown; contentDescription = "英文候选：$shown"; style(this)
                setOnClickListener {
                    if (!destroyed && request.generation == generation && request.text == recent && canShow() && word in current) {
                        hide(); onPick(shown, request.prefix)
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, appearance.rowHeightPx))
        }
        view.visibility = if (words.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun style(item: TextView) {
        item.textSize = appearance.textSizeSp; item.typeface = appearance.typeface; item.setTextColor(appearance.foreground)
        item.includeFontPadding = false; item.isSingleLine = true; item.gravity = Gravity.CENTER
        item.setPadding(appearance.itemPaddingPx, 0, appearance.itemPaddingPx, 0); item.background = appearance.ripple()
    }
    override fun applyAppearance(appearance: CandidateStripAppearance) {
        this.appearance = appearance
        (view.getChildAt(0) as TextView).setTextColor(appearance.secondary)
        close.setImageDrawable(appearance.close); close.background = appearance.ripple()
        for (i in 0 until view.childCount) view.getChildAt(i).layoutParams = view.getChildAt(i).layoutParams.apply { height = appearance.rowHeightPx }
        for (i in 0 until row.childCount) style(row.getChildAt(i) as TextView)
    }
    fun destroy() { onMain(); reset(); destroyed = true; latest.set(null); worker.shutdown(); main.removeCallbacksAndMessages(null) }
}
