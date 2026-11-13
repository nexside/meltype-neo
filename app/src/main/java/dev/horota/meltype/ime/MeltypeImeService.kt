// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.ime

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.TypedValue
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.TextView
import dev.horota.meltype.ui.ScreenInsets
import dev.horota.meltype.R
import dev.horota.meltype.core.CompositionView
import dev.horota.meltype.core.CoreBridge
import dev.horota.meltype.core.CoreOpenResult
import dev.horota.meltype.input.EditorPolicy
import dev.horota.meltype.input.KeyInput
import dev.horota.meltype.settings.DictionaryFiles
import dev.horota.meltype.settings.MeltypePrefs
import dev.horota.meltype.settings.WordRegisterActivity
import java.io.File

/**
 * 物理キーと画面 QWERTY キーを同じ変換コントローラーへ渡す。
 * 物理キーでは候補をカーソルのそばに、画面キーではタップ可能な候補をキーボード内に出す。
 * Android 11 以降は、表示を頼まれていない IME の窓は見えないので、1px の入力用の窓 (input view) を出し、
 * 文字を打ち始めたら自分で表示を頼む (requestShowSelf)。
 */
class MeltypeImeService : InputMethodService(), OverlayOwner {
    override val overlay: OverlayHost by lazy { OverlayHost(this) }
    private var overlayDialog: android.app.Dialog? = null

    private fun diag(line: String) {
        if (::prefs.isInitialized && prefs.diagnosticLogging) {
            dev.horota.meltype.settings.DiagLog.add(MeltypePrefs.dataDirectory(this), line)
        }
    }

    private lateinit var softwareKeyboard: SoftwareKeyboard
    private var touchComposition: CompositionView? = null

    private fun softwareKeyboardWanted(): Boolean =
        (::prefs.isInitialized && prefs.alwaysShowSoftwareKeyboard) || !hasPhysicalKeyboard()

    private fun hasPhysicalKeyboard(): Boolean {
        val config = resources.configuration
        val external = InputDevice.getDeviceIds().toList().mapNotNull { id ->
            InputDevice.getDevice(id)?.takeIf { !it.isVirtual && it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC }?.isExternal
        }
        return PhysicalKeyboardPolicy.available(config.keyboard, config.hardKeyboardHidden, external)
    }

    private fun showKeyboardPicker() {
        if (::controller.isInitialized) controller.commitComposition()
        getSystemService(android.view.inputmethod.InputMethodManager::class.java).showInputMethodPicker()
    }

    private fun pressSoftwareKey(key: KeyInput) {
        if (!::controller.isInitialized) return
        vibrateVirtualKey()
        SoftwareInput(controller, InputConnectionSink({ currentInputConnection }, ::onCommitted)) {
            sendDefaultEditorAction(true)
        }.press(key)
        syncSoftwareKeyboard()
    }

    private fun syncSoftwareKeyboard() {
        if (!::softwareKeyboard.isInitialized || !::controller.isInitialized) return
        val visible = softwareKeyboardWanted()
        softwareKeyboard.visibility = if (visible) View.VISIBLE else View.GONE
        softwareKeyboard.setContext(controller.digitPadKind, controller.mode, controller.showsBar,
            SoftwareKeys.enterLabel(currentInputEditorInfo?.imeOptions ?: 0))
        softwareKeyboard.showComposition(touchComposition)
        inputViewRoot?.requestApplyInsets()
    }

    override fun ensureOverlay(): Boolean {
        val existing = overlayDialog
        if (existing != null && existing.isShowing) return true
        // 入力の窓が出ていないと、窓を付けられない (物理キーボードがあるとここが出ないことが多い)。出すよう頼む
        if (!isInputViewShown) {
            diag("input window not shown; requesting explicit show")
            try {
                requestShowSelf(SHOW_EXPLICIT)
            } catch (e: RuntimeException) {
                diag("requestShowSelf failed: $e")
            }
        }
        return try {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
            val d = android.app.Dialog(this)
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            d.setContentView(overlay)
            val w = d.window ?: return false
            val params = w.attributes
            // 付ける先は入力の窓の View の窓トークン (PopupWindow と同じ)。入力の窓の中のトークンではない
            val parentToken = window.window?.decorView?.windowToken
            if (parentToken == null) {
                diag("overlay: IME decor has no window token yet")
                return false
            }
            params.token = parentToken
            params.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            params.x = 0
            params.y = 0
            params.width = ViewGroup.LayoutParams.MATCH_PARENT
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.dimAmount = 0f
            w.attributes = params
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
            d.show()
            overlayDialog = d
            diag("overlay dialog shown token=${params.token != null} attached=${overlay.isAttachedToWindow}")
            true
        } catch (e: Throwable) {
            overlayDialog = null
            diag("overlay dialog FAILED: $e")
            false
        }
    }

    override fun releaseOverlayIfEmpty() {
        if (!overlay.isEmpty) return
        try {
            overlayDialog?.dismiss()
        } catch (e: RuntimeException) {
            // 窓がもう無いときは何もしない
        }
        overlayDialog = null
    }
    private lateinit var prefs: MeltypePrefs
    private lateinit var controller: ImeController
    private var core: CoreBridge? = null
    private val handler = Handler(Looper.getMainLooper())

