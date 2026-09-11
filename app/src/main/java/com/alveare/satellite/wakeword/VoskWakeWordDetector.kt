package com.alveare.satellite.wakeword

import android.util.Log
import com.alveare.satellite.audio.CircularAudioBuffer
import com.google.gson.JsonParser
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

enum class VoskDetectorStatus {
    UNINITIALIZED,
    LOADING,
    READY,
    UNAVAILABLE,
    ERROR
}

/**
 * Real offline lexical wake phrase detector using Vosk speech recognition.
 * Restricts recognition grammar to target phrase (e.g., "ehi alveare") with unknown token handling ("[unk]").
 * Loads model asynchronously to prevent ANR / main-thread blocking, and retains pre-roll PCM audio.
 */
class VoskWakeWordDetector(
    private val modelDir: File?,
    private val sampleRate: Float = 16000.0f,
    private val wakePhrase: String = "ehi alveare",
    private val preRollDurationMs: Int = 800,
    private val isConnectedProvider: () -> Boolean = { true },
    private val onWakeWordDetected: (preRollAudio: ByteArray) -> Unit
) {
    companion object {
        private const val TAG = "VoskWakeWord"
        private const val DEBOUNCE_MS = 2500L

        fun buildGrammarJson(phrase: String): String {
            val clean = phrase.trim().lowercase()
            return "[\"$clean\", \"[unk]\"]"
        }

        fun matchesWakePhrase(jsonStr: String, phrase: String): Boolean {
            if (jsonStr.isBlank()) return false
            return try {
                val element = JsonParser.parseString(jsonStr)
                if (!element.isJsonObject) return false
                val obj = element.asJsonObject

                val text = when {
                    obj.has("text") -> obj.get("text").asString
                    obj.has("partial") -> obj.get("partial").asString
                    else -> ""
                }.trim().lowercase()

                if (text.isBlank()) return false

                val target = phrase.trim().lowercase()
                val normalized = text.replace(Regex("[.,;:!?]"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim()

                normalized.contains(target)
            } catch (e: Exception) {
                false
            }
        }
    }

    val isEnabled = AtomicBoolean(false)
    val isPaused = AtomicBoolean(false)

    @Volatile
    var status: VoskDetectorStatus = VoskDetectorStatus.UNINITIALIZED
        private set

    private var voskModel: Model? = null
    private var recognizer: Recognizer? = null
    private var lastTriggerTime = 0L
    private val initGeneration = AtomicInteger(0)
    private var initThread: Thread? = null

    // Pre-roll buffer: 16000 samples/sec * 2 bytes/sample * (preRollDurationMs / 1000)
    private val preRollCapacity = (sampleRate * 2 * (preRollDurationMs / 1000f)).toInt().coerceAtLeast(4096)
    private val preRollBuffer = CircularAudioBuffer(preRollCapacity)

    init {
        loadModelAsync()
    }

    val isModelLoaded: Boolean
        get() = status == VoskDetectorStatus.READY && recognizer != null

    fun loadModelAsync() {
        val currentGen = initGeneration.incrementAndGet()

        if (modelDir == null || !modelDir.exists() || !modelDir.isDirectory) {
            status = VoskDetectorStatus.UNAVAILABLE
            Log.i(TAG, "Vosk model directory not available: ${modelDir?.absolutePath}")
            return
        }

        status = VoskDetectorStatus.LOADING
        initThread = Thread({
            try {
                val model = Model(modelDir.absolutePath)
                if (initGeneration.get() != currentGen) {
                    try { model.close() } catch (ignored: Exception) {}
                    return@Thread
                }

                val grammarJson = buildGrammarJson(wakePhrase)
                val rec = Recognizer(model, sampleRate, grammarJson)

                synchronized(this) {
                    if (initGeneration.get() == currentGen) {
                        voskModel = model
                        recognizer = rec
                        status = VoskDetectorStatus.READY
                        Log.i(TAG, "Vosk offline recognizer initialized asynchronously for: '$wakePhrase'")
                    } else {
                        try { rec.close() } catch (ignored: Exception) {}
                        try { model.close() } catch (ignored: Exception) {}
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to load Vosk model from ${modelDir.absolutePath}: ${e.message}", e)
                synchronized(this) {
                    if (initGeneration.get() == currentGen) {
                        recognizer = null
                        voskModel = null
                        status = VoskDetectorStatus.ERROR
                    }
                }
            }
        }, "Alveare-VoskAsyncInit").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    @Synchronized
    fun feedAudio(pcmChunk: ByteArray, length: Int = pcmChunk.size) {
        if (!isEnabled.get() || isPaused.get() || status != VoskDetectorStatus.READY) {
            return
        }

        if (!isConnectedProvider()) {
            return
        }

        val rec = recognizer ?: return
        val len = length.coerceAtMost(pcmChunk.size)
        if (len <= 0) return

        // Retain pre-roll audio
        preRollBuffer.write(pcmChunk, len)

        try {
            val now = System.currentTimeMillis()
            if (now - lastTriggerTime < DEBOUNCE_MS) {
                return
            }

            val isComplete = rec.acceptWaveForm(pcmChunk, len)
            val jsonResult = if (isComplete) rec.result else rec.partialResult

            if (matchesWakePhrase(jsonResult, wakePhrase)) {
                val debounceElapsed = now - lastTriggerTime
                if (debounceElapsed >= DEBOUNCE_MS) {
                    lastTriggerTime = now
                    Log.i(TAG, "Wake phrase '$wakePhrase' detected via offline lexical model! Result: $jsonResult")
                    val preRoll = preRollBuffer.readRecent()
                    preRollBuffer.clear()
                    rec.reset()
                    onWakeWordDetected(preRoll)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error feeding audio to Vosk recognizer: ${e.message}", e)
        }
    }

    @Synchronized
    fun reset() {
        preRollBuffer.clear()
        try {
            recognizer?.reset()
        } catch (ignored: Exception) {}
    }

    @Synchronized
    fun release() {
        initGeneration.incrementAndGet()
        isEnabled.set(false)
        isPaused.set(false)
        preRollBuffer.clear()
        try {
            initThread?.interrupt()
        } catch (ignored: Exception) {}
        initThread = null
        try {
            recognizer?.close()
        } catch (ignored: Exception) {}
        recognizer = null
        try {
            voskModel?.close()
        } catch (ignored: Exception) {}
        voskModel = null
        status = VoskDetectorStatus.UNINITIALIZED
    }
}
