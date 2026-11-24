/*
 * SPDX-FileCopyrightText: 2015 - 2025 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */

package com.osfans.trime.ime.keyboard

import android.app.Dialog
import android.content.Intent
import android.view.ContextThemeWrapper
import android.view.KeyEvent
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.osfans.trime.R
import com.osfans.trime.core.KeyModifiers
import com.osfans.trime.core.RimeApi
import com.osfans.trime.core.RimeKeyEvent
import com.osfans.trime.daemon.RimeSession
import com.osfans.trime.daemon.launchOnReady
import com.osfans.trime.data.prefs.AppPrefs
import com.osfans.trime.data.theme.ColorManager
import com.osfans.trime.data.theme.Theme
import com.osfans.trime.data.theme.ThemeManager
import com.osfans.trime.data.theme.model.LiquidKeyboard
import com.osfans.trime.ime.clipboard.ClipboardWindow
import com.osfans.trime.ime.core.TrimeInputMethodService
import com.osfans.trime.ime.glass.LiquidGlassStyle
import com.osfans.trime.ime.gesture.GestureKind
import com.osfans.trime.ime.gesture.GestureEngine
import com.osfans.trime.ime.gesture.GestureEngineState
import com.osfans.trime.ime.gesture.NiumaGestureExecutor
import com.osfans.trime.ime.dialog.EnabledSchemaPickerDialog
import com.osfans.trime.ime.switches.SwitchOptionWindow
import com.osfans.trime.ime.symbol.LiquidWindow
import com.osfans.trime.ime.window.BoardWindowManager
import com.osfans.trime.ui.main.settings.ColorPickerDialog
import com.osfans.trime.ui.main.settings.SoundEffectPickerDialog
import com.osfans.trime.ui.main.settings.ThemePickerDialog
import com.osfans.trime.util.AppUtils
import com.osfans.trime.util.InputMethodUtils
import com.osfans.trime.util.buildIntentFromAction
import com.osfans.trime.util.buildIntentFromArgument
import com.osfans.trime.util.customFormatDateTime
import com.osfans.trime.util.isAsciiPrintable
import com.osfans.trime.util.toast
import kotlinx.coroutines.launch
import org.kodein.di.DI
import org.kodein.di.DIAware
import org.kodein.di.instance
import splitties.systemservices.clipboardManager
import splitties.systemservices.inputMethodManager
import timber.log.Timber

class CommonKeyboardActionListener(override val di: DI) : DIAware {

    private val context: ContextThemeWrapper by instance()
    private val service: TrimeInputMethodService by instance()
    private val theme: Theme by instance()
    private val rime: RimeSession by instance()
    private val windowManager: BoardWindowManager by instance()
    private val keyboardWindow: KeyboardWindow by instance()
    private val liquidWindow: LiquidWindow by instance()

    private val prefs = AppPrefs.defaultInstance()

    private fun showDialog(dialog: suspend (RimeApi) -> Dialog) {
        rime.launchOnReady { api ->
            service.lifecycleScope.launch {
                service.showDialog(dialog(api))
            }
        }
    }

    private fun showThemePicker() {
        showDialog { api ->
            ThemePickerDialog.build(service.lifecycleScope, context) {
                api.commitComposition()
            }
        }
    }

    private fun showColorPicker() {
        showDialog { api ->
            ColorPickerDialog.build(service.lifecycleScope, context) {
                api.commitComposition()
            }
        }
    }

    private fun showSoundEffectPicker() {
        showDialog {
            SoundEffectPickerDialog.build(service.lifecycleScope, context)
        }
    }

    private fun showEnabledSchemaPicker() {
        showDialog { api ->
            EnabledSchemaPickerDialog.build(api, service.lifecycleScope, context) {
                setNegativeButton(R.string.enable_schemata) { _, _ ->
                    AppUtils.launchMainToSchemaList(context)
                }
            }
        }
    }

    private fun expandActiveText(input: String): String = if (input.matches(PLACEHOLDER_PATTERN)) {
        input.format(
            service.getActiveText(1),
            service.getActiveText(2),
            service.getActiveText(3),
            service.getActiveText(4),
        )
    } else {
        input
    }

