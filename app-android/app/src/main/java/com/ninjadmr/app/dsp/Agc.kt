package com.ninjadmr.app.dsp

import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Automatic gain control (simple RMS-driven peak limiter).
 *
 * The web client relies on WebAudio's `DynamicsCompressorNode`; this is a light
 * stand-in that pulls quiet input up toward [targetRms] and prevents clipping.
 */
class Agc(
    private val targetRms: Double = 6000.0,
    private val maxGain: Double = 6.0,
    private val minGain: Double = 0.25,
    private val attack: Double = 0.10,
    private val release: Double = 0.002,
) {
    private var gain = 1.0

    fun process(buf: ShortArray) {
        var sum = 0.0
        for (s in buf) {
            val v = s.toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / buf.size)
        if (rms > 1.0) {
            val desired = (targetRms / rms).coerceIn(minGain, maxGain)
            val rate = if (desired < gain) attack else release
            gain += (desired - gain) * rate
        }
        if (gain != 1.0) {
            for (i in buf.indices) {
                val v = buf[i] * gain
                buf[i] = when {
                    v > 32767.0 -> 32767
                    v < -32768.0 -> -32768
                    else -> v.toInt().toShort()
                }
            }
        }
    }

    fun reset() {
        gain = 1.0
    }
}

/**
 * Raised-cosine fade helpers used to eliminate speaker pops at burst onsets
 * (the web client ramps the first ~0.5 ms of each new burst).
 */
object Envelope {
    fun fadeIn(buf: ShortArray, fadeSamples: Int = 4) {
        val n = buf.size
        val f = fadeSamples.coerceIn(1, n)
        for (i in 0 until f) {
            val w = 0.5 - 0.5 * cos(Math.PI * i / f)
            buf[i] = (buf[i] * w).toInt().toShort()
        }
    }

    fun fadeOut(buf: ShortArray, fadeSamples: Int = 4) {
        val n = buf.size
        val f = fadeSamples.coerceIn(1, n)
        for (i in 0 until f) {
            val w = 0.5 - 0.5 * cos(Math.PI * i / f)
            buf[n - 1 - i] = (buf[n - 1 - i] * w).toInt().toShort()
        }
    }
}
