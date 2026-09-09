package com.alveare.satellite.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class AudioPlaybackManager(
    private var sampleRate: Int = 24000,
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit = {}
) {
    private var audioTrack: AudioTrack? = null
    private val audioQueue = LinkedBlockingQueue<ByteArray>()
    private val isRunning = AtomicBoolean(false)
    private var playbackThread: Thread? = null

    init {
        initAudioTrack(sampleRate)
    }

    @Synchronized
    fun setSampleRate(newSampleRate: Int) {
        if (newSampleRate > 0 && newSampleRate != sampleRate) {
            sampleRate = newSampleRate
            stopAndFlush()
            initAudioTrack(sampleRate)
        }
    }

    @Synchronized
    private fun initAudioTrack(rate: Int) {
        try {
            audioTrack?.release()
        } catch (ignored: Exception) {}

        val minBufferSize = AudioTrack.getMinBufferSize(
            rate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBufferSize * 4, 16384)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        totalFramesWritten = 0
    }

    @Volatile
    private var totalFramesWritten: Long = 0

    fun start() {
        if (isRunning.getAndSet(true)) return

        playbackThread = Thread({
            var wasPlaying = false
            while (isRunning.get()) {
                try {
                    val chunk = audioQueue.poll(150, java.util.concurrent.TimeUnit.MILLISECONDS)
                    if (chunk != null && chunk.isNotEmpty() && isRunning.get()) {
                        if (!wasPlaying) {
                            wasPlaying = true
                            try {
                                if (audioTrack?.playState != AudioTrack.PLAYSTATE_PLAYING) {
                                    audioTrack?.play()
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            onPlaybackStateChanged(true)
                        }
                        audioTrack?.write(chunk, 0, chunk.size)
                        totalFramesWritten += (chunk.size / 2)
                    } else if (chunk == null && wasPlaying && audioQueue.isEmpty()) {
                        // Check if the physical speaker has finished playing the buffered frames
                        val head = audioTrack?.playbackHeadPosition?.toLong() ?: 0L
                        if (head >= totalFramesWritten) {
                            wasPlaying = false
                            try {
                                audioTrack?.pause()
                                audioTrack?.flush()
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            onPlaybackStateChanged(false)
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            try {
                audioTrack?.pause()
                audioTrack?.flush()
            } catch (e: Exception) {}
            if (wasPlaying) {
                onPlaybackStateChanged(false)
            }
        }, "Alveare-AudioPlaybackThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun enqueueAudio(pcmChunk: ByteArray) {
        if (pcmChunk.isNotEmpty()) {
            // If the audio chunk has a 44-byte WAV header (RIFF...WAVE), strip it for clean PCM streaming
            val pcmData = if (pcmChunk.size > 44 &&
                pcmChunk[0] == 'R'.code.toByte() &&
                pcmChunk[1] == 'I'.code.toByte() &&
                pcmChunk[2] == 'F'.code.toByte() &&
                pcmChunk[3] == 'F'.code.toByte()
            ) {
                val wavRate = (pcmChunk[24].toInt() and 0xFF) or
                        ((pcmChunk[25].toInt() and 0xFF) shl 8) or
                        ((pcmChunk[26].toInt() and 0xFF) shl 16) or
                        ((pcmChunk[27].toInt() and 0xFF) shl 24)
                if (wavRate in 8000..96000 && wavRate != sampleRate) {
                    setSampleRate(wavRate)
                }
                pcmChunk.copyOfRange(44, pcmChunk.size)
            } else {
                pcmChunk
            }
            if (pcmData.isNotEmpty()) {
                audioQueue.offer(pcmData)
            }
        }
    }

    @Synchronized
    fun stopAndFlush() {
        audioQueue.clear()
        totalFramesWritten = 0
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (ignored: Exception) {}
        onPlaybackStateChanged(false)
    }

    fun release() {
        isRunning.set(false)
        audioQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (ignored: Exception) {}
        audioTrack = null
    }
}
