package com.alveare.satellite.tts

import java.util.Collections
import java.util.LinkedHashSet

/**
 * Tracks utterance lifecycle in streaming Android Native TTS.
 * Ensures that multi-sentence turns do not trigger completion callbacks
 * until all utterances have finished and generation is marked done,
 * and errors/empty turns fail safely without sticking in PROCESSING.
 */
class TtsPlaybackTracker(
    private val onPlaybackStarted: () -> Unit = {},
    private val onPlaybackFinished: () -> Unit = {}
) {
    private val pendingUtteranceIds = Collections.synchronizedSet(LinkedHashSet<String>())
    @Volatile private var isGenerationDone = false
    @Volatile private var isPlaying = false

    @Synchronized
    fun onSentenceQueued(utteranceId: String) {
        pendingUtteranceIds.add(utteranceId)
        if (!isPlaying) {
            isPlaying = true
            onPlaybackStarted()
        }
    }

    @Synchronized
    fun onUtteranceStarted() {
        if (!isPlaying) {
            isPlaying = true
            onPlaybackStarted()
        }
    }

    @Synchronized
    fun onUtteranceDone(utteranceId: String) {
        pendingUtteranceIds.remove(utteranceId)
        checkDrain()
    }

    @Synchronized
    fun onUtteranceError(utteranceId: String) {
        pendingUtteranceIds.remove(utteranceId)
        checkDrain()
    }

    @Synchronized
    fun markGenerationDone() {
        isGenerationDone = true
        if (pendingUtteranceIds.isEmpty()) {
            finishPlayback()
        }
    }

    @Synchronized
    fun reset() {
        pendingUtteranceIds.clear()
        isGenerationDone = false
        finishPlayback()
    }

    private fun checkDrain() {
        if (isGenerationDone && pendingUtteranceIds.isEmpty()) {
            finishPlayback()
        }
    }

    private fun finishPlayback() {
        if (isPlaying) {
            isPlaying = false
            isGenerationDone = false
            onPlaybackFinished()
        } else if (isGenerationDone) {
            isGenerationDone = false
            onPlaybackFinished()
        }
    }
}
