// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import dev.horota.meltype.core.CompositionView
import dev.horota.meltype.input.DigitPadKind
import dev.horota.meltype.input.KeyInput

/** 物理キーのない端末用。候補も同じ入力ビュー内に置き、タップで選べるようにする。 */
class SoftwareKeyboard(
    context: Context,
    private val onKey: (KeyInput) -> Unit,
    private val onCandidate: (Int) -> Unit,
    private val onConvert: (ConvertTarget) -> Unit,
    private val onPicker: () -> Unit,
) : LinearLayout(context) {
    private var theme = CandidateTheme.LIGHT
    private var kind = DigitPadKind.NONE
    private var mode = InputMode.JAPANESE
    private var candidatesAllowed = true
    private var enterLabel = "↵"
    private var shifted = false
    private var capsLock = false
    private var symbols = false
    private var moreSymbols = false
    private var composition: CompositionView? = null
    private val reading = TextView(context).apply { textSize = 16f; gravity = Gravity.CENTER_VERTICAL }
    private val header = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val candidates = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val candidateScroll = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(candidates, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }
    private val keys = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        orientation = VERTICAL
        setPadding(dp(3), dp(4), dp(3), dp(5))
        header.addView(reading, LayoutParams(0, dp(32), 1f))
        header.addView(button("カナ", "soft-katakana") { onConvert(ConvertTarget.KATAKANA) }, LayoutParams(dp(52), dp(32)))
        header.addView(button("英数", "soft-alphanumeric") { onConvert(ConvertTarget.ALPHANUMERIC) }, LayoutParams(dp(52), dp(32)))
        addView(header)
        addView(candidateScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        addView(keys)
        renderKeys()
        applyTheme(theme)
    }

    fun applyTheme(next: CandidateTheme) {
        theme = next
        setBackgroundColor(theme.background)
        reading.setTextColor(theme.text)
        renderKeys()
        showComposition(composition)
    }

    fun setContext(nextKind: DigitPadKind, nextMode: InputMode, allowCandidates: Boolean, action: String) {
        val changed = kind != nextKind || mode != nextMode || enterLabel != action
        kind = nextKind
        mode = nextMode
        enterLabel = action
        candidatesAllowed = allowCandidates && kind == DigitPadKind.NONE
        header.visibility = if (candidatesAllowed) VISIBLE else GONE
        candidateScroll.visibility = if (candidatesAllowed) VISIBLE else GONE
        if (!candidatesAllowed) composition = null
        if (changed) renderKeys()
        showComposition(composition)
    }

    fun reset() {
        shifted = false
        capsLock = false
        symbols = false
        moreSymbols = false
        composition = null
        renderKeys()
        showComposition(null)
    }

    fun showComposition(view: CompositionView?) {
        composition = if (candidatesAllowed) view else null
        reading.text = composition?.text.orEmpty()
        candidates.removeAllViews()
        if (!candidatesAllowed) return
        val items = composition?.let { it.candidates.ifEmpty { it.predictions } }.orEmpty()
        if (items.isEmpty()) {
            candidates.addView(TextView(context).apply {
                text = "スペースで変換・↵で確定"
                setTextColor(theme.text)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(8), 0)
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        items.forEachIndexed { index, text ->
            candidates.addView(button(text, "soft-candidate-$index") { onCandidate(index) },
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT).apply { marginEnd = dp(4) })
        }
    }

    private fun renderKeys() {
        keys.removeAllViews()
        if (kind != DigitPadKind.NONE) {
            for (digits in listOf("123", "456", "789")) row { digits.forEach { character(it) } }
            row {
                val extra = when (kind) { DigitPadKind.PHONE -> '+'; DigitPadKind.DECIMAL -> '.'; else -> '-' }
                character(extra)
                character('0')
                key("⌫", "soft-backspace") { onKey(SoftwareKeys.special(KeyEvent.KEYCODE_DEL)) }
            }
            row {
                key("🌐", "soft-picker", action = onPicker)
                key(enterLabel, "soft-enter", 2f) { onKey(SoftwareKeys.special(KeyEvent.KEYCODE_ENTER)) }
            }
            return
        }
        if (symbols) {
            row { "1234567890".forEach { character(it) } }
            row { (if (moreSymbols) "<>^~`|€£¥_" else "@#$%&*-+=_").forEach { character(it) } }
            row {
                key("#+=", "soft-more-symbols", 1.5f) { moreSymbols = !moreSymbols; renderKeys() }
                (if (moreSymbols) "()[]{}\\" else "!?/:;'\"").forEach { character(it) }
                key("⌫", "soft-backspace", 1.5f) { onKey(SoftwareKeys.special(KeyEvent.KEYCODE_DEL)) }
            }
        } else {
            row { "qwertyuiop".forEach { character(it) } }
            row {
                addView(View(context), LayoutParams(0, 1, 0.5f))
                "asdfghjkl".forEach { character(it) }
                addView(View(context), LayoutParams(0, 1, 0.5f))
            }
            row {
                val shift = key(if (capsLock) "⇪" else "⇧", "soft-shift", 1.5f) {
                    shifted = !shifted; capsLock = false; renderKeys()
                }
                shift.setOnLongClickListener { shifted = true; capsLock = true; renderKeys(); true }
                "zxcvbnm".forEach { character(it) }
                key("⌫", "soft-backspace", 1.5f) { onKey(SoftwareKeys.special(KeyEvent.KEYCODE_DEL)) }
            }
        }
        row {
            key(if (symbols) "ABC" else "123", "soft-symbols", 1.25f) { symbols = !symbols; renderKeys() }
            key(if (mode == InputMode.JAPANESE) "あ" else "A", "soft-mode") {
                onKey(SoftwareKeys.special(KeyEvent.KEYCODE_ZENKAKU_HANKAKU))
            }
            key("🌐", "soft-picker", action = onPicker)
            character(',')
            key("空白", "soft-space", 3f) { onKey(SoftwareKeys.character(' ')) }
            character('.')
            key(enterLabel, "soft-enter", 1.6f) { onKey(SoftwareKeys.special(KeyEvent.KEYCODE_ENTER)) }
        }
    }

    private fun row(build: LinearLayout.() -> Unit) {
        keys.addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            build()
        })
    }

    private fun LinearLayout.character(base: Char) {
        val value = if (shifted && base in 'a'..'z') base.uppercaseChar() else base
        key(value.toString(), "soft-key-$base") {
            onKey(SoftwareKeys.character(value))
            if (shifted && !capsLock && base.isLetter()) { shifted = false; renderKeys() }
        }
    }

    private fun LinearLayout.key(label: String, tagName: String, weight: Float = 1f, action: () -> Unit): TextView {
        val height = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) 40 else 48
        return button(label, tagName, action).also {
            addView(it, LayoutParams(0, dp(height), weight).apply {
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
    }

    private fun button(label: String, tagName: String, action: () -> Unit) = TextView(context).apply {
        text = label
        tag = tagName
        contentDescription = when (tagName) {
            "soft-backspace" -> "削除"
            "soft-shift" -> "大文字切り替え"
            "soft-picker" -> "入力方法を切り替える"
            "soft-space" -> "スペース・変換"
            else -> label
        }
        textSize = if (label.length > 2) 15f else 19f
        gravity = Gravity.CENTER
        setPadding(dp(3), 0, dp(3), 0)
        minWidth = dp(28)
        setTextColor(theme.text)
        background = GradientDrawable().apply {
            setColor(theme.background)
            setStroke(dp(1), theme.border)
            cornerRadius = dp(6).toFloat()
        }
        isClickable = true
        isFocusable = false
        setOnClickListener { action() }
    }

    private fun dp(value: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
}
