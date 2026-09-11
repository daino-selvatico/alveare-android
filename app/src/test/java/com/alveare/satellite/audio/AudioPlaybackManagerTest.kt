package com.alveare.satellite.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class AudioPlaybackManagerTest {

    class FakeAudioSink(val sampleRate: Int) : AudioSink {
        var playStateValue: Int = AudioSink.STATE_STOPPED
        var headPositionValue: Int = 0
        var playCount = 0
        var pauseCount = 0
        var flushCount = 0
        var stopCount = 0
        var releaseCount = 0
        val writtenBytes = mutableListOf<ByteArray>()
        var writeInterceptor: ((ByteArray, Int, Int) -> Int)? = null

        override val playState: Int get() = playStateValue
        override val playbackHeadPosition: Int get() = headPositionValue

        override fun play() {
            playCount++
            playStateValue = AudioSink.STATE_PLAYING
        }

        override fun pause() {
            pauseCount++
            playStateValue = AudioSink.STATE_PAUSED
        }

        override fun flush() {
            flushCount++
            headPositionValue = 0 // Android AudioTrack.flush() resets playbackHeadPosition to 0
        }

        override fun stop() {
            stopCount++
            playStateValue = AudioSink.STATE_STOPPED
        }

        override fun release() {
            releaseCount++
            playStateValue = AudioSink.STATE_STOPPED
        }

        override fun write(audioData: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int {
            writeInterceptor?.let { return it(audioData, offsetInBytes, sizeInBytes) }
            val chunk = audioData.copyOfRange(offsetInBytes, offsetInBytes + sizeInBytes)
            writtenBytes.add(chunk)
            return sizeInBytes
        }
    }

    @Test
    fun testTwoCompleteResponsesDrainAccurately() {
        var activeSink: FakeAudioSink? = null
        val drainCount = AtomicInteger(0)
        val drainLatch1 = CountDownLatch(1)
        val drainLatch2 = CountDownLatch(1)

        val manager = AudioPlaybackManager(
            sampleRate = 24000,
            onPlaybackDrained = {
                val c = drainCount.incrementAndGet()
                if (c == 1) drainLatch1.countDown()
                else if (c == 2) drainLatch2.countDown()
            },
            sinkFactory = { rate ->
                FakeAudioSink(rate).also { activeSink = it }
            }
        )
        manager.start()

        // --- First Response: 1000 frames (2000 bytes) ---
        val response1Data = ByteArray(2000) { 1 }
        manager.enqueueAudio(response1Data)

        // Wait until chunk is written to sink
        val t0 = System.currentTimeMillis()
        while ((activeSink?.writtenBytes?.size ?: 0) == 0 && System.currentTimeMillis() - t0 < 1000) {
            Thread.sleep(10)
        }
        assertTrue("Sink should have received response 1 data", (activeSink?.writtenBytes?.size ?: 0) > 0)
        assertTrue("Manager should be playing", manager.isPlaying)

        // Simulate speaker draining the 1000 frames
        activeSink!!.headPositionValue = 1000
        assertTrue("First response should drain", drainLatch1.await(2, TimeUnit.SECONDS))
        assertEquals(1, drainCount.get())
        assertFalse("Manager should not be playing after drain", manager.isPlaying)
        assertEquals("Head should be reset to 0 by flush after drain", 0, activeSink!!.headPositionValue)

        // --- Second Response: 500 frames (1000 bytes) ---
        val response2Data = ByteArray(1000) { 2 }
        manager.enqueueAudio(response2Data)

        val t1 = System.currentTimeMillis()
        val prevWrittenCount = activeSink!!.writtenBytes.size
        while (activeSink!!.writtenBytes.size == prevWrittenCount && System.currentTimeMillis() - t1 < 1000) {
            Thread.sleep(10)
        }
        assertTrue("Sink should have received response 2 data", activeSink!!.writtenBytes.size > prevWrittenCount)
        assertTrue("Manager should be playing response 2", manager.isPlaying)

        // Simulate speaker draining the 500 frames of response 2
        activeSink!!.headPositionValue = 500
        assertTrue("Second response MUST drain cleanly without hanging on accumulated frames", drainLatch2.await(2, TimeUnit.SECONDS))
        assertEquals(2, drainCount.get())
        assertFalse(manager.isPlaying)

        manager.release()
    }

    @Test
    fun testInterruptWhileWriteBlockedThenNewResponse() {
        var activeSink: FakeAudioSink? = null
        val writeBlockLatch = CountDownLatch(1)
        val unblockLatch = CountDownLatch(1)

        val manager = AudioPlaybackManager(
            sampleRate = 24000,
            sinkFactory = { rate ->
                FakeAudioSink(rate).apply {
                    activeSink = this
                    writeInterceptor = { _, _, len ->
                        writeBlockLatch.countDown()
                        unblockLatch.await(500, TimeUnit.MILLISECONDS)
                        len // pretend written
                    }
                }
            }
        )
        manager.start()

        // Enqueue 2 chunks for response 1
        manager.enqueueAudio(ByteArray(1000) { 0x11 })
        manager.enqueueAudio(ByteArray(1000) { 0x12 })

        // Wait until writer is blocked in chunk 1
        assertTrue("Writer should enter write block", writeBlockLatch.await(2, TimeUnit.SECONDS))

        // INTERRUPT while write is blocked!
        manager.stopAndFlush()
        unblockLatch.countDown() // release writer

        assertFalse("Playback should be stopped after flush", manager.isPlaying)

        // Reset interceptor for clean response 2
        activeSink!!.writeInterceptor = null

        // Enqueue response 2
        val drainLatch = CountDownLatch(1)
        val manager2 = AudioPlaybackManager(
            sampleRate = 24000,
            onPlaybackDrained = { drainLatch.countDown() },
            sinkFactory = { rate ->
                FakeAudioSink(rate).also { activeSink = it }
            }
        )
        manager2.start()
        manager2.enqueueAudio(ByteArray(1000) { 0x22 })

        val t0 = System.currentTimeMillis()
        while ((activeSink?.writtenBytes?.size ?: 0) == 0 && System.currentTimeMillis() - t0 < 1000) {
            Thread.sleep(10)
        }
        assertTrue("Response 2 should be written cleanly", (activeSink?.writtenBytes?.size ?: 0) > 0)
        activeSink!!.headPositionValue = 500
        assertTrue("Response 2 should drain", drainLatch.await(2, TimeUnit.SECONDS))

        manager.release()
        manager2.release()
    }

    @Test
    fun testPartialAndErrorWrites() {
        val writeCalls = AtomicInteger(0)

        val manager = AudioPlaybackManager(
            sampleRate = 24000,
            sinkFactory = { rate ->
                FakeAudioSink(rate).apply {
                    writeInterceptor = { _, _, len ->
                        val call = writeCalls.incrementAndGet()
                        when (call) {
                            1 -> len / 2 // Partial write: writes half
                            2 -> len // Writes the rest
                            3 -> AudioSink.ERROR // Error write
                            else -> len
                        }
                    }
                }
            }
        )
        manager.start()

        // 1000 bytes chunk
        manager.enqueueAudio(ByteArray(1000) { 42 })

        val t0 = System.currentTimeMillis()
        while (writeCalls.get() < 2 && System.currentTimeMillis() - t0 < 1000) {
            Thread.sleep(10)
        }
        assertTrue("Partial write should trigger a second write for remaining bytes", writeCalls.get() >= 2)

        // Now trigger error write
        manager.enqueueAudio(ByteArray(500) { 99 })
        val t1 = System.currentTimeMillis()
        while (writeCalls.get() < 3 && System.currentTimeMillis() - t1 < 1000) {
            Thread.sleep(10)
        }
        assertEquals("Should have encountered error write", 3, writeCalls.get())

        manager.release()
    }

    @Test
    fun testSampleRateTransition() {
        val createdSinks = mutableListOf<FakeAudioSink>()
        val drainLatch = CountDownLatch(1)

        val manager = AudioPlaybackManager(
            sampleRate = 24000,
            onPlaybackDrained = { drainLatch.countDown() },
            sinkFactory = { rate ->
                FakeAudioSink(rate).also { createdSinks.add(it) }
            }
        )
        manager.start()
        assertEquals(1, createdSinks.size)
        assertEquals(24000, createdSinks[0].sampleRate)

        // Switch to 44100 Hz
        manager.setSampleRate(44100)
        assertEquals(2, createdSinks.size)
        assertEquals(44100, createdSinks[1].sampleRate)

        // Enqueue audio at 44.1kHz
        manager.enqueueAudio(ByteArray(2000) { 7 })
        val t0 = System.currentTimeMillis()
        while (createdSinks[1].writtenBytes.isEmpty() && System.currentTimeMillis() - t0 < 1000) {
            Thread.sleep(10)
        }
        assertTrue("Audio at 44100Hz should be written to second sink", createdSinks[1].writtenBytes.isNotEmpty())

        createdSinks[1].headPositionValue = 1000
        assertTrue("Should drain at new sample rate", drainLatch.await(2, TimeUnit.SECONDS))

        manager.release()
    }

    @Test
    fun testUnsignedHeadWrapping() {
        var activeSink: FakeAudioSink? = null
        val drainLatch = CountDownLatch(1)

        val manager = AudioPlaybackManager(
            sampleRate = 24000,
            onPlaybackDrained = { drainLatch.countDown() },
            sinkFactory = { rate ->
                FakeAudioSink(rate).also { activeSink = it }
            }
        )
        manager.start()

        // 1000 frames (2000 bytes)
        manager.enqueueAudio(ByteArray(2000) { 3 })
        val t0 = System.currentTimeMillis()
        while ((activeSink?.writtenBytes?.size ?: 0) == 0 && System.currentTimeMillis() - t0 < 1000) {
            Thread.sleep(10)
        }

        // Int wrapping: simulate position as unsigned int
        activeSink!!.headPositionValue = 1000
        assertTrue("Drain should complete with accurate unsigned head comparison", drainLatch.await(2, TimeUnit.SECONDS))

        manager.release()
    }
}
