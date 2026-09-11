package com.alveare.satellite.network

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AlveareLiveWebSocket(
    private val serverUrl: String,
    private val trustSelfSignedSsl: Boolean = false,
    private val roomName: String = "Salotto",
    private val autoReconnect: Boolean = true,
    private val listener: LiveWebSocketListener
) {
    interface LiveWebSocketListener {
        fun onConnecting()
        fun onConnected(sessionId: String?, inputSampleRate: Int, outputSampleRate: Int)
        fun onDisconnected(reason: String)
        fun onVadSpeechStart()
        fun onVadSpeechEnd()
        fun onTtft(ttftMs: Float)
        fun onTtfa(ttfaMs: Float) {}
        fun onUserTranscript(text: String, latencyMs: Float)
        fun onAssistantDelta(delta: String)
        fun onAssistantSentence(sentence: String)
        fun onAudioChunkReceived(pcmBytes: ByteArray, sampleRate: Int, format: String?)
        fun onToolCall(toolName: String, args: String?, summary: String?, status: String?)
        fun onTurnCompleted(turnId: Int, fullText: String? = null)
        fun onMemoryCleared(message: String)
        fun onInterrupted()
        fun onError(error: String)
    }

    companion object {
        private const val TAG = "AlveareLiveWebSocket"

        fun testServerStatus(
            serverUrl: String,
            trustSelfSignedSsl: Boolean,
            callback: (success: Boolean, message: String) -> Unit
        ) {
            Thread {
                try {
                    val httpBase = com.alveare.satellite.data.AppPreferences.getBaseHttpUrl(serverUrl)
                    val statusUrl = "$httpBase/api/status"

                    val builder = OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(5, TimeUnit.SECONDS)

                    if (trustSelfSignedSsl) {
                        applyInsecureTrustManager(builder)
                    }

                    val client = builder.build()
                    val req = Request.Builder().url(statusUrl).build()
                    val t0 = System.currentTimeMillis()
                    val resp = client.newCall(req).execute()
                    val latency = System.currentTimeMillis() - t0

                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: "{}"
                        val json = Gson().fromJson(body, com.google.gson.JsonObject::class.java)
                        val model = json.get("model")?.asString ?: "gemma4-e4b"
                        val slots = json.getAsJsonObject("slots")
                        val ttsModel = slots?.getAsJsonObject("tts")?.get("model")?.asString ?: "Kokoro-82M"
                        val sttModel = slots?.getAsJsonObject("stt")?.get("model")?.asString ?: "Whisper Large"

                        val msg = "✅ Connessione riuscita! (${latency}ms)\n" +
                                "• LLM: $model\n" +
                                "• STT: $sttModel\n" +
                                "• TTS: $ttsModel"
                        callback(true, msg)
                    } else {
                        callback(false, "❌ Errore HTTP ${resp.code}: ${resp.message}")
                    }
                } catch (e: Exception) {
                    val err = e.localizedMessage ?: e.message ?: "Connessione fallita"
                    callback(false, "❌ Errore: $err\nVerifica che il PC sia acceso e l'IP sia corretto.")
                }
            }.start()
        }

        private fun applyInsecureTrustManager(builder: OkHttpClient.Builder) {
            try {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(null, trustAllCerts, SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }

                val allTls = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
                    .allEnabledTlsVersions()
                    .allEnabledCipherSuites()
                    .build()
                builder.connectionSpecs(listOf(allTls, ConnectionSpec.CLEARTEXT))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply insecure trust manager: ${e.message}", e)
            }
        }

        fun buildRegisterMessage(roomName: String): String {
            val registerMap = mapOf(
                "type" to "register_client",
                "client_id" to "android-satellite",
                "room" to roomName,
                "mode" to "voice_satellite"
            )
            return Gson().toJson(registerMap)
        }

        fun buildConfigMessage(contextTurns: Int, ttsMode: String): String {
            val serverTtsMode = if (ttsMode == "device") "client" else "server"
            val map = mapOf(
                "type" to "config",
                "settings" to mapOf(
                    "max_context_turns" to contextTurns,
                    "tts_mode" to serverTtsMode
                )
            )
            return Gson().toJson(map)
        }
    }

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    val isConnected = AtomicBoolean(false)
    private val isManuallyStopped = AtomicBoolean(false)
    private val connectionGeneration = AtomicInteger(0)
    private val backoff = ReconnectBackoff(initialDelayMs = 1000L, maxDelayMs = 30000L, multiplier = 2.0)
    private var reconnectAttempt = 0
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "Alveare-ReconnectScheduler").apply { isDaemon = true }
    }
    private var reconnectFuture: ScheduledFuture<*>? = null
    private var client: OkHttpClient

    init {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive
            .writeTimeout(8, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false) // Managed via our explicit exponential backoff

        if (trustSelfSignedSsl) {
            Log.w(TAG, "Explicit TLS bypass enabled for self-signed development server at $serverUrl")
            applyInsecureTrustManager(builder)
        }

        client = builder.build()
    }

    fun connect() {
        isManuallyStopped.set(false)
        cancelScheduledReconnect()
        val generation = connectionGeneration.incrementAndGet()

        listener.onConnecting()
        try {
            val request = Request.Builder()
                .url(serverUrl)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (generation != connectionGeneration.get()) {
                        webSocket.close(1000, "Stale connection")
                        return
                    }
                    isConnected.set(true)
                    reconnectAttempt = 0 // Reset backoff on successful connect
                    Log.i(TAG, "Connected to Alveare WebSocket at $serverUrl")

                    val registerMsg = buildRegisterMessage(roomName)
                    webSocket.send(registerMsg)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (generation != connectionGeneration.get()) return
                    handleTextMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (generation != connectionGeneration.get()) return
                    listener.onAudioChunkReceived(bytes.toByteArray(), 24000, "pcm")
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (generation != connectionGeneration.get()) return
                    isConnected.set(false)
                    listener.onDisconnected("Chiusura server: $reason (code: $code)")
                    scheduleReconnectIfNeeded()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (generation != connectionGeneration.get()) return
                    isConnected.set(false)
                    val errorDetail = t.localizedMessage ?: t.message ?: "Connessione rifiutata"
                    val friendlyMsg = when {
                        t is java.net.ConnectException -> "Impossibile raggiungere $serverUrl. Verifica che il PC sia acceso sulla stessa rete Wi-Fi."
                        t is java.net.SocketTimeoutException -> "Timeout di connessione verso $serverUrl (8s)."
                        t is java.net.UnknownHostException -> "Host non trovato: $serverUrl. Controlla l'IP o il nome host."
                        t is javax.net.ssl.SSLException -> "Errore SSL/TLS con $serverUrl: $errorDetail"
                        response?.code == 404 -> "Endpoint non trovato (404) su $serverUrl. Verifica /ws/live."
                        response?.code == 200 -> "Handshake fallito: il server ha restituito HTTP 200 invece di 101 WebSocket."
                        else -> "Errore connessione: $errorDetail"
                    }
                    listener.onDisconnected(friendlyMsg)
                    scheduleReconnectIfNeeded()
                }
            })
        } catch (e: Exception) {
            isConnected.set(false)
            listener.onDisconnected("URL non valido o errore socket: ${e.message}")
            scheduleReconnectIfNeeded()
        }
    }

    private fun scheduleReconnectIfNeeded() {
        if (isManuallyStopped.get() || !autoReconnect) return

        reconnectAttempt++
        val delay = backoff.getDelayMs(reconnectAttempt)
        Log.i(TAG, "Scheduling reconnect attempt $reconnectAttempt in ${delay}ms")

        cancelScheduledReconnect()
        reconnectFuture = scheduler.schedule({
            if (!isManuallyStopped.get() && !isConnected.get()) {
                connect()
            }
        }, delay, TimeUnit.MILLISECONDS)
    }

    private fun cancelScheduledReconnect() {
        reconnectFuture?.cancel(true)
        reconnectFuture = null
    }

    private fun handleTextMessage(jsonStr: String) {
        try {
            val event = gson.fromJson(jsonStr, LiveEvent::class.java) ?: return

            when (event.event) {
                "live_connected" -> {
                    val inputRate = event.inputSampleRate ?: event.sampleRate
                    val outputRate = event.outputSampleRate ?: 24000
                    listener.onConnected(event.sessionId, inputRate, outputRate)
                }
                "vad_speech_start" -> {
                    listener.onVadSpeechStart()
                }
                "vad_speech_end" -> {
                    listener.onVadSpeechEnd()
                }
                "ttft" -> {
                    listener.onTtft(event.ttftMs)
                }
                "user_transcript" -> {
                    event.text?.let { listener.onUserTranscript(it, event.sttLatencyMs) }
                }
                "assistant_delta" -> {
                    event.delta?.let { listener.onAssistantDelta(it) }
                }
                "assistant_sentence", "llm_chunk" -> {
                    event.text?.let { listener.onAssistantSentence(it) }
                }
                "audio_chunk" -> {
                    val rawB64 = event.audioB64 ?: event.data
                    if (!rawB64.isNullOrEmpty()) {
                        val pcm = Base64.decode(rawB64, Base64.DEFAULT)
                        listener.onAudioChunkReceived(pcm, event.sampleRate, event.format ?: "wav")
                    }
                    if (event.ttfaMs > 0) {
                        listener.onTtfa(event.ttfaMs)
                    }
                }
                "tool_call", "tool_call_start" -> {
                    val tName = event.tool ?: event.toolName ?: "tool"
                    val argsStr = event.query ?: event.command ?: event.arguments?.toString() ?: event.toolArgs?.toString()
                    listener.onToolCall(tName, argsStr, event.summary ?: event.resultSummary, "running")
                }
                "tool_call_done", "tool_call_end" -> {
                    val tName = event.tool ?: event.toolName ?: "tool"
                    val argsStr = event.query ?: event.command ?: event.arguments?.toString() ?: event.toolArgs?.toString()
                    listener.onToolCall(tName, argsStr, event.summary ?: event.resultSummary, event.status ?: "ok")
                }
                "turn_complete" -> {
                    listener.onTurnCompleted(event.turnId, event.fullText)
                }
                "memory_cleared" -> {
                    listener.onMemoryCleared(event.message ?: "Memoria conversazione azzerata.")
                }
                "interrupted" -> {
                    listener.onInterrupted()
                }
                "error", "tts_error" -> {
                    listener.onError(event.error ?: "Errore Alveare")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming message: ${e.message}", e)
        }
    }

    fun sendAudioChunk(pcmChunk: ByteArray) {
        if (isConnected.get()) {
            webSocket?.send(pcmChunk.toByteString())
        }
    }

    fun sendInterrupt() {
        if (isConnected.get()) {
            webSocket?.send("""{"type":"interrupt"}""")
        }
    }

    fun sendEndOfSpeech() {
        if (isConnected.get()) {
            webSocket?.send("""{"type":"end_of_speech"}""")
        }
    }

    fun sendConfig(contextTurns: Int, ttsMode: String = "server") {
        if (isConnected.get()) {
            webSocket?.send(buildConfigMessage(contextTurns, ttsMode))
        }
    }

    fun sendClearMemory() {
        if (isConnected.get()) {
            webSocket?.send("""{"type":"clear_memory"}""")
        }
    }

    fun disconnect() {
        isManuallyStopped.set(true)
        cancelScheduledReconnect()
        connectionGeneration.incrementAndGet()
        isConnected.set(false)
        try {
            webSocket?.close(1000, "Manually stopped by user")
        } catch (ignored: Exception) {}
        webSocket = null
    }
}
