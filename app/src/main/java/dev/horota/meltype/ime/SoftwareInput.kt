// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.ime

import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import dev.horota.meltype.input.KeyInput

/** 画面キーは物理イベントを合成せず、同じ変換コントローラーへ直接渡す。 */
object SoftwareKeys {
    fun character(char: Char): KeyInput {
        val lower = char.lowercaseChar()
        val code = when (lower) {
            in 'a'..'z' -> KeyEvent.KEYCODE_A + (lower - 'a')
            in '0'..'9' -> KeyEvent.KEYCODE_0 + (lower - '0')
            ' ' -> KeyEvent.KEYCODE_SPACE
            else -> KeyEvent.KEYCODE_UNKNOWN
        }
        return KeyInput(code, char.code, lower.code, if (char in 'A'..'Z') KeyEvent.META_SHIFT_ON else 0)
    }

    fun special(code: Int) = KeyInput(code, 0, 0, 0)

    fun enterLabel(options: Int): String = when (options and EditorInfo.IME_MASK_ACTION) {
        EditorInfo.IME_ACTION_GO -> "移動"
        EditorInfo.IME_ACTION_SEARCH -> "検索"
        EditorInfo.IME_ACTION_SEND -> "送信"
        EditorInfo.IME_ACTION_NEXT -> "次へ"
        EditorInfo.IME_ACTION_DONE -> "完了"
        else -> "↵"
    }
}

class SoftwareInput(
    private val controller: ImeController,
    private val sink: EditorSink,
    private val editorAction: () -> Boolean,
) {
    fun press(key: KeyInput) {
        val consumed = try { controller.keyDown(key) } finally { controller.keyUp(key.keyCode) }
        if (consumed) return
        when (key.keyCode) {
            KeyEvent.KEYCODE_DEL -> sink.deleteBackward()
            KeyEvent.KEYCODE_ENTER -> if (!editorAction()) sink.commit("\n")
            else -> if (key.char >= 0x20) sink.commit(String(Character.toChars(key.char)))
        }
    }
}
