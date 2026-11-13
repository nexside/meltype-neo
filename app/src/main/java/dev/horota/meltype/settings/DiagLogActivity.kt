// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 診断ログを見る画面 (表示の不具合の調査用)。 */
class DiagLogActivity : Activity() {
    private lateinit var body: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        body = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
        }
        val copy = Button(this).apply {
            text = "コピー"
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("meltype", body.text))
                Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
            }
        }
        val reload = Button(this).apply {
            text = "更新"
            setOnClickListener { refresh() }
        }
        val clear = Button(this).apply {
            text = "消す"
            setOnClickListener {
                DiagLog.clear(MeltypePrefs.dataDirectory(context))
                refresh()
            }
        }
        setContentView(
            ScrollView(this).apply {
                addView(
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(24, 24, 24, 24)
                        addView(LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            addView(reload)
                            addView(copy)
                            addView(clear)
                        })
                        addView(body)
                    },
                )
            },
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val directory = MeltypePrefs.dataDirectory(this)
        DictionaryJobs.run(this, { DiagLog.read(directory) }, { body.text = it }, { body.text = "(読めません)" })
    }
}
