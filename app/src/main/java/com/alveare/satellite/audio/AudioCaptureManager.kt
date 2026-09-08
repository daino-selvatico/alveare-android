package com.alveare.satellite.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

class AudioCaptureManager(
    private val sampleRate: Int = 16000,
    private val onAudioChunkReady: (ByteArray) -> Unit,
    private val onAmplitudeChanged: (Float) -> Unit,
    private val onSilenceDetected: () -> Unit = {}
) {
    private var audioRecord: AudioRecord? = null
    private val isRecording = AtomicBoolean(false)
    val isStreamingAudio = AtomicBoolean(false)
    private var captureThread: Thread? = null

    // Simple VAD parameters
    private var speechFramesCount = 0
    private var silenceFramesCount = 0
    private val speechThresholdRms = 400.0f
    private val silenceFramesNeeded = 18 // ~360ms of silence at 20ms frames

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRecording.getAndSet(true)) return

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBufferSize * 2, 4096)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                isRecording.set(false)
                return
            }

            audioRecord?.startRecording()
        } catch (e: Exception) {
            e.printStackTrace()
            isRecording.set(false)
            return
        }

        captureThread = Thread({
            val audioBuffer = ShortArray(640) // 40ms buffer at 16kHz
            val byteBuffer = ByteArray(audioBuffer.size * 2)

            while (isRecording.get()) {
                val readCount = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: -1
                if (readCount > 0) {
                    // 1. Calculate RMS Amplitude
                    var sumSquare = 0.0
                    for (i in 0 until readCount) {
                        val sample = audioBuffer[i].toDouble()
                        sumSquare += sample * sample

                        // Convert to little-endian bytes
                        val byteIdx = i * 2
                        byteBuffer[byteIdx] = (audioBuffer[i].toInt() and 0xFF).toByte()
                        byteBuffer[byteIdx + 1] = ((audioBuffer[i].toInt() shr 8) and 0xFF).toByte()
                    }

                    val rms = sqrt(sumSquare / readCount).toFloat()
                    val normalizedAmp = (rms / 4000.0f).coerceIn(0.0f, 1.0f)
                    onAmplitudeChanged(normalizedAmp)

                    // 2. If actively streaming to Alveare, forward the chunk and check VAD
                    if (isStreamingAudio.get()) {
                        val bytesToSend = byteBuffer.copyOf(readCount * 2)
                        onAudioChunkReady(bytesToSend)

                        // VAD Silence Tracking
                        if (rms >= speechThresholdRms) {
                            speechFramesCount++
                            silenceFramesCount = 0
                        } else {
                            if (speechFramesCount > 6) { // Spoke at least ~250ms
                                silenceFramesCount++
                                if (silenceFramesCount >= silenceFramesNeeded) {
                                    // Turn-taking silence threshold reached!
                                    silenceFramesCount = 0
                                    speechFramesCount = 0
                                    onSilenceDetected()
                                }
                            }
                        }
                    } else {
                        silenceFramesCount = 0
                        speechFramesCount = 0
                    }
                }
            }
        }, "Alveare-AudioCaptureThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun startStreaming() {
        silenceFramesCount = 0
        speechFramesCount = 0
        isStreamingAudio.set(true)
    }

    fun stopStreaming() {
        isStreamingAudio.set(false)
        silenceFramesCount = 0
        speechFramesCount = 0
    }

    fun release() {
        isRecording.set(false)
        isStreamingAudio.set(false)
        captureThread?.interrupt()
        captureThread = null
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (ignored: Exception) {}
        audioRecord = null
    }
}
