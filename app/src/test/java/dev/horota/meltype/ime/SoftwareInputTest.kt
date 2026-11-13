// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.ime

import android.text.InputType
import android.view.KeyEvent
import dev.horota.meltype.core.SessionResult
import dev.horota.meltype.core.TextEdit
import dev.horota.meltype.input.EditorPolicy
import dev.horota.meltype.input.FieldPolicy
import org.junit.Assert.*
import org.junit.Test

class SoftwareInputTest {
    private val editor = FakeEditor()
    private val core = FakeCore().apply { next = SessionResult(false) }
    private val controller = ImeController(core, editor, editor, object : StatusDisplay { override fun showMode(mode: InputMode) {} })
    private var actions = 0
    private val input = SoftwareInput(controller, editor) { actions++; true }

    init { controller.startInput(FieldPolicy(false, false, false)); editor.ops.clear(); core.calls.clear() }

    @Test fun screenCharactersUseTheConversionCoreAndFallBackToCommitText() {
        input.press(SoftwareKeys.character('a'))
        assertTrue(core.calls.any { it.startsWith("key:65:97:") })
        assertEquals(1, editor.ops.count { it == "commit:a" })
        assertFalse(controller.keyUp(KeyEvent.KEYCODE_A))
    }

    @Test fun consumedConversionIsNotCommittedTwice() {
        core.next = SessionResult(true, listOf(TextEdit(0, "今日")))
        input.press(SoftwareKeys.character('a'))
        assertEquals(1, editor.ops.count { it == "commit:今日" })
        assertFalse(editor.ops.contains("commit:a"))
    }

    @Test fun passwordsNeverPassScreenCharactersToTheCore() {
        controller.startInput(EditorPolicy.of(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, "test", emptySet()))
        core.calls.clear()
        input.press(SoftwareKeys.character('S'))
        assertFalse(core.calls.any { it.startsWith("key:") })
        assertTrue(editor.ops.contains("commit:S"))
    }

    @Test fun backspaceDoesNotSplitASurrogatePair() {
        editor.before = "a😀"
        input.press(SoftwareKeys.special(KeyEvent.KEYCODE_DEL))
        assertTrue(editor.ops.contains("delete:2"))
    }

    @Test fun enterRunsTheEditorsActionOnlyWhenTheCoreDidNotConsumeIt() {
        input.press(SoftwareKeys.special(KeyEvent.KEYCODE_ENTER))
        assertEquals(1, actions)
        assertFalse(editor.ops.contains("commit:\n"))
        core.next = SessionResult(true, listOf(TextEdit(0, "確定")))
        input.press(SoftwareKeys.special(KeyEvent.KEYCODE_ENTER))
        assertEquals(1, actions)
        assertTrue(editor.ops.contains("commit:確定"))
    }

    @Test fun multilineEnterFallsBackToANewline() {
        SoftwareInput(controller, editor) { false }.press(SoftwareKeys.special(KeyEvent.KEYCODE_ENTER))
        assertTrue(editor.ops.contains("commit:\n"))
    }

    @Test fun spaceNeverInheritsTheScreenShiftState() {
        input.press(SoftwareKeys.character(' '))
        assertFalse(core.calls.any { it.startsWith("direct:") })
        assertTrue(editor.ops.contains("commit: "))
    }

    @Test fun textStillWorksIfTheNativeCoreCannotLoad() {
        val unavailable = ImeController(null, editor, editor, object : StatusDisplay { override fun showMode(mode: InputMode) {} })
        SoftwareInput(unavailable, editor) { false }.press(SoftwareKeys.character('x'))
        assertTrue(editor.ops.contains("commit:x"))
    }
}
