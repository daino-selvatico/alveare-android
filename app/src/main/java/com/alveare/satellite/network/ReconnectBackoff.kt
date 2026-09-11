package com.alveare.satellite.network

import kotlin.math.min
import kotlin.math.pow

class ReconnectBackoff(
    val initialDelayMs: Long = 1000L,
    val maxDelayMs: Long = 30000L,
    val multiplier: Double = 2.0
) {
    fun getDelayMs(attempt: Int): Long {
        if (attempt <= 1) return initialDelayMs
        val factor = multiplier.pow((attempt - 1).toDouble())
        val calculated = (initialDelayMs * factor).toLong()
        return min(calculated, maxDelayMs)
    }
}
