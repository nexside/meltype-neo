// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.ime

import dev.horota.meltype.core.CompositionView

/** 入力欄への書き込み。本物は InputConnectionSink (InputConnection)。 */
interface EditorSink {
    fun beginBatch()
    fun endBatch()
    fun textBeforeCursor(length: Int): String?
    fun textAfterCursor(length: Int): String?
    fun deleteBefore(length: Int)
    fun deleteBackward() {
        val before = textBeforeCursor(2).orEmpty()
        val pair = before.length >= 2 && Character.isHighSurrogate(before[before.length - 2]) && Character.isLowSurrogate(before.last())
        deleteBefore(if (pair) 2 else 1)
    }
    fun commit(text: String)
    /** open + close を確定して、カーソルを間に置く (「()」の中に書き始められるように)。非対応なら続けて確定するだけ。 */
    fun commitInside(open: String, close: String) {
        commit(open + close)
    }
    /** 未確定の文字を出す。"" なら消す。 */
    fun setComposing(text: String)
    /** 未確定の文字のうち highlight の範囲 (変換中の文節) を反転して出す。非対応なら今までどおり。 */
    fun setComposing(text: String, highlight: IntRange?) {
        setComposing(text)
    }
    fun finishComposing()
}

/** 候補バー。 */
interface CandidateDisplay {
    /** inlineReading: 入力欄が未確定の文字を描かないので、候補バーに読みも出す (Termux など)。 */
    fun show(view: CompositionView, inlineReading: Boolean)
    fun hide()
}
