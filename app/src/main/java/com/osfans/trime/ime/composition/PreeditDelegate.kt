/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.composition

import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewOutlineProvider
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.ime.glass.LiquidGlassStyle
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.core.TouchEventReceiverWindow
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.horizontalPadding

class PreeditDelegate(override val di: DI) :
    DIAware,
    InputBroadcastReceiver {

    private val context: ContextThemeWrapper by instance()
    private val scope: ThemeScope by instance()
    private val rime: RimeSession by instance()

    private val theme: Theme
        get() = scope.theme

    private val embedded = ThemeManager.prefs.selectedTheme.getValue() == LiquidGlassStyle.CONFIG_ID

    /** Applies the text-background styling; re-applied on scheme refreshes. */
    private fun setupPreeditBackground(view: View) {
        if (embedded) {
            // In-panel preedit inherits the shared material; no extra rectangle/window.
            view.background = null
            view.clipToOutline = false
            return
        }
        val startRadius = view.dp(theme.preedit.topStartRadius)
        val endRadius = view.dp(theme.preedit.topEndRadius)
        val radii = if (view.layoutDirection == View.LAYOUT_DIRECTION_LTR) {
            floatArrayOf(startRadius, startRadius, endRadius, endRadius, 0f, 0f, 0f, 0f)
        } else {
            floatArrayOf(endRadius, endRadius, startRadius, startRadius, 0f, 0f, 0f, 0f)
        }
        view.background = GradientDrawable().apply {
            setColor(scope.colors.textBackColor)
            shape = GradientDrawable.RECTANGLE
            cornerRadii = radii
        }
        view.clipToOutline = true
        view.outlineProvider = ViewOutlineProvider.BACKGROUND
    }

    val ui =
        PreeditUi(
            context,
            scope,
            setupPreeditView = {
                setupPreeditBackground(this)
                horizontalPadding = if (embedded) 0 else dp(theme.preedit.horizontalPadding)
                if (embedded) {
                    minHeight = dp(24)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    includeFontPadding = false
                    setSingleLine(true)
                }
            },
            onMoveCursor = { pos -> rime.launchOnReady { it.moveCursorPos(pos) } },
        ).apply {
            root.alpha = theme.preedit.alpha
            root.visibility = View.INVISIBLE // Keep the embedded fixed header in the measure tree.
        }

    /** Restyles the text and its background after a scheme switch. */
    fun refreshColors() {
        ui.refreshColors()
        setupPreeditBackground(ui.preedit)
    }

    private val touchEventReceiverWindow by lazy { TouchEventReceiverWindow(ui.root) }

    override fun onCompositionUpdate(data: CompositionProto) {
        ui.update(data)
        ui.root.visibility = if (ui.visible) View.VISIBLE else View.INVISIBLE
        // Normal child dispatch already supports tapping to move the code caret.
        // The upstream transparent PopupWindow is needed only above the IME bounds.
        if (embedded) return
        if (data.length > 0) {
            touchEventReceiverWindow.show()
        } else {
            touchEventReceiverWindow.dismiss()
        }
    }
}
