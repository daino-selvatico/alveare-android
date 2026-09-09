package com.alveare.satellite.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

object SoundEffects {
    private const val SAMPLE_RATE = 24000
    private val scope = CoroutineScope(Dispatchers.Default)
    private val isPlaying = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Google Home / Alexa style ascending dual-tone chime (523Hz -> 659Hz)
     * Plays when the satellite starts listening.
     */
    fun playWakeChime() {
        scope.launch {
            playTones(
                Tone(523.25, 0.08, 0.25), // C5
                Tone(659.25, 0.10, 0.30)  // E5
            )
        }
    }

    /**
     * Subtle descending chime when speech finishes and processing begins.
     */
    fun playProcessingChime() {
        scope.launch {
            playTones(
                Tone(659.25, 0.06, 0.20),
                Tone(523.25, 0.08, 0.18)
            )
        }
    }

    /**
     * Soft click / ding when interrupted.
     */
    fun playInterruptChime() {
        scope.launch {
            playTones(
                Tone(392.0, 0.05, 0.20) // G4
            )
        }
    }

    private data class Tone(val freq: Double, val durationSec: Double, val maxVolume: Double)

    private fun playTones(vararg tones: Tone) {
        if (!isPlaying.compareAndSet(false, true)) {
            // Drop duplicate chime requests if already playing to prevent feedback pile-up
            return
        }
        var audioTrack: AudioTrack? = null
        try {
            val totalSamples = tones.sumOf { (it.durationSec * SAMPLE_RATE).toInt() }
            val buffer = ShortArray(totalSamples)
            var offset = 0

            for (tone in tones) {
                val count = (tone.durationSec * SAMPLE_RATE).toInt()
                for (i in 0 until count) {
                    val t = i.toDouble() / SAMPLE_RATE
                    // Hann envelope to smooth edges and prevent clicks
                    val envelope = 0.5 * (1.0 - kotlin.math.cos(2.0 * PI * i / count))
                    val sample = sin(2.0 * PI * tone.freq * t) * envelope * tone.maxVolume
                    buffer[offset + i] = (sample * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
                }
                offset += count
            }

            val bufferSize = buffer.size * 2
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack.write(buffer, 0, buffer.size)
            audioTrack.play()
            // Wait for playback then stop and release cleanly
            val sleepMs = (tones.sumOf { it.durationSec } * 1000).toLong() + 30
            Thread.sleep(sleepMs)
            try {
                if (audioTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop()
                }
            } catch (ignored: Exception) {}
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try {
                audioTrack?.release()
            } catch (ignored: Exception) {}
            isPlaying.set(false)
        }
    }
}
