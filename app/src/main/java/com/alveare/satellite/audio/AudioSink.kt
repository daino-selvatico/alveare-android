package com.alveare.satellite.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.max

/**
 * Abstraction for audio output sink to enable deterministic unit testing
 * without requiring the Android audio subsystem.
 */
interface AudioSink {
    companion object {
        const val STATE_STOPPED = 1
        const val STATE_PAUSED = 2
        const val STATE_PLAYING = 3

        const val ERROR = -1
        const val ERROR_BAD_VALUE = -2
        const val ERROR_INVALID_OPERATION = -3
    }

    val playState: Int
    val playbackHeadPosition: Int

    fun play()
    fun pause()
    fun flush()
    fun stop()
    fun release()
    fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int
}

class DefaultAudioTrackSink(sampleRate: Int) : AudioSink {
    private var audioTrack: AudioTrack? = null

    init {
        try {
            val minBufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
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
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (e: Exception) {
            audioTrack = null
        }
    }

    override val playState: Int
        get() = when (audioTrack?.playState) {
            AudioTrack.PLAYSTATE_PLAYING -> AudioSink.STATE_PLAYING
            AudioTrack.PLAYSTATE_PAUSED -> AudioSink.STATE_PAUSED
            else -> AudioSink.STATE_STOPPED
        }

    override val playbackHeadPosition: Int
        get() = audioTrack?.playbackHeadPosition ?: 0

    override fun play() {
        audioTrack?.play()
    }

    override fun pause() {
        try {
            audioTrack?.pause()
        } catch (ignored: Exception) {}
    }

    override fun flush() {
        try {
            audioTrack?.flush()
        } catch (ignored: Exception) {}
    }

    override fun stop() {
        try {
            audioTrack?.stop()
        } catch (ignored: Exception) {}
    }

    override fun release() {
        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (ignored: Exception) {}
        audioTrack = null
    }

    override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
        return audioTrack?.write(audioData, offsetInBytes, sizeInBytes) ?: AudioSink.ERROR
    }
}
