package com.alveare.satellite

import okhttp3.*
import org.junit.Test
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import org.junit.Assert.*

class AlveareLiveWebSocketTest {

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
        val client = createClient(listOf(ConnectionSpec.CLEARTEXT, ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.MODERN_TLS))
        val request = Request.Builder().url("https://192.168.132.197:8443/api/status").build()
        val response = client.newCall(request).execute()
        println("testCurrentSpecs response: ${response.code} ${response.handshake?.tlsVersion}")
        assertTrue(response.isSuccessful)
    }

    @Test
    fun testModernTlsSpecs() {
        val client = createClient(listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
        val request = Request.Builder().url("https://192.168.132.197:8443/api/status").build()
        val response = client.newCall(request).execute()
        println("testModernTlsSpecs response: ${response.code} ${response.handshake?.tlsVersion}")
        assertTrue(response.isSuccessful)
    }

    @Test
    fun testWebSocketDirectConnection() {
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
}
