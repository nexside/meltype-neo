// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.settings

import android.content.Context
import java.io.File
import java.io.IOException

/** 同梱のジャンル。語は assets/genres/<id>.txt (「読み[Tab]単語」)。 */
data class BuiltInGenre(val id: String, val name: String, val description: String) {
    val key: String get() = "builtin:$id"
    val asset: String get() = "genres/$id.txt"
}

object GenreCatalog {
    val builtIns: List<BuiltInGenre> = listOf(
        BuiltInGenre("it", "IT・開発", "GitHub・Python・API などの名前と略語、開発の用語"),
        BuiltInGenre("medical", "医療・健康", "病名・薬・検査・症状の用語"),
        BuiltInGenre("law", "法律", "民法・労働法・知的財産などの法律用語"),
        BuiltInGenre("finance", "金融・経済・会計", "投資・年金・税・簿記・財務諸表の用語"),
        BuiltInGenre("science", "科学・工学", "数学・物理・化学・生物・電気の用語"),
        BuiltInGenre("business", "ビジネス文書・敬語", "メール・文書の定型の言い回し"),
    )
}

/**
 * ジャンル別の辞書。ジャンルごとにオン / オフでき、オンのものは dictionaries/genre-*.plist にして本家 Core に渡す
 * (本家は入力欄を作るたびに .plist を読み直す。登録した読みは、変換エンジンの区切りに関係なくその語になる)。
 * 自分で作るジャンルは <データ>/genres/<名前>.txt (「読み[Tab]単語」)。
 */
class GenreStore(private val context: Context) {
    private val prefs = MeltypePrefs(context)
    private val dataDirectory: File get() = MeltypePrefs.dataDirectory(context)
    private val genreDirectory: File get() = File(dataDirectory, "genres")
    private val dictionaryDirectory: File get() = File(dataDirectory, "dictionaries")

    fun userGenres(): List<String> =
        genreDirectory.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.map { it.nameWithoutExtension }?.sorted().orEmpty()

    fun userGenreFile(name: String): File = File(genreDirectory, "$name.txt")

    fun isEnabled(key: String): Boolean = key in prefs.enabledGenres

    fun setEnabled(key: String, enabled: Boolean) {
        val previous = prefs.enabledGenres
        prefs.enabledGenres = if (enabled) previous + key else previous - key
        try {
            sync()
        } catch (e: IOException) {
            prefs.enabledGenres = previous
            throw e
        }
    }

    /** 自分のジャンルを作る。作れなければ理由を返す。 */
    fun create(rawName: String): String? {
        val name = GenreNames.sanitize(rawName)
        if (name.isEmpty()) return "名前を入れてください"
        val file = userGenreFile(name)
        if (file.exists()) return "同じ名前のジャンルがあります"
        return try {
            genreDirectory.mkdirs()
            DictionaryStorage.save(file, "")
            null
        } catch (e: IOException) {
            "作れませんでした: ${e.message}"
        }
    }

    fun delete(name: String) {
        userGenreFile(name).delete()
        prefs.enabledGenres = prefs.enabledGenres - "user:$name"
        sync()
    }

    /** ジャンルの語。読めなければ空。 */
    fun entries(key: String): List<UserDictionaryImport.Entry> {
        val text = try {
            when {
                key.startsWith("builtin:") -> {
                    val genre = GenreCatalog.builtIns.firstOrNull { it.key == key } ?: return emptyList()
                    context.assets.open(genre.asset).use { it.readBytes().toString(Charsets.UTF_8) }
                }
                key.startsWith("user:") -> {
                    val file = userGenreFile(key.removePrefix("user:"))
                    if (file.exists()) UserDictionaryImport.decode(file.readBytes()) else return emptyList()
                }
                else -> return emptyList()
            }
        } catch (e: IOException) {
            return emptyList()
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }
        return UserDictionaryImport.parse(text).entries
    }

    /**
     * オンのジャンルを dictionaries/genre-*.plist に書き、オフになった分は消す。中身が同じなら書き直さない
     * (書き直すと IME が辞書の変更と見なして、入力の本体を作り直すため)。
     */
    fun sync() {
        val wanted = LinkedHashMap<String, String>()
        for (key in prefs.enabledGenres.sorted()) {
            val entries = entries(key)
            if (entries.isNotEmpty()) wanted["genre-${fileSafe(key)}.plist"] = PlistWriter.write(entries)
        }
        val directory = dictionaryDirectory
        directory.mkdirs()
        directory.listFiles { f -> f.isFile && f.name.startsWith("genre-") && f.name.endsWith(".plist") }
            ?.forEach { if (it.name !in wanted) it.delete() }
        for ((name, xml) in wanted) {
            val file = File(directory, name)
            if (!file.exists() || DictionaryStorage.read(file) != xml) DictionaryStorage.save(file, xml)
        }
    }

    private fun fileSafe(key: String): String = key.replace(':', '-')
}
