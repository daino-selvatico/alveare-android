package com.alveare.satellite.audio

/**
 * Thread-safe circular audio buffer for retaining pre-roll audio before wake word detection,
 * preventing trimming of the user's first words upon activation.
 */
class CircularAudioBuffer(private val capacityBytes: Int) {
    private val buffer = ByteArray(capacityBytes)
    private var writeHead = 0
    private var count = 0

    @Synchronized
    fun write(data: ByteArray, length: Int = data.size) {
        val len = length.coerceAtMost(data.size)
        if (len <= 0) return

        if (len >= capacityBytes) {
            // New chunk completely overwrites buffer
            System.arraycopy(data, len - capacityBytes, buffer, 0, capacityBytes)
            writeHead = 0
            count = capacityBytes
            return
        }

        val firstChunk = (capacityBytes - writeHead).coerceAtMost(len)
        System.arraycopy(data, 0, buffer, writeHead, firstChunk)

        val secondChunk = len - firstChunk
        if (secondChunk > 0) {
            System.arraycopy(data, firstChunk, buffer, 0, secondChunk)
            writeHead = secondChunk
        } else {
            writeHead = (writeHead + firstChunk) % capacityBytes
        }

        count = (count + len).coerceAtMost(capacityBytes)
    }

    @Synchronized
    fun readRecent(): ByteArray {
        if (count == 0) return ByteArray(0)

        val out = ByteArray(count)
        val start = (writeHead - count + capacityBytes) % capacityBytes

        if (start + count <= capacityBytes) {
            System.arraycopy(buffer, start, out, 0, count)
        } else {
            val part1 = capacityBytes - start
            val part2 = count - part1
            System.arraycopy(buffer, start, out, 0, part1)
            System.arraycopy(buffer, 0, out, part1, part2)
        }

        return out
    }

    @Synchronized
    fun clear() {
        writeHead = 0
        count = 0
    }
}
