package com.alveare.satellite.network

import android.util.Base64
import com.google.gson.Gson
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AlveareLiveWebSocket(
    private val serverUrl: String,
    private val trustSelfSignedSsl: Boolean = true,
    private val roomName: String = "Salotto",
    private val listener: LiveWebSocketListener
) {
    interface LiveWebSocketListener {
        fun onConnecting()
        fun onConnected(sessionId: String?, sampleRate: Int)
        fun onDisconnected(reason: String)
        fun onVadSpeechStart()
        fun onVadSpeechEnd()
        fun onTtft(ttftMs: Float)
        fun onUserTranscript(text: String, latencyMs: Float)
        fun onAssistantDelta(delta: String)
        fun onAssistantSentence(sentence: String)
        fun onAudioChunkReceived(pcmBytes: ByteArray, sampleRate: Int)
        fun onToolCall(toolName: String)
        fun onTurnCompleted(turnId: Int)
        fun onInterrupted()
        fun onError(error: String)
    }

    private val gson = Gson()
    private var webSocket: WebSocket? = null
    private val isConnected = AtomicBoolean(false)
    private var client: OkHttpClient

    init {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive
            .writeTimeout(8, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)

        if (trustSelfSignedSsl) {
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
                e.printStackTrace()
            }
        }

        client = builder.build()
    }

    fun connect() {
        listener.onConnecting()
        try {
            val request = Request.Builder()
                .url(serverUrl)
                .build()

            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    isConnected.set(true)
                    // Register room/client
                    val registerMsg = """
                        {"type":"register_client","client_id":"android-satellite","room":"$roomName","mode":"voice_satellite"}
                    """.trimIndent()
                    webSocket.send(registerMsg)
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleTextMessage(text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    listener.onAudioChunkReceived(bytes.toByteArray(), 24000)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    isConnected.set(false)
                    listener.onDisconnected("Chiusura: $reason (code: $code)")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
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
                }
            })
        } catch (e: Exception) {
            isConnected.set(false)
            listener.onDisconnected("URL non valido o errore socket: ${e.message}")
        }
    }

    private fun handleTextMessage(jsonStr: String) {
        try {
            val event = gson.fromJson(jsonStr, LiveEvent::class.java) ?: return

            when (event.event) {
                "live_connected" -> {
                    listener.onConnected(event.sessionId, event.sampleRate)
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
                        listener.onAudioChunkReceived(pcm, event.sampleRate)
                    }
                }
                "tool_call" -> {
                    listener.onToolCall(event.toolName ?: "Tool")
                }
                "turn_complete" -> {
                    listener.onTurnCompleted(event.turnId)
                }
                "interrupted" -> {
                    listener.onInterrupted()
                }
                "error" -> {
                    listener.onError(event.error ?: "Errore Alveare")
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
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

    fun sendConfig(contextTurns: Int) {
        if (isConnected.get()) {
            webSocket?.send("""{"type":"config","settings":{"max_context_turns":$contextTurns}}""")
        }
    }

    fun disconnect() {
        isConnected.set(false)
        webSocket?.close(1000, "App closed")
        webSocket = null
    }

    companion object {
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
    }
}
