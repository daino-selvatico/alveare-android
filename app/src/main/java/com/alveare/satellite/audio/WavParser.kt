package com.alveare.satellite.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val pcmData: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as WavInfo

        if (sampleRate != other.sampleRate) return false
        if (channels != other.channels) return false
        if (bitsPerSample != other.bitsPerSample) return false
        if (!pcmData.contentEquals(other.pcmData)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = sampleRate
        result = 31 * result + channels
        result = 31 * result + bitsPerSample
        result = 31 * result + pcmData.contentHashCode()
        return result
    }
}

/**
 * Robust RIFF WAV chunk parser for Alveare audio streaming.
 * Handles non-standard headers, extra chunks (JUNK, LIST, metadata),
 * varying sample rates (e.g. 16000, 24000, 44100), and validates format.
 */
object WavParser {

    fun parse(bytes: ByteArray): WavInfo? {
        if (bytes.size < 12) return null

        // Check RIFF header: "RIFF" .... "WAVE"
        if (bytes[0] != 'R'.code.toByte() ||
            bytes[1] != 'I'.code.toByte() ||
            bytes[2] != 'F'.code.toByte() ||
            bytes[3] != 'F'.code.toByte() ||
            bytes[8] != 'W'.code.toByte() ||
            bytes[9] != 'A'.code.toByte() ||
            bytes[10] != 'V'.code.toByte() ||
            bytes[11] != 'E'.code.toByte()
        ) {
            return null
        }

        var offset = 12
        var sampleRate: Int? = null
        var channels: Int? = null
        var bitsPerSample: Int? = null
        var pcmData: ByteArray? = null

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        while (offset + 8 <= bytes.size) {
            val chunkId = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = buffer.getInt(offset + 4)
            offset += 8

            if (chunkSize < 0) {
                // Invalid or corrupt chunk size
                break
            }

            when (chunkId) {
                "fmt " -> {
                    if (chunkSize >= 16 && offset + 16 <= bytes.size) {
                        val audioFormat = buffer.getShort(offset).toInt() and 0xFFFF
                        val ch = buffer.getShort(offset + 2).toInt() and 0xFFFF
                        val sRate = buffer.getInt(offset + 4)
                        val bits = buffer.getShort(offset + 14).toInt() and 0xFFFF

                        // Accept PCM (1) or IEEE float (3)
                        if (audioFormat == 1 || audioFormat == 3) {
                            channels = ch
                            sampleRate = sRate
                            bitsPerSample = bits
                        }
                    }
                }
                "data" -> {
                    val available = (bytes.size - offset).coerceAtLeast(0)
                    val actualLen = minOf(chunkSize, available)
                    if (actualLen > 0) {
                        pcmData = bytes.copyOfRange(offset, offset + actualLen)
                    } else {
                        pcmData = ByteArray(0)
                    }
                }
                else -> {
                    // Skip unknown chunk (JUNK, LIST, INFO, etc.)
                }
            }

            // RIFF chunks are word-aligned (padded to 2 bytes if chunkSize is odd)
            val paddedSize = if (chunkSize % 2 != 0) chunkSize + 1 else chunkSize
            offset = offset + paddedSize
            if (offset < 0) break // Overflow safeguard
        }

        if (sampleRate != null && channels != null && bitsPerSample != null && pcmData != null) {
            return WavInfo(
                sampleRate = sampleRate,
                channels = channels,
                bitsPerSample = bitsPerSample,
                pcmData = pcmData
            )
        }

        return null
    }
}
