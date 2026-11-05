package com.ninjadmr.lite.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Live Tactical Oscilloscope / Audio Waveform & Radar Heartbeat View.
 * Lightweight 100% Native Android Canvas for Android 5.0+ (API 21).
 */
class HudWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class Mode { STANDBY, RX, TX }

    private var mode: Mode = Mode.STANDBY
    private var audioLevel: Float = 0f
    private var phase: Float = 0f

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#060A12")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#2A3F66")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#182644")
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8")
        style = Paint.Style.STROKE
        strokeWidth = dp(1.6f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val wavePath = Path()
    private val rect = RectF()

    private val colorCyan = Color.parseColor("#38BDF8")
    private val colorGreen = Color.parseColor("#34D399")
    private val colorRed = Color.parseColor("#F43F5E")
    private val colorEdgeNormal = Color.parseColor("#2A3F66")

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    fun setModeAndLevel(newMode: Mode, level: Float) {
        this.mode = newMode
        this.audioLevel = level.coerceIn(0f, 1f)
        when (newMode) {
            Mode.STANDBY -> {
                wavePaint.color = colorCyan
                borderPaint.color = colorEdgeNormal
            }
            Mode.RX -> {
                wavePaint.color = colorGreen
                borderPaint.color = colorGreen
            }
            Mode.TX -> {
                wavePaint.color = colorRed
                borderPaint.color = colorRed
            }
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val r = dp(4f)
        rect.set(dp(0.5f), dp(0.5f), w - dp(0.5f), h - dp(0.5f))
        canvas.drawRoundRect(rect, r, r, bgPaint)
        canvas.drawRoundRect(rect, r, r, borderPaint)

        val midY = h / 2f
        // Center horizon grid line
        canvas.drawLine(dp(2f), midY, w - dp(2f), midY, gridPaint)
        canvas.drawLine(w / 2f, dp(2f), w / 2f, h - dp(2f), gridPaint)

        phase += if (mode == Mode.STANDBY) 0.14f else 0.42f

        wavePath.reset()
        val step = dp(2.2f).coerceAtLeast(2f)
        val maxAmp = (h * 0.40f)
        var x = dp(3f)
        var first = true
        val endX = w - dp(3f)

        while (x <= endX) {
            val normX = x / w
            val yOffset = if (mode == Mode.RX || mode == Mode.TX) {
                val activeAmp = (0.22f + audioLevel * 0.78f) * maxAmp
                (sin(normX * 16f + phase) * cos(normX * 9f - phase * 0.7f)) * activeAmp
            } else {
                // Standby radar pulse blip moving left to right
                val pulsePos = (phase * 0.15f) % 1.0f
                val dist = abs(normX - pulsePos)
                if (dist < 0.12f) {
                    sin((0.12f - dist) * 52f) * (maxAmp * 0.45f)
                } else {
                    0f
                }
            }
            val y = (midY + yOffset).coerceIn(dp(2f), h - dp(2f))
            if (first) {
                wavePath.moveTo(x, y)
                first = false
            } else {
                wavePath.lineTo(x, y)
            }
            x += step
        }

        canvas.drawPath(wavePath, wavePaint)

        // Keep animating smoothly
        if (isShown) {
            val delay = if (mode == Mode.STANDBY) 90L else 45L
            postInvalidateDelayed(delay)
        }
    }
}
