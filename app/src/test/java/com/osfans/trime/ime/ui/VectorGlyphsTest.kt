package com.osfans.trime.ime.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class VectorGlyphsTest :
    StringSpec({
        "explicit glyph tokens expose human-readable descriptions" {
            listOf(
                "settings" to "设置",
                "phrase" to "短语",
                "edit" to "编辑",
                "clipboard" to "剪贴板",
                "emoji" to "表情",
                "voice" to "语音输入",
                "shift" to "大小写切换",
                "capslock" to "大写锁定",
                "backspace" to "删除",
                "number" to "数字",
                "schema" to "输入方案",
                "space" to "空格",
                "symbol" to "符号",
                "search" to "搜索",
                "send" to "发送",
                "return" to "换行",
                "done" to "完成",
                "go" to "前往",
                "next" to "下一项",
                "previous" to "上一项",
                "cut" to "剪切",
                "copy" to "复制",
                "paste" to "粘贴",
                "close" to "关闭",
            ).forEach { (glyph, label) ->
                VectorGlyphs.description("vec@$glyph") shouldBe label
            }
        }

        "unknown vector tokens are not claimed as glyphs" {
            VectorGlyphs.description("vec@not_a_glyph") shouldBe null
            VectorGlyphs.description("plain text") shouldBe null
            VectorGlyphs.description("settings") shouldBe null
            VectorGlyphs.description("space") shouldBe null
        }
    })
