package com.ninjadmr.lite.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * 18-Segment Pro LED S-Meter with Peak-Hold & S-Scale Markings.
 * 100% Native Android Canvas (Android 5.0+ API 21 compatible, zero dependencies).
 */
class HudSmeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val numSegments = 18
    private var currentLevel = 0f     // 0f .. 1f
    private var smoothedLevel = 0f    // 0f .. 1f
    private var peakSegment = -1
    private var lastPeakTime = 0L

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#050912")
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E2F4D")
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val segPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#64748B")
        textSize = dp(9.5f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val rect = RectF()

    private val colorDim = Color.parseColor("#111C30")
    private val colorGreen = Color.parseColor("#34D399")
    private val colorCyan = Color.parseColor("#38BDF8")
    private val colorAmber = Color.parseColor("#FBBF24")
    private val colorRed = Color.parseColor("#F43F5E")
    private val colorPeak = Color.parseColor("#F8FAFC")

    private val scaleMarks = arrayOf("S1", "S3", "S5", "S7", "S9", "+40")

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    fun setLevel(level: Float) {
        currentLevel = level.coerceIn(0f, 1f)
        if (currentLevel > smoothedLevel) {
            smoothedLevel = currentLevel
        } else {
            smoothedLevel = smoothedLevel * 0.65f + currentLevel * 0.35f
        }
        val activeSegs = (smoothedLevel * numSegments).toInt().coerceIn(0, numSegments)
        val now = System.currentTimeMillis()
        if (activeSegs - 1 >= peakSegment && activeSegs > 0) {
            peakSegment = min(numSegments - 1, activeSegs - 1)
            lastPeakTime = now
        }
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val now = System.currentTimeMillis()
        if (peakSegment >= 0 && now - lastPeakTime > 180L) {
            peakSegment--
            lastPeakTime = now
            if (peakSegment >= 0 || smoothedLevel > 0.01f) {
                postInvalidateDelayed(70L)
            }
        }
        if (currentLevel == 0f && smoothedLevel > 0.01f) {
            smoothedLevel *= 0.72f
            if (smoothedLevel < 0.02f) smoothedLevel = 0f
            postInvalidateDelayed(50L)
        }

        val scaleHeight = dp(11f)
        val barTop = if (h > dp(16f)) scaleHeight else 0f
        val barHeight = max(dp(6f), h - barTop)

        // Draw S-scale labels if enough vertical height
        if (barTop > 0f) {
            val step = (w - dp(18f)) / (scaleMarks.size - 1)
            for (i in scaleMarks.indices) {
                val label = scaleMarks[i]
                textPaint.color = if (i == scaleMarks.lastIndex) colorRed else Color.parseColor("#64748B")
                val x = i * step
                canvas.drawText(label, x, scaleHeight - dp(2.5f), textPaint)
            }
        }

        // Draw bar frame
        rect.set(0f, barTop, w, barTop + barHeight)
        val r = dp(3f)
        canvas.drawRoundRect(rect, r, r, bgPaint)
        canvas.drawRoundRect(rect, r, r, borderPaint)

        val pad = dp(2f)
        val gap = dp(2f)
        val innerW = w - pad * 2f
        val segW = (innerW - gap * (numSegments - 1)) / numSegments
        val segTop = barTop + pad
        val segBot = barTop + barHeight - pad

        val litCount = (smoothedLevel * numSegments).toInt().coerceIn(0, numSegments)

        for (i in 0 until numSegments) {
            val left = pad + i * (segW + gap)
            rect.set(left, segTop, left + segW, segBot)

            when {
                i < litCount -> {
                    segPaint.color = when {
                        i < 9 -> colorGreen
                        i < 13 -> colorCyan
                        i < 15 -> colorAmber
                        else -> colorRed
                    }
                }
                i == peakSegment && peakSegment > 0 -> {
                    segPaint.color = colorPeak
                }
                else -> {
                    segPaint.color = colorDim
                }
            }
            canvas.drawRoundRect(rect, dp(1.2f), dp(1.2f), segPaint)
        }
    }
}
