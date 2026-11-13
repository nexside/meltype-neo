// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.ime

import android.content.res.Configuration

/** 外付けキーは使える。閉じたスライド式の内蔵キーは使えると見なさない。 */
object PhysicalKeyboardPolicy {
    fun available(keyboard: Int, hidden: Int, alphabeticDevicesExternal: List<Boolean>): Boolean {
        if (alphabeticDevicesExternal.any { it }) return true
        if (hidden == Configuration.HARDKEYBOARDHIDDEN_YES) return false
        return keyboard == Configuration.KEYBOARD_QWERTY || alphabeticDevicesExternal.isNotEmpty()
    }
}
