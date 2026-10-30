package com.ninjadmr.app.dsp

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Automatic gain control with smooth per-sample gain interpolation to eliminate
 * boundary clicking/popping ("meletup") between adjacent frames.
 */
class Agc(
    private val targetRms: Double = 6000.0,
    private val maxGain: Double = 2.5,
    private val minGain: Double = 0.5,
    private val attack: Double = 0.08,
    private val release: Double = 0.005,
) {
    private var currentGain = 1.0

    fun process(buf: ShortArray) {
        if (buf.isEmpty()) return
        var sum = 0.0
        for (s in buf) {
            val v = s.toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / buf.size)
        // Do not amplify low-level background noise/silence
        val targetGain = if (rms > 80.0) {
            (targetRms / rms).coerceIn(minGain, maxGain)
        } else {
            1.0
        }
        val rate = if (targetGain < currentGain) attack else release
        val endGain = currentGain + (targetGain - currentGain) * rate
        val step = (endGain - currentGain) / buf.size.toDouble()

        for (i in buf.indices) {
            val g = currentGain + step * i
            val v = buf[i] * g
            buf[i] = when {
                v > 32767.0 -> 32767
                v < -32768.0 -> -32768
                else -> v.toInt().toShort()
            }
        }
        currentGain = endGain
    }

    fun reset() {
        currentGain = 1.0
    }
}

/**
 * Raised-cosine fade helpers used to eliminate speaker pops at burst onsets
 * (40 samples = 5 ms at 8 kHz sample rate).
 */
object Envelope {
    fun fadeIn(buf: ShortArray, fadeSamples: Int = 40) {
        val n = buf.size
        val f = fadeSamples.coerceIn(1, n)
        for (i in 0 until f) {
            val w = 0.5 - 0.5 * cos(Math.PI * i / f)
            buf[i] = (buf[i] * w).toInt().toShort()
        }
    }

    fun fadeOut(buf: ShortArray, fadeSamples: Int = 40) {
        val n = buf.size
        val f = fadeSamples.coerceIn(1, n)
        for (i in 0 until f) {
            val w = 0.5 - 0.5 * cos(Math.PI * i / f)
            buf[n - 1 - i] = (buf[n - 1 - i] * w).toInt().toShort()
        }
    }
}
