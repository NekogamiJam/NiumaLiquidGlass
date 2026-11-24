/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.core

import android.annotation.SuppressLint
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.ImageView
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.core.CompositionProto
import com.osfans.trime.core.RimeMessage
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ThemeScope
import com.osfans.trime.ime.glass.GlassInputPanelController
import com.osfans.trime.ime.glass.LiquidGlassStyle
import com.osfans.trime.ime.glass.GlassDiagnosticWriter
import com.osfans.trime.ime.glass.CandidateStripAppearance
import com.osfans.trime.ime.glass.CandidateStripAppearanceTarget
import com.osfans.trime.ime.ui.VectorGlyphs
import com.osfans.trime.ime.glass.InnerStrokeLight
import com.osfans.trime.ime.glass.Result as GlassResult
import com.osfans.trime.ime.glass.Params as GlassParams
import com.osfans.trime.ime.bar.InputBarDelegate
import com.osfans.trime.ime.broadcast.EnterKeyDisplayDelegate
import com.osfans.trime.ime.broadcast.InputBroadcastReceiver
import com.osfans.trime.ime.broadcast.InputBroadcaster
import com.osfans.trime.ime.candidates.compact.CompactCandidateDelegate
import com.osfans.trime.ime.candidates.popup.PopupCandidatesMode
import com.osfans.trime.ime.composition.PreeditDelegate
import com.osfans.trime.ime.keyboard.CommonKeyboardActionListener
import com.osfans.trime.ime.keyboard.KeyboardPrefs.isLandscapeMode
import com.osfans.trime.ime.keyboard.KeyboardWindow
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.allInstances
import org.kodein.di.bindInstance
import org.kodein.di.bindSingleton
import org.kodein.di.instance
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.endToStartOf
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.startToEndOf
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable

/**
 * Successor of the old InputRoot
 */
