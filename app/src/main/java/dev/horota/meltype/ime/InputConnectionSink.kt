// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.ime

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.inputmethod.InputConnection

/** EditorSink の本物。InputConnection は入力欄が変わるたびに変わるので、毎回取り直す。 */
class InputConnectionSink(
    private val connection: () -> InputConnection?,
    /** 確定した文字を知らせる (最近使った絵文字を覚えるため)。 */
    private val onCommit: (String) -> Unit = {},
) : EditorSink {
    override fun beginBatch() {
        connection()?.beginBatchEdit()
    }

    override fun endBatch() {
        connection()?.endBatchEdit()
    }

    override fun textBeforeCursor(length: Int): String? = connection()?.getTextBeforeCursor(length, 0)?.toString()

    override fun textAfterCursor(length: Int): String? = connection()?.getTextAfterCursor(length, 0)?.toString()

    override fun deleteBefore(length: Int) {
        connection()?.deleteSurroundingText(length, 0)
    }

    override fun deleteBackward() {
        val ic = connection() ?: return
        if (!ic.getSelectedText(0).isNullOrEmpty()) {
            ic.commitText("", 1)
        } else if (!ic.deleteSurroundingTextInCodePoints(1, 0)) {
            super<EditorSink>.deleteBackward()
        }
    }

    override fun commit(text: String) {
        connection()?.commitText(text, 1)
        onCommit(text)
    }

    override fun commitInside(open: String, close: String) {
        val ic = connection() ?: return
        ic.commitText(open, 1)
        // newCursorPosition が 0 以下なら、この文字の手前にカーソルが来る (= 間になる)
        ic.commitText(close, 0)
    }

    override fun setComposing(text: String) {
        connection()?.setComposingText(text, 1)
    }

    override fun setComposing(text: String, highlight: IntRange?) {
        if (highlight == null || text.isEmpty()) return setComposing(text)
        val styled = SpannableString(text)
        val start = highlight.first.coerceIn(0, text.length)
        val end = (highlight.last + 1).coerceIn(start, text.length)
        if (end > start) {
            styled.setSpan(BackgroundColorSpan(Color.rgb(0x4C, 0xA0, 0xFF)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            styled.setSpan(ForegroundColorSpan(Color.WHITE), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        connection()?.setComposingText(styled, 1)
    }

    override fun finishComposing() {
        connection()?.finishComposingText()
    }
}
