// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.settings

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.TypedValue
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.os.Build
import android.view.Gravity
import dev.horota.meltype.ui.ScreenInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Switch
import dev.horota.meltype.ime.ThemeMode
import android.widget.TextView
import android.widget.Toast

/**
 * 設定。セクションごとのカードにまとめ、色は端末のダークモードに従う。
 * 初期設定は、済んでいれば畳んでおく (手順はボタンで開ける)。
 */
class SettingsActivity : Activity() {
    private lateinit var prefs: MeltypePrefs

    override fun onCreate(savedInstanceState: Bundle?) {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        title = "${getString(dev.horota.meltype.R.string.app_name)} ver ${dev.horota.meltype.BuildConfig.VERSION_NAME}"
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        prefs = MeltypePrefs(this)
        try {
            setScreen(buildSettingsUi())
        } catch (e: Throwable) {
            // 整理した画面が端末で作れないときは、原因をそのまま画面に出す (原因を特定するため)
            if (prefs.diagnosticLogging) DiagLog.add(MeltypePrefs.dataDirectory(this), "settings build FAILED: ${e.javaClass.simpleName}")
            val trace = android.util.Log.getStackTraceString(e)
            setScreen(ScrollView(this).apply {
                addView(TextView(context).apply {
                    text = "設定画面を作れませんでした。\n$e\n\n$trace"
                    textSize = 11f
                    setTextIsSelectable(true)
                    setPadding(32, 32, 32, 32)
                })
            })
        }
    }

    private fun isNight(): Boolean = resources.configuration.uiMode and
        android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun setScreen(content: View) {
        val screen = LinearLayout(this).apply {
            tag = "settings-safe-root"
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(if (isNight()) Color.rgb(18, 18, 18) else Color.rgb(250, 250, 250))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
            addView(TextView(context).apply {
                text = getString(dev.horota.meltype.R.string.app_name)
                tag = "settings-title"
                textSize = 21f
                setTextColor(if (isNight()) Color.WHITE else Color.BLACK)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(TextView(context).apply {
                text = "ver ${dev.horota.meltype.BuildConfig.VERSION_NAME}"
                tag = "settings-version"
                textSize = 15f
                setTextColor(if (isNight()) Color.LTGRAY else Color.DKGRAY)
            })
        }
        screen.addView(header)
        screen.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        ScreenInsets.apply(screen, includeIme = true)
        setContentView(screen)
        if (Build.VERSION.SDK_INT >= 30) {
            val appearance = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (isNight()) 0 else appearance, appearance)
        }
    }

    private fun buildSettingsUi(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(20))
        }

