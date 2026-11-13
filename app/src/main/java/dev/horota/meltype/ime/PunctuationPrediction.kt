// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.ime

import android.view.KeyEvent
import dev.horota.meltype.input.KeyInput

/**
 * 確定済みの文を見て、「？」を候補にするかだけを決める。自動では入れない。
 * 単語の予測は本家の Core の仕事で、ここではやらない。
 */
object PunctuationPrediction {
    private val notAQuestion = listOf(
        "ほか", "僅か", "わずか", "いつか", "なにか", "何か",
        "だれか", "誰か", "どこか", "なぜか", "何故か", "どうか",
        "たしか", "確か", "まさか", "どうにか", "なんだか",
    )

    /** カーソルの前が疑問の形で、まだ記号が無く、直後に文が続いていなければ「？」。 */
    fun questionMark(before: String?, after: String?): String? {
        val tail = before?.trimEnd() ?: return null
        if (tail.isEmpty() || tail.last() in "？?！!。.") return null
        val rest = after.orEmpty().dropWhile { it == ' ' || it == '\u3000' || it == '\t' }
        if (rest.isNotEmpty() && rest.first() != '\n') return null
        val sentence = tail.substringAfterLast('。').substringAfterLast('\n')
            .substringAfterLast('！').substringAfterLast('!').trim()
        if (sentence.length < 2) return null
        if (notAQuestion.any { sentence.endsWith(it) }) return null
        val asks = sentence.endsWith("かな") || sentence.endsWith("だろ") || sentence.endsWith("だろう") || sentence.endsWith("か")
        return if (asks) "？" else null
    }

    /** 候補が出ているとき、これで「？」を入れる。Shift+Space は切り替えのまま残す。 */
    fun accepts(key: KeyInput): Boolean {
        if (key.metaState and (KeyEvent.META_ALT_ON or KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON) != 0) return false
        if (key.keyCode == KeyEvent.KEYCODE_SPACE) return key.metaState and KeyEvent.META_SHIFT_ON == 0
        return key.keyCode == KeyEvent.KEYCODE_ENTER ||
            key.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            key.keyCode == KeyEvent.KEYCODE_DPAD_CENTER
    }
}
