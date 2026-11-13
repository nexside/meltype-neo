// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.settings

import android.content.Context
import dev.horota.meltype.ime.EmojiData
import dev.horota.meltype.ime.ThemeMode
import dev.horota.meltype.input.BindingOptions
import dev.horota.meltype.input.EditorPolicy
import java.io.File

/** Android 版だけの設定 (SharedPreferences)。判定・変換の細かい設定は本家と同じ config.json にある。 */
class MeltypePrefs(context: Context) {
    private val prefs = context.getSharedPreferences("meltype", Context.MODE_PRIVATE)

    init {
        if (prefs.getInt("diagnostic_format", 0) < 2) {
            DiagLog.clear(dataDirectory(context))
            prefs.edit().putInt("diagnostic_format", 2).apply()
        }
    }

    var diagnosticLogging: Boolean
        get() = prefs.getBoolean("diagnostic_logging", false)
        set(value) {
            prefs.edit().putBoolean("diagnostic_logging", value).apply()
        }

    var codeApps: Set<String>
        get() = prefs.getStringSet(KEY_CODE_APPS, null)?.toSet() ?: EditorPolicy.DEFAULT_CODE_APPS
        set(value) = prefs.edit().putStringSet(KEY_CODE_APPS, value).apply()

    var shiftSpaceToggles: Boolean
        get() = prefs.getBoolean(KEY_SHIFT_SPACE, true)
        set(value) = prefs.edit().putBoolean(KEY_SHIFT_SPACE, value).apply()

    var ctrlSpaceToggles: Boolean
        get() = prefs.getBoolean(KEY_CTRL_SPACE, true)
        set(value) = prefs.edit().putBoolean(KEY_CTRL_SPACE, value).apply()

    /** 確定した文字が「()」のようなかっこの組なら、カーソルを間に置く。 */
    var cursorBetweenBrackets: Boolean
        get() = prefs.getBoolean(KEY_BRACKETS, true)
        set(value) = prefs.edit().putBoolean(KEY_BRACKETS, value).apply()

    /** Fキー行を固定表示するか (変換が終わっても出し続ける)。即時書き込み (画面遷移でプロセスが落とされても消えないように)。 */
    var fBarPinned: Boolean
        get() = prefs.getBoolean(KEY_FBAR_PINNED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_FBAR_PINNED, value).commit()
        }

    /** 変換中に Fキー行を自動で出すか。切ると、固定したときだけ出る。 */
    var fBarAutoShow: Boolean
        get() = prefs.getBoolean(KEY_FBAR_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_FBAR_AUTO, value).apply()

    /** Fキー行の見た目。0=おまかせ (機種で自動)、1=ダーク、2=Q25ブルー。 */
    var keycapStyle: Int
        get() = prefs.getInt(KEY_KEYCAP_STYLE, 0)
        set(value) = prefs.edit().putInt(KEY_KEYCAP_STYLE, value).apply()

    /** 物理キーを打つたびに軽く振動させるか。 */
    var keyVibrate: Boolean
        get() = prefs.getBoolean(KEY_KEY_VIBRATE, true)
        set(value) = prefs.edit().putBoolean(KEY_KEY_VIBRATE, value).apply()

    /** 振動の強さ。0=弱い (25ms)、1=普通 (50ms)、2=強い (120ms)。 */
    var vibeLevel: Int
        get() = prefs.getInt(KEY_VIBE_LEVEL, 1)
        set(value) = prefs.edit().putInt(KEY_VIBE_LEVEL, value).apply()

    /** 最近使った絵文字 (新しい順)。 */
    var recentEmoji: List<String>
        get() = EmojiData.parseRecent(prefs.getString(KEY_RECENT_EMOJI, null))
        set(value) = prefs.edit().putString(KEY_RECENT_EMOJI, EmojiData.formatRecent(value)).apply()

    /** オンにしているジャンル別辞書 ("builtin:it"、"user:名前")。 */
    var enabledGenres: Set<String>
        get() = prefs.getStringSet(KEY_GENRES, null)?.toSet() ?: emptySet()
        set(value) = prefs.edit().putStringSet(KEY_GENRES, value).apply()

    /** 候補の配色。 */
    var themeMode: ThemeMode
        get() = ThemeMode.values().firstOrNull { it.name == prefs.getString(KEY_THEME, null) } ?: ThemeMode.SYSTEM
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    val bindingOptions: BindingOptions
        get() = BindingOptions(shiftSpaceToggles = shiftSpaceToggles, ctrlSpaceToggles = ctrlSpaceToggles)

    companion object {
        private const val KEY_CODE_APPS = "code_apps"
        private const val KEY_SHIFT_SPACE = "shift_space_toggles"
        private const val KEY_CTRL_SPACE = "ctrl_space_toggles"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_BRACKETS = "cursor_between_brackets"
        private const val KEY_RECENT_EMOJI = "recent_emoji"
        private const val KEY_FBAR_PINNED = "fbar_pinned"
        private const val KEY_FBAR_AUTO = "fbar_auto_show"
        private const val KEY_KEYCAP_STYLE = "keycap_style"
        private const val KEY_KEY_VIBRATE = "key_vibrate"
        private const val KEY_VIBE_LEVEL = "vibe_level"
        private const val KEY_GENRES = "enabled_genres"

        /** 本家の MELTYPE_DATA_DIR に渡す場所 (設定・学習・ユーザー辞書・ログ)。 */
        fun dataDirectory(context: Context): File = File(context.filesDir, "Meltype")
    }
}
