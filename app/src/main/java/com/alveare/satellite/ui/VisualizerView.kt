package com.alveare.satellite.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class VisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        IDLE,
        LISTENING,
        PROCESSING,
        SPEAKING
    }

    private var currentState = State.IDLE
    private var targetAmplitude = 0f
    private var currentAmplitude = 0f
    private var phase = 0f

    private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setState(state: State) {
        if (currentState != state) {
            currentState = state
            invalidate()
        }
    }

    fun setAmplitude(amplitude: Float) {
        targetAmplitude = amplitude.coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f
        val baseRadius = min(cx, cy) * 0.55f

        // Smooth amplitude interpolation
        currentAmplitude += (targetAmplitude - currentAmplitude) * 0.25f
        phase += 0.08f

        when (currentState) {
            State.IDLE -> drawIdleOrb(canvas, cx, cy, baseRadius)
            State.LISTENING -> drawListeningWaves(canvas, cx, cy, baseRadius)
            State.PROCESSING -> drawProcessingSpinner(canvas, cx, cy, baseRadius)
            State.SPEAKING -> drawSpeakingOrb(canvas, cx, cy, baseRadius)
        }

        postInvalidateOnAnimation()
    }

    private fun drawIdleOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val breathe = (sin(phase * 0.4) * 0.08 + 0.92).toFloat()
        val r = radius * breathe

        // Outer glow
        fillPaint.color = Color.parseColor("#153B82F6")
        canvas.drawCircle(cx, cy, r * 1.35f, fillPaint)

        // Mid glow
        fillPaint.color = Color.parseColor("#333B82F6")
        canvas.drawCircle(cx, cy, r * 1.15f, fillPaint)

        // Core
        fillPaint.color = Color.parseColor("#803B82F6")
        canvas.drawCircle(cx, cy, r, fillPaint)

        // Ring
        circlePaint.color = Color.parseColor("#FF3B82F6")
        circlePaint.strokeWidth = 4f
        canvas.drawCircle(cx, cy, r, circlePaint)
    }

    private fun drawListeningWaves(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val boost = 1f + currentAmplitude * 0.8f
        val r = radius * boost

        // Outer emerald halo
        fillPaint.color = Color.parseColor("#2510B981")
        canvas.drawCircle(cx, cy, r * 1.4f, fillPaint)

        // Mid emerald ring
        circlePaint.color = Color.parseColor("#8010B981")
        circlePaint.strokeWidth = 6f
        canvas.drawCircle(cx, cy, r * 1.15f, circlePaint)

        // Inner reactive core
        fillPaint.color = Color.parseColor("#CC10B981")
        canvas.drawCircle(cx, cy, r * 0.85f, fillPaint)

        // 8 radiating reactive bars
        wavePaint.color = Color.parseColor("#FF10B981")
        val bars = 16
        for (i in 0 until bars) {
            val angle = (2 * PI * i / bars).toFloat()
            val barLen = (radius * 0.35f) * (0.3f + currentAmplitude * 1.2f * (0.5f + 0.5f * sin(phase * 2f + i)))
            val startX = cx + cos(angle) * (r * 0.9f)
            val startY = cy + sin(angle) * (r * 0.9f)
            val endX = cx + cos(angle) * (r * 0.9f + barLen)
            val endY = cy + sin(angle) * (r * 0.9f + barLen)

            circlePaint.strokeWidth = 5f
            circlePaint.color = Color.parseColor("#FF10B981")
            canvas.drawLine(startX, startY, endX, endY, circlePaint)
        }
    }

    private fun drawProcessingSpinner(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        // Honey Amber glowing core
        fillPaint.color = Color.parseColor("#33F59E0B")
        canvas.drawCircle(cx, cy, radius * 1.2f, fillPaint)

        fillPaint.color = Color.parseColor("#80F59E0B")
        canvas.drawCircle(cx, cy, radius * 0.8f, fillPaint)

        // Rotating arc ring
        circlePaint.color = Color.parseColor("#FFF59E0B")
        circlePaint.strokeWidth = 6f
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val startAngle = (phase * 120f) % 360f
        canvas.drawArc(rect, startAngle, 220f, false, circlePaint)
    }

    private fun drawSpeakingOrb(canvas: Canvas, cx: Float, cy: Float, radius: Float) {
        val pulse = (sin(phase * 1.2) * 0.15 + 0.95).toFloat()
        val r = radius * pulse

        // Violet waves
        fillPaint.color = Color.parseColor("#228B5CF6")
        canvas.drawCircle(cx, cy, r * 1.5f, fillPaint)

        fillPaint.color = Color.parseColor("#558B5CF6")
        canvas.drawCircle(cx, cy, r * 1.2f, fillPaint)

        fillPaint.color = Color.parseColor("#DD8B5CF6")
        canvas.drawCircle(cx, cy, r * 0.9f, fillPaint)

        // Concentric expanding ring
        val ringR = (r * (1f + (phase % 1.5f) * 0.4f))
        val ringAlpha = ((1f - ((phase % 1.5f) / 1.5f)) * 255).toInt().coerceIn(0, 255)
        circlePaint.color = Color.argb(ringAlpha, 139, 92, 246)
        circlePaint.strokeWidth = 4f
        canvas.drawCircle(cx, cy, ringR, circlePaint)
    }
}
