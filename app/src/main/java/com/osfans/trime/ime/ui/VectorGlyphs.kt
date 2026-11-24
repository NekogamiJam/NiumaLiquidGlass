package com.osfans.trime.ime.ui

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.graphics.drawable.DrawableCompat
import com.osfans.trime.R

/** Resolves opt-in theme glyph tokens to real, tintable Android vector resources. */
object VectorGlyphs {
    private data class Glyph(@androidx.annotation.DrawableRes val resource: Int, val description: String)

    private val glyphs = mapOf(
        "settings" to Glyph(R.drawable.vec_settings, "设置"),
        "phrase" to Glyph(R.drawable.vec_phrase, "短语"),
        "edit" to Glyph(R.drawable.vec_edit, "编辑"),
        "clipboard" to Glyph(R.drawable.vec_clipboard, "剪贴板"),
        "emoji" to Glyph(R.drawable.vec_emoji, "表情"),
        "voice" to Glyph(R.drawable.vec_voice, "语音输入"),
        "shift" to Glyph(R.drawable.vec_shift, "大小写切换"),
        "capslock" to Glyph(R.drawable.vec_capslock, "大写锁定"),
        "backspace" to Glyph(R.drawable.vec_backspace, "删除"),
        "number" to Glyph(R.drawable.vec_number, "数字"),
        "schema" to Glyph(R.drawable.vec_schema, "输入方案"),
        "space" to Glyph(R.drawable.vec_space, "空格"),
        "symbol" to Glyph(R.drawable.vec_symbol, "符号"),
        "search" to Glyph(R.drawable.vec_search, "搜索"),
        "send" to Glyph(R.drawable.vec_send, "发送"),
        "return" to Glyph(R.drawable.vec_return, "换行"),
        "done" to Glyph(R.drawable.vec_done, "完成"),
        "go" to Glyph(R.drawable.vec_go, "前往"),
        "next" to Glyph(R.drawable.vec_next, "下一项"),
        "previous" to Glyph(R.drawable.vec_previous, "上一项"),
        "cut" to Glyph(R.drawable.vec_cut, "剪切"),
        "copy" to Glyph(R.drawable.vec_copy, "复制"),
        "paste" to Glyph(R.drawable.vec_paste, "粘贴"),
        "close" to Glyph(R.drawable.vec_close, "关闭"),
    )

    private fun glyph(token: String): Glyph? =
        if (token.startsWith(PREFIX)) glyphs[token.removePrefix(PREFIX)] else null

    fun description(token: String): String? = glyph(token)?.description

    fun drawable(context: Context, token: String, tint: Int): Drawable? {
        val glyph = glyph(token) ?: return null
        return AppCompatResources.getDrawable(context, glyph.resource)?.mutate()?.also {
            DrawableCompat.setTint(it, tint)
        }
    }

    const val PREFIX = "vec@"
}
