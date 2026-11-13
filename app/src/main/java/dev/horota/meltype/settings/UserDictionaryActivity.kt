// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.settings

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.view.View
import android.view.ViewGroup
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/**
 * 本家の userdict.txt (「読み[Tab]単語」) の編集と、テキストの一括登録。
 * 反映は、次に入力欄を開いたとき (Meltype が入力の本体を作り直す)。
 */
class UserDictionaryActivity : Activity() {
    private lateinit var file: File
    private var genre: String? = null
    private lateinit var editor: EditText
    private lateinit var bulk: EditText
    private lateinit var result: TextView
    private lateinit var count: TextView
    private lateinit var root: LinearLayout
    private var lastSavedText = ""
    private var busy = false

    private data class Content(val text: String, val words: Int)
    private data class Imported(val content: Content, val summary: String, val clearInput: Boolean)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val genreName = intent.getStringExtra(EXTRA_GENRE)
        genre = genreName
        file = if (genreName != null) GenreStore(this).userGenreFile(genreName) else File(MeltypePrefs.dataDirectory(this), "userdict.txt")
        title = if (genreName != null) "ジャンル: $genreName" else "ユーザー辞書"

        count = TextView(this)
        result = TextView(this).apply { gravity = Gravity.START }

        bulk = EditText(this).apply {
            hint = "めるたいぷ\tMeltype\nきごうとう,記号等\nにほん 日本"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 6
            gravity = Gravity.TOP or Gravity.START
        }
        val addBulk = Button(this).apply {
            text = "貼り付けた内容を追加"
            setOnClickListener { addFromText(bulk.text.toString(), clearInput = true) }
        }
        val pick = Button(this).apply {
            text = "ファイルから取り込む"
            setOnClickListener { pickFile() }
        }

        editor = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 10
            gravity = Gravity.TOP or Gravity.START
        }
        val save = Button(this).apply {
            text = "保存"
            setOnClickListener {
                val text = editor.text.toString()
                val expected = lastSavedText
                task(work = {
                    val invalid = UserDictionaryText.invalidLines(text)
                    require(invalid.isEmpty()) { "形式が違う行: ${invalid.take(MAX_SHOWN_LINES).joinToString()} (読み[Tab]単語)" }
                    DictionaryStorage.save(file, text, expected)
                    syncGenre()
                    content(text)
                }) {
                    showContent(it)
                    Toast.makeText(this@UserDictionaryActivity, "保存しました。次に入力欄を開いたときに反映されます", Toast.LENGTH_LONG).show()
                }
            }
        }

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            addView(heading("一括登録"))
            addView(
                TextView(context).apply {
                    text = "1 行に 1 語。「読み[Tab]単語」「読み,単語」「読み 単語」のどれでも読めます。" +
                        "Microsoft IME の一覧の出力や Google 日本語入力のエクスポート (UTF-8 / UTF-16) もそのまま取り込めます。" +
                        "! と # で始まる行は飛ばし、読みのカタカナはひらがなにします。同じ読みと単語は二重に登録しません。"
                },
            )
            addView(bulk)
            addView(addBulk)
            addView(pick)
            addView(result)
            addView(heading("登録済みの内容 (直接編集)"))
            addView(TextView(context).apply { text = "1 行に「読み[Tab]単語」。# と ! で始まる行はコメント。" })
            addView(count)
            addView(editor)
            addView(save)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }

    private fun pickFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_PICK)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_PICK || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        importText(clearInput = false) { readFile(uri) }
    }

    private fun readFile(uri: Uri): String {
        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            val limit = UserDictionaryImport.MAX_FILE_BYTES
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                total += n
                if (total > limit) throw IllegalArgumentException("ファイルが大きすぎます (${limit / 1024 / 1024} MB まで)")
                out.write(chunk, 0, n)
            }
            out.toByteArray()
        } ?: throw IOException("ファイルを開けませんでした")
        return UserDictionaryImport.decode(bytes)
    }

    private fun addFromText(text: String, clearInput: Boolean) {
        if (text.isBlank()) {
            showResult("追加する内容がありません")
            return
        }
        importText(clearInput) { text }
    }

    private fun importText(clearInput: Boolean, read: () -> String) {
        task(work = {
            val parsed = UserDictionaryImport.parse(read())
            val merged = DictionaryStorage.merge(file, parsed.entries)
            if (merged.added > 0) syncGenre()
            Imported(content(merged.text), summary(merged.added, merged.duplicates, parsed.skipped), clearInput && parsed.skipped.isEmpty())
        }) {
            if (it.clearInput) bulk.setText("")
            showContent(it.content)
            showResult(it.summary)
        }
    }

    private fun summary(added: Int, duplicates: Int, skipped: List<Int>): String {
        val parts = mutableListOf("${added} 語を追加しました")
        if (duplicates > 0) parts += "登録済みで飛ばした: $duplicates"
        if (skipped.isNotEmpty()) {
            val lines = skipped.take(MAX_SHOWN_LINES).joinToString() + if (skipped.size > MAX_SHOWN_LINES) " …" else ""
            parts += "読み取れなかった行 (${skipped.size}): $lines"
        }
        if (added > 0) parts += "次に入力欄を開いたときに反映されます"
        return parts.joinToString("\n")
    }

    private fun syncGenre() {
        if (genre != null) GenreStore(applicationContext).sync()
    }

    private fun content(text: String): Content = Content(text, text.lineSequence().count { line ->
        val fields = line.split('\t')
        !line.startsWith("#") && !line.startsWith("!") && fields.size >= 2 && fields[0].isNotBlank() && fields[1].isNotBlank()
    })

    private fun refresh() = task(work = { content(DictionaryStorage.read(file)) }, success = ::showContent)

    private fun showContent(content: Content) {
        lastSavedText = content.text
        editor.setText(content.text)
        count.text = "登録数: ${content.words}"
    }

    private fun setBusy(value: Boolean) {
        busy = value
        fun enable(view: View) {
            view.isEnabled = !value
            if (view is ViewGroup) for (i in 0 until view.childCount) enable(view.getChildAt(i))
        }
        enable(root)
    }

    private fun <T> task(work: () -> T, success: (T) -> Unit) {
        if (busy) return
        setBusy(true)
        showResult("処理中…")
        DictionaryJobs.run(this, work, {
            setBusy(false)
            showResult("")
            success(it)
        }, {
            setBusy(false)
            showResult("処理できませんでした: ${it.message.orEmpty()}")
        })
    }

    private fun showResult(text: String) {
        result.text = text
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, 32, 0, 8)
    }

    companion object {
        const val EXTRA_GENRE = "genre"
        const val REQUEST_PICK = 1
        const val MAX_SHOWN_LINES = 10
    }
}
