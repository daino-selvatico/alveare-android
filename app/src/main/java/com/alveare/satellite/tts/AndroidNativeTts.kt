package com.alveare.satellite.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class AndroidNativeTts(
    context: Context,
    private val onPlaybackStarted: () -> Unit = {},
    private val onPlaybackFinished: () -> Unit = {}
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "AndroidNativeTts"
    }

    private var tts: TextToSpeech? = null
    private val isReady = AtomicBoolean(false)
    private val pendingSentences = mutableListOf<String>()
    private val tracker = TtsPlaybackTracker(
        onPlaybackStarted = onPlaybackStarted,
        onPlaybackFinished = onPlaybackFinished
    )
    private val utteranceCounter = AtomicLong(0L)
    private var lastSpokenSentence: String? = null

    init {
        try {
            tts = TextToSpeech(context.applicationContext, this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate TextToSpeech: ${e.message}", e)
            isReady.set(false)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.ITALIAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setLanguage(Locale.getDefault())
            }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    tracker.onUtteranceStarted()
                }

                override fun onDone(utteranceId: String?) {
                    tracker.onUtteranceDone(utteranceId ?: "")
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    tracker.onUtteranceError(utteranceId ?: "")
                }
            })
            isReady.set(true)

            synchronized(pendingSentences) {
                for (text in pendingSentences) {
                    speakInternal(text)
                }
                pendingSentences.clear()
            }
        } else {
            Log.e(TAG, "TextToSpeech init failed with status: $status")
            isReady.set(false)
            tracker.reset()
        }
    }

    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    fun setPitch(pitch: Float) {
        tts?.setPitch(pitch.coerceIn(0.5f, 2.0f))
    }

    fun speak(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return

        if (trimmed == lastSpokenSentence) {
            return
        }
        lastSpokenSentence = trimmed

        if (!isReady.get()) {
            synchronized(pendingSentences) {
                pendingSentences.add(trimmed)
            }
            return
        }

        speakInternal(trimmed)
    }

    private fun speakInternal(text: String) {
        val uttId = "alveare_${System.currentTimeMillis()}_${utteranceCounter.incrementAndGet()}"
        tracker.onSentenceQueued(uttId)

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, uttId)

        val res = tts?.speak(text, TextToSpeech.QUEUE_ADD, params, uttId)
        if (res != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TextToSpeech speak returned error code: $res")
            tracker.onUtteranceError(uttId)
        }
    }

    fun markGenerationDone() {
        tracker.markGenerationDone()
    }

    fun stop() {
        lastSpokenSentence = null
        synchronized(pendingSentences) {
            pendingSentences.clear()
        }
        try {
            tts?.stop()
        } catch (ignored: Exception) {}
        tracker.reset()
    }

    fun shutdown() {
        stop()
        try {
            tts?.shutdown()
        } catch (ignored: Exception) {}
        tts = null
        isReady.set(false)
    }
}
