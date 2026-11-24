// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.ime.association

import android.content.Context
import android.content.res.Configuration
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
import org.json.JSONObject
import java.util.concurrent.Executors

/** Separate frontend suggestions. Only appearance is shared with Rime's candidate row. */
class NiumaAssociationController(
    private val context: Context,
    private val canShow: () -> Boolean,
    private val onPick: (String) -> Unit,
) : CandidateStripAppearanceTarget {
    private val main = Handler(Looper.getMainLooper())
    private val loader = Executors.newSingleThreadExecutor()
    private val state = AssociationState()
    private var destroyed = false
    private val density = context.resources.displayMetrics.density
    private fun dp(n: Int) = (n * density).toInt()
    private val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private var appearance = CandidateStripAppearance(20f, if (dark) Color.WHITE else Color.rgb(30,30,30), Color.GRAY, null, dp(32), dp(12), null, null)
    private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val label = TextView(context).apply { text = "联想"; textSize = 11f; gravity = Gravity.CENTER; includeFontPadding = false }
    private val close = ImageButton(context).apply {
        contentDescription = "关闭本次联想"
        setPadding(dp(4), dp(4), dp(4), dp(4))
        setOnClickListener { reset() }
    }
    val view = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.TRANSPARENT)
        visibility = View.GONE
        tag = this@NiumaAssociationController
        addView(label, LinearLayout.LayoutParams(dp(36), appearance.rowHeightPx))
        addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(row) }, LinearLayout.LayoutParams(0, appearance.rowHeightPx, 1f))
        addView(close, LinearLayout.LayoutParams(dp(44), appearance.rowHeightPx))
    }

    override fun applyAppearance(appearance: CandidateStripAppearance) {
        this.appearance = appearance
        view.setBackgroundColor(Color.TRANSPARENT)
        label.setTextColor(appearance.secondary)
        label.typeface = appearance.typeface
        close.setImageDrawable(appearance.close)
        close.background = appearance.ripple()
        for (i in 0 until view.childCount) view.getChildAt(i).layoutParams = view.getChildAt(i).layoutParams.apply { height = appearance.rowHeightPx }
        for (i in 0 until row.childCount) (row.getChildAt(i) as? TextView)?.let { styleItem(it) }
    }
    private fun styleItem(item: TextView) {
        item.textSize = appearance.textSizeSp
        item.typeface = appearance.typeface
        item.setTextColor(appearance.foreground)
        item.includeFontPadding = false
        item.isSingleLine = true
        item.gravity = Gravity.CENTER
        item.setPadding(appearance.itemPaddingPx, 0, appearance.itemPaddingPx, 0)
        item.background = appearance.ripple()
        item.layoutParams?.let { item.layoutParams = it.apply { height = appearance.rowHeightPx } }
    }
    init {
        loader.execute {
            val index = runCatching {
                val root = context.assets.open("niuma/niuma_associations.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                require(root.getInt("format") == 1)
                val entries = root.getJSONObject("entries")
                buildMap<String,List<String>> {
                    entries.keys().forEach { key ->
                        val array = entries.getJSONArray(key)
                        put(key, (0 until array.length()).map { array.getString(it) })
                    }
                }
            }.getOrDefault(emptyMap())
            main.post { if (!destroyed) state.installIndex(index) }
        }
    }
    private fun onMain() { check(Looper.myLooper() == Looper.getMainLooper()) }
    fun hide() { onMain(); state.onInputStarted(); view.visibility = View.GONE; row.removeAllViews() }
    fun reset() { onMain(); state.reset(); view.visibility = View.GONE; row.removeAllViews() }
    fun onCommitted(text: String) {
        onMain()
        if (destroyed || !canShow()) { reset(); return }
        state.onCommitted(text)
        row.removeAllViews()
        state.suggestions.forEach { candidate ->
            row.addView(TextView(context).apply {
                this.text = candidate
                styleItem(this)
                contentDescription = "联想续写：$candidate"
                setOnClickListener {
                    if (!destroyed && canShow() && candidate in state.suggestions) {
                        hide()
                        onPick(candidate)
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, appearance.rowHeightPx))
        }
        view.visibility = if (state.suggestions.isEmpty()) View.GONE else View.VISIBLE
    }
    fun destroy() { onMain(); destroyed = true; loader.shutdownNow(); main.removeCallbacksAndMessages(null); reset() }
}
