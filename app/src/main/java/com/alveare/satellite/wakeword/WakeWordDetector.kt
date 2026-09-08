package com.alveare.satellite.wakeword

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight on-device wake-word & activation detector.
 * Detects vocal call bursts ("Ehi Alveare" phonetic energy signature)
 * and triggers voice interaction without waking the cloud.
 */
class WakeWordDetector(
    private val isConnectedProvider: () -> Boolean = { true },
    private val onWakeWordDetected: () -> Unit
) {
    val isEnabled = AtomicBoolean(false)
    var sensitivity = 0.35f // Configurable amplitude threshold (0.20f - 0.60f)
    private var lastTriggerTime = 0L

    // Rhythmic syllable energy burst detector for "E-hi Al-ve-a-re"
    private var energyBurstCount = 0
    private var lastBurstTime = 0L

    fun processAudioSample(amplitude: Float) {
        if (!isEnabled.get()) return
        if (!isConnectedProvider()) return // Never trigger if disconnected from Alveare!

        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < 5000) {
            // Debounce after trigger
            return
        }

        // Detect dynamic vocal rise with configurable sensitivity
        if (amplitude >= sensitivity) {
            val delta = now - lastBurstTime
            if (delta in 180..650) {
                energyBurstCount++
                if (energyBurstCount >= 4) { // 4 distinct rhythmic syllables
                    energyBurstCount = 0
                    lastTriggerTime = now
                    onWakeWordDetected()
                }
            } else if (delta > 750) {
                energyBurstCount = 1
            }
            lastBurstTime = now
        }
    }

    fun reset() {
        energyBurstCount = 0
        lastBurstTime = 0L
    }
}
