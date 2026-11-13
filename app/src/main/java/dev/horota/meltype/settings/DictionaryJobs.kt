// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.settings

import android.app.Activity
import java.util.concurrent.Executors

/** 辞書操作は画面を止めず、順番どおり実行する。閉じた画面には結果を届けない。 */
object DictionaryJobs {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "meltype-dictionary").apply { isDaemon = true }
    }

    fun <T> run(activity: Activity, work: () -> T, success: (T) -> Unit, failure: (Throwable) -> Unit) {
        worker.execute {
            val result = runCatching(work)
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) result.fold(success, failure)
            }
        }
    }
}
