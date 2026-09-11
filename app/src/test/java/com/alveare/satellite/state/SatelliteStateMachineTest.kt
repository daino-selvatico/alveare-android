package com.alveare.satellite.state

import org.junit.Assert.*
import org.junit.Test

class SatelliteStateMachineTest {

    @Test
    fun testAssistantModeFlow() {
        val sm = SatelliteStateMachine(initialMode = SatelliteMode.ASSISTANT, isWakeWordEnabled = true)
        
        // Initial state in assistant mode with wake word enabled: IDLE_WAITING_ACTIVATION
        assertEquals(SatelliteState.IDLE_WAITING_ACTIVATION, sm.currentState)
        assertTrue("Wake word should be active when idle in assistant mode", sm.isWakeWordActive)

        // Wake phrase detected
        sm.onWakeWordTriggered()
        assertEquals(SatelliteState.LISTENING, sm.currentState)
        assertTrue(sm.isStreamingAudioToWebSocket)
        assertFalse("Wake word should be paused while listening", sm.isWakeWordActive)

        // End of speech detected
        sm.onEndOfSpeech()
        assertEquals(SatelliteState.PROCESSING, sm.currentState)
        assertFalse(sm.isStreamingAudioToWebSocket)
        assertFalse("Wake word should be paused while processing", sm.isWakeWordActive)

        // Assistant starts speaking
        sm.onAssistantPlaybackStarted()
        assertEquals(SatelliteState.SPEAKING, sm.currentState)
        assertFalse(sm.isStreamingAudioToWebSocket)
        assertFalse("Wake word should be paused while assistant is speaking", sm.isWakeWordActive)

        // Turn complete received from server, BUT audio is still playing!
        sm.onServerTurnCompleted()
        assertEquals(SatelliteState.SPEAKING, sm.currentState) // Remains SPEAKING until playback drains
        assertFalse(sm.isWakeWordActive)

        // Audio playback finishes
        sm.onAssistantPlaybackFinished()
        assertEquals(SatelliteState.IDLE_WAITING_ACTIVATION, sm.currentState)
        assertTrue("Wake word should re-arm after playback finishes", sm.isWakeWordActive)
    }

    @Test
    fun testLiveModeFlow() {
        val sm = SatelliteStateMachine(initialMode = SatelliteMode.LIVE, isWakeWordEnabled = false)

        // Initial state in live mode: continuous streaming
        assertEquals(SatelliteState.LIVE_IDLE_STREAMING, sm.currentState)
        assertTrue(sm.isStreamingAudioToWebSocket)
        assertFalse("Wake word should not be active in live mode", sm.isWakeWordActive)

        // Server VAD detects speech
        sm.onVadSpeechStart()
        assertEquals(SatelliteState.LISTENING, sm.currentState)

        // Server VAD speech end
        sm.onVadSpeechEnd()
        assertEquals(SatelliteState.PROCESSING, sm.currentState)

        // Assistant starts speaking
        sm.onAssistantPlaybackStarted()
        assertEquals(SatelliteState.SPEAKING, sm.currentState)

        // User barges in (interruption)
        sm.onInterrupted()
        assertEquals(SatelliteState.LIVE_IDLE_STREAMING, sm.currentState)
        assertTrue(sm.isStreamingAudioToWebSocket)
    }

    @Test
    fun testTapToTalkAssistantMode() {
        val sm = SatelliteStateMachine(initialMode = SatelliteMode.ASSISTANT, isWakeWordEnabled = false)
        assertEquals(SatelliteState.IDLE_WAITING_ACTIVATION, sm.currentState)

        // Tap to start speaking
        sm.onTapToTalkTriggered()
        assertEquals(SatelliteState.LISTENING, sm.currentState)
        assertTrue(sm.isStreamingAudioToWebSocket)

        // Tap again while speaking to finish early
        sm.onTapToTalkTriggered()
        assertEquals(SatelliteState.PROCESSING, sm.currentState)
        assertFalse(sm.isStreamingAudioToWebSocket)

        // Assistant speaks
        sm.onAssistantPlaybackStarted()
        assertEquals(SatelliteState.SPEAKING, sm.currentState)

        // Tap while speaking interrupts
        sm.onTapToTalkTriggered()
        assertEquals(SatelliteState.IDLE_WAITING_ACTIVATION, sm.currentState)
    }

    @Test
    fun testModeSwitching() {
        val sm = SatelliteStateMachine(initialMode = SatelliteMode.ASSISTANT, isWakeWordEnabled = true)
        assertEquals(SatelliteState.IDLE_WAITING_ACTIVATION, sm.currentState)

        sm.setMode(SatelliteMode.LIVE)
        assertEquals(SatelliteMode.LIVE, sm.mode)
        assertEquals(SatelliteState.LIVE_IDLE_STREAMING, sm.currentState)
        assertTrue(sm.isStreamingAudioToWebSocket)

        sm.setMode(SatelliteMode.ASSISTANT)
        assertEquals(SatelliteMode.ASSISTANT, sm.mode)
        assertEquals(SatelliteState.IDLE_WAITING_ACTIVATION, sm.currentState)
        assertFalse(sm.isStreamingAudioToWebSocket)
    }

    @Test
    fun testPrivacyMute() {
        val sm = SatelliteStateMachine(initialMode = SatelliteMode.LIVE, isWakeWordEnabled = false)
        sm.setPrivacyMuted(true)

        assertEquals(SatelliteState.MUTED, sm.currentState)
        assertFalse(sm.isStreamingAudioToWebSocket)
        assertFalse(sm.isWakeWordActive)

        // Unmute restores mode
        sm.setPrivacyMuted(false)
        assertEquals(SatelliteState.LIVE_IDLE_STREAMING, sm.currentState)
        assertTrue(sm.isStreamingAudioToWebSocket)
    }
}
