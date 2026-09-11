package com.alveare.satellite.audio

import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Robust audio playback manager for Alveare neural TTS audio streams.
 * Uses WavParser to extract PCM data and sample rate from RIFF WAV containers (e.g. Kokoro 24kHz),
 * bounded queue to prevent memory growth or latency drift, generation-tokenized audio commands
 * ensuring flush interrupts actual output with no stale chunks surviving, accurate unsigned head
 * tracking, and drain completion callbacks.
 */
class AudioPlaybackManager(
    private var sampleRate: Int = 24000,
    private val onPlaybackStateChanged: (isPlaying: Boolean) -> Unit = {},
    private val onPlaybackDrained: () -> Unit = {},
    private val sinkFactory: (sampleRate: Int) -> AudioSink = { rate -> DefaultAudioTrackSink(rate) }
) {
    companion object {
        private const val TAG = "AudioPlayback"
        private const val MAX_QUEUE_CHUNKS = 32
    }

    private data class PlaybackChunk(val pcmData: ByteArray, val generation: Int)

    private var audioSink: AudioSink? = null
    private val audioQueue = ArrayBlockingQueue<PlaybackChunk>(MAX_QUEUE_CHUNKS)
    private val isRunning = AtomicBoolean(false)
    private var playbackThread: Thread? = null
    private val playbackGeneration = AtomicInteger(0)

    @Volatile
    private var totalFramesWritten: Long = 0

    @Volatile
    var isPlaying: Boolean = false
        private set

    val hasPendingAudio: Boolean
        get() = isPlaying || !audioQueue.isEmpty()

    init {
        initSink(sampleRate)
    }

    @Synchronized
    fun setSampleRate(newSampleRate: Int) {
        if (newSampleRate in 8000..96000 && newSampleRate != sampleRate) {
            Log.i(TAG, "Changing sample rate from $sampleRate to $newSampleRate Hz")
            sampleRate = newSampleRate
            stopAndFlush()
            initSink(sampleRate)
        }
    }

    @Synchronized
    private fun initSink(rate: Int) {
        try {
            audioSink?.release()
        } catch (ignored: Exception) {}

        audioSink = sinkFactory(rate)
        totalFramesWritten = 0
    }

    fun start() {
        if (isRunning.getAndSet(true)) return

        playbackThread = Thread({
            var wasPlaying = false

            while (isRunning.get()) {
                try {
                    val item = audioQueue.poll(50, TimeUnit.MILLISECONDS)
                    val currentGen = playbackGeneration.get()

                    if (item != null) {
                        // Discard chunks from a previous generation (e.g. before flush)
                        if (item.generation != currentGen) {
                            continue
                        }

                        val chunk = item.pcmData
                        if (chunk.isNotEmpty() && isRunning.get()) {
                            val sink = audioSink
                            if (sink != null) {
                                if (!wasPlaying || sink.playState != AudioSink.STATE_PLAYING) {
                                    wasPlaying = true
                                    isPlaying = true
                                    try {
                                        sink.play()
                                    } catch (e: Exception) {
                                        Log.e(TAG, "AudioSink play error: ${e.message}", e)
                                    }
                                    onPlaybackStateChanged(true)
                                }

                                // Write loop with generation token check on every chunk slice
                                var offset = 0
                                while (offset < chunk.size && isRunning.get() && playbackGeneration.get() == currentGen) {
                                    val toWrite = chunk.size - offset
                                    val written = sink.write(chunk, offset, toWrite)
                                    if (written < 0) {
                                        Log.e(TAG, "AudioSink write error code: $written")
                                        break
                                    } else if (written > 0) {
                                        offset += written
                                        totalFramesWritten += (written / 2)
                                    } else {
                                        // 0 bytes written, yield briefly
                                        Thread.sleep(5)
                                    }
                                }
                            }
                        }
                    } else if (wasPlaying && audioQueue.isEmpty()) {
                        // Check if speaker has finished playing buffered frames
                        val sink = audioSink
                        val rawHead = sink?.playbackHeadPosition ?: 0
                        val head = rawHead.toLong() and 0xFFFFFFFFL
                        if (head >= totalFramesWritten) {
                            wasPlaying = false
                            isPlaying = false
                            try {
                                sink?.pause()
                                sink?.flush()
                            } catch (e: Exception) {
                                Log.e(TAG, "AudioSink pause error: ${e.message}", e)
                            }
                            // Reset totalFramesWritten to 0 so subsequent responses drain cleanly!
                            totalFramesWritten = 0L
                            onPlaybackStateChanged(false)
                            onPlaybackDrained()
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in playback loop: ${e.message}", e)
                }
            }

            try {
                audioSink?.pause()
                audioSink?.flush()
            } catch (ignored: Exception) {}
            if (wasPlaying) {
                isPlaying = false
                onPlaybackStateChanged(false)
            }
        }, "Alveare-AudioPlaybackThread").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun enqueueAudio(audioBytes: ByteArray, hintedSampleRate: Int? = null) {
        if (audioBytes.isEmpty()) return

        // 1. Parse WAV container if present
        val wavInfo = WavParser.parse(audioBytes)
        val rateToUse = wavInfo?.sampleRate ?: (hintedSampleRate ?: sampleRate)
        val pcmData = wavInfo?.pcmData ?: audioBytes

        if (rateToUse in 8000..96000 && rateToUse != sampleRate) {
            setSampleRate(rateToUse)
        }

        if (pcmData.isEmpty()) return

        // 2. Generation-tagged chunk
        val chunk = PlaybackChunk(pcmData, playbackGeneration.get())

        // 3. Bounded enqueue: drop oldest chunk if saturated
        synchronized(audioQueue) {
            if (!audioQueue.offer(chunk)) {
                audioQueue.poll()
                audioQueue.offer(chunk)
                Log.w(TAG, "Playback queue saturated, dropped oldest chunk to preserve real-time latency")
            }
        }
    }

    @Synchronized
    fun stopAndFlush() {
        playbackGeneration.incrementAndGet()
        audioQueue.clear()
        totalFramesWritten = 0
        isPlaying = false
        try {
            audioSink?.pause()
            audioSink?.flush()
        } catch (ignored: Exception) {}
        onPlaybackStateChanged(false)
    }

    fun release() {
        isRunning.set(false)
        playbackGeneration.incrementAndGet()
        audioQueue.clear()
        playbackThread?.interrupt()
        playbackThread = null
        try {
            audioSink?.stop()
            audioSink?.release()
        } catch (ignored: Exception) {}
        audioSink = null
        isPlaying = false
    }
}
