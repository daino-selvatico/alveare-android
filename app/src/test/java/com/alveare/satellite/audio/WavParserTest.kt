package com.alveare.satellite.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavParserTest {

    private fun createWavBytes(
        sampleRate: Int = 24000,
        channels: Short = 1,
        bitsPerSample: Short = 16,
        pcmData: ByteArray = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7),
        includeExtraChunk: Boolean = false
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = (channels * (bitsPerSample / 8)).toShort()

        // RIFF header
        baos.write("RIFF".toByteArray(Charsets.US_ASCII))
        
        var totalDataSize = pcmData.size + 36
        if (includeExtraChunk) {
            totalDataSize += 16 // 8 bytes JUNK header + 8 bytes padding
        }
        
        // File size - 8
        val sizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataSize).array()
        baos.write(sizeBuf)
        baos.write("WAVE".toByteArray(Charsets.US_ASCII))

        if (includeExtraChunk) {
            // Extra JUNK chunk before fmt
            baos.write("JUNK".toByteArray(Charsets.US_ASCII))
            val junkSize = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(8).array()
            baos.write(junkSize)
            baos.write(ByteArray(8) { 0x55.toByte() })
        }

        // fmt chunk
        baos.write("fmt ".toByteArray(Charsets.US_ASCII))
        val fmtSize = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16).array()
        baos.write(fmtSize)

        val fmtBuf = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1) // PCM format
            .putShort(channels)
            .putInt(sampleRate)
            .putInt(byteRate)
            .putShort(blockAlign)
            .putShort(bitsPerSample)
            .array()
        baos.write(fmtBuf)

        // data chunk
        baos.write("data".toByteArray(Charsets.US_ASCII))
        val dataSizeBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(pcmData.size).array()
        baos.write(dataSizeBuf)
        baos.write(pcmData)

        return baos.toByteArray()
    }

    @Test
    fun testStandardWavParsing() {
        val originalPcm = byteArrayOf(10, 20, 30, 40, 50, 60)
        val wav = createWavBytes(sampleRate = 24000, pcmData = originalPcm)

        val result = WavParser.parse(wav)
        assertNotNull("Expected valid WavInfo", result)
        assertEquals(24000, result!!.sampleRate)
        assertEquals(1, result.channels)
        assertEquals(16, result.bitsPerSample)
        assertArrayEquals(originalPcm, result.pcmData)
    }

    @Test
    fun testWavWithExtraChunk() {
        val originalPcm = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val wav = createWavBytes(sampleRate = 44100, pcmData = originalPcm, includeExtraChunk = true)

        val result = WavParser.parse(wav)
        assertNotNull("Expected valid WavInfo even with extra chunk", result)
        assertEquals(44100, result!!.sampleRate)
        assertArrayEquals(originalPcm, result.pcmData)
    }

    @Test
    fun testRawPcmFallback() {
        val rawPcm = byteArrayOf(1, 2, 3, 4, 5, 6)
        val result = WavParser.parse(rawPcm)
        assertNull("Raw PCM without RIFF header should return null WavInfo", result)
    }

    @Test
    fun testTruncatedHeader() {
        val truncated = "RIFF1234WAV".toByteArray()
        val result = WavParser.parse(truncated)
        assertNull("Truncated header should safely return null without throwing", result)
    }
}
