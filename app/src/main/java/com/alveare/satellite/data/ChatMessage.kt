package com.alveare.satellite.data

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val sender: String, // "user", "assistant", "tool"
    var text: String,
    val timestamp: Long = System.currentTimeMillis(),
    var isStreaming: Boolean = false,
    var latencyMs: Float = 0f,
    var toolName: String? = null,
    var toolArgs: String? = null,
    var toolSummary: String? = null,
    var toolStatus: String? = "success"
)
