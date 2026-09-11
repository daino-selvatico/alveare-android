package com.alveare.satellite

import com.alveare.satellite.data.AppPreferences
import com.alveare.satellite.network.ReconnectBackoff
import org.junit.Assert.*
import org.junit.Test

class AppPreferencesAndNetworkTest {

    @Test
    fun testUrlNormalizationDefault() {
        val result = AppPreferences.normalizeServerUrl("")
        assertEquals(AppPreferences.DEFAULT_SERVER_URL, result)
    }

    @Test
    fun testUrlNormalizationBareHost() {
        val result = AppPreferences.normalizeServerUrl("192.168.1.50")
        assertEquals("wss://192.168.1.50:8443/ws/live", result)
    }

    @Test
    fun testUrlNormalizationExplicitWsPreserved() {
        // User explicitly specified ws://; do not silently escalate to wss
        val result = AppPreferences.normalizeServerUrl("ws://192.168.1.50:8443/ws/live")
        assertEquals("ws://192.168.1.50:8443/ws/live", result)
    }

    @Test
    fun testUrlNormalizationExplicitWss() {
        val result = AppPreferences.normalizeServerUrl("wss://192.168.1.50:8443")
        assertEquals("wss://192.168.1.50:8443/ws/live", result)
    }

    @Test
    fun testUrlNormalizationHttpScheme() {
        val result = AppPreferences.normalizeServerUrl("http://192.168.1.50:8000")
        assertEquals("ws://192.168.1.50:8000/ws/live", result)
    }

    @Test
    fun testUrlNormalizationHttpsScheme() {
        val result = AppPreferences.normalizeServerUrl("https://192.168.1.50:8000")
        assertEquals("wss://192.168.1.50:8000/ws/live", result)
    }

    @Test
    fun testUrlNormalizationWithSpacesAndDainoLocal() {
        val result1 = AppPreferences.normalizeServerUrl("wss:// daino-ai.local:8443/ws/live")
        assertEquals("wss://192.168.132.197:8443/ws/live", result1)

        val result2 = AppPreferences.normalizeServerUrl("daino-ai.local")
        assertEquals("wss://192.168.132.197:8443/ws/live", result2)

        val result3 = AppPreferences.normalizeServerUrl("https:// 192.168.132.197 : 8443 ")
        assertEquals("wss://192.168.132.197:8443/ws/live", result3)
    }

    @Test
    fun testGetBaseHttpUrl() {
        val wsUrl = "wss://192.168.132.197:8443/ws/live"
        val httpUrl = AppPreferences.getBaseHttpUrl(wsUrl)
        assertEquals("https://192.168.132.197:8443", httpUrl)

        val plainWsUrl = "ws://192.168.1.100:8000/ws/live"
        val plainHttpUrl = AppPreferences.getBaseHttpUrl(plainWsUrl)
        assertEquals("http://192.168.1.100:8000", plainHttpUrl)
    }

    @Test
    fun testReconnectExponentialBackoff() {
        val backoff = ReconnectBackoff(initialDelayMs = 1000L, maxDelayMs = 30000L, multiplier = 2.0)
        
        assertEquals(1000L, backoff.getDelayMs(1))
        assertEquals(2000L, backoff.getDelayMs(2))
        assertEquals(4000L, backoff.getDelayMs(3))
        assertEquals(8000L, backoff.getDelayMs(4))
        assertEquals(16000L, backoff.getDelayMs(5))
        assertEquals(30000L, backoff.getDelayMs(6)) // Capped at 30s
        assertEquals(30000L, backoff.getDelayMs(10)) // Capped at 30s
    }
}
