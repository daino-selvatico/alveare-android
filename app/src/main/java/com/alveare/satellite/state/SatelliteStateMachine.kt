package com.alveare.satellite.state

enum class SatelliteMode {
    ASSISTANT,
    LIVE
}

enum class SatelliteState {
    IDLE_WAITING_ACTIVATION,
    LIVE_IDLE_STREAMING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    MUTED
}

/**
 * Deterministic, thread-safe state machine governing Assistant (turn-based) vs Live (continuous full duplex)
 * modes, audio streaming, wake word arming, and privacy mute.
 */
class SatelliteStateMachine(
    initialMode: SatelliteMode = SatelliteMode.ASSISTANT,
    var isWakeWordEnabled: Boolean = false,
    private val onStateChanged: (SatelliteState) -> Unit = {}
) {
    var mode: SatelliteMode = initialMode
        private set

    var currentState: SatelliteState = if (mode == SatelliteMode.LIVE) {
        SatelliteState.LIVE_IDLE_STREAMING
    } else {
        SatelliteState.IDLE_WAITING_ACTIVATION
    }
        private set

    private var isPrivacyMuted = false
    private var isAudioPlaying = false
    private var isServerTurnDone = false

    val isStreamingAudioToWebSocket: Boolean
        @Synchronized get() {
            if (isPrivacyMuted) return false
            return when (mode) {
                SatelliteMode.LIVE -> currentState != SatelliteState.MUTED
                SatelliteMode.ASSISTANT -> currentState == SatelliteState.LISTENING
            }
        }

    val isWakeWordActive: Boolean
        @Synchronized get() {
            if (isPrivacyMuted || !isWakeWordEnabled) return false
            return mode == SatelliteMode.ASSISTANT && currentState == SatelliteState.IDLE_WAITING_ACTIVATION
        }

    private fun setStateInternal(newState: SatelliteState) {
        if (currentState != newState) {
            currentState = newState
            onStateChanged(newState)
        }
    }

    @Synchronized
    fun setMode(newMode: SatelliteMode) {
        if (mode != newMode) {
            mode = newMode
            resetToIdle()
        }
    }

    @Synchronized
    fun setPrivacyMuted(muted: Boolean) {
        isPrivacyMuted = muted
        if (muted) {
            setStateInternal(SatelliteState.MUTED)
        } else {
            resetToIdle()
        }
    }

    @Synchronized
    fun onWakeWordTriggered() {
        if (isPrivacyMuted) return
        if (mode == SatelliteMode.ASSISTANT && currentState == SatelliteState.IDLE_WAITING_ACTIVATION) {
            isAudioPlaying = false
            isServerTurnDone = false
            setStateInternal(SatelliteState.LISTENING)
        }
    }

    @Synchronized
    fun onTapToTalkTriggered() {
        if (isPrivacyMuted) return
        when (currentState) {
            SatelliteState.SPEAKING,
            SatelliteState.PROCESSING -> {
                onInterrupted()
            }
            SatelliteState.LISTENING -> {
                onEndOfSpeech()
            }
            SatelliteState.IDLE_WAITING_ACTIVATION,
            SatelliteState.LIVE_IDLE_STREAMING -> {
                isAudioPlaying = false
                isServerTurnDone = false
                setStateInternal(SatelliteState.LISTENING)
            }
            SatelliteState.MUTED -> {}
        }
    }

    @Synchronized
    fun onVadSpeechStart() {
        if (isPrivacyMuted) return
        if (currentState != SatelliteState.SPEAKING && currentState != SatelliteState.PROCESSING) {
            setStateInternal(SatelliteState.LISTENING)
        }
    }

    @Synchronized
    fun onVadSpeechEnd() {
        if (isPrivacyMuted) return
        if (currentState == SatelliteState.LISTENING) {
            setStateInternal(SatelliteState.PROCESSING)
        }
    }

    @Synchronized
    fun onEndOfSpeech() {
        if (isPrivacyMuted) return
        if (currentState == SatelliteState.LISTENING) {
            setStateInternal(SatelliteState.PROCESSING)
        }
    }

    @Synchronized
    fun onAudioQueued() {
        if (isPrivacyMuted) return
        isAudioPlaying = true
        setStateInternal(SatelliteState.SPEAKING)
    }

    @Synchronized
    fun onAssistantPlaybackStarted() {
        if (isPrivacyMuted) return
        isAudioPlaying = true
        setStateInternal(SatelliteState.SPEAKING)
    }

    @Synchronized
    fun onServerTurnCompleted() {
        isServerTurnDone = true
        if (!isAudioPlaying) {
            resetToIdle()
        }
    }

    @Synchronized
    fun onAssistantPlaybackFinished() {
        isAudioPlaying = false
        if (isServerTurnDone || mode == SatelliteMode.LIVE) {
            resetToIdle()
        }
    }

    @Synchronized
    fun onInterrupted() {
        isAudioPlaying = false
        isServerTurnDone = false
        resetToIdle()
    }

    @Synchronized
    fun resetToIdle() {
        isAudioPlaying = false
        isServerTurnDone = false

        if (isPrivacyMuted) {
            setStateInternal(SatelliteState.MUTED)
            return
        }

        val next = when (mode) {
            SatelliteMode.LIVE -> SatelliteState.LIVE_IDLE_STREAMING
            SatelliteMode.ASSISTANT -> SatelliteState.IDLE_WAITING_ACTIVATION
        }
        setStateInternal(next)
    }
}
