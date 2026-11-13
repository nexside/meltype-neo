// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.settings

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 入力内容を含まない診断情報。書き込みをまとめ、入力のスレッドでファイルを触らない。 */
object DiagLog {
    private const val HEADER = "meltype-diagnostics-v2\n"
    private const val MAX_LINES = 150
    private const val MAX_LINE_CHARS = 400
    private const val MAX_BYTES = 256 * 1024L
    private val lock = Any()
    private val ioLock = Any()
    private data class Entry(val time: Long, val line: String)
    private val pending = mutableMapOf<File, ArrayDeque<Entry>>()
    private var scheduled = false
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "meltype-diagnostics").apply { isDaemon = true }
    }

    fun add(dataDirectory: File, line: String) = synchronized(lock) {
        val queue = pending.getOrPut(dataDirectory) { ArrayDeque() }
        queue.addLast(Entry(System.currentTimeMillis(), line.replace('\n', ' ').replace('\r', ' ').take(MAX_LINE_CHARS)))
        while (queue.size > MAX_LINES) queue.removeFirst()
        if (!scheduled) {
            scheduled = true
            worker.schedule(Runnable { flush() }, 250, TimeUnit.MILLISECONDS)
        }
    }

    internal fun flush() = synchronized(ioLock) {
        val batch = synchronized(lock) {
            val result = pending.mapValues { it.value.toList() }
            pending.clear()
            scheduled = false
            result
        }
        val format = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
        for ((directory, entries) in batch) {
            try {
                val file = File(directory, "diag.txt")
                val oldText = if (file.exists() && file.length() <= MAX_BYTES) file.readText() else ""
                val old = if (oldText.startsWith(HEADER)) oldText.removePrefix(HEADER).lineSequence().filter { it.isNotBlank() }.toList() else emptyList()
                val lines = old + entries.map { "${format.format(Date(it.time))} ${it.line}" }
                AtomicTextFile.write(file, HEADER + lines.takeLast(MAX_LINES).joinToString("\n", postfix = "\n"))
            } catch (_: Exception) {
                // 診断情報が保存できなくても入力は続ける。
            }
        }
    }

    fun read(dataDirectory: File): String = synchronized(ioLock) {
        try {
            val file = File(dataDirectory, "diag.txt")
            if (!file.exists() || file.length() > MAX_BYTES) "(まだありません)"
            else file.readText().let { if (it.startsWith(HEADER)) it.removePrefix(HEADER).ifEmpty { "(まだありません)" } else "(まだありません)" }
        } catch (_: Exception) {
            "(読めません)"
        }
    }

    fun clear(dataDirectory: File) {
        synchronized(lock) { pending.remove(dataDirectory) }
        worker.execute {
            synchronized(ioLock) {
                try { File(dataDirectory, "diag.txt").delete() } catch (_: Exception) { }
            }
        }
    }
}