@SuppressLint("ViewConstructor")
class InputView(
    service: TrimeInputMethodService,
    rime: RimeSession,
    scope: ThemeScope,
) : BaseInputView(service, rime, scope),
    DIAware {
    private val keyboardBackground =
        imageView {
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
    private val placeholderListener = OnClickListener { }

    private val leftPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val rightPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val bottomPaddingSpace =
        view(::View) {
            isFocusable = false
            setOnClickListener(placeholderListener)
        }

    private val updateWindowViewHeightJob: Job
    private var nativeGlass: GlassInputPanelController? = null
    private var lastNativeResult: GlassResult? = null

    override val di = DI {
        bindInstance<InputView> { this@InputView }
        bindInstance<ContextThemeWrapper> { themedContext }
        bindInstance<ThemeScope> { scope }
        bindInstance<Theme> { scope.theme }
        bindInstance<TrimeInputMethodService> { service }
        bindInstance<RimeSession> { rime }
        bindSingleton { InputBroadcaster() }
        bindSingleton { PopupDelegate(di) }
        bindSingleton { EnterKeyDisplayDelegate(di) }
        bindSingleton { PreeditDelegate(di) }
        bindSingleton { CommonKeyboardActionListener(di) }
        bindSingleton { BoardWindowManager(di) }
        bindSingleton { InputBarDelegate(di) }
        bindSingleton { CompactCandidateDelegate(di) }
        bindSingleton { KeyboardWindow(di) }
        bindSingleton { LiquidWindow(di) }
    }

    private val broadcaster: InputBroadcaster by instance()
    private val popup: PopupDelegate by instance()
    private val enterKeyDisplay: EnterKeyDisplayDelegate by instance()
    private val preedit: PreeditDelegate by instance()
    private val windowManager: BoardWindowManager by instance()
    private val inputBar: InputBarDelegate by instance()
    private val keyboardWindow: KeyboardWindow by instance()
    private val liquidWindow: LiquidWindow by instance()

    private val embeddedPreedit = ThemeManager.prefs.selectedTheme.getValue() == LiquidGlassStyle.CONFIG_ID

    private val candidatesMode by AppPrefs.defaultInstance().candidates.mode

    private val keyboardSidePadding = theme.style.keyboardPadding
    private val keyboardSidePaddingLandscape = theme.style.keyboardPaddingLand
    private val keyboardBottomPadding = theme.style.keyboardPaddingBottom
    private val keyboardBottomPaddingLandscape = theme.style.keyboardPaddingLandBottom

    private val keyboardSidePaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardSidePaddingLandscape else keyboardSidePadding
            return dp(value)
        }

    private var lastAppearanceState = Triple(false, false, false)

    private fun broadcastKeyAppearanceUpdate() {
        val composing = rime.run { statusCached.isComposing }
        val hasMenu = rime.run { hasMenu }
        val paging = rime.run { paging }
        val current = Triple(composing, hasMenu, paging)
        if (current != lastAppearanceState) {
            lastAppearanceState = current
            broadcaster.onKeyAppearanceUpdate(current.first, current.second, current.third)
        }
    }

    private val keyboardBottomPaddingPx: Int
        get() {
            val value =
                if (context.isLandscapeMode()) keyboardBottomPaddingLandscape else keyboardBottomPadding
            return dp(value)
        }

    val keyboardView: View

    /** Restyles colors after a scheme switch without rebuilding the view tree. */
    fun refreshColors() {
        keyboardBackground.imageDrawable = scope.drawable("keyboard_background")
        popup.refreshColors()
        keyboardWindow.refreshColors()
        inputBar.refreshColors()
        preedit.refreshColors()
        windowManager.refreshColors()
        styleNiumaStatusStrips()
        nativeGlass?.refresh()
    }

    init {
        // MUST call before any operation
        val receivers: List<InputBroadcastReceiver> by allInstances()
        receivers.forEach { broadcaster.addReceiver(it) }

        windowManager.cacheResidentWindow(keyboardWindow, createView = true)
        windowManager.cacheResidentWindow(liquidWindow)
        // show KeyboardWindow by default
        windowManager.attachWindow(KeyboardWindow)

        keyboardBackground.imageDrawable = scope.drawable("keyboard_background")

        keyboardView =
            constraintLayout {
                isMotionEventSplittingEnabled = true
                add(
                    keyboardBackground,
                    lParams {
                        centerInParent()
                    },
                )
                if (embeddedPreedit) {
                    // The same native panel encloses raw code and the candidate bar.
                    add(preedit.ui.root, lParams(matchParent, dp(LiquidGlassStyle.PREEDIT_ROW_DP)) {
                        topOfParent()
                        centerHorizontally()
                    })
                }
                add(
                    inputBar.view,
                    lParams(matchParent, dp(inputBar.themedHeight)) {
                        if (embeddedPreedit) below(preedit.ui.root) else topOfParent()
                        centerHorizontally()
                    },
                )
                add(
                    leftPaddingSpace,
                    lParams {
                        below(inputBar.view)
                        startOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    rightPaddingSpace,
                    lParams {
                        below(inputBar.view)
                        endOfParent()
                        bottomOfParent()
                    },
                )
                add(
                    windowManager.view,
                    lParams {
                        below(inputBar.view)
                        above(bottomPaddingSpace)
                    },
                )
                add(
                    bottomPaddingSpace,
                    lParams {
                        startToEndOf(leftPaddingSpace)
                        endToStartOf(rightPaddingSpace)
                        bottomOfParent()
                    },
                )
            }

        updateWindowViewHeightJob =
            service.lifecycleScope.launch {
                keyboardWindow.currentKeyboardHeight.collect {
                    windowManager.view.updateLayoutParams {
                        height = it
                    }
                }
            }

        updateKeyboardSize()

        if (!embeddedPreedit) {
            // Other themes retain the upstream floating composition layout.
            add(preedit.ui.root, lParams(wrapContent, wrapContent) {
                above(keyboardView)
                startOfParent()
            })
        }

        add(
            keyboardView,
            lParams(matchParent, wrapContent) {
                centerHorizontally()
                bottomOfParent()
            },
        )

        add(
            popup.root,
            lParams(matchParent, matchParent) {
                centerInParent()
            },
        )
        // Independent frontend association strip; never replaces or rewrites Rime's table menu.
        service.onNiumaInputAction(resetContext = true)
        val associationView = service.niumaAssociationView()
        (associationView.parent as? android.view.ViewGroup)?.removeView(associationView)
        with(keyboardView) {
            // Parent-relative constraints stay inside the material even when its outer ID is unset.
            add(associationView, lParams(matchParent, wrapContent) {
                topOfParent()
                centerHorizontally()
            })
        }
        val englishView = service.niumaEnglishView()
        (englishView.parent as? android.view.ViewGroup)?.removeView(englishView)
        with(keyboardView) {
            add(englishView, lParams(matchParent, wrapContent) { topOfParent(); centerHorizontally() })
        }
        // Listening status shares the same material-local overlay position as associations.
        service.cancelNiumaVoiceInput()
        val voiceStatus = service.niumaVoiceStatusView()
        (voiceStatus.parent as? android.view.ViewGroup)?.removeView(voiceStatus)
        with(keyboardView) {
            add(voiceStatus, lParams(matchParent, wrapContent) {
                topOfParent()
                centerHorizontally()
            })
        }
        keyboardView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // Geometry-only publication; does not rebuild native material or start recognition.
            lastNativeResult?.let(::publishPanelState)
        }
        styleNiumaStatusStrips()
        configureNativeGlass()
    }

    private fun styleNiumaStatusStrips() {
        val foreground = scope.colors.candidateTextColor
        for (view in listOf(service.niumaAssociationView(), service.niumaEnglishView(), service.niumaVoiceStatusView())) {
            (view.tag as? CandidateStripAppearanceTarget)?.applyAppearance(CandidateStripAppearance(
                textSizeSp = theme.style.candidateTextSize,
                foreground = foreground,
                secondary = scope.colors.commentTextColor,
                typeface = theme.fonts.candidate,
                rowHeightPx = dp(LiquidGlassStyle.PREEDIT_ROW_DP),
                itemPaddingPx = dp(theme.style.candidatePadding),
                done = VectorGlyphs.drawable(context, "vec@done", scope.colors.hilitedCandidateTextColor),
                close = VectorGlyphs.drawable(context, "vec@close", foreground),
            ))
        }
    }

    /** One shared native panel only; keycaps are cheap translucent drawing with sharp parent glyphs. */
    private fun configureNativeGlass() {
        if (ThemeManager.prefs.selectedTheme.getValue() != LiquidGlassStyle.CONFIG_ID) return
        // The optional segmented glint was visible as dotted/misaligned decoration.
        // Remove it instead of hiding the issue under a stronger full-panel border.
        keyboardView.foreground = null
        nativeGlass = GlassInputPanelController(
            panel = keyboardView,
            windowProvider = { service.window.window },
            backgroundViews = listOf(keyboardBackground),
            paramsProvider = {
                // The active palette is the single source of truth, including manual overrides.
                val dark = LiquidGlassStyle.isDarkForKeyText(scope.colors.keyTextColor)
                val material = LiquidGlassStyle.panel(dark)
                val radius = 18f * resources.displayMetrics.density
                GlassParams(
                    blur = material.blur,
                    color1 = material.maskColor,
                    color2 = material.maskColor,
                    opacity1 = material.maskOpacity,
                    intensity = material.refractionIntensity,
                    range = material.refractionRange,
                    cornerTopLeftPx = radius,
                    cornerTopRightPx = radius,
                    cornerBottomLeftPx = 0f,
                    cornerBottomRightPx = 0f,
                    fallbackColor = material.fallbackColor,
                    innerStroke = if (LiquidGlassStyle.NATIVE_INNER_STROKE_ENABLED)
                        InnerStrokeLight(intensity = if (dark) 0.45f else 0.55f) else null,
                )
            },
            onResult = { result ->
                lastNativeResult = result
                publishPanelState(result)
            },
        ).also { it.attach() }
    }

    /** Rendering geometry only; emitted after empty/nonempty changes without reapplying material. */
    private fun publishPanelState(result: GlassResult) {
                runCatching {
                    val location = IntArray(2)
                    keyboardView.getLocationOnScreen(location)
                    val material = LiquidGlassStyle.panel(
                        LiquidGlassStyle.isDarkForKeyText(scope.colors.keyTextColor))
                    val preeditLocation = IntArray(2)
                    preedit.ui.root.getLocationOnScreen(preeditLocation)
                    val preeditInside = preeditLocation[0] >= location[0] &&
                        preeditLocation[1] >= location[1] &&
                        preeditLocation[0] + preedit.ui.root.width <= location[0] + keyboardView.width &&
                        preeditLocation[1] + preedit.ui.root.height <= location[1] + keyboardView.height
                    fun insidePanel(view: View): Boolean {
                        val p = IntArray(2)
                        view.getLocationOnScreen(p)
                        return view.parent === keyboardView && p[0] >= location[0] && p[1] >= location[1] &&
                            p[0] + view.width <= location[0] + keyboardView.width &&
                            p[1] + view.height <= location[1] + keyboardView.height
                    }
                    val associationStrip = service.niumaAssociationView()
                    val voiceStrip = service.niumaVoiceStatusView()
                    val state = org.json.JSONObject()
                        .put("association_strip_parent_inside_panel", associationStrip.parent === keyboardView)
                        .put("association_strip_visible", associationStrip.visibility == View.VISIBLE)
                        .put("association_strip_bounds_inside_panel", if (associationStrip.visibility == View.VISIBLE) insidePanel(associationStrip) else org.json.JSONObject.NULL)
                        .put("voice_strip_parent_inside_panel", voiceStrip.parent === keyboardView)
                        .put("voice_strip_visible", voiceStrip.visibility == View.VISIBLE)
                        .put("voice_strip_bounds_inside_panel", if (voiceStrip.visibility == View.VISIBLE) insidePanel(voiceStrip) else org.json.JSONObject.NULL)
                        .put("preedit_embedded", embeddedPreedit)
                        .put("preedit_visible", preedit.ui.visible)
                        .put("preedit_bounds_inside_panel", if (preedit.ui.visible) preeditInside else org.json.JSONObject.NULL)
                        .put("appearance_recipe", 5)
                        .put("fixed_preedit_row_dp", LiquidGlassStyle.PREEDIT_ROW_DP)
                        .put("native_inner_stroke_requested", result.innerStrokeRequested)
                        .put("native_inner_stroke_applied", result.innerStrokeApplied)
                        .put("native_inner_stroke_failure", result.innerStrokeFailure ?: org.json.JSONObject.NULL)
                        .put("native_key_materials", 0)
                        .put("panel_glints_enabled", false)
                        .put("panel_blur", material.blur)
                        .put("panel_mask_opacity", material.maskOpacity)
                        .put("panel_mask_argb", Integer.toHexString(material.maskColor))
                        .put("refraction_intensity", material.refractionIntensity)
                        .put("refraction_range", material.refractionRange)
                        .put("success", result.success).put("available", result.available)
                        .put("message", result.message)
                        .put("optional_failures", org.json.JSONArray(result.optionalFailures))
                        .put("window_type", service.window.window?.attributes?.type)
                        .put("hardware_accelerated", keyboardView.isHardwareAccelerated)
                        .put("theme_id", ThemeManager.prefs.selectedTheme.getValue())
                        .put("x", location[0]).put("y", location[1])
                        .put("width", keyboardView.width).put("height", keyboardView.height)
                    GlassDiagnosticWriter.publish(service.filesDir, state.toString(2))
                }
    }

    private fun updateKeyboardSize() {
        bottomPaddingSpace.updateLayoutParams {
            height = keyboardBottomPaddingPx
        }
        val sidePadding = keyboardSidePaddingPx
        val unset = LayoutParams.UNSET
        if (sidePadding == 0) {
            // hide side padding space views when unnecessary
            leftPaddingSpace.visibility = View.GONE
            rightPaddingSpace.visibility = View.GONE
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToEnd = unset
                endToStart = unset
                startOfParent()
                endOfParent()
            }
        } else {
            leftPaddingSpace.visibility = View.VISIBLE
            rightPaddingSpace.visibility = View.VISIBLE
            leftPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            rightPaddingSpace.updateLayoutParams {
                width = sidePadding
            }
            windowManager.view.updateLayoutParams<LayoutParams> {
                startToStart = unset
                endToEnd = unset
                startToEndOf(leftPaddingSpace)
                endToStartOf(rightPaddingSpace)
            }
        }
        if (embeddedPreedit) {
            // Keep letters inside the rounded upper corner, not on its clipping edge.
            preedit.ui.root.setPadding(dp(14), dp(6), dp(14), dp(2))
        } else {
            preedit.ui.root.setPadding(sidePadding, 0, sidePadding, 0)
        }
        inputBar.view.setPadding(sidePadding, 0, sidePadding, 0)
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        bottomPaddingSpace.updateLayoutParams<LayoutParams> {
            bottomMargin = getNavBarBottomInset(insets)
        }
        return insets
    }

    fun startInput(
        info: EditorInfo,
        restarting: Boolean = false,
    ) {
        updateEnterKeyLabel(info)
        broadcaster.onStartInput(info)
        if (!restarting) {
            windowManager.attachWindow(KeyboardWindow)
        }
    }

    fun updateEnterKeyLabel(info: EditorInfo) {
        enterKeyDisplay.updateLabelOnEditorInfo(info)
    }

    override fun handleRimeMessage(it: RimeMessage<*>) {
        when (it) {
            is RimeMessage.SchemaMessage -> {
                broadcaster.onRimeSchemaUpdated(it.data)

                windowManager.attachWindow(KeyboardWindow)
            }

            is RimeMessage.OptionMessage -> {
                broadcaster.onRimeOptionUpdated(it.data)

                if (it.data.option == "_liquid_keyboard") {
                    ContextCompat.getMainExecutor(service).execute {
                        windowManager.attachWindow(LiquidWindow)
                        liquidWindow.setDataByIndex(0)
                    }
                }
            }

            is RimeMessage.CompositionMessage -> {
                val data = if (candidatesMode == PopupCandidatesMode.ALWAYS_SHOW) {
                    CompositionProto()
                } else {
                    it.data
                }
                val wasVisible = preedit.ui.visible
                broadcaster.onCompositionUpdate(data)
                if (embeddedPreedit && wasVisible != preedit.ui.visible) {
                    // INVISIBLE keeps identical geometry. A diagnostic update is not a material rebuild.
                    keyboardView.post { lastNativeResult?.let(::publishPanelState) }
                }
            }

            is RimeMessage.BulkCandidatesMessage -> {
                broadcaster.onCandidateListUpdate(it.data)
            }

            else -> {}
        }
        broadcastKeyAppearanceUpdate()
    }

    fun updateSelection(
        start: Int,
        end: Int,
    ) {
        broadcaster.onSelectionUpdate(start, end)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun handleInlineSuggestions(response: InlineSuggestionsResponse): Boolean = inputBar.handleInlineSuggestions(response)

    override fun onDetachedFromWindow() {
        nativeGlass?.detach()
        nativeGlass = null
        ViewCompat.setOnApplyWindowInsetsListener(this, null)
        // cancel the notification job and clear all broadcast receivers,
        // implies that InputView should not be attached again after detached.
        updateWindowViewHeightJob.cancel()
        popup.root.removeAllViews()
        broadcaster.clear()
        super.onDetachedFromWindow()
    }
}
