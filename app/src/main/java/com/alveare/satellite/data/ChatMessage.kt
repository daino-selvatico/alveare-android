package com.alveare.satellite.data

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String, // "user", "assistant", "tool"
    var text: String,
    val timestamp: Long = System.currentTimeMillis(),
    var isStreaming: Boolean = false,
    var isPlaying: Boolean = false,
    var sttLatencyMs: Float = 0f,
    var ttftMs: Float = 0f,
    var ttfaMs: Float = 0f,
    var latencyMs: Float = 0f,
    var toolName: String? = null,
    var toolArgs: String? = null,
    var toolSummary: String? = null,
    var toolStatus: String? = "success" // "running", "ok", "success", "error"
)