    /** 入力欄が教えてくれた、入力カーソルの位置 (教えてくれない入力欄では null)。 */
    private var cursorInfo: CursorAnchorInfo? = null
    private lateinit var floating: FloatingCandidates
    private lateinit var emojiPanel: EmojiPanel

    /** 一覧のキーで使ったキー (離したときも入力に回さない)。 */
    private val panelConsumed = mutableSetOf<Int>()

    /** 起動時に見せる知らせ (Mozc が使えないなど)。一度だけ。 */
    private var startupNotice: String? = null

    /** 読み込んだときの userdict.txt の更新時刻。変わっていたら、入力の本体を作り直して辞書を読み直す。 */
    private var userDictStamp = 0L

    private val indicatorLabel by lazy {
        TextView(this).apply {
            // 「あ」は A より上下左右にはみ出す。枠ぴったりだと切れるので、余白と最小サイズを多めに取る
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            includeFontPadding = true
            setPadding(dp(16), dp(10), dp(16), dp(10))
            minWidth = dp(52)
            minHeight = dp(44)
            background = GradientDrawable().apply {
                setColor(Color.argb(0xE6, 0x33, 0x33, 0x33))
                cornerRadius = dp(8).toFloat()
            }
        }
    }
    private val indicatorWindow by lazy { ImeDialog({ this }, indicatorLabel, touchable = false) }
    private val hideIndicator = Runnable { indicatorWindow.dismiss() }

    private val display = object : CandidateDisplay {
        override fun show(view: CompositionView, inlineReading: Boolean) {
            touchComposition = view
            val ok = if (softwareKeyboardWanted()) {
                floating.hide()
                if (::softwareKeyboard.isInitialized) softwareKeyboard.showComposition(view)
                true
            } else floating.show(view, inlineReading)
            diag("candidates show ok=$ok cands=${view.candidates.size} inline=$inlineReading anchor=${composingAnchor()}")
            if (!fComposing) {
                fComposing = true
                syncFKeyBar()
            }
        }

        override fun hide() {
            touchComposition = null
            if (::softwareKeyboard.isInitialized) softwareKeyboard.showComposition(null)
            floating.hide()
            if (fComposing) {
                fComposing = false
                syncFKeyBar()
            }
        }
    }

    /** 「あ」/「A」の表示。切り替えたときだけ、入力カーソルの近くに短く出す。 */
    private val status = object : StatusDisplay {
        private var last: String? = null

        override fun showMode(mode: InputMode) {
            val text = when (mode) {
                InputMode.JAPANESE -> "あ"
                InputMode.DIRECT -> "A"
                InputMode.PAUSED -> "⏸"
                InputMode.UNAVAILABLE -> "⚠"
            }
            // 上のバーのアイコン (入力中だけ出る。onFinishInput で消す)
            statusIcon(mode)
            val changed = last != null && last != text
            last = text
            if (::softwareKeyboard.isInitialized) syncSoftwareKeyboard()
            if (changed && !softwareKeyboardWanted()) showIndicator(text, 1200)
        }
    }