        // 初期設定 (済んでいれば畳む)
        val setup = card(root, "初期設定")
        val done = setupDone()
        val steps = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        steps.addView(note("一度だけ、上から順に設定します。"))
        steps.addView(button("1. Meltype を有効にする") { open(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) })
        steps.addView(button("2. Meltype を選ぶ") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() })
        steps.addView(button("3. キーボード配列") { open(Intent(Settings.ACTION_HARD_KEYBOARD_SETTINGS)) })
        steps.addView(note("JIS キーボードは、配列を「日本語」にします。物理キーボードがない場合は画面QWERTYキーを表示します。"))
        if (done) {
            steps.visibility = View.GONE
            setup.addView(note("有効化と選択は済んでいます。"))
            setup.addView(button("手順を表示") { view ->
                val show = steps.visibility != View.VISIBLE
                steps.visibility = if (show) View.VISIBLE else View.GONE
                (view as? Button)?.text = if (show) "手順を隠す" else "手順を表示"
            })
        }
        setup.addView(steps)

        // 切り替え
        val toggle = card(root, "切り替え")
        toggle.addView(note("半角/全角はいつでも使えます。Ctrl+半角/全角 で一時停止します。"))
        toggle.addView(switch("Shift+Space で切り替える", prefs.shiftSpaceToggles) { prefs.shiftSpaceToggles = it })
        toggle.addView(note("Zinwa Q25 向け。"))
        toggle.addView(switch("Ctrl+Space で切り替える", prefs.ctrlSpaceToggles) { prefs.ctrlSpaceToggles = it })

        // 入力
        val input = card(root, "入力")
        input.addView(switch("画面キーボードを常に表示", prefs.alwaysShowSoftwareKeyboard) { prefs.alwaysShowSoftwareKeyboard = it })
        input.addView(note("標準では物理キーボードがない場合だけ表示します。"))
        input.addView(switch("確定したかっこの中にカーソルを置く", prefs.cursorBetweenBrackets) { prefs.cursorBetweenBrackets = it })
        input.addView(note("() や 「」 を確定したとき。"))
        input.addView(switch("変換中にFキー行を自動で出す", prefs.fBarAutoShow) { prefs.fBarAutoShow = it })
        input.addView(switch("Fキー行を常に表示", prefs.fBarPinned) { prefs.fBarPinned = it })
        input.addView(note("常に表示は、Fキー行のピンの固定と同じ設定です。自動で出すを切ると、固定したときだけ出ます。"))
        input.addView(switch("物理キー入力で軽く振動", prefs.keyVibrate) { prefs.keyVibrate = it })
        input.addView(Spinner(this).apply {
            val names = listOf("振動の強さ：弱い", "振動の強さ：普通", "振動の強さ：強い")
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(prefs.vibeLevel)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.vibeLevel = position
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        })
        input.addView(note("打つたびに振動します。電池の持ちが気になるときは切ってください。"))
        input.addView(note("絵文字は Ctrl+. (Ctrl+; と Win+. も)。Ctrl の無い Q25 は Alt+Enter。"))

        // 表示
        val displayCard = card(root, "表示")
        displayCard.addView(note("候補の色。この設定画面自体は、端末のダークモードに従います。"))
        displayCard.addView(Spinner(this).apply {
            val names = listOf("システムに合わせる", "ライト", "ダーク")
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(prefs.themeMode.ordinal)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.themeMode = ThemeMode.values()[position]
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        })
        displayCard.addView(note("Fキー行の見た目。おまかせでは、Q25 で青、それ以外の機種ではダークになります。"))
        displayCard.addView(Spinner(this).apply {
            val names = listOf("おまかせ", "ダーク", "Q25ブルー")
            adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(prefs.keycapStyle)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    prefs.keycapStyle = position
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        })

        // 辞書
        val dict = card(root, "辞書")
        dict.addView(button("ユーザー辞書") { startActivity(Intent(this, UserDictionaryActivity::class.java)) })
        dict.addView(button("ジャンル別の辞書") { startActivity(Intent(this, GenreDictionaryActivity::class.java)) })
        dict.addView(dangerButton("学習データを消す") { confirmClearLearning() })
        dict.addView(note("覚えた変換だけ消します。ユーザー辞書と設定は残ります。"))

        // コード入力
        val code = card(root, "コード入力")
        code.addView(note("コード入力モードにするアプリ。パッケージ名を 1 行に 1 つ。"))
        val codeApps = EditText(this).apply {
            setText(CodeAppList.format(prefs.codeApps))
            minLines = 3
            setHint("com.example.app")
        }
        code.addView(codeApps)
        code.addView(button("パッケージ名を保存") {
            prefs.codeApps = CodeAppList.parse(codeApps.text.toString())
            codeApps.setText(CodeAppList.format(prefs.codeApps))
            Toast.makeText(this, "保存しました", Toast.LENGTH_SHORT).show()
        })

        // その他
        val other = card(root, "その他")
        other.addView(note("バージョン ${dev.horota.meltype.BuildConfig.VERSION_NAME}"))
        other.addView(switch("診断ログを記録する", prefs.diagnosticLogging) {
            prefs.diagnosticLogging = it
            if (!it) DiagLog.clear(MeltypePrefs.dataDirectory(this))
        })
        other.addView(note("標準ではオフ。入力文字は記録しません。"))
        other.addView(button("診断ログ") { startActivity(Intent(this, DiagLogActivity::class.java)) })
        other.addView(button("ライセンス") { showNotices() })

        return ScrollView(this).apply { addView(root) }
    }

    /**
     * Meltype の有効化と選択が済んでいるか。
     * Settings.Secure の enabled_input_methods は、対象 SDK 34 以降のアプリからは読めない
     * (SecurityException になる) ので、有効な一覧は InputMethodManager から取る。
     * 選択中かどうかも読めない環境では、有効なら済んだものとして扱う (手順はボタンで開ける)。
     */
    private fun setupDone(): Boolean {
        val serviceClass = dev.horota.meltype.ime.MeltypeImeService::class.java.name
        val imm = getSystemService(InputMethodManager::class.java)
        val isEnabled = imm?.enabledInputMethodList?.any {
            it.packageName == packageName && it.serviceName == serviceClass
        } == true
        if (!isEnabled) return false
        val selected = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
        } catch (e: SecurityException) {
            null
        } ?: return true
        val sel = android.content.ComponentName.unflattenFromString(selected) ?: return true
        return sel.packageName == packageName && sel.className == serviceClass
    }

    private fun confirmClearLearning() {
        AlertDialog.Builder(this)
            .setMessage("覚えた変換・英語 / 日本語の判定を消します (ユーザー辞書と設定は残ります)。Meltype を再起動します。")
            .setPositiveButton("消す") { _, _ ->
                LearningData.requestClear(MeltypePrefs.dataDirectory(this))
                // 同じプロセスの IME ごと終了する。次に IME が起動したとき (CoreBridge.open) に消える。
                Process.killProcess(Process.myPid())
            }
            .setNegativeButton("やめる", null)
            .show()
    }

    private fun showNotices() {
        val text = Notices.compose { name ->
            try {
                assets.open("notices/$name").bufferedReader().use { it.readText() }
            } catch (e: java.io.IOException) {
                null
            }
        }
        AlertDialog.Builder(this)
            .setTitle("ライセンス")
            .setView(ScrollView(this).apply {
                addView(TextView(context).apply {
                    this.text = text
                    textSize = 11f
                    setTextIsSelectable(true)
                    setPadding(32, 16, 32, 16)
                })
            })
            .setPositiveButton("閉じる", null)
            .show()
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
    }

    /** セクションのカードを作って root に足し、中身を入れる LinearLayout を返す。 */
    private fun card(root: LinearLayout, title: String): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(themeColor(android.R.attr.colorBackgroundFloating, 0xFFF7F7F7.toInt()))
                cornerRadius = dp(14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }
        }
        body.addView(TextView(this).apply {
            text = title
            textSize = 15.5f
            paint.isFakeBoldText = true
            setPadding(0, 0, 0, dp(6))
        })
        root.addView(body)
        return body
    }

    /** 端末テーマの色を引く。引けなければ fallback。 */
    private fun themeColor(attr: Int, fallback: Int): Int {
        val value = TypedValue()
        return if (theme.resolveAttribute(attr, value, true)) value.data else fallback
    }

    private fun note(text: String) = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(themeColor(android.R.attr.textColorSecondary, 0xFF888888.toInt()))
        setPadding(0, dp(2), 0, dp(6))
    }

    private fun button(text: String, onClick: (View) -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener(onClick)
    }

    private fun dangerButton(text: String, onClick: (View) -> Unit) = button(text, onClick).apply {
        setTextColor(themeColor(android.R.attr.colorError, 0xFFE53935.toInt()))
    }

    private fun switch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        this.text = text
        isChecked = checked
        setPadding(0, dp(6), 0, dp(6))
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()
}
