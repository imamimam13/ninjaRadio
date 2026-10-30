package com.ninjadmr.app

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time audio spectrum & waveform visualizer for DMR radio.
 * Processes 480-sample (60 ms, 8 kHz) PCM bursts from both RX (speaker) and TX (mic)
 * to drive the radio's S-Meter and 20-band audio spectrum analyzer.
 */
object AudioVisualizer {
    const val NUM_BARS = 20

    // Smoothed bar levels [0.0f .. 1.0f]
    private val currentBars = FloatArray(NUM_BARS)

    // Target levels from latest audio frame
    private val targetBars = FloatArray(NUM_BARS)

    @Volatile var currentRms: Float = 0f
        private set

    @Volatile var isTx: Boolean = false
        private set

    @Volatile var lastFrameTs: Long = 0L
        private set

    // Precomputed Hamming window for 480 samples
    private val window = FloatArray(480) { i ->
        (0.54 - 0.46 * cos(2.0 * Math.PI * i / 479.0)).toFloat()
    }

    /**
     * Feed a 480-sample PCM frame from RX playback or TX mic.
     */
    fun feed(samples: ShortArray, tx: Boolean) {
        if (samples.isEmpty()) return
        isTx = tx
        lastFrameTs = System.currentTimeMillis()

        val n = min(samples.size, 480)
        var sumSquares = 0.0
        for (i in 0 until n) {
            val s = samples[i].toDouble()
            sumSquares += s * s
        }
        val rms = sqrt(sumSquares / n).toFloat()
        currentRms = rms

        // Compute 20-band spectrum analyzer via logarithmically spaced frequency DFT bins
        // combined with time-domain envelope slices
        synchronized(targetBars) {
            val bandSizes = max(1, n / NUM_BARS)
            for (b in 0 until NUM_BARS) {
                // 1. Time-domain envelope slice
                var sliceSum = 0.0
                val start = b * bandSizes
                val end = min(start + bandSizes, n)
                for (i in start until end) {
                    sliceSum += abs(samples[i].toInt())
                }
                val timeAmp = if (end > start) (sliceSum / (end - start)) / 6000.0 else 0.0

                // 2. Direct Fourier component at speech frequency for this band (150 Hz to 3600 Hz)
                val freqHz = 150.0 + (3450.0 * (b.toDouble() / (NUM_BARS - 1)).let { it * it })
                val omega = 2.0 * Math.PI * freqHz / 8000.0
                var re = 0.0
                var im = 0.0
                val step = max(1, n / 64)
                for (i in 0 until n step step) {
                    val w = window[i] * samples[i]
                    val angle = omega * i
                    re += w * cos(angle)
                    im -= w * sin(angle)
                }
                val specAmp = sqrt(re * re + im * im) / 25000.0

                // Blend time slice & frequency peak
                val rawVal = (specAmp * 0.55 + timeAmp * 0.45).toFloat()
                // Perceptual dynamic range compression for lively movement
                val compressed = sqrt(rawVal.coerceIn(0f, 3.0f) / 1.4f).coerceIn(0f, 1f)
                targetBars[b] = compressed
            }
        }
    }

    /**
     * Poll smoothed bars at UI refresh rate (~30 FPS).
     * Fast attack upwards, smooth analog decay downwards during pauses/silence.
     */
    fun getBars(): FloatArray {
        val now = System.currentTimeMillis()
        val isSilent = (now - lastFrameTs) > 180L

        synchronized(targetBars) {
            for (i in 0 until NUM_BARS) {
                val target = if (isSilent) 0f else targetBars[i]
                if (target > currentBars[i]) {
                    // Fast rise / attack
                    currentBars[i] = currentBars[i] + (target - currentBars[i]) * 0.75f
                } else {
                    // Smooth exponential decay (VU/analog meter falloff)
                    currentBars[i] = max(0f, currentBars[i] * 0.82f - 0.015f)
                }
            }
            if (isSilent) {
                currentRms = max(0f, currentRms * 0.85f - 15f)
            }
            return currentBars.clone()
        }
    }

    /**
     * Calculate S-Meter from active audio RMS.
     * Returns Pair("S-METER S...", "-... dBm")
     */
    fun getSMeter(): Pair<String, String> {
        val now = System.currentTimeMillis()
        if (now - lastFrameTs > 200L || currentRms < 100f) {
            return "S-METER S0" to "-121 dBm"
        }
        val rms = currentRms
        return when {
            rms < 300f -> "S-METER S1" to "-117 dBm"
            rms < 700f -> "S-METER S3" to "-105 dBm"
            rms < 1500f -> "S-METER S5" to "-93 dBm"
            rms < 3000f -> "S-METER S7" to "-81 dBm"
            rms < 6000f -> "S-METER S9" to "-73 dBm"
            rms < 10000f -> "S-METER S9+10" to "-63 dBm"
            rms < 16000f -> "S-METER S9+20" to "-53 dBm"
            else -> "S-METER S9+40" to "-33 dBm"
        }
    }
}
