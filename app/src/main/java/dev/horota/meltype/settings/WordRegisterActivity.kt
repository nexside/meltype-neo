// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.settings

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 1 語をユーザー辞書に登録する小さな画面 (入力中の候補の「登録」ボタンから開く)。
 * 読みと単語を入れて「登録」を押すと userdict.txt に足す。反映は次に入力欄を開いたとき。
 */
class WordRegisterActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val reading = EditText(this).apply {
            hint = "読み (ひらがな)"
            setText(intent.getStringExtra(EXTRA_READING).orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val word = EditText(this).apply {
            hint = "単語"
            setText(intent.getStringExtra(EXTRA_WORD).orEmpty())
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        val save = Button(this).apply {
            text = "登録"
            setOnClickListener {
                val rawReading = reading.text.toString()
                val rawWord = word.text.toString()
                isEnabled = false
                reading.isEnabled = false
                word.isEnabled = false
                DictionaryJobs.run(this@WordRegisterActivity, { register(rawReading, rawWord) }, { message ->
                    isEnabled = true
                    reading.isEnabled = true
                    word.isEnabled = true
                    Toast.makeText(context, message.text, Toast.LENGTH_LONG).show()
                    if (message.ok) finish()
                }, {
                    isEnabled = true
                    reading.isEnabled = true
                    word.isEnabled = true
                    Toast.makeText(context, "登録できませんでした。元の辞書は保持されています", Toast.LENGTH_LONG).show()
                })
            }
        }
        val cancel = Button(this).apply {
            text = "キャンセル"
            setOnClickListener { finish() }
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 32, 48, 32)
                addView(TextView(context).apply { text = "ユーザー辞書に登録"; textSize = 18f })
                addView(reading)
                addView(word)
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(save)
                        addView(cancel)
                    },
                )
            },
        )
    }

    private class Message(val ok: Boolean, val text: String)

    private fun register(rawReading: String, rawWord: String): Message {
        val reading = UserDictionaryImport.normalizeReading(rawReading)
        val word = rawWord.trim()
        if (reading.length < UserDictionaryImport.MIN_READING_LENGTH) {
            return Message(false, "読みは ${UserDictionaryImport.MIN_READING_LENGTH} 文字以上にしてください")
        }
        if (!UserDictionaryImport.isValidEntry(reading, word)) {
            return Message(false, "読みはひらがな・英数字だけ、単語は空にできません (タブや改行も使えません)")
        }
        val file = File(MeltypePrefs.dataDirectory(this), "userdict.txt")
        val merged = DictionaryStorage.merge(file, listOf(UserDictionaryImport.Entry(reading, word)))
        if (merged.added == 0) return Message(true, "もう登録されています")
        return Message(true, "「$word」を登録しました")
    }

    companion object {
        const val EXTRA_READING = "reading"
        const val EXTRA_WORD = "word"
    }
}