    val listener by lazy {
        object : KeyboardActionListener {
            override fun onPress(keyEventCode: Int) {
                service.onNiumaInputAction(resetContext = keyEventCode !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z, preserveEnglish = service.preserveNiumaEnglishKey(keyEventCode))
                InputFeedbackManager.run {
                    keyPressSound(keyEventCode)
                    keyPressSpeak(keyEventCode)
                }
            }

            override fun onNiumaClearBeforeCursor(pressEpoch: Long) {
                service.clearNiumaBeforeCursor(pressEpoch)
            }

            override fun onAction(action: KeyAction) {
                service.onNiumaInputAction(resetContext = action.code !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z, preserveEnglish = service.preserveNiumaEnglishKey(action.code))
                if (action.code != KeyEvent.KEYCODE_VOICE_ASSIST) service.cancelNiumaVoiceInput()
                val keyboard = KeyboardWindow.currentKeyboard
                if (action.commit.isEmpty() && action.modifier == 0 && keyboard.isCapsLock &&
                    keyboard.modifier == KeyEvent.META_SHIFT_ON && action.code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z &&
                    ThemeManager.prefs.selectedTheme.getValue() == LiquidGlassStyle.CONFIG_ID
                ) {
                    val guard = service.niumaGestureSessionGuard(service.niumaGestureEpoch()) ?: return
                    val letter = ('A'.code + action.code - KeyEvent.KEYCODE_A).toChar().toString()
                    service.postRimeJob {
                        if (!guard()) return@postRimeJob
                        if (statusCached.schemaId == "niuma_glass" && !statusCached.isAsciiMode) {
                            // Shifted letters otherwise enter onText below and can create a Chinese preedit.
                            commitLiteralAfterComposition(letter, guard)
                        } else {
                            simulateKeySequence(letter)
                        }
                    }
                    return
                }
                val text = action.getText(keyboard)
                val shouldHandle = when {
                    action.commit.isNotEmpty() -> {
                        service.commitText(action.commit)
                        false
                    }

                    text.isNotEmpty() -> {
                        onText(text)
                        false
                    }

                    else -> true
                }

                if (shouldHandle) {
                    when (action.code) {
                        KeyEvent.KEYCODE_SWITCH_CHARSET -> handleSwitchCharset(action)
                        KeyEvent.KEYCODE_EISU -> keyboardWindow.switchKeyboard(action.select)
                        KeyEvent.KEYCODE_LANGUAGE_SWITCH -> handleLanguageSwitch(action)
                        KeyEvent.KEYCODE_FUNCTION -> handleFunctionCommand(action)
                        KeyEvent.KEYCODE_SETTINGS -> handleSettings(action)
                        KeyEvent.KEYCODE_PROG_RED -> showColorPicker()
                        KeyEvent.KEYCODE_MENU -> showEnabledSchemaPicker()
                        KeyEvent.KEYCODE_VOICE_ASSIST -> service.startNiumaVoiceInput()
                        else -> handleDefaultKeyAction(action)
                    }
                }
            }

            private fun handleSwitchCharset(action: KeyAction) {
                val option = action.toggle.ifEmpty { return }

                rime.launchOnReady { api ->
                    service.lifecycleScope.launch {
                        val isEnabled = api.getRuntimeOption(option)
                        val isComposing = api.statusCached.isComposing
                        api.setRuntimeOption(option, !isEnabled)
                        if (option == "ascii_mode" && isComposing) {
                            api.getRawInput().takeIf { it.isNotEmpty() }?.let {
                                service.commitText(it)
                                api.clearComposition()
                            }
                        }
                    }
                }
            }

            private fun handleLanguageSwitch(action: KeyAction) {
                when {
                    action.select == ".next" -> service.switchToNextIme()
                    action.select.isNotEmpty() -> service.switchToPrevIme()
                    else -> inputMethodManager.showInputMethodPicker()
                }
            }

            private fun handleFunctionCommand(action: KeyAction) {
                val arg = expandActiveText(action.option)

                when (action.command) {
                    "liquid_keyboard" -> handleLiquidKeyboard(arg)
                    "menu_keyboard" -> windowManager.attachWindow(SwitchOptionWindow(di))
                    "clipboard_window" -> handleClipboardWindow(arg)
                    "set_color_scheme" -> handleColorScheme(arg)
                    "set_theme" -> handleTheme(arg)
                    "broadcast" -> service.sendBroadcast(Intent(arg))
                    "clipboard" -> handleClipboard()
                    "commit" -> service.commitText(arg)
                    "date" -> service.commitText(customFormatDateTime(arg))
                    "run" -> handleRunCommand(arg)
                    "apply" -> handleApplyCommand(arg)
                    "share_text" -> service.shareText()
                    "select_candidate" -> handleSelectCandidate(arg)
                    "switch_hide_key_symbol" -> switchHideKeySymbol()
                    "switch_hide_key_hint" -> switchHideKeyHint()
                    else -> handleIntentAction(action.command, arg)
                }
            }

            private fun handleLiquidKeyboard(arg: String) {
                // for compatibility
                if (arg == "剪贴" || arg == "clipboard") {
                    windowManager.attachWindow(ClipboardWindow(di))
                    return
                }
                val liquidTagList = theme.liquidKeyboard.getTagList()
                val index = liquidTagList.indexOfFirst { tag ->
                    tag.label == arg || runCatching {
                        LiquidKeyboard.DataType.valueOf(arg.uppercase())
                    }.getOrNull() == tag.type
                }

                if (index >= 0) {
                    windowManager.attachWindow(LiquidWindow)
                    liquidWindow.setDataByIndex(index)
                } else {
                    windowManager.attachWindow(KeyboardWindow)
                }
            }

            private fun handleClipboardWindow(arg: String) {
                val tabIndex = arg.toIntOrNull()?.coerceIn(0, 1) ?: 0
                windowManager.attachWindow(ClipboardWindow(di, tabIndex))
            }

            private fun handleColorScheme(arg: String) {
                if (arg in ThemeManager.activeTheme.presetColorSchemes) {
                    ColorManager.setColorScheme(arg)
                }
            }

            private fun handleTheme(arg: String) {
                if (arg.isEmpty()) {
                    // 参数为空时，刷新当前主题
                    val themeId = ThemeManager.prefs.selectedTheme.getValue()
                    service.lifecycleScope.launch { ThemeManager.selectTheme(themeId) }
                } else {
                    // 通过主题名称查找对应的配置ID并切换主题
                    ThemeManager.getAllThemes()
                        .find { it.name.equals(arg, ignoreCase = true) }?.let { item ->
                            service.lifecycleScope.launch { ThemeManager.selectTheme(item.configId) }
                        }
                }
            }

            private fun handleClipboard() {
                clipboardManager.primaryClip
                    ?.getItemAt(0)
                    ?.coerceToText(service)
                    ?.let { service.commitText(it.toString()) }
            }

            private fun handleRunCommand(arg: String) {
                buildIntentFromArgument(arg)?.let { intent ->
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
                    service.startActivity(intent)
                }
            }

            private fun handleApplyCommand(arg: String) {
                when (arg) {
                    "DEPLOY" -> {
                        Timber.i("try to start maintenance via command ...")
                        rime.launchOnReady { api -> api.deploy() }
                    }

                    "SYNC_USER_DATA" -> {
                        Timber.i("try to sync rime user data via command ...")
                        rime.launchOnReady { api -> api.syncUserData() }
                    }

                    "UPDATE_CONFIG" -> {
                        Timber.i("try to update rime config via command ...")
                        rime.launchOnReady { api ->
                            api.updateConfig()
                            service.lifecycleScope.launch {
                                Toast.makeText(service, R.string.done, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }

                    else -> Timber.w("Unknown apply method: $arg")
                }
            }

            private fun handleIntentAction(command: String, arg: String) {
                buildIntentFromAction(command, arg)?.let { intent ->
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_HISTORY
                    service.startActivity(intent)
                }
            }

            private fun handleSelectCandidate(arg: String) {
                val index = arg.toIntOrNull() ?: return
                rime.launchOnReady { api ->
                    service.lifecycleScope.launch {
                        api.selectCandidate(index, false)
                    }
                }
            }

            private fun switchHideKeySymbol() {
                val preference = prefs.keyboard.hideKeySymbol
                preference.setValue(!preference.getValue())
            }

            private fun switchHideKeyHint() {
                val preference = prefs.keyboard.hideKeyHint
                preference.setValue(!preference.getValue())
            }

            private fun handleSettings(action: KeyAction) {
                when (action.option) {
                    "theme" -> showThemePicker()
                    "color" -> showColorPicker()
                    "schema" -> AppUtils.launchMainToSchemaList(context)
                    "sound" -> showSoundEffectPicker()
                    else -> AppUtils.launchMainActivity(service)
                }
            }

            private fun switchToVoiceInputMethod() {
                val pkgName = prefs.general.preferredVoiceInput.getValue()
                val voiceInputSubType = if (pkgName.isNotEmpty()) {
                    InputMethodUtils.voiceInputMethods().find {
                        it.first.packageName == pkgName
                    }?.let {
                        it.first.id to it.second
                    } ?: InputMethodUtils.firstVoiceInput()
                } else {
                    InputMethodUtils.firstVoiceInput()
                }
                if (voiceInputSubType != null) {
                    val (id, subType) = voiceInputSubType
                    InputMethodUtils.switchInputMethod(service, id, subType)
                } else {
                    service.toast(R.string.no_voice_input_installed)
                }
            }

            private fun handleDefaultKeyAction(action: KeyAction) {
                val shouldHookShiftKey = when {
                    prefs.keyboard.hookShiftSpace.getValue() && action.code == KeyEvent.KEYCODE_SPACE -> true
                    prefs.keyboard.hookShiftNum.getValue() && action.code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> true
                    prefs.keyboard.hookShiftSymbol.getValue() && action.code in KeyEvent.KEYCODE_GRAVE..KeyEvent.KEYCODE_SLASH -> true
                    prefs.keyboard.hookShiftSymbol.getValue() && action.code in setOf(KeyEvent.KEYCODE_COMMA, KeyEvent.KEYCODE_PERIOD) -> true
                    else -> false
                }

                if (action.modifier == 0 && KeyboardWindow.currentKeyboard.isOnlyShiftOn && shouldHookShiftKey) {
                    onKey(action.code, 0)
                    return
                }

                val modifier = when {
                    // Caps Lock only affects letters: modifierFor drops its SHIFT bit for other keys.
                    action.modifier == 0 -> KeyboardWindow.currentKeyboard.modifierFor(action.code)

                    (action.modifier and KeyEvent.META_CTRL_ON) != 0 && isNavigationKey(action.code) ->
                        action.modifier or KeyboardWindow.currentKeyboard.modifierFor(action.code)

                    else -> action.modifier
                }

                onKey(action.code, modifier)
            }

            private fun isNavigationKey(keyCode: Int): Boolean = keyCode in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT ||
                keyCode == KeyEvent.KEYCODE_MOVE_HOME ||
                keyCode == KeyEvent.KEYCODE_MOVE_END

            override fun onNiumaGesture(action: KeyAction, behavior: KeyBehavior, fallback: KeyAction?, pressEpoch: Long): Boolean {
                if (ThemeManager.prefs.selectedTheme.getValue() != LiquidGlassStyle.CONFIG_ID ||
                    action.code !in setOf(KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_Z) ||
                    (behavior != KeyBehavior.CLICK && behavior != KeyBehavior.SWIPE_UP) ||
                    (behavior == KeyBehavior.CLICK && action.code != KeyEvent.KEYCODE_Z)
                ) return false
                val keyboard = KeyboardWindow.currentKeyboard
                val modifiers = keyboard.modifier or action.modifier
                // Shift/Control/Alt and inline bindings retain their complete original path.
                if (modifiers != 0 || action.commit.isNotEmpty() || action.getText(keyboard).isNotEmpty()) return false
                val fallbackText = fallback?.getText(keyboard).orEmpty()
                if (fallback != null && (fallback.commit.isNotEmpty() || fallback.command.isNotEmpty() ||
                    (fallbackText.isEmpty() && !KeyCode.isStandardKey(fallback.code)) ||
                    fallbackText.any { it == '{' || it == '}' || it.isISOControl() })
                ) return false
                val guard = service.niumaGestureSessionGuard(pressEpoch) ?: return true // discard old touch
                val fallbackCode = fallback?.code
                val fallbackModifiers = fallback?.modifier?.takeIf { it != 0 } ?: keyboard.modifier
                service.onNiumaInputAction()
                service.cancelNiumaVoiceInput()
                service.postRimeJob {
                    val api = this
                    val engine = object : GestureEngine {
                        override suspend fun snapshot(): GestureEngineState = GestureEngineState(
                            getRawInput(), selectedSchemaId(), getRuntimeOption("ascii_mode"),
                            getCandidates(0, 3).map { it.text }, compositionCached.cursorPos,
                        )
                        override suspend fun selectGlobal(index: Int): Boolean = selectCandidate(index, global = true)
                    }
                    NiumaGestureExecutor.execute(engine,
                        if (action.code == KeyEvent.KEYCODE_L) 'l' else 'z',
                        if (behavior == KeyBehavior.SWIPE_UP) GestureKind.SWIPE_UP else GestureKind.TAP,
                        modifiers, service.niumaGestureIndex(), guard,
                    ) {
                        // Deliberately inline: do not post another job behind subsequent letters.
                        if (fallbackText.isNotEmpty()) api.simulateKeySequence(fallbackText)
                        else if (fallbackCode != null) api.dispatchKeyInline(fallbackCode, fallbackModifiers)
                    }
                }
                return true
            }

            override fun onKey(keyEventCode: Int, metaState: Int) {
                service.cancelNiumaVoiceInput()
                service.postRimeJob { dispatchKeyInline(keyEventCode, metaState) }
            }

            /** The original onKey path shared by normal input and same-job gesture fallback. */
            private suspend fun RimeApi.dispatchKeyInline(keyEventCode: Int, metaState: Int) {
                val value = if (metaState and KeyEvent.META_SHIFT_ON != 0 &&
                    keyEventCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z
                ) {
                    'A'.code + (keyEventCode - KeyEvent.KEYCODE_A)
                } else {
                    RimeKeyEvent.getKeycodeByName(KeyCode.codeToKeyName(keyEventCode) ?: "VoidSymbol")
                }
                val m = if (keyEventCode in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_EQUALS)
                    metaState or KeyEvent.META_NUM_LOCK_ON else metaState
                val modifiers = KeyModifiers.fromMetaState(m).modifiers
                if (service.hookKeyboard(keyEventCode, m)) return
                if (processKey(value, modifiers)) return
                if (AppUtils.launchKeyCategory(service, keyEventCode)) return
                if (keyEventCode == KeyEvent.KEYCODE_BACK) service.requestHideSelf(0)
            }

            override fun onText(input: String) {
                service.onNiumaInputAction(resetContext = !input.all { it in 'a'..'z' || it in 'A'..'Z' }, preserveEnglish = input.length <= 64 && input.all { it.code in 9..126 })
                service.cancelNiumaVoiceInput()
                if (input.isEmpty()) return
                Timber.d("onText: %d code units", input.length)
                val status = rime.run { statusCached }
                if (!input[0].isAsciiPrintable() && status.isComposing) {
                    service.postRimeJob { commitComposition() }
                }

                val escaped = input.replace("{}", "{braceleft}{braceright}")
                var i = 0
                while (i < escaped.length) {
                    val value = when (val match = TEXT_INPUT_PATTERN.matchEntire(escaped.substring(i))) {
                        match if (match != null) -> match.groupValues[1]
                        else -> escaped[i].toString()
                    }

                    service.postRimeJob {
                        if (value.run { startsWith('{') && endsWith('}') }) {
                            val token = value.removeSurrounding("{", "}")
                            onAction(theme.resolveAction(token))
                        } else if (!value[0].isAsciiPrintable()) {
                            service.commitText(value)
                        } else {
                            simulateKeySequence(value)
                        }
                    }

                    i += value.length
                }
            }
        }
    }

    companion object {
        /**
         * Regex for combined key events.
         * group(1) captures either:
         *   - a plain prefix (optionally preceded by {Escape}) from the left branch,
         *   - or a standalone {xxx} block from the right branch.
         * The trailing .* consumes the rest of the input without affecting group(1).
         */
        private val TEXT_INPUT_PATTERN = """^((?:\{Escape\})?[^{}]+|\{[^{}]+\}).*$""".toRegex()

        private val PLACEHOLDER_PATTERN = Regex(".*(%([1-4]\\$)?s).*")
    }
}
