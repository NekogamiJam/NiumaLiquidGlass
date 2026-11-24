/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Original modification: lifecycle-safe controller for a Trime IME glass panel.
 */
package com.osfans.trime.ime.glass

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.View.OnAttachStateChangeListener
import android.view.View.OnLayoutChangeListener
import android.view.ViewParent
import android.view.Window
import android.widget.ImageView
import java.util.IdentityHashMap

/** Theme for the panel's translucent material mask and opaque failure fallback. */
enum class GlassPanelTone { LIGHT, DARK }

/**
 * Owns glass setup for one visible keyboard panel only.
 *
 * `windowProvider` MUST return this IME's Window, never an Activity/settings Window. Ancestor and
 * explicitly supplied background views are made transparent while attached, then their prior
 * backgrounds/drawables are restored on detach. Only listed views are cleared; no child traversal
 * is performed, so keycap backgrounds remain intact unless the caller explicitly lists them.
 * All public lifecycle methods must run on the main thread. This class does not configure window
 * type, manifest flags, permissions, or special unsupported window modes; it does not simulate
 * refraction or claim that HALF_WINDOW is supported.
 */
class GlassInputPanelController(
    private val panel: View,
    private val windowProvider: () -> Window?,
    backgroundViews: List<View> = emptyList(),
    private val paramsProvider: (() -> Params)? = null,
    private val onResult: ((Result) -> Unit)? = null,
    private val tone: GlassPanelTone = GlassPanelTone.LIGHT,
    private val debounceMillis: Long = 80L,
) {
    private val selectedBackgrounds = backgroundViews.toList()
    private val handler = Handler(Looper.getMainLooper())
    private val viewBackgrounds = IdentityHashMap<View, Drawable?>()
    private val imageDrawables = IdentityHashMap<ImageView, Drawable?>()
    private val applyRunnable = Runnable { applyNow() }
    private var observedWindow: Window? = null
    private var windowBackground: Drawable? = null
    private var windowCaptured = false
    private var attached = false
    private var disposed = false
    private var listenersInstalled = false
    private val applyGate = MaterialApplyGate<Params>()

    private val layoutListener = OnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
        // Translation/unchanged layout during the IME slide does not rebuild a material.
        if (attached && !disposed && (r-l != oldR-oldL || b-t != oldB-oldT)) scheduleApply()
    }
    private val attachListener = object : OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            applyGate.invalidate()
            if (attached && !disposed) scheduleApply()
        }
        override fun onViewDetachedFromWindow(v: View) {
            handler.removeCallbacks(applyRunnable)
            applyGate.invalidate()
            if (attached && !disposed) NativeGlassRenderer.clear(panel, fallbackColor())
        }
    }

    /** Starts ownership, installs at most one pair of listeners, and schedules a debounced apply. */
    fun attach() {
        requireMainThread()
        check(!disposed) { "Controller has been detached permanently" }
        if (!attached) {
            attached = true
            capturePanelBackground()
            if (!listenersInstalled) {
                panel.addOnLayoutChangeListener(layoutListener)
                panel.addOnAttachStateChangeListener(attachListener)
                listenersInstalled = true
            }
        }
        scheduleApply()
    }

    /** Re-clears only configured transparent backgrounds and reapplies after a short layout debounce. */
    fun refresh() {
        requireMainThread()
        if (disposed || !attached) return
        scheduleApply()
    }

    /** Cancels pending work/listeners, clears native state and restores backgrounds captured at attach. */
    fun detach() {
        requireMainThread()
        if (disposed) return
        disposed = true
        attached = false
        applyGate.invalidate()
        handler.removeCallbacks(applyRunnable)
        if (listenersInstalled) {
            panel.removeOnLayoutChangeListener(layoutListener)
            panel.removeOnAttachStateChangeListener(attachListener)
            listenersInstalled = false
        }
        NativeGlassRenderer.clear(panel, fallbackColor())
        restoreWindow()
        restoreViewBackgrounds()
        restoreImageDrawables()
        observedWindow = null
    }

    private fun scheduleApply() {
        handler.removeCallbacks(applyRunnable)
        handler.postDelayed(applyRunnable, debounceMillis.coerceAtLeast(0L))
    }

    private fun applyNow() {
        if (!attached || disposed || !panel.isAttachedToWindow || panel.width <= 0 || panel.height <= 0) return
        val window = try { windowProvider() } catch (_: Throwable) { null }
        trackWindow(window)
        clearImeWindowAndAncestors(window)
        clearSelectedBackgrounds()
        val params = try { paramsProvider?.invoke() ?: defaultParams() }
        catch (_: Throwable) { defaultParams() }
        if (!applyGate.needsApply(params, panel.width, panel.height, window)) return
        val result = NativeGlassRenderer.apply(panel, params)
        if (result.success) applyGate.applied(params, panel.width, panel.height, window)
        else panel.setBackgroundColor(params.fallbackColor)
        try { onResult?.invoke(result) } catch (_: Throwable) { /* Client callback cannot break UI dispatch. */ }
    }

    private fun trackWindow(window: Window?) {
        if (observedWindow === window && (windowCaptured || window == null)) return
        restoreWindow()
        observedWindow = window
        if (window != null) {
            windowBackground = window.decorView.background
            windowCaptured = true
            captureViewBackground(window.decorView)
        }
    }

    private fun clearImeWindowAndAncestors(window: Window?) {
        if (window != null) {
            // Caller contract limits this Window to the IME; never resolve or mutate another Window.
            if (!isTransparent(window.decorView)) window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearBackgroundIfNeeded(window.decorView)
        }
        var parent: ViewParent? = panel.parent
        while (parent is View) {
            captureViewBackground(parent)
            clearBackgroundIfNeeded(parent)
            parent = parent.parent
        }
    }

    private fun clearSelectedBackgrounds() {
        selectedBackgrounds.forEach { view ->
            if (view is ImageView) {
                if (!imageDrawables.containsKey(view)) imageDrawables[view] = view.drawable
                if (view.drawable != null) view.setImageDrawable(null)
            } else {
                captureViewBackground(view)
                clearBackgroundIfNeeded(view)
            }
        }
    }

    private fun isTransparent(view: View): Boolean =
        view.background == null || (view.background as? ColorDrawable)?.color == Color.TRANSPARENT

    private fun clearBackgroundIfNeeded(view: View) {
        if (!isTransparent(view)) view.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun capturePanelBackground() = captureViewBackground(panel)

    private fun captureViewBackground(view: View) {
        if (!viewBackgrounds.containsKey(view)) viewBackgrounds[view] = view.background
    }

    private fun restoreWindow() {
        if (windowCaptured) {
            try { observedWindow?.setBackgroundDrawable(windowBackground) } catch (_: Throwable) { }
        }
        windowBackground = null
        windowCaptured = false
    }

    private fun restoreViewBackgrounds() {
        viewBackgrounds.forEach { (view, background) ->
            try { view.background = background } catch (_: Throwable) { }
        }
        viewBackgrounds.clear()
    }

    private fun restoreImageDrawables() {
        imageDrawables.forEach { (view, drawable) ->
            try { view.setImageDrawable(drawable) } catch (_: Throwable) { }
        }
        imageDrawables.clear()
    }

    private fun defaultParams(): Params {
        val dark = tone == GlassPanelTone.DARK
        val mask = if (dark) 0xff202124.toInt() else 0xfff2f2f2.toInt()
        val fallback = if (dark) 0xff202124.toInt() else 0xfff2f2f2.toInt()
        val radiusPx = 18f * panel.resources.displayMetrics.density
        return Params(
            blur = 0.008f,
            saturation = 20f,
            color1 = mask,
            opacity1 = 0.10f,
            color2 = mask,
            opacity2 = 0f,
            finalOpacity = 1f,
            intensity = 120f,
            range = 32f,
            cornerTopLeftPx = radiusPx,
            cornerTopRightPx = radiusPx,
            cornerBottomLeftPx = 0f,
            cornerBottomRightPx = 0f,
            fallbackColor = fallback,
            style = 0,
            content = 2,
        )
    }

    private fun fallbackColor(): Int = try { (paramsProvider?.invoke() ?: defaultParams()).fallbackColor }
    catch (_: Throwable) { defaultParams().fallbackColor }

    private fun requireMainThread() {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "GlassInputPanelController must be used on the main thread"
        }
    }
}
