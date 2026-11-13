// SPDX-License-Identifier: GPL-3.0-or-later
package dev.horota.meltype.ime

import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.*

class VoiceInputTest {
    private val context = mock(Context::class.java)
    private val first = mock(SpeechRecognizer::class.java)
    private val second = mock(SpeechRecognizer::class.java)
    private val texts = mutableListOf<String>()
    private val ends = mutableListOf<String?>()
    private val voice = VoiceInput(context, { texts += it }, { ends += it })

    private fun withRecognizer(test: () -> Unit) {
        `when`(context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO))
            .thenReturn(PackageManager.PERMISSION_GRANTED)
        mockStatic(SpeechRecognizer::class.java).use { speech ->
            speech.`when`<Boolean> { SpeechRecognizer.isRecognitionAvailable(context) }.thenReturn(true)
            speech.`when`<SpeechRecognizer> { SpeechRecognizer.createSpeechRecognizer(context) }
                .thenReturn(first, second)
            test()
        }
    }

    private fun listener(rec: SpeechRecognizer): RecognitionListener {
        val captor = ArgumentCaptor.forClass(RecognitionListener::class.java)
        verify(rec).setRecognitionListener(captor.capture())
        return captor.value
    }

    private fun result(text: String): Bundle = mock(Bundle::class.java).also {
        `when`(it.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)).thenReturn(arrayListOf(text))
    }

    @Test
    fun cancellationDropsLateResultsAndErrors() = withRecognizer {
        assertTrue(voice.start())
        val old = listener(first)
        voice.cancel()
        old.onPartialResults(result("古い途中結果"))
        old.onResults(result("古い結果"))
        old.onError(SpeechRecognizer.ERROR_CLIENT)
        assertTrue(texts.isEmpty())
        assertTrue(ends.isEmpty())
    }

    @Test
    fun finalResultIsDeliveredOnlyOnceEvenIfDestroyCallsBack() = withRecognizer {
        voice.start()
        val active = listener(first)
        doAnswer { active.onError(SpeechRecognizer.ERROR_CLIENT); null }.`when`(first).destroy()
        active.onResults(result("確定"))
        active.onResults(result("重複"))
        assertEquals(listOf("確定"), texts)
        assertTrue(ends.isEmpty())
        verify(first, times(1)).destroy()
    }

    @Test
    fun oldSessionCannotOverwriteOrFinishTheNewSession() = withRecognizer {
        voice.start()
        val old = listener(first)
        voice.start()
        val active = listener(second)
        active.onPartialResults(result("新しい途中結果"))
        old.onPartialResults(result("古い途中結果"))
        old.onResults(result("古い確定"))
        old.onError(SpeechRecognizer.ERROR_CLIENT)
        verify(second, never()).destroy()
        active.onResults(null)
        assertEquals(listOf("新しい途中結果"), texts)
        assertTrue(ends.isEmpty())
    }

    @Test
    fun stopFailureDeliversPartialResultOnce() = withRecognizer {
        voice.start()
        val active = listener(first)
        active.onPartialResults(result("途中まで"))
        doThrow(RuntimeException("stopped")).`when`(first).stopListening()
        voice.finish()
        active.onResults(result("遅延"))
        assertEquals(listOf("途中まで"), texts)
        assertTrue(ends.isEmpty())
    }

    @Test
    fun emptyResultEndsOnce() = withRecognizer {
        voice.start()
        val active = listener(first)
        active.onResults(null)
        active.onError(SpeechRecognizer.ERROR_CLIENT)
        assertEquals(listOf<String?>(null), ends)
        assertTrue(texts.isEmpty())
    }
}
