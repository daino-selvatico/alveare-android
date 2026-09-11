package com.alveare.satellite.state

import org.junit.Assert.*
import org.junit.Test

class SatelliteControllerTest {

    @Test
    fun testLiveModeFullDuplexWebSocketEligibility() {
        val sm = SatelliteStateMachine(initialMode = SatelliteMode.LIVE, isWakeWordEnabled = false)

        // 1. Idle streaming
        assertEquals(SatelliteState.LIVE_IDLE_STREAMING, sm.currentState)
        assertTrue("Live mode idle should stream audio to server", sm.isStreamingAudioToWebSocket)
        assertFalse("Wake word should not be active in live mode", sm.isWakeWordActive)

        // 2. Server VAD speech start -> LISTENING
        sm.onVadSpeechStart()
        assertEquals(SatelliteState.LISTENING, sm.currentState)
        assertTrue("Live listening should stream audio", sm.isStreamingAudioToWebSocket)

        // 3. Server VAD speech end -> PROCESSING
        sm.onVadSpeechEnd()
        assertEquals(SatelliteState.PROCESSING, sm.currentState)
        assertTrue("Live processing MUST continue streaming audio for full-duplex barge-in", sm.isStreamingAudioToWebSocket)

        // 4. Assistant playback starts -> SPEAKING
        sm.onAssistantPlaybackStarted()
        assertEquals(SatelliteState.SPEAKING, sm.currentState)
        assertTrue("Live speaking MUST continue streaming audio (AEC-conditioned uplink)", sm.isStreamingAudioToWebSocket)
        assertFalse(sm.isWakeWordActive)

        // 5. Server turn complete received while speaking
        sm.onServerTurnCompleted()
        assertEquals(SatelliteState.SPEAKING, sm.currentState)
        assertTrue("Should remain streaming while assistant speaks", sm.isStreamingAudioToWebSocket)

        // 6. Playback finishes -> returns to idle streaming
        sm.onAssistantPlaybackFinished()
        assertEquals(SatelliteState.LIVE_IDLE_STREAMING, sm.currentState)
        assertTrue(sm.isStreamingAudioToWebSocket)
    }

    @Test
    fun testAssistantModeHalfDuplexAndHotwordSuppression() {
        val sm = SatelliteStateMachine(initialMode = SatelliteMode.ASSISTANT, isWakeWordEnabled = true)

        // 1. Idle -> Wake word active, WebSocket uplink inactive
        assertEquals(SatelliteState.IDLE_WAITING_ACTIVATION, sm.currentState)
        assertFalse("Assistant idle should NOT stream to WebSocket", sm.isStreamingAudioToWebSocket)
        assertTrue("Assistant idle should have wake word active", sm.isWakeWordActive)

        // 2. Trigger wake word -> LISTENING
        sm.onWakeWordTriggered()
        assertEquals(SatelliteState.LISTENING, sm.currentState)
        assertTrue("Listening state streams to WebSocket", sm.isStreamingAudioToWebSocket)
        assertFalse("Wake word suppressed while listening", sm.isWakeWordActive)

        // 3. End of speech -> PROCESSING
        sm.onEndOfSpeech()
        assertEquals(SatelliteState.PROCESSING, sm.currentState)
        assertFalse("Processing state does NOT stream to WebSocket", sm.isStreamingAudioToWebSocket)
        assertFalse("Wake word suppressed while processing", sm.isWakeWordActive)

        // 4. Assistant playback starts -> SPEAKING
        sm.onAssistantPlaybackStarted()
        assertEquals(SatelliteState.SPEAKING, sm.currentState)
        assertFalse("Speaking state does NOT stream to WebSocket in Assistant mode", sm.isStreamingAudioToWebSocket)
        assertFalse("Wake word MUST remain suppressed while speaking to prevent self-trigger", sm.isWakeWordActive)

        // 5. Server turn complete while speaking
        sm.onServerTurnCompleted()
        assertEquals("Remains in SPEAKING until audio drains", SatelliteState.SPEAKING, sm.currentState)
        assertFalse("Wake word MUST NOT rearm before audio drains", sm.isWakeWordActive)

        // 6. Playback finished -> returns to IDLE
        sm.onAssistantPlaybackFinished()
        assertEquals(SatelliteState.IDLE_WAITING_ACTIVATION, sm.currentState)
        assertTrue("Wake word rearms only after playback fully drained", sm.isWakeWordActive)
    }

    @Test
    fun testTurnCompleteDoesNotRearmWhileAudioQueuedBeforePlaybackStarts() {
        val sm = SatelliteStateMachine(initialMode = SatelliteMode.ASSISTANT, isWakeWordEnabled = true)
        sm.onWakeWordTriggered()
        sm.onEndOfSpeech()
        assertEquals(SatelliteState.PROCESSING, sm.currentState)

        // Audio chunk arrives and is enqueued into playback manager
        sm.onAudioQueued()
        assertEquals(SatelliteState.SPEAKING, sm.currentState)

        // Turn complete arrives from server BEFORE speaker has actually started playing
        sm.onServerTurnCompleted()

        // MUST stay in SPEAKING and NOT rearm wake word!
        assertEquals("Must remain SPEAKING if audio is queued", SatelliteState.SPEAKING, sm.currentState)
        assertFalse("Wake word must not rearm while audio is queued", sm.isWakeWordActive)

        // Speaker starts playing
        sm.onAssistantPlaybackStarted()
        assertEquals(SatelliteState.SPEAKING, sm.currentState)
        assertFalse(sm.isWakeWordActive)

        // Speaker finishes playback
        sm.onAssistantPlaybackFinished()
        assertEquals(SatelliteState.IDLE_WAITING_ACTIVATION, sm.currentState)
        assertTrue(sm.isWakeWordActive)
    }

    @Test
    fun testModeSwitchCancelsCurrentTurnAndAudio() {
        val sm = SatelliteStateMachine(initialMode = SatelliteMode.ASSISTANT, isWakeWordEnabled = true)
        sm.onWakeWordTriggered()
        sm.onEndOfSpeech()
        sm.onAudioQueued()
        assertEquals(SatelliteState.SPEAKING, sm.currentState)

        // Switch mode to LIVE while SPEAKING
        sm.setMode(SatelliteMode.LIVE)
        assertEquals(SatelliteMode.LIVE, sm.mode)
        assertEquals(SatelliteState.LIVE_IDLE_STREAMING, sm.currentState)
        assertTrue(sm.isStreamingAudioToWebSocket)
        assertFalse(sm.isWakeWordActive)

        // Switch back to ASSISTANT
        sm.setMode(SatelliteMode.ASSISTANT)
        assertEquals(SatelliteMode.ASSISTANT, sm.mode)
        assertEquals(SatelliteState.IDLE_WAITING_ACTIVATION, sm.currentState)
        assertFalse(sm.isStreamingAudioToWebSocket)
        assertTrue(sm.isWakeWordActive)
    }
}
