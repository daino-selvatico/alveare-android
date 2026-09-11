package com.alveare.satellite.audio

import org.junit.Assert.*
import org.junit.Test

class CircularAudioBufferTest {

    @Test
    fun testBufferUnderCapacity() {
        val buffer = CircularAudioBuffer(capacityBytes = 10)
        val data = byteArrayOf(1, 2, 3, 4)
        buffer.write(data, data.size)

        val read = buffer.readRecent()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), read)
    }

    @Test
    fun testBufferOverflowKeepsMostRecent() {
        val buffer = CircularAudioBuffer(capacityBytes = 6)
        buffer.write(byteArrayOf(1, 2, 3, 4), 4)
        buffer.write(byteArrayOf(5, 6, 7, 8), 4)

        // Total written 8 bytes, capacity 6 -> should keep last 6 bytes: 3, 4, 5, 6, 7, 8
        val read = buffer.readRecent()
        assertEquals(6, read.size)
        assertArrayEquals(byteArrayOf(3, 4, 5, 6, 7, 8), read)
    }

    @Test
    fun testBufferClear() {
        val buffer = CircularAudioBuffer(capacityBytes = 10)
        buffer.write(byteArrayOf(1, 2, 3), 3)
        buffer.clear()
        val read = buffer.readRecent()
        assertEquals(0, read.size)
    }
}
