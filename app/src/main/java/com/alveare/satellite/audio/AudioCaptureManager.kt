package com.alveare.satellite.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Single AudioRecord owner for Alveare smart speaker.
 * Feeds PCM wake recognizer while idle and WebSocket after activation.
 * Enforces real hardware stop/release on privacy mute, safe-mode AEC handling,
 * and half-duplex self-trigger prevention in Assistant mode while allowing full-duplex
 * AEC uplink in Live mode.
 */
class AudioCaptureManager(
    private val sampleRate: Int = 16000,
    private val onAudioChunkReady: (ByteArray) -> Unit,
    private val onAmplitudeChanged: (Float) -> Unit,
    private val onSpeechStarted: () -> Unit = {},
    private val onSilenceDetected: () -> Unit = {},
    private val onWakeWordAudioChunk: (ByteArray, Int) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "AudioCapture"
    }

    private var audioRecord: AudioRecord? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    val isRecording = AtomicBoolean(false)
    val isStreamingAudio = AtomicBoolean(false)
    val isPrivacyMuted = AtomicBoolean(false)
    val isAssistantSpeaking = AtomicBoolean(false)
    private var captureThread: Thread? = null

    // Adaptive client VAD parameters for Assistant mode cutoff
    private var speechFramesCount = 0
    private var silenceFramesCount = 0
    var speechThresholdRms = 110.0f
    private val silenceFramesNeeded = 35 // ~1400ms natural pause tolerance at 40ms frames
    private var hasDetectedSpeech = false
    private var streamStartTimestamp = 0L

    private val mutedUntilMs = AtomicLong(0L)
    var isContinuousMode: Boolean = false
    var isAecSafeMode: Boolean = true

    fun muteFor(durationMs: Long) {
        val target = System.currentTimeMillis() + durationMs
        mutedUntilMs.set(maxOf(mutedUntilMs.get(), target))
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (isPrivacyMuted.get()) {
            Log.i(TAG, "Audio capture not started because privacy mute is active")
            return false
        }

        if (isRecording.get()) return true

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBufferSize * 2, 4096)

        try {
            // Prefer VOICE_COMMUNICATION for hardware Acoustic Echo Cancellation (AEC)
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            // Enable hardware audio effects if available
            try {
                val sessionId = audioRecord?.audioSessionId ?: 0
                if (sessionId > 0) {
                    if (AcousticEchoCanceler.isAvailable()) {
                        aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                    }
                    if (NoiseSuppressor.isAvailable()) {
                        ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                    }
                    if (AutomaticGainControl.isAvailable()) {
                        agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not attach audio effects: ${e.message}")
            }

            audioRecord?.startRecording()
            isRecording.set(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord: ${e.message}", e)
            try {
                audioRecord?.release()
            } catch (ignored: Exception) {}
            audioRecord = null
            isRecording.set(false)
            return false
        }

        captureThread = Thread({
            val audioBuffer = ShortArray(640) // 40ms buffer at 16kHz
            val byteBuffer = ByteArray(audioBuffer.size * 2)

            while (isRecording.get()) {
                val readCount = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: -1
                if (readCount > 0) {
                    val now = System.currentTimeMillis()

                    if (now < mutedUntilMs.get()) {
                        onAmplitudeChanged(0.0f)
                        continue
                    }

                    // In Assistant mode (half duplex) OR Live AEC Safe Mode (devices without verified AEC):
                    // skip capture frames while assistant is speaking to prevent self-trigger or acoustic feedback.
                    // In normal Live full duplex: uplink continues during playback so server can detect barge-in!
                    if (isAssistantSpeaking.get() && (!isContinuousMode || isAecSafeMode)) {
                        onAmplitudeChanged(0.0f)
                        continue
                    }

                    // 1. Calculate RMS Amplitude & convert to little-endian bytes
                    var sumSquare = 0.0
                    for (i in 0 until readCount) {
                        val sample = audioBuffer[i].toDouble()
                        sumSquare += sample * sample

                        val byteIdx = i * 2
                        byteBuffer[byteIdx] = (audioBuffer[i].toInt() and 0xFF).toByte()
                        byteBuffer[byteIdx + 1] = ((audioBuffer[i].toInt() shr 8) and 0xFF).toByte()
                    }

                    val rms = sqrt(sumSquare / readCount).toFloat()
                    val normalizedAmp = when {
                        rms <= 10f -> 0.0f
                        else -> ((rms - 10f) / 300f).coerceIn(0.0f, 1.0f)
                    }
                    onAmplitudeChanged(normalizedAmp)

                    val bytesLen = readCount * 2

                    // 2. Route PCM: to active WebSocket if streaming, or to offline wake recognizer if idle
                    if (isStreamingAudio.get()) {
                        val bytesToSend = byteBuffer.copyOf(bytesLen)
                        onAudioChunkReady(bytesToSend)

                        // Client-side silence cutoff for Assistant mode
                        if (!isContinuousMode) {
                            val nowMs = System.currentTimeMillis()
                            if (!hasDetectedSpeech) {
                                if (rms >= speechThresholdRms) {
                                    speechFramesCount++
                                    if (speechFramesCount >= 6) { // Spoke at least ~240ms
                                        hasDetectedSpeech = true
                                        silenceFramesCount = 0
                                        onSpeechStarted()
                                    }
                                } else {
                                    if (speechFramesCount > 0) speechFramesCount--
                                    // User activated assistant mode but did not speak within 8.0 seconds
                                    if (nowMs - streamStartTimestamp > 8000L) {
                                        hasDetectedSpeech = false
                                        speechFramesCount = 0
                                        silenceFramesCount = 0
                                        onSilenceDetected()
                                    }
                                }
                            } else {
                                // User has started speaking: count silence frames to detect natural turn completion
                                if (rms >= speechThresholdRms) {
                                    silenceFramesCount = 0
                                } else {
                                    silenceFramesCount++
                                    if (silenceFramesCount >= silenceFramesNeeded) { // ~1400ms silence
                                        hasDetectedSpeech = false
                                        silenceFramesCount = 0
                                        speechFramesCount = 0
                                        onSilenceDetected()
                                    }
                                }
                            }
                        }
                    } else {
                        // Pass to offline wake phrase recognizer (single AudioRecord owner!)
                        silenceFramesCount = 0
                        speechFramesCount = 0
                        hasDetectedSpeech = false
                        onWakeWordAudioChunk(byteBuffer, bytesLen)
                    }
                }
            }
        }, "Alveare-AudioCaptureThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }

        return true
    }

    fun startStreaming() {
        silenceFramesCount = 0
        speechFramesCount = 0
        hasDetectedSpeech = false
        streamStartTimestamp = System.currentTimeMillis()
        muteFor(450L) // Mute capture during wake/tap chime playback
        isStreamingAudio.set(true)
    }

    fun stopStreaming() {
        isStreamingAudio.set(false)
        silenceFramesCount = 0
        speechFramesCount = 0
        hasDetectedSpeech = false
    }

    @Synchronized
    fun stop() {
        isRecording.set(false)
        isStreamingAudio.set(false)
        captureThread?.interrupt()
        captureThread = null
        try {
            aec?.release()
            aec = null
            ns?.release()
            ns = null
            agc?.release()
            agc = null
            audioRecord?.stop()
            audioRecord?.release()
        } catch (ignored: Exception) {}
        audioRecord = null
    }

    fun release() {
        stop()
    }
}
