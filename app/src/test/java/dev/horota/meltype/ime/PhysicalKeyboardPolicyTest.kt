// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.ime

import android.content.res.Configuration
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalKeyboardPolicyTest {
    @Test
    fun touchOnlyPhoneNeedsAnotherIme() {
        assertFalse(PhysicalKeyboardPolicy.available(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_YES, emptyList()))
        assertFalse(PhysicalKeyboardPolicy.available(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_UNDEFINED, emptyList()))
    }

    @Test
    fun visibleBuiltInKeyboardStaysInMeltype() {
        assertTrue(PhysicalKeyboardPolicy.available(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_NO, listOf(false)))
    }

    @Test
    fun realDeviceCanIdentifyAKeyboardDespiteIncompleteConfiguration() {
        assertTrue(PhysicalKeyboardPolicy.available(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_UNDEFINED, listOf(false)))
    }

    @Test
    fun closedSliderNeedsSoftwareInput() {
        assertFalse(PhysicalKeyboardPolicy.available(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_YES, listOf(false)))
    }

    @Test
    fun externalKeyboardRemainsUsableWhenBuiltInKeyboardIsHidden() {
        assertTrue(PhysicalKeyboardPolicy.available(Configuration.KEYBOARD_QWERTY, Configuration.HARDKEYBOARDHIDDEN_YES, listOf(false, true)))
        assertTrue(PhysicalKeyboardPolicy.available(Configuration.KEYBOARD_NOKEYS, Configuration.HARDKEYBOARDHIDDEN_YES, listOf(true)))
    }
}
