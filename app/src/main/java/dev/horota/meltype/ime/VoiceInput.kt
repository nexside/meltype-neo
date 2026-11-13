// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.ime

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 押し話しているあいだの音声認識。結果の文字列だけを返し、ログには残さない。
 * finish はここまでの認識を確定する。cancel は捨てる。
 */
class VoiceInput(
    private val context: Context,
    private val onText: (String) -> Unit,
    private val onEnd: (String?) -> Unit,
) {
    private var recognizer: SpeechRecognizer? = null
    private var latest = ""

    fun start(): Boolean {
        cancel()
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            onEnd("permit")
            return false
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onEnd("unavailable")
            return false
        }
        val rec = try {
            SpeechRecognizer.createSpeechRecognizer(context)
        } catch (e: RuntimeException) {
            onEnd("unavailable")
            return false
        }
        recognizer = rec
        latest = ""
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                if (recognizer !== rec) return
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                if (text.isNotEmpty()) latest = text
            }

            override fun onError(error: Int) {
                if (recognizer !== rec) return
                val text = latest
                if (!release(rec)) return
                if (text.isNotEmpty()) onText(text) else onEnd(null)
            }

            override fun onResults(results: Bundle?) {
                if (recognizer !== rec) return
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim().orEmpty()
                    .ifEmpty { latest }
                if (!release(rec)) return
                if (text.isNotEmpty()) onText(text) else onEnd(null)
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ja-JP")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 離すまで切らない。確定は finish の stopListening に任せる。
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 30_000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 30_000L)
        }
        return try {
            rec.startListening(intent)
            true
        } catch (e: SecurityException) {
            if (release(rec)) onEnd("permit")
            false
        } catch (e: RuntimeException) {
            if (release(rec)) onEnd("unavailable")
            false
        }
    }

    /** 離したので、ここまでの認識を確定する。 */
    fun finish() {
        val rec = recognizer ?: return
        try {
            rec.stopListening()
        } catch (e: RuntimeException) {
            val text = latest
            if (release(rec)) {
                if (text.isNotEmpty()) onText(text) else onEnd(null)
            }
        }
    }

    fun cancel() {
        val rec = recognizer ?: return
        recognizer = null
        latest = ""
        try {
            rec.cancel()
        } catch (e: RuntimeException) {
            // もう止まっている
        }
        try {
            rec.destroy()
        } catch (e: RuntimeException) {
            // 破棄できなくても入力は続ける
        }
    }

    private fun release(rec: SpeechRecognizer): Boolean {
        if (recognizer !== rec) return false
        recognizer = null
        latest = ""
        try {
            rec.destroy()
        } catch (e: RuntimeException) {
            // 破棄できなくても入力は続ける
        }
        return true
    }
}
