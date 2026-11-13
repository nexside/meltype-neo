// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.settings

import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DictionaryStorageTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun savesCompleteUtf8TextAndReplacesExistingFile() {
        val file = File(temporary.root, "nested/userdict.txt")
        DictionaryStorage.save(file, "よみ\t単語😀\n")
        assertEquals("よみ\t単語😀\n", file.readText())
        DictionaryStorage.save(file, "つぎ\t次\n")
        assertEquals("つぎ\t次\n", file.readText())
        assertEquals(listOf("userdict.txt"), file.parentFile!!.list()!!.toList())
    }

    @Test
    fun failedReplacementPreservesTheOldDictionaryAndCleansTemporaryFile() {
        val file = temporary.newFile("userdict.txt").apply { writeText("元の辞書") }
        try {
            AtomicTextFile.write(file, "新しい辞書") { source, _ ->
                assertEquals("新しい辞書", source.toFile().readText())
                throw IOException("cannot replace")
            }
            fail("expected an IO failure")
        } catch (_: IOException) { }
        assertEquals("元の辞書", file.readText())
        assertEquals(listOf("userdict.txt"), temporary.root.list()!!.toList())
    }

    @Test
    fun staleEditorCannotOverwriteWordsAddedByAnotherScreen() {
        val file = temporary.newFile("userdict.txt").apply { writeText("よみ\t最初\n") }
        val snapshot = DictionaryStorage.read(file)
        DictionaryStorage.merge(file, listOf(UserDictionaryImport.Entry("ついか", "追加")))
        try {
            DictionaryStorage.save(file, "へんしゅう\t古い編集\n", expected = snapshot)
            fail("expected a conflict")
        } catch (_: IOException) { }
        assertTrue(file.readText().contains("ついか\t追加"))
        assertTrue(file.readText().contains("よみ\t最初"))
    }

    @Test
    fun simultaneousRegistrationsDoNotLoseWords() {
        val file = File(temporary.root, "userdict.txt")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = (0..1).map { writer -> pool.submit {
                check(start.await(5, TimeUnit.SECONDS))
                repeat(20) { index ->
                    DictionaryStorage.merge(file, listOf(UserDictionaryImport.Entry("word$writer$index", "単語$writer$index")))
                }
            } }
            start.countDown()
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(40, UserDictionaryImport.parse(file.readText()).entries.size)
        } finally {
            pool.shutdownNow()
        }
    }
}
