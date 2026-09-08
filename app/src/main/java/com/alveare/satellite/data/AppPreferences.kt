package com.alveare.satellite.data

import android.content.Context
import android.content.SharedPreferences

class AppPreferences(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("alveare_satellite_prefs", Context.MODE_PRIVATE)

    companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_ROOM_NAME = "room_name"
        const val KEY_TRUST_SSL = "trust_ssl"
        const val KEY_TTS_MODE = "tts_mode" // "server" or "device"
        const val KEY_SPEECH_RATE = "speech_rate"
        const val KEY_PITCH = "pitch"
        const val KEY_WAKE_WORD_ENABLED = "wake_word_enabled"
        const val KEY_WAKE_WORD_SENSITIVITY = "wake_word_sensitivity"
        const val KEY_LISTEN_MODE = "listen_mode"
        const val KEY_SMART_DISPLAY = "smart_display"
        const val KEY_CONTEXT_TURNS = "context_turns"

        const val DEFAULT_SERVER_URL = "wss://192.168.132.197:8443/ws/live"
        const val DEFAULT_ROOM_NAME = "Salotto"
        const val MODE_SERVER = "server"
        const val MODE_DEVICE = "device"

        const val LISTEN_MODE_PUSH_TO_TALK = "push_to_talk"
        const val LISTEN_MODE_CONTINUOUS = "continuous"
        const val LISTEN_MODE_WAKE_WORD = "wake_word"

        /**
         * Normalizes any server URL input into a valid WebSocket URL.
         * Handles cases like:
         * - "192.168.132.197" -> "wss://192.168.132.197:8443/ws/live"
         * - "192.168.132.197:8443" -> "wss://192.168.132.197:8443/ws/live"
         * - "https://192.168.132.197:8443" -> "wss://192.168.132.197:8443/ws/live"
         * - "http://192.168.132.197:8080" -> "ws://192.168.132.197:8080/ws/live"
         * - "wss://192.168.132.197:8443/ws/live" -> unmodified
         */
        fun normalizeServerUrl(input: String): String {
            var raw = input.trim()
            if (raw.isEmpty()) return DEFAULT_SERVER_URL

            raw = raw.trimEnd('/')

            var isSsl = true
            if (raw.startsWith("http://", ignoreCase = true)) {
                isSsl = false
                raw = raw.substring(7)
            } else if (raw.startsWith("https://", ignoreCase = true)) {
                isSsl = true
                raw = raw.substring(8)
            } else if (raw.startsWith("ws://", ignoreCase = true)) {
                isSsl = false
                raw = raw.substring(5)
            } else if (raw.startsWith("wss://", ignoreCase = true)) {
                isSsl = true
                raw = raw.substring(6)
            }

            val slashIdx = raw.indexOf('/')
            val hostPort = if (slashIdx != -1) raw.substring(0, slashIdx) else raw
            var path = if (slashIdx != -1) raw.substring(slashIdx) else ""

            val finalHostPort = if (!hostPort.contains(':')) {
                "$hostPort:8443"
            } else {
                hostPort
            }

            // If port is 8443 or 443, it MUST use SSL (wss://) because Alveare runs on HTTPS/WSS!
            if (finalHostPort.endsWith(":8443") || finalHostPort.endsWith(":443")) {
                isSsl = true
            } else if (finalHostPort.endsWith(":8080") || finalHostPort.endsWith(":8000") || finalHostPort.endsWith(":80")) {
                isSsl = false
            }

            if (path.isEmpty() || path == "/") {
                path = "/ws/live"
            } else if (!path.endsWith("/ws/live")) {
                path = if (path.endsWith("/")) "${path}ws/live" else "$path/ws/live"
            }

            val scheme = if (isSsl) "wss" else "ws"
            return "$scheme://$finalHostPort$path"
        }

        /**
         * Extracts base HTTP/HTTPS URL from any WebSocket or HTTP URL.
         * E.g. "wss://192.168.132.197:8443/ws/live" -> "https://192.168.132.197:8443"
         */
        fun getBaseHttpUrl(input: String): String {
            val normalized = normalizeServerUrl(input)
            val isSsl = normalized.startsWith("wss://", ignoreCase = true)
            val withoutScheme = if (isSsl) normalized.substring(6) else normalized.substring(5)
            val slashIdx = withoutScheme.indexOf('/')
            val hostPort = if (slashIdx != -1) withoutScheme.substring(0, slashIdx) else withoutScheme
            val scheme = if (isSsl) "https" else "http"
            return "$scheme://$hostPort"
        }
    }

    var serverUrl: String
        get() {
            val saved = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
            return normalizeServerUrl(saved)
        }
        set(value) {
            val normalized = normalizeServerUrl(value)
            prefs.edit().putString(KEY_SERVER_URL, normalized).apply()
        }

    var roomName: String
        get() = prefs.getString(KEY_ROOM_NAME, DEFAULT_ROOM_NAME) ?: DEFAULT_ROOM_NAME
        set(value) = prefs.edit().putString(KEY_ROOM_NAME, value).apply()

    var trustSelfSignedSsl: Boolean
        get() = prefs.getBoolean(KEY_TRUST_SSL, true)
        set(value) = prefs.edit().putBoolean(KEY_TRUST_SSL, value).apply()

    var ttsMode: String
        get() = prefs.getString(KEY_TTS_MODE, MODE_SERVER) ?: MODE_SERVER
        set(value) = prefs.edit().putString(KEY_TTS_MODE, value).apply()

    var speechRate: Float
        get() = prefs.getFloat(KEY_SPEECH_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEECH_RATE, value).apply()

    var pitch: Float
        get() = prefs.getFloat(KEY_PITCH, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PITCH, value).apply()

    var isWakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, value).apply()

    var wakeWordSensitivity: Float
        get() = prefs.getFloat(KEY_WAKE_WORD_SENSITIVITY, 0.35f)
        set(value) = prefs.edit().putFloat(KEY_WAKE_WORD_SENSITIVITY, value).apply()

    var listenMode: String
        get() = prefs.getString(KEY_LISTEN_MODE, LISTEN_MODE_PUSH_TO_TALK) ?: LISTEN_MODE_PUSH_TO_TALK
        set(value) = prefs.edit().putString(KEY_LISTEN_MODE, value).apply()

    var isSmartDisplayEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMART_DISPLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_SMART_DISPLAY, value).apply()

    var contextTurns: Int
        get() = prefs.getInt(KEY_CONTEXT_TURNS, 6)
        set(value) = prefs.edit().putInt(KEY_CONTEXT_TURNS, value).apply()
}
