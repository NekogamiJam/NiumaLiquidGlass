"""Source-contract checks for the keyboard swipe-up / Caps Lock / emoji bar changes.

Static checks only: no Gradle, ADB, device, or packaging. They pin the owned source
strings so later edits cannot silently drop the gesture, the Caps-letters-only rule,
or the toolbar change.
"""
from pathlib import Path
import hashlib
import re
import unittest

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "app/src/main"
KB = APP / "java/com/osfans/trime/ime/keyboard"
SYM = APP / "java/com/osfans/trime/ime/symbol"
UI = APP / "java/com/osfans/trime/ime/ui"
CUSTOM = ROOT / "custom-data/niuma_glass.trime.yaml"
MIRROR = APP / "assets/shared/niuma_glass.trime.yaml"
PRESET = APP / "assets/shared/trime.yaml"


def read(path):
    return path.read_text(encoding="utf-8")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class CapsLockModel(unittest.TestCase):
    def setUp(self):
        self.src = read(KB / "Keyboard.kt")

    def test_caps_flag_and_shifted_hook(self):
        self.assertIn("var isCapsLock: Boolean = false", self.src)
        self.assertIn("|| isCapsLock", self.src)

    def test_toggle_and_clear_defined(self):
        self.assertIn("fun toggleCapsLock(): Boolean", self.src)
        self.assertIn("fun clearCapsLock(): Boolean", self.src)

    def test_toggle_keeps_one_shot_shift(self):
        # Caps is stored as the SHIFT bit, OR-ed with a latched one-shot Shift so neither clears the other.
        self.assertIn("setModifier(KeyEvent.META_SHIFT_ON, isCapsLock || isShiftOneShotOn)", self.src)
        self.assertIn("return setModifier(KeyEvent.META_SHIFT_ON, isShiftOneShotOn)", self.src)

    def test_modifier_for_letters_only(self):
        # Non-letter keys drop the caps-only SHIFT bit; A..Z keep it. Prevents `,` -> `<`.
        self.assertIn("fun modifierFor(keyCode: Int): Int", self.src)
        self.assertIn("keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) return modifier", self.src)
        self.assertIn("return modifier and KeyEvent.META_SHIFT_ON.inv()", self.src)

    def test_refresh_modifier_keeps_caps_on(self):
        self.assertIn("!mShiftKey!!.isOn && !isCapsLock", self.src)

    def test_set_shifted_and_reset_survive_caps(self):
        self.assertIn("return setModifier(KeyEvent.META_SHIFT_ON, shifted || isCapsLock)", self.src)
        self.assertIn("setModifier(KeyEvent.META_SHIFT_ON, isCapsLock)", self.src)

    def test_only_shift_excludes_pure_caps(self):
        self.assertIn("isShifted && (!isCapsLock || isShiftOneShotOn) &&", self.src)


class ListenerUsesModifierFor(unittest.TestCase):
    def setUp(self):
        self.src = read(KB / "CommonKeyboardActionListener.kt")

    def test_default_branches_use_modifier_for(self):
        self.assertIn("KeyboardWindow.currentKeyboard.modifierFor(action.code)", self.src)
        self.assertNotIn("action.modifier == 0 -> KeyboardWindow.currentKeyboard.modifier\n", self.src)


class KeyViewGestures(unittest.TestCase):
    def setUp(self):
        self.src = read(KB / "KeyView.kt")

    def test_shift_swipe_up_toggles_caps(self):
        self.assertIn("if (isShiftKey() && !key.hasAction(behavior))", self.src)
        self.assertIn("keyboard.toggleCapsLock()", self.src)

    def test_shift_tap_releases_caps(self):
        self.assertIn("keyboard.isCapsLock", self.src)
        self.assertIn("keyboard.clearCapsLock()", self.src)

    def test_printable_fallback_removed(self):
        # Symbol/number swipe-up is now explicit per-key in YAML, not a broad KeyView fallback.
        self.assertNotIn("isPrintableUpperKey", self.src)

    def test_letter_liquid_path_preserved(self):
        self.assertIn("onNiumaGesture(normal, behavior, upper, gesturePressEpoch)", self.src)

    def test_shift_vector_top_right(self):
        self.assertIn("if (isTop && isShiftKey()) {", self.src)
        self.assertIn("width - paddingRight - dp(5f) - halfSize + sp(offsetX)", self.src)


class EmojiBarChange(unittest.TestCase):
    def setUp(self):
        self.src = read(SYM / "LiquidWindow.kt")

    def test_return_button_removed(self):
        for token in ("returnButton", "返回主键盘", "ic_baseline_keyboard_24"):
            self.assertNotIn(token, self.src, token)

    def test_tabs_still_in_bar(self):
        self.assertIn("addView(\n                tabs,", self.src)

    def test_bottom_left_return_key_unchanged(self):
        preset = read(PRESET)
        self.assertIn('liquid_keyboard_exit: {label: 返回, send: FUNCTION, command: liquid_keyboard, option: "-1"}', preset)
        self.assertIn("- liquid_keyboard_exit", preset)


class CapsVectorHint(unittest.TestCase):
    def test_vector_resource_exists(self):
        self.assertTrue((APP / "res/drawable/vec_capslock.xml").exists())

    def test_glyph_registered(self):
        self.assertIn('"capslock" to Glyph(R.drawable.vec_capslock', read(UI / "VectorGlyphs.kt"))

    def test_shift_key_uses_label_symbol_capslock(self):
        self.assertIn("{click: Shift_L, label: 'vec@shift', label_symbol: 'vec@capslock', width: 15}", read(CUSTOM))

    def test_custom_mirror_byte_identical(self):
        self.assertEqual(digest(CUSTOM), digest(MIRROR))


class NumericAndSymbolSwipeUp(unittest.TestCase):
    """Every printable long_click in the owned number/symbols overrides must have an explicit swipe_up."""

    @classmethod
    def setUpClass(cls):
        text = read(CUSTOM)
        cls.number = text.split("  number:", 1)[1].split("  symbols:", 1)[0]
        cls.symbols = text.split("  symbols:", 1)[1].split("  default:", 1)[0]

    def test_number_default_key_has_swipe_up(self):
        self.assertIn("{click: Keyboard_default, long_click: Keyboard_symbols, swipe_up: Keyboard_symbols}", self.number)

    def test_symbol_printable_long_clicks_have_swipe_up(self):
        # Each entry with a printable long_click (single non-special character) must also carry swipe_up.
        special = {"one_hand_switch_1", "one_hand_switch_3", "Escape", "Menu", "Color_switch",
                   "Theme_settings", "Delete", "select_all"}
        for line in self.symbols.splitlines():
            m = re.search(r"long_click: '([^']+)'", line)
            if not m or m.group(1) in special:
                continue
            self.assertIn("swipe_up:", line, line)

    def test_symbol_special_long_clicks_unchanged(self):
        for token in ("long_click: one_hand_switch_1", "long_click: one_hand_switch_3", "long_click: Escape"):
            self.assertIn(token, self.symbols)


if __name__ == "__main__":
    unittest.main()