    private fun statusIcon(mode: InputMode) {
        val icon = when (mode) {
            InputMode.JAPANESE -> R.drawable.ic_mode_ja
            InputMode.DIRECT -> R.drawable.ic_mode_en
            InputMode.PAUSED -> R.drawable.ic_mode_paused
            InputMode.UNAVAILABLE -> R.drawable.ic_mode_unavailable
        }
        try {
            showStatusIcon(icon)
        } catch (e: RuntimeException) {
            // アイコンを出せなくても入力は続ける
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = MeltypePrefs(this)
        openCore()
        floating = FloatingCandidates(
            this,
            anchor = ::composingAnchor,
            recent = { prefs.recentEmoji },
            onSelect = { index -> controller.selectCandidate(index) },
            onConvert = { target -> controller.convert(target) },
            onRegister = ::openRegistration,
        )
        emojiPanel = EmojiPanel(
            this,
            anchor = ::composingAnchor,
            recent = { prefs.recentEmoji },
            onPick = ::commitEmoji,
        )
        controller = newController()
        applyTheme()
    }

    private fun openCore() {
        when (val opened = CoreBridge.open(MeltypePrefs.dataDirectory(this), File(applicationInfo.nativeLibraryDir, "libmozc_helper.so"))) {
            is CoreOpenResult.Ready -> {
                core = opened.core
                if (!opened.mozcAvailable) startupNotice = "漢字変換を使えません"
            }
            is CoreOpenResult.Failed -> {
                core = null
                startupNotice = "Meltype を初期化できませんでした"
            }
        }
        userDictStamp = DictionaryFiles.stamp(MeltypePrefs.dataDirectory(this))
    }

    private fun newController(): ImeController =
        ImeController(core, InputConnectionSink({ currentInputConnection }, ::onCommitted), display, status).also {
            it.onOpenEmojiPanel = ::openEmojiPanel
        }


    /** ユーザー辞書が変わっていたら (設定画面・単語登録・一括登録)、入力の本体を作り直して読み直す。 */
    private fun reloadIfUserDictionaryChanged() {
        if (DictionaryFiles.stamp(MeltypePrefs.dataDirectory(this)) == userDictStamp) return
        floating.hide()
        controller.shutdown()
        core?.close()
        core = null
        openCore()
        controller = newController()
    }

    /** 候補の配色 (設定。「システムに合わせる」なら端末のダークモード) を反映する。 */
    private fun applyTheme() {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val theme = CandidateTheme.of(prefs.themeMode, night)
        floating.applyTheme(theme)
        emojiPanel.applyTheme(theme)
        if (::softwareKeyboard.isInitialized) softwareKeyboard.applyTheme(theme)
    }

    /** 確定した文字。絵文字なら「最近使った絵文字」に入れる (候補から選んだときも)。 */
    private fun onCommitted(text: String) {
        if (text.length <= MAX_EMOJI_LENGTH && EmojiData.isEmoji(text)) rememberEmoji(text)
    }

    private fun rememberEmoji(emoji: String) {
        prefs.recentEmoji = EmojiData.pushRecent(prefs.recentEmoji, emoji)
    }

    private fun openEmojiPanel() {
        emojiPanel.show()
    }

    private fun commitEmoji(emoji: String) {
        rememberEmoji(emoji)
        currentInputConnection?.commitText(emoji, 1)
    }

    /** 変換した語をユーザー辞書に登録する画面を開く (読みは、変換する前に打っていた文字)。 */
    private fun openRegistration(view: CompositionView) {
        val intent = Intent(this, WordRegisterActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(WordRegisterActivity.EXTRA_READING, controller.lastReading.orEmpty())
            .putExtra(WordRegisterActivity.EXTRA_WORD, view.text)
        startActivity(intent)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        cursorInfo = null
        syncSoftwareKeyboard()
        if (softwareKeyboardWanted()) floating.hide()
        // カーソルが動くたびに位置を教えてもらう (対応していない入力欄では何も来ない)
        currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo) {
        cursorInfo = cursorAnchorInfo
        floating.reposition()
    }

    /** 変換中の文字の左端と、その行の上端 / 下端 (画面の座標)。分からなければ null。 */
    private fun composingAnchor(): FloatingCandidates.Anchor? {
        val info = cursorInfo ?: return null
        if ((info.insertionMarkerFlags and CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION) == 0) return null
        var left = info.insertionMarkerHorizontal
        val top = info.insertionMarkerTop
        val bottom = info.insertionMarkerBottom
        if (left.isNaN() || top.isNaN() || bottom.isNaN()) return null
        val start = info.composingTextStart
        if (info.composingText != null && start >= 0) {
            try {
                info.getCharacterBounds(start)?.let { left = it.left }
            } catch (e: RuntimeException) {
                // 文字の位置を教えてくれない入力欄では、カーソルの位置を使う
            }
        }
        val points = floatArrayOf(left, top, left, bottom)
        info.matrix.mapPoints(points) // 入力欄の座標 → 画面の座標
        // 入力欄が (0, 0) のような意味のない位置を返すことがある。画面の外・高さ 0 のときは位置不明として扱う
        val metrics = resources.displayMetrics
        val valid = points[3] > points[1] && points[1] > 0f && points[3] < metrics.heightPixels &&
            points[0] >= 0f && points[0] < metrics.widthPixels
        if (!valid) return null
        return FloatingCandidates.Anchor(points[0], points[1], points[3])
    }

    /** 「あ」/「A」を、入力カーソルの上に出す。上が足りないときだけ下。画面の外には出さない。 */
    private fun showIndicator(text: String, millis: Long) {
        val metrics = resources.displayMetrics
        indicatorLabel.text = text
        indicatorLabel.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels - dp(32), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        // measured ぴったりだと字形の端が 1px 切れる
        val w = indicatorLabel.measuredWidth + dp(4)
        val h = indicatorLabel.measuredHeight + dp(6)
        val anchor = composingAnchor()
        val x = if (anchor != null) anchor.x.toInt().coerceIn(0, maxOf(0, metrics.widthPixels - w)) else maxOf(0, (metrics.widthPixels - w) / 2)
        // 入力欄が画面の一番下だと、カーソルの下は画面の外。収まる側 (ふつうは上) に出す
        val y = indicatorY(anchor, h, metrics.heightPixels)
        val shown = indicatorWindow.show(x, y, w, h)
        run { // 診断 (表示されない原因の調査用。あとで外す)
            val loc = IntArray(2)
            overlay.getLocationOnScreen(loc)
            diag("indicator '$text' shown=$shown attached=${overlay.isAttachedToWindow} host=${overlay.width}x${overlay.height}@${loc[0]},${loc[1]} place=$x,$y,${w}x$h anchor=${anchor?.let { "${it.x.toInt()},${it.top.toInt()}-${it.bottom.toInt()}" }}")
            val note = "診断 shown=$shown attached=${overlay.isAttachedToWindow} host=${overlay.width}x${overlay.height}@${loc[0]},${loc[1]} " +
                "vis=${overlay.isShown} place=$x,$y,${w}x$h anchor=${anchor?.let { "${it.x.toInt()},${it.top.toInt()}-${it.bottom.toInt()}" }} " +
                "label=${indicatorLabel.left},${indicatorLabel.top},${indicatorLabel.width}x${indicatorLabel.height} screen=${metrics.widthPixels}x${metrics.heightPixels}"
        }
        if (!shown) {
            // 窓を出せない端末・入力欄では、システムの短い通知で知らせる
            android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        handler.removeCallbacks(hideIndicator)
        handler.postDelayed(hideIndicator, millis)
    }

    /** 画面の下をふさがっている高さ (Alt の刻印、数字行)。 */
    private fun coveredBottom(): Int {
        var cover = 0
        if (symbolLegend.parent != null) {
            cover = maxOf(cover, symbolLegend.height.takeIf { it > 0 } ?: symbolLegend.measuredHeight)
        }
        if (::digitPad.isInitialized && digitPad.isActive) cover = maxOf(cover, digitPad.height)
        return cover
    }

    /** 「あ」/「A」が画面の外や、下の表示に隠れない位置。 */
    private fun indicatorY(anchor: FloatingCandidates.Anchor?, h: Int, screenH: Int): Int {
        val margin = dp(8)
        val floor = screenH - coveredBottom() - margin
        if (anchor != null) {
            val above = anchor.top.toInt() - h - dp(4)
            val below = anchor.bottom.toInt() + dp(4)
            if (above >= margin) return above
            if (below + h <= floor) return below
        }
        return (floor - h).coerceAtLeast(margin)
    }

    private fun dismissIndicator() {
        handler.removeCallbacks(hideIndicator)
        indicatorWindow.dismiss()
    }

    private fun syncDigitPad() {
        if (!::digitPad.isInitialized || !::controller.isInitialized) return
        digitPad.setKind(if (softwareKeyboardWanted()) dev.horota.meltype.input.DigitPadKind.NONE else controller.digitPadKind)
        syncSoftwareKeyboard()
        digitPad.requestLayout()
    }

    /** 数字行から直接入れる。変換にも診断ログにも残さない。 */
    private fun insertDigit(text: String) {
        vibrateVirtualKey()
        currentInputConnection?.commitText(text, 1)
    }

    private fun deleteDigitBeforeCursor() {
        vibrateVirtualKey()
        InputConnectionSink({ currentInputConnection }).deleteBackward()
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private lateinit var digitPad: DigitPad
    private lateinit var fKeyBar: FKeyBar

    /** 入力ビューのルート (タッチ可能領域の指定用)。 */
    private var inputViewRoot: View? = null

    /** 未確定の文字があるか (Fキー行を自動で出す条件)。候補の表示と連動する。 */
    private var fComposing = false

    /** Fキー行が出ているときの入力ビューの上端 (窓内座標)。一度出たら入力終了まで保持し、アプリのレイアウトが変換のたびに上下しないようにする。-1 は未保持。 */
    private var fBarHeldTop = -1

    /**
     * 物理キーのときは小さな数字行・Fキー行、タッチ端末では画面キーボードを出す。
     * その上に Fキー行 (変換中だけ自動表示、固定も可)。
     * 候補はこれまでどおり浮かべる窓に出す。
     */
    override fun onCreateInputView(): View {
        val container = android.widget.LinearLayout(this).apply { orientation = android.widget.LinearLayout.VERTICAL }
        fKeyBar = FKeyBar(this, ::pressFunctionKey, ::toggleFBarPin)
        fKeyBar.diag = { msg -> diag(msg) }
        digitPad = DigitPad(this, ::insertDigit, ::deleteDigitBeforeCursor)
        container.addView(fKeyBar)
        container.addView(digitPad)
        softwareKeyboard = SoftwareKeyboard(this, ::pressSoftwareKey, { controller.selectCandidate(it) },
            { controller.convert(it) }, ::showKeyboardPicker)
        container.addView(softwareKeyboard)
        ScreenInsets.apply(container, includeIme = false, includeTop = false, enabled = ::softwareKeyboardWanted)
        inputViewRoot = container
        if (::controller.isInitialized) syncDigitPad()
        applyTheme()
        syncFKeyBar()
        return container
    }

    /** 物理キーボードがあっても入力用の窓を出す。文字のパスワード欄では出さない。暗証番号は数字行のために出す。 */
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return softwareKeyboardWanted() || !::controller.isInitialized || controller.showsWindow || fBarWanted()
    }

    /** Fキー行を出したい状態か (固定中、または自動表示が有効で未確定の文字がある)。 */
    private fun fBarWanted(): Boolean =
        (::prefs.isInitialized && prefs.fBarPinned) || (fComposing && (!::prefs.isInitialized || prefs.fBarAutoShow))

    /** Fキー行の見た目を設定と機種に合わせる (おまかせのときは Q25 なら青、それ以外はダーク)。 */
    private fun applyKeycapStyle() {
        if (!::fKeyBar.isInitialized || !::prefs.isInitialized) return
        val preset = when (prefs.keycapStyle) {
            1 -> KeycapStyle.PRESET_DARK
            2 -> KeycapStyle.PRESET_Q25_BLUE
            else -> if (android.os.Build.MODEL?.contains("Q25") == true) {
                KeycapStyle.PRESET_Q25_BLUE
            } else {
                KeycapStyle.PRESET_DARK
            }
        }
        if (KeycapStyle.apply(preset)) {
            fKeyBar.restyle()
            if (::digitPad.isInitialized) digitPad.restyle()
        }
    }

    /** Fキー行の表示をいまの状態に合わせる (数字行と同じく、sync してから窓の表示を更新する)。 */
    private fun syncFKeyBar() {
        if (!::fKeyBar.isInitialized || !::prefs.isInitialized) return
        applyKeycapStyle()
        val want = fBarWanted() && !softwareKeyboardWanted()
        diag("fbar sync want=$want pinned=${prefs.fBarPinned} composing=$fComposing barActive=${fKeyBar.isActive} inputShown=$isInputViewShown barSize=${fKeyBar.width}x${fKeyBar.height}")
        fKeyBar.setActive(want, prefs.fBarPinned)
        try {
            updateInputViewShown()
        } catch (e: RuntimeException) {
            diag("updateInputViewShown (fbar) failed: $e")
        }
    }

    /** 画面の Fキーを、物理キーと同じ入口から流す。 */
    private fun pressFunctionKey(keyCode: Int) {
        vibrateVirtualKey()
        val now = android.os.SystemClock.uptimeMillis()
        onKeyDown(keyCode, KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        onKeyUp(keyCode, KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    /** Fキー行の固定を切り替える (設定に残す)。 */
    private fun toggleFBarPin() {
        if (!::prefs.isInitialized) return
        vibrateVirtualKey()
        prefs.fBarPinned = !prefs.fBarPinned
        diag("fbar pin -> ${prefs.fBarPinned}")
        syncFKeyBar()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onWindowHidden() {
        hideSymbolLegend()
        super.onWindowHidden()
        try {
            overlay.removeAllViews()
            overlayDialog?.dismiss()
        } catch (e: Throwable) {
            // 窓がもう無いときは何もしない
        }
        overlayDialog = null
    }

    /**
     * 数字行が出ているときはその高さだけ画面を空ける。それ以外はアプリを押し上げない。
     * あわせて、入力ビューが表示されている間はその実範囲をタッチ可能領域として明示する。
     * Q25 (LineageOS) では、物理キーボード前提で強制表示した入力窓だと、バーが描画されても
     * タッチが入力ビューまで届かない (キーの押下反応がない) ため。非表示時には指定しない
     * (見えていない領域がアプリのタッチを奪わないように)。
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val root = inputViewRoot
        var rootTop = -1
        if (root != null && root.width > 0 && root.height > 0) {
            val loc = IntArray(2)
            root.getLocationInWindow(loc)
            rootTop = loc[1]
            if (isInputViewShown) {
                outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
                outInsets.touchableRegion.set(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
            }
        }
        if (::softwareKeyboard.isInitialized && softwareKeyboard.visibility == View.VISIBLE && rootTop >= 0) {
            outInsets.contentTopInsets = rootTop
            outInsets.visibleTopInsets = rootTop
            return
        }
        if (::digitPad.isInitialized && digitPad.isActive) return
        val bottom = window.window?.decorView?.height ?: return
        if (bottom <= 0) return
        // Fキー行が出ている間は、その上端をアプリに伝える (対応アプリは入力欄を持ち上げる)。
        // 変換のたびにアプリが上下しないよう、出たときの上端を入力終了まで保持する。
        val barActiveNow = ::fKeyBar.isInitialized && fKeyBar.isActive
        if (barActiveNow && rootTop >= 0) fBarHeldTop = rootTop
        val effectiveTop = if (barActiveNow) rootTop else fBarHeldTop
        if (effectiveTop >= 0) {
            outInsets.contentTopInsets = effectiveTop
            outInsets.visibleTopInsets = effectiveTop
        } else {
            outInsets.contentTopInsets = bottom
            outInsets.visibleTopInsets = bottom
        }
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        cancelZeroHold()
        fBarHeldTop = -1
        touchComposition = null
        if (::softwareKeyboard.isInitialized) softwareKeyboard.reset()
        vibeLogged = false
        diag("onStartInput pkg=${attribute.packageName} inputView=$isInputViewShown")
        if (hasPhysicalKeyboard() && !isInputViewShown && !attribute.packageName.isNullOrEmpty()) {
            try {
                requestShowSelf(SHOW_EXPLICIT) // 物理キーボードがあっても入力の窓を出す (表示物を付けるために必要)
            } catch (e: RuntimeException) {
                diag("requestShowSelf failed: $e")
            }
        }
        reloadIfUserDictionaryChanged()
        controller.options = prefs.bindingOptions
        controller.cursorBetweenBrackets = prefs.cursorBetweenBrackets
        applyTheme()
        currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
        controller.startInput(EditorPolicy.of(attribute.inputType, attribute.packageName, prefs.codeApps, attribute.imeOptions))
        syncDigitPad()
        syncFKeyBar()
        updateInputViewShown()
        startupNotice?.let {
            startupNotice = null
            if (softwareKeyboardWanted()) android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_LONG).show()
            else showIndicator(it, 4000)
        }
    }

    override fun onFinishInput() {
        cancelZeroHold()
        hideSymbolLegend()
        if (::digitPad.isInitialized) digitPad.hide()
        touchComposition = null
        if (::softwareKeyboard.isInitialized) softwareKeyboard.showComposition(null)
        fComposing = false
        fBarHeldTop = -1
        if (::fKeyBar.isInitialized && ::prefs.isInitialized) {
            diag("fbar finishInput pinned=${prefs.fBarPinned} barActive=${fKeyBar.isActive}")
            fKeyBar.setActive(prefs.fBarPinned, prefs.fBarPinned)
        }
        hideStatusIcon()
        emojiPanel.hide()
        floating.hide()
        dismissIndicator()
        cursorInfo = null
        controller.finishInput()
        super.onFinishInput()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        controller.selectionChanged(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
    }

    override fun onDestroy() {
        hideStatusIcon()
        emojiPanel.hide()
        floating.hide()
        dismissIndicator()
        hideSymbolLegend()
        cancelZeroHold()
        controller.shutdown()
        core?.close()
        core = null
        super.onDestroy()
    }

    private val symbolLegend by lazy {
        SymbolLegend(this).also { legend ->
            legend.onSymbol = { letter, symbol -> onSymbolTap(letter, symbol) }
        }
    }
    private val symbolWindow by lazy { ImeDialog({ this }, symbolLegend, touchable = false) }

    /** 記号面をタップで押せるようにするための、候補窓と同じ持ち方の実ダイアログ。 */
    private var symbolDialog: android.app.Dialog? = null
    private var symbolOwnShown = false

    private fun showSymbolLegend() {
        applyKeycapStyle()
        symbolLegend.restyle()
        val metrics = resources.displayMetrics
        symbolLegend.measure(
            View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val w = symbolLegend.measuredWidth.coerceAtLeast(1)
        val h = symbolLegend.measuredHeight.coerceAtLeast(1)
        val y = (metrics.heightPixels - h).coerceAtLeast(0)
        if (!isInputViewShown) {
            try {
                requestShowSelf(SHOW_EXPLICIT)
            } catch (e: RuntimeException) {
                diag("requestShowSelf failed: $e")
            }
        }
        if (showSymbolOwn(0, y, w, h)) return
        dismissSymbolOwn()
        (symbolLegend.parent as? android.view.ViewGroup)?.removeView(symbolLegend)
        symbolWindow.show(0, y, w, h)
    }

    private fun showSymbolOwn(x: Int, y: Int, w: Int, h: Int): Boolean {
        val token = try {
            window?.window?.decorView?.windowToken
        } catch (e: RuntimeException) {
            null
        } ?: return false
        return try {
            symbolWindow.dismiss()
            (symbolLegend.parent as? android.view.ViewGroup)?.removeView(symbolLegend)
            val d = symbolDialog ?: android.app.Dialog(this).also {
                it.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
                symbolDialog = it
            }
            d.setContentView(symbolLegend)
            val win = d.window ?: return false
            val params = win.attributes
            params.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            params.token = token
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.START
            params.x = x
            params.y = y
            params.width = w
            params.height = h
            params.dimAmount = 0f
            win.attributes = params
            win.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            win.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            win.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
            if (!d.isShowing) d.show()
            symbolOwnShown = true
            true
        } catch (e: Throwable) {
            false
        }
    }

    private fun dismissSymbolOwn() {
        if (!symbolOwnShown && symbolDialog?.isShowing != true) return
        try {
            symbolDialog?.dismiss()
        } catch (e: RuntimeException) {
            // 窓がもう無いときは何もしない
        }
        symbolOwnShown = false
    }

    private fun hideSymbolLegend() {
        dismissSymbolOwn()
        symbolWindow.dismiss()
    }

    /** 記号面のタップを、物理の Alt+キーと同じ形 (KeyInput) で本体へ流す。 */
    private fun onSymbolTap(letter: String, symbol: String) {
        if (!::controller.isInitialized || letter.isEmpty() || symbol.isEmpty()) return
        vibrateVirtualKey()
        val keyCode = KeyEvent.KEYCODE_A + (letter.first() - 'a')
        val key = KeyInput(keyCode, symbol.first().code, letter.first().code, KeyEvent.META_ALT_ON)
        val handled = controller.keyDown(key)
        controller.keyUp(keyCode)
        diag("sym tap handled=$handled")
        if (handled && !isInputViewShown) {
            try {
                requestShowSelf(SHOW_EXPLICIT)
            } catch (e: RuntimeException) {
                diag("requestShowSelf failed: $e")
            }
        }
    }

    private var zeroStartedAt = 0L
    private var zeroVoiceFired = false
    private var zeroDown = false
    private var zerosTyped = 0
    private var micKeyCode = -1
    private var voiceGeneration = 0L
    private var voice: VoiceInput? = null

    private val zeroLongPress = Runnable {
        if (zeroStartedAt == 0L || zeroVoiceFired) return@Runnable
        if (::controller.isInitialized && !controller.acceptsVoice) return@Runnable
        zeroVoiceFired = true
        eraseTypedZeros()
        beginVoice()
    }

    /** 短く離したら 0。押し続けの途中で一度上がるキーは、隙間のうちはまだ押しているとみなす。 */
    private val zeroTap = Runnable {
        if (zeroDown || zeroStartedAt == 0L) return@Runnable
        if (zeroVoiceFired) {
            endVoice()
            zeroStartedAt = 0L
            zeroVoiceFired = false
            micKeyCode = -1
            return@Runnable
        }
        zeroStartedAt = 0L
        micKeyCode = -1
        typeZero()
    }

    private val micRelease = Runnable {
        if (zeroDown) return@Runnable
        endVoice()
        zeroStartedAt = 0L
        zeroVoiceFired = false
        micKeyCode = -1
    }

    private fun isQ25Key(event: KeyEvent): Boolean {
        val device = event.device ?: InputDevice.getDevice(event.deviceId)
        val name = device?.name.orEmpty()
        if (name.contains("Q25", ignoreCase = true)) return true
        val phone = "${Build.MODEL} ${Build.DEVICE} ${Build.PRODUCT}"
        if (!phone.contains("Q25", ignoreCase = true)) return false
        return device == null || !device.isVirtual
    }

    private fun isMicKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        val zero = code == KeyEvent.KEYCODE_0 || code == KeyEvent.KEYCODE_NUMPAD_0 ||
            event.unicodeChar == '0'.code || event.unicodeChar == '０'.code
        if (!zero && code != KeyEvent.KEYCODE_VOICE_ASSIST) return false
        if (event.metaState and (KeyEvent.META_ALT_ON or KeyEvent.META_CTRL_ON or KeyEvent.META_META_ON) != 0) return false
        return isQ25Key(event)
    }

    private fun onMicDown(keyCode: Int) {
        handler.removeCallbacks(zeroTap)
        handler.removeCallbacks(micRelease)
        val now = SystemClock.uptimeMillis()
        val gap = if (zeroStartedAt == 0L) Long.MAX_VALUE else now - zeroStartedAt
        if (gap > MIC_GAP_MS) {
            zeroStartedAt = now
            zeroVoiceFired = false
            zerosTyped = 0
            micKeyCode = keyCode
            handler.removeCallbacks(zeroLongPress)
            handler.postDelayed(zeroLongPress, MIC_HOLD_MS)
        }
        zeroDown = true
    }

    private fun onMicUp() {
        zeroDown = false
        if (zeroVoiceFired) {
            handler.postDelayed(micRelease, MIC_GAP_MS)
            return
        }
        handler.postDelayed(zeroTap, MIC_GAP_MS)
    }

    private fun voiceSession(): VoiceInput = voice ?: VoiceInput(
        this,
        onText = { text ->
            val generation = voiceGeneration
            handler.post {
                if (generation != voiceGeneration || !controller.acceptsVoice) return@post
                hideSpeakIcon()
                dismissIndicator()
                val connection = currentInputConnection
                if (connection != null) {
                    connection.finishComposingText()
                    connection.commitText(text, 1)
                }
            }
        },
        onEnd = { reason ->
            val generation = voiceGeneration
            handler.post {
                if (generation != voiceGeneration) return@post
                hideSpeakIcon()
                dismissIndicator()
                when (reason) {
                    "permit" -> askForMicrophone()
                    "unavailable" -> showIndicator("音声なし", 1200)
                }
            }
        },
    ).also { voice = it }

    private val speakIcon by lazy { SpeakIcon(this) }
    private val speakWindow by lazy { ImeDialog({ this }, speakIcon, touchable = false) }

    private fun showSpeakIcon() {
        val metrics = resources.displayMetrics
        val size = dp(180)
        val x = (metrics.widthPixels - size) / 2
        val y = (metrics.heightPixels - size) / 3
        speakWindow.show(x.coerceAtLeast(0), y.coerceAtLeast(0), size, size)
    }

    private fun hideSpeakIcon() {
        speakWindow.dismiss()
    }

    private fun beginVoice() {
        if (!::controller.isInitialized || !controller.acceptsVoice) return
        voiceGeneration += 1
        if (voiceSession().start()) showSpeakIcon()
    }

    private fun endVoice() {
        hideSpeakIcon()
        voice?.finish()
    }

    private fun askForMicrophone() {
        try {
            startActivity(Intent(this, MicPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            diag("voice activity failed: $e")
            showIndicator("マイク", 1500)
        }
    }

    private fun typeZero() {
        val key = KeyInput(KeyEvent.KEYCODE_0, '0'.code, '0'.code, 0)
        if (!::controller.isInitialized || !controller.keyDown(key)) {
            currentInputConnection?.commitText("0", 1)
        } else if (!isInputViewShown) {
            try {
                requestShowSelf(SHOW_EXPLICIT)
            } catch (e: RuntimeException) {
                diag("requestShowSelf failed: $e")
            }
        }
        if (::controller.isInitialized) controller.keyUp(KeyEvent.KEYCODE_0)
        zerosTyped += 1
    }

    private fun eraseTypedZeros() {
        val n = zerosTyped
        zerosTyped = 0
        if (n <= 0) return
        val before = currentInputConnection?.getTextBeforeCursor(n, 0)?.toString().orEmpty()
        if (before.length == n && before.all { it == '0' }) {
            currentInputConnection?.deleteSurroundingText(n, 0)
        }
    }

    private fun cancelZeroHold() {
        voiceGeneration += 1
        handler.removeCallbacks(zeroLongPress)
        handler.removeCallbacks(zeroTap)
        handler.removeCallbacks(micRelease)
        voice?.cancel()
        hideSpeakIcon()
        zeroStartedAt = 0L
        zeroVoiceFired = false
        zeroDown = false
        zerosTyped = 0
        micKeyCode = -1
    }

    private fun isQ25Alt(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_ALT_LEFT && event.keyCode != KeyEvent.KEYCODE_ALT_RIGHT) return false
        val name = event.device?.name ?: return false
        return name.contains("Q25", ignoreCase = true)
    }

    /** 診断用: 直前の onKeyDown の時刻 (キー同士の間隔を見る)。 */
    private var lastKeyDownAtMs = 0L

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val nowMs = android.os.SystemClock.uptimeMillis()
        val dtMs = if (lastKeyDownAtMs == 0L) -1L else nowMs - lastKeyDownAtMs
        lastKeyDownAtMs = nowMs
        val handled = onKeyDownInner(keyCode, event)
        if (isNavKey(keyCode)) {
            // 診断 (パッド入力の切り分け用。あとで外す): どのデバイスから来て、処理されたか
            diag(
                "key nav code=${KeyEvent.keyCodeToString(keyCode)} scan=${event.scanCode} " +
                    "dev=${event.device?.name} devId=${event.deviceId} src=${event.source} " +
                    "rep=${event.repeatCount} dt=${dtMs}ms handled=$handled",
            )
        }
        return handled
    }

    private fun isNavKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
            keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER

    private fun onKeyDownInner(keyCode: Int, event: KeyEvent): Boolean {
        if (emojiPanel.isShowing && emojiPanel.onKey(keyCode, event)) {
            panelConsumed += keyCode
            return true
        }
        if (isQ25Alt(event)) showSymbolLegend()
        if (event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_0 || event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST)
        ) {
            diag("mic-key code=${event.keyCode} dev=${event.device?.name} model=${Build.MODEL}")
        }
        if (isMicKey(event)) {
            if (event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
                if (event.repeatCount == 0) {
                    micKeyCode = event.keyCode
                    beginVoice()
                }
                return true
            }
            if (event.repeatCount == 0) onMicDown(event.keyCode)
            return true
        }
        val key = KeyInput(keyCode, event.getUnicodeChar(event.metaState), event.getUnicodeChar(0), event.metaState)
        if (controller.keyDown(key)) {
            // 物理キーの打鍵にだけ、気持ち振動を返す (画面キーの合成イベントは除く)。
            if (::prefs.isInitialized && prefs.keyVibrate && event.repeatCount == 0 && event.deviceId >= 0) {
                vibrateKey()
            }
            // 入力欄をタップせずに打ち始めたときは、まだ窓が出ていない
            if (!isInputViewShown) requestShowSelf(SHOW_EXPLICIT)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** 画面のキー (F キー行・数字行・記号面・候補窓のボタン) 用。物理キーと同じ設定で鳴らす。 */
    fun vibrateVirtualKey() {
        if (::prefs.isInitialized && prefs.keyVibrate) vibrateKey()
    }

    /** 振動の試行を入力ごとに1回だけ記録したか (効かないときの切り分け用)。 */
    private var vibeLogged = false

    /** 打鍵の振動。安いモーターでも感じられるよう、短いが強めに。できない環境では何もしない。 */
    private fun vibrateKey() {
        try {
            val vibrator = getSystemService(Vibrator::class.java) ?: return
            if (!vibeLogged) {
                vibeLogged = true
                diag("vibe attempt hasVibrator=${vibrator.hasVibrator()}")
            }
            if (!vibrator.hasVibrator()) return
            // 安いモーターは短いと回り切らないので、長さで強さを調整する (最大振幅)。
            // 打ち続けると次の振動に置き換わるので、速く打つと細かく、ゆっくり打つと1打ずつ残る。
            val ms = when (if (::prefs.isInitialized) prefs.vibeLevel else 1) {
                0 -> 25L
                2 -> 120L
                else -> 50L
            }
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: RuntimeException) {
            if (!vibeLogged) {
                vibeLogged = true
                diag("vibe failed: $e")
            }
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_ALT_LEFT || event.keyCode == KeyEvent.KEYCODE_ALT_RIGHT) hideSymbolLegend()
        if (micKeyCode != -1 && event.keyCode == micKeyCode) {
            if (event.keyCode == KeyEvent.KEYCODE_VOICE_ASSIST) {
                micKeyCode = -1
                endVoice()
            } else {
                onMicUp()
            }
            return true
        }
        return panelConsumed.remove(keyCode) || controller.keyUp(keyCode) || super.onKeyUp(keyCode, event)
    }

    private companion object {
        /** 「最近使った絵文字」に入れる、確定した文字の長さの上限 (旗や家族の絵文字は 10 文字を超えることがある)。 */
        const val MAX_EMOJI_LENGTH = 16

        /** InputMethod.SHOW_EXPLICIT: 物理キーボードがあっても入力の窓を出すよう頼む。 */
        const val SHOW_EXPLICIT = 1

        /** 0 をこの時間押し続けたら音声入力。連打の隙間がこれ未満なら、まだ押し続けとみなす。 */
        const val MIC_HOLD_MS = 400L
        const val MIC_GAP_MS = 160L
    }
}
