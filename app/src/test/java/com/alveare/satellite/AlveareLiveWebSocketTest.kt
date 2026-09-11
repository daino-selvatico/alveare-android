package com.alveare.satellite

import com.alveare.satellite.network.AlveareLiveWebSocket
import okhttp3.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class AlveareLiveWebSocketTest {

    private fun isServerReachable(host: String = "192.168.132.197", port: Int = 8443): Boolean {
        return try {
            val socket = java.net.Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 1000)
            socket.close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun createClient(specs: List<ConnectionSpec>?): OkHttpClient {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAllCerts, SecureRandom())

        val builder = OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }

        if (specs != null) {
            builder.connectionSpecs(specs)
        }
        return builder.build()
    }

    @Test
    fun testCurrentSpecs() {
        assumeTrue("Server 192.168.132.197:8443 is online", isServerReachable())
        val client = createClient(listOf(ConnectionSpec.CLEARTEXT, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.MODERN_TLS))
        val request = Request.Builder().url("https://192.168.132.197:8443/api/status").build()
        val response = client.newCall(request).execute()
        println("testCurrentSpecs response: ${response.code} ${response.handshake?.tlsVersion}")
        assertTrue(response.isSuccessful)
    }

    @Test
    fun testModernTlsSpecs() {
        assumeTrue("Server 192.168.132.197:8443 is online", isServerReachable())
        val client = createClient(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
        val request = Request.Builder().url("https://192.168.132.197:8443/api/status").build()
        val response = client.newCall(request).execute()
        println("testModernTlsSpecs response: ${response.code} ${response.handshake?.tlsVersion}")
        assertTrue(response.isSuccessful)
    }

    @Test
    fun testWebSocketDirectConnection() {
        assumeTrue("Server 192.168.132.197:8443 is online", isServerReachable())
        val client = createClient(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
        val latch = CountDownLatch(1)
        var connected = false
        var failureMsg = ""

        val request = Request.Builder().url("wss://192.168.132.197:8443/ws/live").build()
        val ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("WebSocket open! HTTP ${response.code}")
                connected = true
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failureMsg = "${t.message} (code: ${response?.code})"
                println("WebSocket failure: $failureMsg")
                t.printStackTrace()
                latch.countDown()
            }
        })

        latch.await(5, TimeUnit.SECONDS)
        ws.close(1000, "Done")
        assertTrue("WebSocket failed: $failureMsg", connected)
    }

    @Test
    fun testLiveWebSocketClassDirectConnection() {
        assumeTrue("Server 192.168.132.197:8443 is online", isServerReachable())
        val latch = CountDownLatch(1)
        var connected = false
        var failureMsg = ""

        val listener = object : AlveareLiveWebSocket.LiveWebSocketListener {
            override fun onConnecting() {}
            override fun onConnected(sessionId: String?, inputSampleRate: Int, outputSampleRate: Int) {
                connected = true
                latch.countDown()
            }
            override fun onDisconnected(reason: String) {
                failureMsg = reason
                latch.countDown()
            }
            override fun onVadSpeechStart() {}
            override fun onVadSpeechEnd() {}
            override fun onTtft(ttftMs: Float) {}
            override fun onUserTranscript(text: String, latencyMs: Float) {}
            override fun onAssistantDelta(delta: String) {}
            override fun onAssistantSentence(sentence: String) {}
            override fun onAudioChunkReceived(pcmBytes: ByteArray, sampleRate: Int, format: String?) {}
            override fun onToolCall(toolName: String, args: String?, summary: String?, status: String?) {}
            override fun onTurnCompleted(turnId: Int, fullText: String?) {}
            override fun onMemoryCleared(message: String) {}
            override fun onInterrupted() {}
            override fun onError(error: String) {
                failureMsg = error
                latch.countDown()
            }
        }

        val ws = AlveareLiveWebSocket(
            serverUrl = "wss://192.168.132.197:8443/ws/live",
            trustSelfSignedSsl = true,
            autoReconnect = false,
            listener = listener
        )
        ws.connect()
        latch.await(5, TimeUnit.SECONDS)
        val wasConnected = ws.isConnected.get()
        ws.disconnect()
        assertTrue("AlveareLiveWebSocket failed to connect: $failureMsg", connected || wasConnected)
    }

    @Test
    fun testDisconnectionAndStaleCallbacks() {
        var disconnectedCalled = false
        val listener = object : AlveareLiveWebSocket.LiveWebSocketListener {
            override fun onConnecting() {}
            override fun onConnected(sessionId: String?, inputSampleRate: Int, outputSampleRate: Int) {}
            override fun onDisconnected(reason: String) { disconnectedCalled = true }
            override fun onVadSpeechStart() {}
            override fun onVadSpeechEnd() {}
            override fun onTtft(ttftMs: Float) {}
            override fun onUserTranscript(text: String, latencyMs: Float) {}
            override fun onAssistantDelta(delta: String) {}
            override fun onAssistantSentence(sentence: String) {}
            override fun onAudioChunkReceived(pcmBytes: ByteArray, sampleRate: Int, format: String?) {}
            override fun onToolCall(toolName: String, args: String?, summary: String?, status: String?) {}
            override fun onTurnCompleted(turnId: Int, fullText: String?) {}
            override fun onMemoryCleared(message: String) {}
            override fun onInterrupted() {}
            override fun onError(error: String) {}
        }

        val ws = AlveareLiveWebSocket(
            serverUrl = "ws://127.0.0.1:54321/ws/live",
            trustSelfSignedSsl = false,
            autoReconnect = false,
            listener = listener
        )

        assertFalse(ws.isConnected.get())
        ws.disconnect()
        assertFalse(ws.isConnected.get())
        assertFalse("Disconnected callback should not fire for unstarted socket", disconnectedCalled)
    }

    @Test
    fun testRegisterPayloadJsonEscaping() {
        val dangerousRoom = "Salotto \"Super\" & Cucina\nTest\\Path"
        val payload = AlveareLiveWebSocket.buildRegisterMessage(dangerousRoom)

        val parsed = com.google.gson.JsonParser.parseString(payload).asJsonObject
        assertEquals("register_client", parsed.get("type").asString)
        assertEquals("android-satellite", parsed.get("client_id").asString)
        assertEquals("voice_satellite", parsed.get("mode").asString)
        assertEquals(dangerousRoom, parsed.get("room").asString)
    }

    @Test
    fun testConfigPayloadJson() {
        val serverTtsPayload = AlveareLiveWebSocket.buildConfigMessage(8, "server")
        val parsedServer = com.google.gson.JsonParser.parseString(serverTtsPayload).asJsonObject
        val settingsServer = parsedServer.getAsJsonObject("settings")
        assertEquals(8, settingsServer.get("max_context_turns").asInt)
        assertEquals("server", settingsServer.get("tts_mode").asString)

        val deviceTtsPayload = AlveareLiveWebSocket.buildConfigMessage(12, "device")
        val parsedDevice = com.google.gson.JsonParser.parseString(deviceTtsPayload).asJsonObject
        val settingsDevice = parsedDevice.getAsJsonObject("settings")
        assertEquals(12, settingsDevice.get("max_context_turns").asInt)
        assertEquals("client", settingsDevice.get("tts_mode").asString)
    }
}
