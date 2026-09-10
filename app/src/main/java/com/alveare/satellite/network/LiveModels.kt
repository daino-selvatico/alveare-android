package com.alveare.satellite.network

import com.google.gson.annotations.SerializedName

data class LiveEvent(
    val event: String? = null,
    val type: String? = null,
    val text: String? = null,
    val delta: String? = null,
    val data: String? = null, // Base64 audio if present
    @SerializedName("audio_b64") val audioB64: String? = null,
    val error: String? = null,
    @SerializedName("turn_id") val turnId: Int = 0,
    @SerializedName("sample_rate") val sampleRate: Int = 24000,
    @SerializedName("stt_latency_ms") val sttLatencyMs: Float = 0f,
    @SerializedName("ttft_ms") val ttftMs: Float = 0f,
    @SerializedName("ttfa_ms") val ttfaMs: Float = 0f,
    @SerializedName("e2e_latency_ms") val e2eLatencyMs: Float = 0f,
    @SerializedName("session_id") val sessionId: String? = null,
    @SerializedName("hardware_profile") val hardwareProfile: Map<String, Any>? = null,
    @SerializedName("full_text") val fullText: String? = null,
    @SerializedName("chunk_idx") val chunkIdx: Int = 0,
    val tool: String? = null,
    val query: String? = null,
    val command: String? = null,
    val summary: String? = null,
    @SerializedName("tool_name") val toolName: String? = null,
    @SerializedName("tool_args") val toolArgs: Any? = null,
    @SerializedName("arguments") val arguments: Any? = null,
    @SerializedName("result_summary") val resultSummary: String? = null,
    val status: String? = null,
    val message: String? = null
)
