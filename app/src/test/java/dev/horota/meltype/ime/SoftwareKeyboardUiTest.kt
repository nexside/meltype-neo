// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.ime

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import dev.horota.meltype.core.CompositionView
import dev.horota.meltype.input.DigitPadKind
import dev.horota.meltype.input.KeyInput
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29, 35, 37])
class SoftwareKeyboardUiTest {
    private val pressed = mutableListOf<KeyInput>()
    private val selected = mutableListOf<Int>()
    private var pickers = 0
    private fun keyboard() = SoftwareKeyboard(RuntimeEnvironment.getApplication(), { pressed += it }, { selected += it }, {}, { pickers++ })
    private fun key(view: View, tag: String): TextView = view.findViewWithTag(tag)

    @Test fun qwertyHasTouchableSizeAndUppercaseIsOneShot() {
        val keyboard = keyboard()
        keyboard.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1800, View.MeasureSpec.AT_MOST))
        keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
        assertTrue(keyboard.height > 0)
        assertTrue(key(keyboard, "soft-key-q").width > 0)
        assertTrue(key(keyboard, "soft-key-q").height > 0)
        key(keyboard, "soft-shift").performClick()
        assertEquals("Q", key(keyboard, "soft-key-q").text.toString())
        key(keyboard, "soft-key-q").performClick()
        assertEquals('Q'.code, pressed.single().char)
        assertEquals("q", key(keyboard, "soft-key-q").text.toString())
    }

    @Test fun candidatesCanBeSelectedWithoutAFloatingWindow() {
        val keyboard = keyboard()
        keyboard.showComposition(CompositionView(text = "きょう", candidates = listOf("今日", "京")))
        key(keyboard, "soft-candidate-1").performClick()
        assertEquals(listOf(1), selected)
    }

    @Test fun passwordContextRemovesPreviousReadingAndCandidates() {
        val keyboard = keyboard()
        keyboard.showComposition(CompositionView(text = "前の入力", candidates = listOf("前の候補")))
        keyboard.setContext(DigitPadKind.NONE, InputMode.DIRECT, false, "完了")
        fun texts(view: View): List<String> = when (view) {
            is TextView -> listOf(view.text.toString())
            is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
            else -> emptyList()
        }
        assertFalse(texts(keyboard).any { it.contains("前の") })
        assertNotNull(keyboard.findViewWithTag<View>("soft-key-q"))
    }

    @Test fun numericFieldsGetAFullNumericPadAndAnActionKey() {
        val keyboard = keyboard()
        keyboard.setContext(DigitPadKind.PHONE, InputMode.DIRECT, false, "次へ")
        assertNull(keyboard.findViewWithTag<View>("soft-key-q"))
        key(keyboard, "soft-key-+").performClick()
        assertEquals('+'.code, pressed.single().char)
        assertEquals("次へ", key(keyboard, "soft-enter").text.toString())
    }

    @Test fun symbolPagesAndExplicitKeyboardSwitchAreAvailable() {
        val keyboard = keyboard()
        key(keyboard, "soft-symbols").performClick()
        key(keyboard, "soft-key-_").performClick()
        key(keyboard, "soft-more-symbols").performClick()
        key(keyboard, "soft-key-\\").performClick()
        assertEquals(listOf('_'.code, '\\'.code), pressed.map { it.char })
        assertEquals(0, pickers)
        key(keyboard, "soft-picker").performClick()
        assertEquals(1, pickers)
    }

    @Test fun actualImeCreatesScreenKeysOnATouchOnlyPhone() {
        val lifecycle = Robolectric.buildService(MeltypeImeService::class.java).create()
        try {
            val service = lifecycle.get()
            service.resources.configuration.keyboard = android.content.res.Configuration.KEYBOARD_NOKEYS
            service.resources.configuration.hardKeyboardHidden = android.content.res.Configuration.HARDKEYBOARDHIDDEN_YES
            val inputView = service.onCreateInputView()
            assertTrue(service.onEvaluateInputViewShown())
            assertNotNull(inputView.findViewWithTag<View>("soft-key-q"))
            val info = android.view.inputmethod.EditorInfo().apply {
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                packageName = "test.editor"
            }
            service.onStartInput(info, false)
            assertTrue(service.onEvaluateInputViewShown())
            assertNotNull(inputView.findViewWithTag<View>("soft-key-q"))
        } finally { lifecycle.destroy() }
    }
}
