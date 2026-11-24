// SPDX-License-Identifier: GPL-3.0-or-later
package com.osfans.trime.data.theme

import com.osfans.trime.data.theme.model.KeyActionToken
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.collections.shouldContainAll
import java.io.File

/** Exercises the production Kotlin theme decoder, not just librime's YAML compiler. */
class NiumaThemeTest : FunSpec({
    fun load(): Theme {
        val dir = File("src/main/assets/shared")
        // Configuration id includes .trime; runtime lookup appends only .yaml.
        val source = requireNotNull(ThemeLoader.findSourceFile("niuma_glass.trime", listOf(dir)))
        source.name shouldBe "niuma_glass.trime.yaml"
        val node = ThemeTestSupport.yaml.parseToYamlNode(source.readText())
        return ThemeLoader.decodeSource("niuma_glass.trime", node) { id ->
            val candidate = File(dir, "$id.yaml")
            if (candidate.isFile) ThemeTestSupport.yaml.parseToYamlNode(candidate.readText()) else null
        }
    }

    test("custom theme expands upstream and has two day/night schemes and stock utility keyboards") {
        val theme = load()
        theme.name shouldBe "液态玻璃"
        theme.presetColorSchemes.keys.shouldContainAll("default", "niuma_dark")
        theme.presetKeyboards.keys.shouldContainAll("default", "number", "symbols", "niuma_edit")
        theme.style.candidateViewHeight shouldBe 38
        // KeyboardWindow smart matching resolves schema id before qwerty.
        theme.presetKeyboards.getValue("niuma_glass").importPreset shouldBe "default"
        theme.presetKeyboards.getValue("qwerty").importPreset shouldBe "default"
    }
    test("custom utility keyboards decode click and upper swipe as distinct actions") {
        val keyboards = load().presetKeyboards
        fun key(layout: String, token: String) = keyboards.getValue(layout).keys.single {
            (it.click as? KeyActionToken.Plain)?.token == token
        }
        val symbolUpper = mapOf("~" to "`", "(" to "{", ")" to "}", "[" to "【", "]" to "】", "/" to "√", "-" to "_")
        for ((lower, upper) in symbolUpper) {
            val symbol = key("symbols", lower)
            symbol.swipeUp shouldBe KeyActionToken.Plain(upper)
            symbol.longClick shouldBe symbol.swipeUp
        }
        val numberReturn = key("number", "Keyboard_default")
        numberReturn.swipeUp shouldBe KeyActionToken.Plain("Keyboard_symbols")
        numberReturn.longClick shouldBe numberReturn.swipeUp
        key("default", "Shift_L").labelSymbol shouldBe "vec@capslock"
    }
    test("QWERTY rows retain all 26 letters and widths sum to four complete rows") {
        val keyboard = load().presetKeyboards.getValue("default")
        keyboard.asciiMode shouldBe 0
        val letters = keyboard.keys.mapNotNull { (it.click as? KeyActionToken.Plain)?.token }
            .filter { it.length == 1 && it[0] in 'a'..'z' }
        letters.toSet() shouldBe ('a'..'z').map { it.toString() }.toSet()
        keyboard.keys.sumOf { if (it.width > 0f) it.width.toDouble() else keyboard.width.toDouble() } shouldBe 400.0
    }
    test("bottom row follows requested order and space has equal total width on both sides") {
        val keyboard = load().presetKeyboards.getValue("default")
        val row = keyboard.keys.takeLast(7)
        row.map { (it.click as KeyActionToken.Plain).token } shouldBe listOf(
            "Keyboard_number", "Menu", ",", "space", ".", "Keyboard_symbols", "Return",
        )
        row.map { it.width } shouldBe listOf(12f, 12f, 10f, 32f, 10f, 12f, 12f)
        row.take(3).sumOf { it.width.toDouble() } shouldBe 34.0
        row.takeLast(3).sumOf { it.width.toDouble() } shouldBe 34.0
        row.sumOf { it.width.toDouble() } shouldBe 100.0
    }
    test("liquid glass has transparent candidate surfaces and low-transparency keys in both palettes") {
        val theme = load()
        theme.style.candidateBorder shouldBe 0
        theme.style.keyTextSize shouldBe 24f
        theme.style.symbolTextSize shouldBe 9f
        for (id in listOf("default", "niuma_dark")) {
            val scheme = theme.presetColorSchemes.getValue(id)
            for (key in listOf("candidate_background", "candidate_border_color", "hilited_candidate_back_color", "hilited_back_color", "keyboard_background")) {
                scheme.getValue(key) shouldBe "0x00000000"
            }
            val argb = scheme.getValue("key_back_color").removePrefix("0x").toLong(16)
            ((argb ushr 24) in 150L..210L) shouldBe true
        }
    }
    test("automatic day night resolution keeps labels and native masks on the same side") {
        val schemes = load().presetColorSchemes
        for (night in listOf(false, true)) {
            val resolved = ColorSchemeResolver.resolve(schemes, "default", true, night)
            val glyph = resolved.getValue("key_text_color").removePrefix("0x").toLong(16).toInt()
            com.osfans.trime.ime.glass.LiquidGlassStyle.isDarkForKeyText(glyph) shouldBe night
        }
    }
    test("embedded raw-code styling remains readable without a separate text rectangle") {
        val theme = load()
        theme.preedit.alpha shouldBe 1f
        theme.preedit.horizontalPadding shouldBe 0
        theme.preedit.foreground.fontSize shouldBe 16f
        for (id in listOf("default", "niuma_dark")) {
            theme.presetColorSchemes.getValue(id).getValue("text_back_color") shouldBe "0x00000000"
        }
    }
    test("toolbar includes real voice and local clipboard/edit/phrase/emoji actions") {
        val toolbar = load().toolBar
        toolbar.buttons.map { it.action }.shouldContainAll(
            "VOICE_ASSIST", "liquid_keyboard_emoji", "clipboard_window", "niuma_edit", "niuma_phrases", "Hide",
        )
        toolbar.primaryButton?.action shouldBe "Settings"
        toolbar.buttons.single { it.action == "liquid_keyboard_emoji" }.foreground?.style shouldBe "vec@emoji"
        toolbar.buttons.single { it.action == "liquid_keyboard_emoji" }.foreground?.fontSize shouldBe 20f
        toolbar.buttons.single { it.action == "VOICE_ASSIST" }.foreground?.style shouldBe "vec@voice"
        toolbar.primaryButton?.foreground?.style shouldBe "vec@settings"
        toolbar.buttons.all { it.size == listOf(if (it.action == "Hide") 36 else if (it.action == "liquid_keyboard_emoji") 40 else 42, 38) } shouldBe true
    }
    test("space long-press routes to the existing voice assistant without changing click") {
        val keyboard = load().presetKeyboards.getValue("default")
        val space = keyboard.keys.single { (it.click as? KeyActionToken.Plain)?.token == "space" }
        (space.longClick as? KeyActionToken.Plain)?.token shouldBe "VOICE_ASSIST"
        load().presetKeys.getValue("space").repeatable shouldBe false
        load().presetKeys.getValue("Return1").label shouldBe "enter_labels"
        space.width shouldBe 32f
        // Bottom-row widths and order are unchanged by the label additions.
        val row = keyboard.keys.takeLast(7)
        row.map { it.width } shouldBe listOf(12f, 12f, 10f, 32f, 10f, 12f, 12f)
    }
    test("function keys carry vec@ glyph labels while keeping their real actions") {
        val keyboard = load().presetKeyboards.getValue("default")
        fun labelOf(action: String): String? =
            keyboard.keys.firstOrNull { (it.click as? KeyActionToken.Plain)?.token == action }?.label
        labelOf("Shift_L") shouldBe "vec@shift"
        labelOf("BackSpace") shouldBe "vec@backspace"
        labelOf("Keyboard_number") shouldBe "vec@number"
        labelOf("Menu") shouldBe "vec@schema"
        labelOf("space") shouldBe "vec@space"
        labelOf("Keyboard_symbols") shouldBe "vec@symbol"
        keyboard.keys.single { (it.click as? KeyActionToken.Plain)?.token == "space" }.labelSymbol shouldBe "vec@voice"
        // Menu keeps its long-press mode switch; letters are untouched.
        val menu = keyboard.keys.single { (it.click as? KeyActionToken.Plain)?.token == "Menu" }
        (menu.longClick as? KeyActionToken.Plain)?.token shouldBe "Mode_switch"
        (menu.swipeUp as? KeyActionToken.Plain)?.token shouldBe "Mode_switch"
        val letters = keyboard.keys.mapNotNull { (it.click as? KeyActionToken.Plain)?.token }
            .filter { it.length == 1 && it[0] in 'a'..'z' }
        letters.toSet() shouldBe ('a'..'z').map { it.toString() }.toSet()
    }
})
