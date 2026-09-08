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
        fun onConnected(sessionId: String?, sampleRate: Int)
        fun onDisconnected(reason: String)
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
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // Keep alive
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)

        if (trustSelfSignedSsl) {
            try {
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                })

                val sslContext = SSLContext.getInstance("SSL")
                sslContext.init(null, trustAllCerts, SecureRandom())
                builder.sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                builder.hostnameVerifier { _, _ -> true }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        client = builder.build()
    }

    fun connect() {
        val request = Request.Builder()
            .url(serverUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                isConnected.set(true)
                // Register room/client
                val registerMsg = """
                    {"type":"register_client","client_id":"android-satellite","room":"$roomName","mode":"voice_satellite"}
                """.trimIndent()
                ws.send(registerMsg)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                handleTextMessage(text)
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                listener.onAudioChunkReceived(bytes.toByteArray(), 24000)
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                isConnected.set(false)
                listener.onDisconnected("Chiusura: $reason ($code)")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                isConnected.set(false)
                listener.onDisconnected(t.message ?: "Connessione fallita")
            }
        })
    }

    private fun handleTextMessage(jsonStr: String) {
        try {
            val event = gson.fromJson(jsonStr, LiveEvent::class.java) ?: return

            when (event.event) {
                "live_connected" -> {
                    listener.onConnected(event.sessionId, event.sampleRate)
                }
                "user_transcript" -> {
                    event.text?.let { listener.onUserTranscript(it, event.sttLatencyMs) }
                }
                "assistant_delta" -> {
                    event.delta?.let { listener.onAssistantDelta(it) }
                }
                "assistant_sentence" -> {
                    event.text?.let { listener.onAssistantSentence(it) }
                }
                "audio_chunk" -> {
                    if (!event.data.isNullOrEmpty()) {
                        val pcm = Base64.decode(event.data, Base64.DEFAULT)
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
}
