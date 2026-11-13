// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.settings

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagLogTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun legacyLogsAreNeitherDisplayedNorCarriedIntoTheNewLog() {
        File(temporary.root, "diag.txt").writeText("candidates show text='以前の入力内容'\n二行目の入力内容\n")
        assertFalse(DiagLog.read(temporary.root).contains("入力内容"))
        DiagLog.add(temporary.root, "candidates show ok=true cands=3")
        DiagLog.flush()
        val stored = File(temporary.root, "diag.txt").readText()
        assertFalse(stored.contains("入力内容"))
        assertTrue(DiagLog.read(temporary.root).contains("cands=3"))
    }

    @Test
    fun burstsStayBoundedAndCannotInjectAdditionalLogLines() {
        repeat(300) { DiagLog.add(temporary.root, "event-$it\n" + "x".repeat(1000)) }
        DiagLog.flush()
        val lines = DiagLog.read(temporary.root).lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(150, lines.size)
        assertTrue(lines.last().contains("event-299"))
        assertTrue(lines.all { it.length < 430 })
    }
}
