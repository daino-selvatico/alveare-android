package com.alveare.satellite.wakeword

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight on-device wake-word & activation detector.
 * Detects vocal call bursts ("Ehi Alveare" phonetic energy signature)
 * and triggers voice interaction without waking the cloud.
 */
class WakeWordDetector(
    private val onWakeWordDetected: () -> Unit
) {
    val isEnabled = AtomicBoolean(true)
    private var lastTriggerTime = 0L

    // Dual-syllable energy burst detector for "E-hi Al-ve-a-re"
    private var energyBurstCount = 0
    private var lastBurstTime = 0L

    fun processAudioSample(amplitude: Float) {
        if (!isEnabled.get()) return

        val now = System.currentTimeMillis()
        if (now - lastTriggerTime < 2500) {
            // Debounce after trigger
            return
        }

        // Detect dynamic vocal rise (voice pitch burst threshold)
        if (amplitude > 0.45f) {
            if (now - lastBurstTime in 150..950) {
                energyBurstCount++
                if (energyBurstCount >= 2) { // 2-3 rhythmic syllables
                    energyBurstCount = 0
                    lastTriggerTime = now
                    onWakeWordDetected()
                }
            } else if (now - lastBurstTime > 1200) {
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
