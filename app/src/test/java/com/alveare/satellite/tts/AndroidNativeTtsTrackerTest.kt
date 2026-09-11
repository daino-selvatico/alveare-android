package com.alveare.satellite.tts

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AndroidNativeTtsTrackerTest {

    @Test
    fun testMultiSentenceQueueDoesNotRearmUntilAllDoneAndGenerationCompleted() {
        val startedCount = AtomicInteger(0)
        val finishedCount = AtomicInteger(0)

        val tracker = TtsPlaybackTracker(
            onPlaybackStarted = { startedCount.incrementAndGet() },
            onPlaybackFinished = { finishedCount.incrementAndGet() }
        )

        // Queue sentence 1 and sentence 2
        tracker.onSentenceQueued("utt1")
        tracker.onSentenceQueued("utt2")

        assertEquals("Playback should have started", 1, startedCount.get())
        assertEquals(0, finishedCount.get())

        // Sentence 1 completes
        tracker.onUtteranceDone("utt1")
        assertEquals("Should NOT finish playback while utt2 is still pending", 0, finishedCount.get())

        // Generation completes from server (turn_complete)
        tracker.markGenerationDone()
        assertEquals("Should NOT finish playback while utt2 is still speaking", 0, finishedCount.get())

        // Sentence 2 completes
        tracker.onUtteranceDone("utt2")
        assertEquals("Playback SHOULD finish once last utterance is done and generation is marked done", 1, finishedCount.get())
    }

    @Test
    fun testGenerationDoneBeforeSentencesDone() {
        val finishedCount = AtomicInteger(0)
        val tracker = TtsPlaybackTracker(
            onPlaybackStarted = {},
            onPlaybackFinished = { finishedCount.incrementAndGet() }
        )

        tracker.onSentenceQueued("u1")
        tracker.markGenerationDone() // Turn complete arrives quickly
        assertEquals(0, finishedCount.get())

        tracker.onUtteranceDone("u1")
        assertEquals(1, finishedCount.get())
    }

    @Test
    fun testTtsErrorDoesNotStickProcessing() {
        val finished = AtomicBoolean(false)
        val tracker = TtsPlaybackTracker(
            onPlaybackStarted = {},
            onPlaybackFinished = { finished.set(true) }
        )

        tracker.onSentenceQueued("u1")
        tracker.markGenerationDone()
        // Error on utterance u1
        tracker.onUtteranceError("u1")

        assertTrue("Playback should finish even on utterance error to prevent sticking in PROCESSING", finished.get())
    }

    @Test
    fun testEmptyGenerationFinishesImmediately() {
        val finished = AtomicBoolean(false)
        val tracker = TtsPlaybackTracker(
            onPlaybackStarted = {},
            onPlaybackFinished = { finished.set(true) }
        )

        tracker.markGenerationDone()
        assertTrue("Empty turn should complete immediately", finished.get())
    }
}
