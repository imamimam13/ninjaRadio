package com.ninjadmr.app.dsp

import kotlin.math.cos
import kotlin.math.sin

/**
 * Direct-form-I biquad filter (RBJ cookbook coefficients).
 *
 * Used by the receive chain to band-limit decoded voice to the 8 kHz voice
 * band, mirroring the web client's high-pass + low-pass pipeline.
 */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    fun lowPass(sampleRate: Double, cutoff: Double, q: Double = 0.7071): Biquad {
        val w0 = 2.0 * Math.PI * cutoff / sampleRate
        val cosw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = (1.0 - cosw) / 2.0 / a0
        b1 = (1.0 - cosw) / a0
        b2 = (1.0 - cosw) / 2.0 / a0
        a1 = -2.0 * cosw / a0
        a2 = (1.0 - alpha) / a0
        return this
    }

    fun highPass(sampleRate: Double, cutoff: Double, q: Double = 0.7071): Biquad {
        val w0 = 2.0 * Math.PI * cutoff / sampleRate
        val cosw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = (1.0 + cosw) / 2.0 / a0
        b1 = -(1.0 + cosw) / a0
        b2 = (1.0 + cosw) / 2.0 / a0
        a1 = -2.0 * cosw / a0
        a2 = (1.0 - alpha) / a0
        return this
    }

    fun process(x: Double): Double {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = x
        y2 = y1
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }
}
