// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.settings

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 同じディレクトリで書き上げてから置換する。置換に失敗したら元のファイルを残す。 */
internal object AtomicTextFile {
    fun write(file: File, text: String, replace: (Path, Path) -> Unit = { source, target ->
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }) {
        val parent = file.absoluteFile.parentFile ?: throw IOException("保存先がありません")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("保存先を作れませんでした")
        val temporary = File.createTempFile(".${file.name}.", ".tmp", parent)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(text.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            replace(temporary.toPath(), file.toPath())
        } finally {
            temporary.delete()
        }
    }
}

/** 読み取り・変更・置換をひとまとまりにし、別画面からの単語登録との競合を防ぐ。 */
object DictionaryStorage {
    private val lock = Any()

    fun <T> transaction(block: () -> T): T = synchronized(lock) { block() }

    fun read(file: File): String = transaction { if (file.exists()) file.readText() else "" }

    fun save(file: File, text: String, expected: String? = null) = transaction {
        val current = read(file)
        if (expected != null && current != expected) {
            throw IOException("別の画面で辞書が更新されました。開き直してから保存してください")
        }
        if (!file.exists() || current != text) AtomicTextFile.write(file, text)
    }

    fun merge(file: File, entries: List<UserDictionaryImport.Entry>): UserDictionaryImport.MergeResult = transaction {
        val merged = UserDictionaryImport.merge(read(file), entries)
        if (merged.added > 0) save(file, merged.text)
        merged
    }
}
