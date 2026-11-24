/*
 * SPDX-FileCopyrightText: 2015 - 2026 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import androidx.core.graphics.drawable.DrawableCompat
import com.mikepenz.iconics.IconicsDrawable
import com.mikepenz.iconics.utils.sizeDp
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.ime.glass.GlassKeySurface
import com.osfans.trime.ime.glass.LiquidGlassStyle
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.popup.PopupAction
import com.osfans.trime.ime.popup.PopupDelegate
import com.osfans.trime.ime.ui.VectorGlyphs
import com.osfans.trime.util.sp
import splitties.bitflags.hasFlag
import splitties.dimensions.dp
import timber.log.Timber

@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
class KeyView(
    context: Context,
    private val theme: Theme,
    private val key: Key,
    private val keyboard: Keyboard,
    private val keyboardView: KeyboardView,
    private val keyboardActionListener: KeyboardActionListener,
) : GestureFrame(context) {

    private val service: TrimeInputMethodService
        get() = keyboardView.service

    private val popup: PopupDelegate
        get() = keyboardView.popup

    private val liquidGlass = ThemeManager.prefs.selectedTheme.getValue() == LiquidGlassStyle.CONFIG_ID
    private val glassSurface = if (liquidGlass) GlassKeySurface(context) else null

    private val deletedTextBuffer = ArrayDeque<String>()

    private var gesturePressEpoch = -1L
    private var keyPressed = false
    override fun isPressed(): Boolean = keyPressed

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val symbolPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private var cachedIcon: IconicsDrawable? = null
    private var cachedIconName: String? = null
    private var cachedVectorLabelToken: String? = null
    private var cachedVectorLabel: Drawable? = null
    private var cachedVectorSymbolToken: String? = null
    private var cachedVectorSymbol: Drawable? = null

    private val cachedLocation = intArrayOf(0, 0)
    private val cachedBounds = Rect()
    private var boundsValid = false

    val bounds: Rect
        get() = cachedBounds.also {
            if (!boundsValid) updateBounds()
        }

    fun updateBounds() {
        val (x, y) = cachedLocation.also { getLocationInWindow(it) }
        cachedBounds.set(x + key.extraWidthLeft, y, x + width - key.extraWidthRight, y + height)
        boundsValid = true
    }

    init {
        glassSurface?.let { addView(it, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.MATCH_PARENT)) }
        setWillNotDraw(false)
        isRepeatable = key.click?.isRepeatable ?: false
        isSlideCursor = key.click?.isSlideCursor ?: false
        isSlideDelete = key.click?.isSlideDelete ?: false
        hasLongPress = key.hasAction(KeyBehavior.LONG_CLICK)
        hasDouble = key.hasAction(KeyBehavior.DOUBLE_CLICK)
        hasLazyDouble = key.hasAction(KeyBehavior.LAZY_DOUBLE_CLICK)
        hasPopup = key.popup.isNotEmpty()

        onPress = {
            gesturePressEpoch = service.niumaGestureEpoch()
            if (keyboard.firstPressedKeyIndex == -1) keyboard.firstPressedKeyIndex = id
            setPressedState(true)
            key.getCode(KeyBehavior.CLICK).let { keyboardActionListener.onPress(it) }
            showPopupPreview()
        }

        onRelease = { behavior, isFromLongPress ->
            Timber.d("KeyView release: label=${key.getLabel()}, behavior=$behavior, fromLongPress=$isFromLongPress")
            if (isFromLongPress) {
                if (hasPopup) {
                    val triggerAction = PopupAction.TriggerAction(id)
                    popup.listener.onPopupAction(triggerAction)
                    triggerAction.outAction?.let { action ->
                        keyboardActionListener.onAction(theme.resolveAction(action))
                        dismissPopupPreview()
                    }
                    setPressedState(false)
                } else if (isRepeatable) {
                    key.getAction(KeyBehavior.CLICK)?.let { processKeyAction(it, KeyBehavior.CLICK) }
                }
            } else {
                when (behavior) {
                    KeyBehavior.CLICK -> {
                        val pressedIdx = keyboard.firstPressedKeyIndex
                        val actionBehavior = if (pressedIdx != -1 && pressedIdx != id) KeyBehavior.COMBO else behavior
                        key.getAction(actionBehavior)?.let { processKeyAction(it, actionBehavior) }
                    }

                    KeyBehavior.SWIPE_UP -> {
                        if (isShiftKey() && !key.hasAction(behavior)) {
                            // Shift swipe-up toggles Caps Lock for Chinese and English alike.
                            if (keyboard.toggleCapsLock()) keyboardView.invalidateAllKeys()
                        } else if (liquidGlass && !key.hasAction(behavior) && key.code == KeyEvent.KEYCODE_DEL) {
                            keyboardActionListener.onNiumaClearBeforeCursor(gesturePressEpoch)
                        } else if (liquidGlass && !key.hasAction(behavior) && key.code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
                            val normal = key.getAction(KeyBehavior.CLICK)
                            // Use the configured action object, never the printed secondary label.
                            val upper = key.longClick
                            val handled = normal != null && keyboardActionListener.onNiumaGesture(normal, behavior, upper, gesturePressEpoch)
                            if (handled) {
                                if (keyboard.refreshModifier()) keyboardView.invalidateAllKeys()
                            } else {
                                upper?.let { processKeyAction(it, KeyBehavior.LONG_CLICK) }
                            }
                        } else key.getAction(behavior)?.let { processKeyAction(it, behavior) }
                    }

                    KeyBehavior.DOUBLE_CLICK, KeyBehavior.LAZY_DOUBLE_CLICK,
                    KeyBehavior.SWIPE_DOWN, KeyBehavior.SWIPE_LEFT, KeyBehavior.SWIPE_RIGHT,
                    ->
                        key.getAction(behavior)?.let { processKeyAction(it, behavior) }

                    else -> {}
                }

                setPressedState(false)
                dismissPopupPreview()
            }
            if (keyboard.firstPressedKeyIndex == id) keyboard.firstPressedKeyIndex = -1
        }

        onSwipe = { direction ->
            setPressedState(true)
            showPopupPreview(direction)
        }

        onSlide = { delta, _, _ ->
            if (isSlideCursor) {
                when {
                    delta > 0 -> keyboardActionListener.onAction(theme.resolveAction("Right"))
                    delta < 0 -> keyboardActionListener.onAction(theme.resolveAction("Left"))
                }
            } else if (isSlideDelete) {
                val ic = service.currentInputConnection
                when {
                    delta < 0 -> {
                        val beforeText = ic.getTextBeforeCursor(1, 0) ?: ""
                        if (beforeText.isNotEmpty()) {
                            deletedTextBuffer.addFirst(beforeText.toString())
                            ic.deleteSurroundingText(1, 0)
                        }
                    }

                    delta > 0 -> {
                        if (deletedTextBuffer.isNotEmpty()) {
                            ic.commitText(deletedTextBuffer.removeFirst(), 1)
                        }
                    }
                }
            }
        }

        onLongClick = {
            if (key.popup.isNotEmpty()) {
                dismissPopupPreview()
                showPopupKeyboard()
            } else if (hasLongPress) {
                key.getAction(KeyBehavior.LONG_CLICK)?.let {
                    processKeyAction(it, KeyBehavior.LONG_CLICK)
                    setPressedState(false)
                    dismissPopupPreview()
                }
            }
        }

        onMove = { x, y, isLongPress ->
            if (isLongPress && hasPopup) {
                popup.listener.onPopupAction(PopupAction.ChangeFocusAction(id, x, y))
            }
        }

        onCancel = {
            deletedTextBuffer.clear()
            setPressedState(false)
            dismissPopupPreview()
        }
    }

    fun setPressedState(pressed: Boolean) {
        if (keyPressed != pressed) {
            keyPressed = pressed
            if (pressed) {
                key.onPressed()
            } else {
                key.onReleased()
            }
            invalidate()
        }
    }

    /** The Shift key: a modifier whose primary mask is META_SHIFT_ON. */
    private fun isShiftKey(): Boolean = key.click?.let { it.isModifierKey && it.modifierKeyOnMask == KeyEvent.META_SHIFT_ON } == true

    private fun processKeyAction(action: KeyAction, behavior: KeyBehavior) {
        Timber.d("processKeyAction: label=${key.getLabel()}, code=${action.code}, type=$behavior")

        if (action.isModifierKey && action.modifierKeyOnMask == KeyEvent.META_SHIFT_ON && keyboard.isCapsLock) {
            // A plain Shift tap while Caps Lock is on only releases the lock.
            if (keyboard.clearCapsLock()) keyboardView.invalidateAllKeys()
            return
        }

        if (action.isModifierKey) {
            keyboard.clickModifierKey(
                action.isShiftLock xor (behavior == KeyBehavior.LONG_CLICK),
                action.modifierKeyOnMask,
            )
            keyboardView.invalidateAllKeys()
            return
        }

        val queuedGesture = liquidGlass && behavior == KeyBehavior.CLICK && action.code == KeyEvent.KEYCODE_Z &&
            keyboardActionListener.onNiumaGesture(action, behavior, action, gesturePressEpoch)
        if (!queuedGesture) keyboardActionListener.onAction(action)

        val hookArrow = if (keyboardView.hookShiftArrow) {
            when (action.code) {
                in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> true
                KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END -> true
                else -> false
            }
        } else {
            false
        }

        if (!hookArrow) {
            if (keyboard.refreshModifier()) {
                keyboardView.invalidateAllKeys()
            }
        }
    }

    private fun showPopupKeyboard() {
        val popupKeys = key.popup
        if (popupKeys.isEmpty()) return

        popup.listener.onPopupAction(
            PopupAction.ShowKeyboardAction(id, popupKeys, bounds),
        )
    }

    private fun showPopupPreview(behavior: KeyBehavior = KeyBehavior.CLICK) {
        if (!keyboardView.popupOnKeyPress) return
        key.getPreviewText(behavior).takeIf { it.isNotEmpty() }?.let { previewText ->
            val context = if (previewText.isIconFont) {
                previewText
            } else {
                String(Character.toChars(previewText.codePointAt(0)))
            }
            popup.listener.onPopupAction(PopupAction.PreviewAction(id, context, bounds))
        }
    }

    private fun dismissPopupPreview() {
        popup.listener.onPopupAction(
            PopupAction.DismissAction(id),
        )
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val totalWidth = key.width + key.extraWidthLeft + key.extraWidthRight
        val desiredWidth = totalWidth + paddingLeft + paddingRight
        val desiredHeight = key.height + paddingTop + paddingBottom

        setMeasuredDimension(
            resolveSize(desiredWidth, widthMeasureSpec),
            resolveSize(desiredHeight, heightMeasureSpec),
        )
        glassSurface?.measure(
            MeasureSpec.makeMeasureSpec((measuredWidth - paddingLeft - paddingRight).coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((measuredHeight - paddingTop - paddingBottom).coerceAtLeast(0), MeasureSpec.EXACTLY),
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // FrameLayout lays the measured child out once, inside this key's padding.
        super.onLayout(changed, left, top, right, bottom)
        boundsValid = false
    }

    override fun dispatchDraw(canvas: Canvas) {
        glassSurface?.updateAppearance(
            LiquidGlassStyle.isDarkForKeyText(ColorManager.currentScope()?.colors?.keyTextColor ?: key.getTextColor()),
            dp(key.roundCorner.coerceAtLeast(0f)), keyPressed,
            key.isOn || (key.click?.let { it.isModifierKey && keyboard.modifier.hasFlag(it.modifierKeyOnMask) } == true),
            // Caps Lock is carried by the SHIFT bit; the Shift key already lights via modifier flags.
        )
        // Background render node first, sharp parent glyphs last. No blur on this whole View.
        super.dispatchDraw(canvas)
        if (glassSurface == null) drawBackground(canvas, key)

        val configuredLabel = key.getLabel()
        val label = when {
            configuredLabel == "enter_labels" && liquidGlass -> keyboardView.enterKeyGlyph
            configuredLabel == "enter_labels" -> keyboardView.labelEnter
            else -> configuredLabel
        }

        if (label.isNotEmpty()) {
            drawLabel(canvas, label)
        }

        val symbol = key.symbolLabel
        if (symbol.isNotEmpty()) {
            drawSymbol(canvas, symbol)
        }

        val hint = key.hint
        if (hint.isNotEmpty()) {
            drawSymbol(canvas, hint, isTop = false)
        }
    }

    private fun drawBackground(canvas: Canvas, k: Key) {
        val bg = k.getBackgroundDrawable() ?: return

        if (bg is GradientDrawable) {
            k.roundCorner.takeIf { it > 0f }?.let { bg.cornerRadius = dp(it) }
            k.keyBorder.takeIf { it > 0 }?.let { bg.setStroke(dp(it), k.getBorderColor()) }
        }

        bg.setBounds(
            paddingLeft,
            paddingTop,
            width - paddingRight,
            height - paddingBottom,
        )
        bg.draw(canvas)
    }

    private fun cachedVectorDrawable(token: String, color: Int, isSymbol: Boolean): Drawable? {
        val cachedToken = if (isSymbol) cachedVectorSymbolToken else cachedVectorLabelToken
        var drawable = if (isSymbol) cachedVectorSymbol else cachedVectorLabel
        if (cachedToken != token) {
            drawable = VectorGlyphs.drawable(context, token, color)
            if (isSymbol) {
                cachedVectorSymbolToken = token
                cachedVectorSymbol = drawable
            } else {
                cachedVectorLabelToken = token
                cachedVectorLabel = drawable
            }
        }
        return drawable?.also { DrawableCompat.setTint(it, color) }
    }

    private fun drawLabel(canvas: Canvas, label: String) {
        val textColor = key.getTextColor()
        val textSize = sp(key.keyTextSize.takeIf { it > 0 } ?: if (label.length > 1 && !label.isIconFont && VectorGlyphs.description(label) == null) keyboardView.keyLongTextSize else keyboardView.keyTextSize)

        val vectorDescription = VectorGlyphs.description(label)
        contentDescription = vectorDescription
        if (vectorDescription != null) {
            val drawable = cachedVectorDrawable(label, textColor, isSymbol = false) ?: return
            val iconSize = textSize.toInt()
            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(key.keyTextOffsetX)
            val centerY = (height - paddingTop - paddingBottom) / 2f + paddingTop + sp(key.keyTextOffsetY)
            val halfSize = iconSize / 2
            drawable.setBounds(
                (centerX - halfSize).toInt(),
                (centerY - halfSize).toInt(),
                (centerX + halfSize).toInt(),
                (centerY + halfSize).toInt(),
            )
            drawable.draw(canvas)
        } else if (label.isIconFont) {
            drawIcon(canvas, label, textSize.toInt(), textColor, key.keyTextOffsetX, key.keyTextOffsetY)
        } else {
            textPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = theme.fonts.key
                isFakeBoldText = liquidGlass && label.length == 1 && label[0].isLetterOrDigit()
                clearShadowLayer()
            }

            val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft
            val centerY = (height - paddingTop - paddingBottom) / 2f + paddingTop
            val fontMetrics = textPaint.fontMetrics
            val adjustmentY = -(fontMetrics.ascent + fontMetrics.descent) / 2f

            canvas.drawText(label, centerX + sp(key.keyTextOffsetX), centerY + adjustmentY + sp(key.keyTextOffsetY), textPaint)
        }
    }

    private fun drawIcon(
        canvas: Canvas,
        iconName: String,
        size: Int,
        color: Int,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        isTop: Boolean? = null,
    ) {
        val halfSize = size / 2

        val cmdName = iconName.toIconName()
        val icon = if (cachedIconName == cmdName) {
            cachedIcon!!
        } else {
            IconicsDrawable(context, cmdName).apply {
                sizeDp = size
            }.also {
                cachedIcon = it
                cachedIconName = cmdName
            }
        }

        icon.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)

        val centerX = (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX)

        val centerY = when (isTop) {
            true -> paddingTop + halfSize + sp(offsetY)
            false -> height - paddingBottom - size + sp(offsetY)
            null -> (height - paddingTop - paddingBottom) / 2f + paddingTop + sp(offsetY)
        }

        icon.setBounds(
            (centerX - halfSize).toInt(),
            (centerY - halfSize).toInt(),
            (centerX + halfSize).toInt(),
            (centerY + halfSize).toInt(),
        )
        icon.draw(canvas)
    }

    private fun drawSymbol(canvas: Canvas, text: String, isTop: Boolean = true) {
        if (isTop && keyboardView.hideKeySymbol) return
        if (!isTop && keyboardView.hideKeyHint) return

        val textColor = key.getSymbolColor()
        val textSize = sp(key.symbolTextSize.takeIf { it > 0f } ?: keyboardView.symbolTextSize)
        val offsetX = if (isTop) key.keySymbolOffsetX else key.keyHintOffsetX
        val offsetY = if (isTop) key.keySymbolOffsetY else key.keyHintOffsetY

        val vectorDescription = VectorGlyphs.description(text)
        if (vectorDescription != null) {
            val drawable = cachedVectorDrawable(text, textColor, isSymbol = true) ?: return
            val halfSize = textSize.toInt() / 2
            // Shift's Caps Lock glyph sits in the top-right corner, like other upper hints; other vectors stay centred.
            val centerX = if (isTop && isShiftKey()) {
                width - paddingRight - dp(5f) - halfSize + sp(offsetX)
            } else {
                (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX)
            }
            val centerY = if (isTop) {
                paddingTop + halfSize + sp(offsetY)
            } else {
                height - paddingBottom - textSize.toInt() + sp(offsetY)
            }
            drawable.setBounds(
                (centerX - halfSize).toInt(),
                (centerY - halfSize).toInt(),
                (centerX + halfSize).toInt(),
                (centerY + halfSize).toInt(),
            )
            drawable.draw(canvas)
            if (contentDescription.isNullOrEmpty()) contentDescription = vectorDescription
        } else if (text.isIconFont) {
            drawIcon(canvas, text, textSize.toInt(), textColor, offsetX, offsetY, isTop)
        } else {
            symbolPaint.apply {
                color = textColor
                this.textSize = textSize
                typeface = theme.fonts.symbol
                textAlign = if (liquidGlass && isTop) Paint.Align.RIGHT else Paint.Align.CENTER
            }

            val lines = text.split("\n")
            val fontMetrics = symbolPaint.fontMetrics
            val lineHeight = fontMetrics.descent - fontMetrics.ascent
            val totalHeight = lineHeight * lines.size

            val centerX = if (liquidGlass && isTop) width - paddingRight - dp(5f) + sp(offsetX)
                else (width - paddingLeft - paddingRight) / 2f + paddingLeft + sp(offsetX)
            val startY = if (isTop) {
                paddingTop - fontMetrics.top + sp(offsetY) - (totalHeight - lineHeight) / 2
            } else {
                height - paddingBottom - fontMetrics.bottom + sp(offsetY) - (totalHeight - lineHeight) / 2
            }

            for (i in lines.indices) {
                val lineY = startY + lineHeight * i
                canvas.drawText(lines[i], centerX, lineY, symbolPaint)
            }
        }
    }
}
