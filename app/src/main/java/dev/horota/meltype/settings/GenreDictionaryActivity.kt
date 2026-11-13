// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** ジャンル一覧の集計・辞書の生成も、入力や画面操作のスレッドを止めずに行う。 */
class GenreDictionaryActivity : Activity() {
    private lateinit var store: GenreStore
    private lateinit var root: LinearLayout
    private var busy = false
    private data class Snapshot(val counts: Map<String, Int>, val mine: List<String>, val enabled: Set<String>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = GenreStore(applicationContext)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun snapshot(): Snapshot {
        val mine = store.userGenres()
        val keys = GenreCatalog.builtIns.map { it.key } + mine.map { "user:$it" }
        return Snapshot(keys.associateWith { store.entries(it).size }, mine, MeltypePrefs(applicationContext).enabledGenres)
    }

    private fun reload(mutate: () -> Unit = {}) {
        if (busy) return
        setBusy(true)
        DictionaryJobs.run(this, { mutate(); snapshot() }, {
            setBusy(false)
            render(it)
        }, {
            setBusy(false)
            Toast.makeText(this, "辞書を更新できませんでした: ${it.message.orEmpty()}", Toast.LENGTH_LONG).show()
            // チェック状態を実際の設定へ戻す。
            setBusy(true)
            DictionaryJobs.run(this, ::snapshot, { data -> setBusy(false); render(data) }, { setBusy(false) })
        })
    }

    private fun setBusy(value: Boolean) {
        busy = value
        fun enable(view: View) {
            view.isEnabled = !value
            if (view is ViewGroup) for (i in 0 until view.childCount) enable(view.getChildAt(i))
        }
        enable(root)
    }

    private fun render(data: Snapshot) {
        root.removeAllViews()
        root.addView(heading("ジャンル別の辞書"))
        root.addView(TextView(this).apply {
            text = "使う分野をオンにすると、その分野の用語が変換で出ます。入力欄を開き直すと反映されます。"
        })
        root.addView(heading("同梱のジャンル"))
        for (genre in GenreCatalog.builtIns) {
            root.addView(switch(genre.key, "${genre.name} (${data.counts[genre.key] ?: 0} 語)\n${genre.description}", data))
        }
        root.addView(heading("自分のジャンル"))
        if (data.mine.isEmpty()) root.addView(TextView(this).apply { text = "まだありません。" })
        for (name in data.mine) {
            val key = "user:$name"
            root.addView(switch(key, "$name (${data.counts[key] ?: 0} 語)", data))
            root.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(smallButton("語を登録・編集") {
                    startActivity(Intent(context, UserDictionaryActivity::class.java).putExtra(UserDictionaryActivity.EXTRA_GENRE, name))
                })
                addView(smallButton("削除") { confirmDelete(name) })
            })
        }
        root.addView(smallButton("ジャンルを作る") { askName() })
    }

    private fun switch(key: String, label: String, data: Snapshot) = CheckBox(this).apply {
        text = label
        isChecked = key in data.enabled
        setOnCheckedChangeListener { _, checked -> reload { store.setEnabled(key, checked) } }
    }

    private fun askName() {
        val input = EditText(this).apply {
            hint = "例: 料理、鉄道"
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        AlertDialog.Builder(this)
            .setTitle("ジャンルの名前")
            .setView(input)
            .setPositiveButton("作る") { _, _ ->
                val name = input.text.toString()
                reload { store.create(name)?.let { throw IllegalArgumentException(it) } }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun confirmDelete(name: String) {
        AlertDialog.Builder(this)
            .setMessage("ジャンル「$name」とその語を削除しますか?")
            .setPositiveButton("削除") { _, _ -> reload { store.delete(name) } }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun heading(text: String) = TextView(this).apply {
        this.text = text
        textSize = 18f
        setPadding(0, 32, 0, 8)
    }

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setOnClickListener { onClick() }
    }
}
