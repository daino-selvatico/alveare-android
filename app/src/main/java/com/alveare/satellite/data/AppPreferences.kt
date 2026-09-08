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
        const val KEY_SMART_DISPLAY = "smart_display"
        const val KEY_CONTEXT_TURNS = "context_turns"

        const val DEFAULT_SERVER_URL = "wss://192.168.50.1:8443/ws/live"
        const val DEFAULT_ROOM_NAME = "Salotto"
        const val MODE_SERVER = "server"
        const val MODE_DEVICE = "device"
    }

    var serverUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) = prefs.edit().putString(KEY_SERVER_URL, value).apply()

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
        get() = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, value).apply()

    var isSmartDisplayEnabled: Boolean
        get() = prefs.getBoolean(KEY_SMART_DISPLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_SMART_DISPLAY, value).apply()

    var contextTurns: Int
        get() = prefs.getInt(KEY_CONTEXT_TURNS, 6)
        set(value) = prefs.edit().putInt(KEY_CONTEXT_TURNS, value).apply()
}
