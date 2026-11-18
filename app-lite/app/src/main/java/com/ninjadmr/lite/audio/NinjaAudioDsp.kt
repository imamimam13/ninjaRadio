package com.ninjadmr.lite.audio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time 8 kHz Voice Conditioning & Noise Cancelling DSP pipeline for NinjaRadio Lite (MD-380).
 *
 * Features:
 * 1. Android Hardware DSP integration (NoiseSuppressor + AcousticEchoCanceler when supported).
 * 2. DC Offset Blocker + 4th-order Butterworth Voice Band-Pass (280 Hz - 3400 Hz) to strip
 *    wind rumble, AC hum, and high-frequency aliasing before MD-380 AMBE+2 encoding.
 * 3. Adaptive Spectral/Envelope Noise Canceller (Downward Expander) to suppress background noise
 *    between words and prevent AMBE+2 "digital wind/watery" noise artifacts.
 * 4. Vocal Presence Clarity EQ (+2.2 dB peaking filter @ 2150 Hz) for crisp radio articulation.
 * 5. Micro-cosine frame boundary de-clicking & soft-knee saturation limiter (anti-clipping).
 */
class NinjaAudioDsp(private val sampleRate: Double = 8000.0) {

    companion object {
        private const val TAG = "NinjaAudioDsp"
    }

    private class Biquad {
        private var b0 = 1.0
        private var b1 = 0.0
        private var b2 = 0.0
        private var a1 = 0.0
        private var a2 = 0.0

        private var x1 = 0.0
        private var x2 = 0.0
        private var y1 = 0.0
        private var y2 = 0.0

        fun highPass(fs: Double, cutoffHz: Double, q: Double = 0.7071): Biquad {
            val w0 = 2.0 * Math.PI * cutoffHz / fs
            val cosw = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = ((1.0 + cosw) * 0.5) / a0
            b1 = -(1.0 + cosw) / a0
            b2 = ((1.0 + cosw) * 0.5) / a0
            a1 = (-2.0 * cosw) / a0
            a2 = (1.0 - alpha) / a0
            return this
        }

        fun lowPass(fs: Double, cutoffHz: Double, q: Double = 0.7071): Biquad {
            val w0 = 2.0 * Math.PI * cutoffHz / fs
            val cosw = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha
            b0 = ((1.0 - cosw) * 0.5) / a0
            b1 = (1.0 - cosw) / a0
            b2 = ((1.0 - cosw) * 0.5) / a0
            a1 = (-2.0 * cosw) / a0
            a2 = (1.0 - alpha) / a0
            return this
        }

        fun peakingEq(fs: Double, centerHz: Double, q: Double, gainDb: Double): Biquad {
            val a = Math.pow(10.0, gainDb / 40.0)
            val w0 = 2.0 * Math.PI * centerHz / fs
            val cosw = cos(w0)
            val alpha = sin(w0) / (2.0 * q)
            val a0 = 1.0 + alpha / a
            b0 = (1.0 + alpha * a) / a0
            b1 = (-2.0 * cosw) / a0
            b2 = (1.0 - alpha * a) / a0
            a1 = (-2.0 * cosw) / a0
            a2 = (1.0 - alpha / a) / a0
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

    // Hardware effects references
    private var hwNoiseSuppressor: NoiseSuppressor? = null
    private var hwEchoCanceler: AcousticEchoCanceler? = null

    // TX filter chain (4th-order Butterworth Band-Pass 280 Hz - 3400 Hz + 2150 Hz Presence EQ)
    private var txDcPrevX = 0.0
    private var txDcPrevY = 0.0
    private val txHp1 = Biquad().highPass(sampleRate, 280.0, 0.5412)
    private val txHp2 = Biquad().highPass(sampleRate, 280.0, 1.3066)
    private val txLp1 = Biquad().lowPass(sampleRate, 3400.0, 0.5412)
    private val txLp2 = Biquad().lowPass(sampleRate, 3400.0, 1.3066)
    private val txPresenceEq = Biquad().peakingEq(sampleRate, 2150.0, 1.15, 2.4)

    // TX Adaptive Noise Canceller state
    private var txNoiseFloorRms = 90.0
    private var txGateGain = 1.0
    private var txAgcGain = 1.0

    // RX filter chain (2nd-order Band-Pass 260 Hz - 3450 Hz + 2100 Hz Clarity EQ)
    private val rxHp = Biquad().highPass(sampleRate, 260.0, 0.7071)
    private val rxLp = Biquad().lowPass(sampleRate, 3450.0, 0.7071)
    private val rxClarityEq = Biquad().peakingEq(sampleRate, 2100.0, 1.10, 2.0)

    // RX De-hisser & de-clicker state
    private var rxNoiseFloorRms = 60.0
    private var rxGateGain = 1.0
    private var rxLastSample = 0.0
    private var rxStreamFresh = true

    fun attachHardwareEffects(audioSessionId: Int, enableNc: Boolean) {
        releaseHardwareEffects()
        if (audioSessionId <= 0 || !enableNc) return
        try {
            if (NoiseSuppressor.isAvailable()) {
                hwNoiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply {
                    enabled = true
                }
                Log.i(TAG, "Hardware NoiseSuppressor attached (enabled=${hwNoiseSuppressor?.enabled})")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "NoiseSuppressor attach failed: ${e.message}")
        }
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                hwEchoCanceler = AcousticEchoCanceler.create(audioSessionId)?.apply {
                    enabled = true
                }
                Log.i(TAG, "Hardware AcousticEchoCanceler attached (enabled=${hwEchoCanceler?.enabled})")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "AcousticEchoCanceler attach failed: ${e.message}")
        }
    }

    fun releaseHardwareEffects() {
        try {
            hwNoiseSuppressor?.release()
        } catch (_: Throwable) {}
        hwNoiseSuppressor = null

        try {
            hwEchoCanceler?.release()
        } catch (_: Throwable) {}
        hwEchoCanceler = null
    }

    @Synchronized
    fun resetTx() {
        txDcPrevX = 0.0
        txDcPrevY = 0.0
        txHp1.reset()
        txHp2.reset()
        txLp1.reset()
        txLp2.reset()
        txPresenceEq.reset()
        txGateGain = 1.0
        txAgcGain = 1.0
    }

    @Synchronized
    fun resetRx() {
        rxHp.reset()
        rxLp.reset()
        rxClarityEq.reset()
        rxGateGain = 1.0
        rxLastSample = 0.0
        rxStreamFresh = true
    }

    /**
     * Soft-knee limiter that prevents harsh digital clipping above ±25000.
     */
    private fun softLimit(x: Double): Short {
        val absX = abs(x)
        if (absX <= 25000.0) {
            return x.toInt().toShort()
        }
        val sign = if (x >= 0.0) 1.0 else -1.0
        val excess = absX - 25000.0
        // Asymptotic soft compression between 25000 and 32700
        val compressed = 25000.0 + 7700.0 * (excess / (excess + 7700.0))
        return (sign * compressed).toInt().coerceIn(-32768, 32767).toShort()
    }

    /**
     * Processes a 160-sample (20 ms) microphone frame before AMBE+2 encoding.
     */
    @Synchronized
    fun processTxFrame(frame: ShortArray, micGain: Float, ncEnabled: Boolean): ShortArray {
        val n = frame.size
        if (n == 0) return frame
        val out = ShortArray(n)
        val filtered = DoubleArray(n)

        // 1. DC Blocker + Band-Pass (280 Hz - 3400 Hz) + Presence EQ
        var sumSq = 0.0
        for (i in 0 until n) {
            val raw = frame[i].toDouble()
            // DC blocker
            val dcFree = raw - txDcPrevX + 0.995 * txDcPrevY
            txDcPrevX = raw
            txDcPrevY = dcFree

            var y = txHp1.process(dcFree)
            if (ncEnabled) {
                y = txHp2.process(y)
                y = txLp1.process(y)
                y = txLp2.process(y)
                y = txPresenceEq.process(y)
            } else {
                y = txLp1.process(y)
            }
            filtered[i] = y
            sumSq += y * y
        }

        val frameRms = sqrt(sumSq / n)

        // 2. Adaptive Noise Cancelling (Downward Expander) + Smooth Leveling AGC
        var targetGate = 1.0
        var targetAgc = 1.0

        if (ncEnabled) {
            // Update adaptive noise floor estimate
            if (frameRms < txNoiseFloorRms) {
                txNoiseFloorRms = 0.85 * txNoiseFloorRms + 0.15 * frameRms.coerceAtLeast(35.0)
            } else if (frameRms < txNoiseFloorRms * 1.8) {
                txNoiseFloorRms = (0.992 * txNoiseFloorRms + 0.008 * frameRms).coerceAtMost(420.0)
            }

            val gateThreshold = (txNoiseFloorRms * 2.1).coerceIn(110.0, 650.0)
            targetGate = when {
                frameRms >= gateThreshold -> 1.0
                frameRms <= gateThreshold * 0.45 -> 0.06 // Deep background noise suppression (-24 dB)
                else -> {
                    val ratio = (frameRms - gateThreshold * 0.45) / (gateThreshold * 0.55)
                    0.06 + 0.94 * (ratio * ratio)
                }
            }

            // Gentle voice leveling AGC when active speech is detected
            val scaledRms = frameRms * micGain
            if (frameRms > gateThreshold && scaledRms > 200.0) {
                targetAgc = (6500.0 / scaledRms).coerceIn(0.65, 1.45)
            }
        }

        val gateRate = if (targetGate > txGateGain) 0.45 else 0.14
        val endGate = txGateGain + (targetGate - txGateGain) * gateRate
        val gateStep = (endGate - txGateGain) / n

        val agcRate = if (targetAgc < txAgcGain) 0.20 else 0.06
        val endAgc = txAgcGain + (targetAgc - txAgcGain) * agcRate
        val agcStep = (endAgc - txAgcGain) / n

        val baseGain = micGain.toDouble()
        for (i in 0 until n) {
            val gGate = txGateGain + gateStep * i
            val gAgc = txAgcGain + agcStep * i
            val sample = filtered[i] * baseGain * gGate * gAgc
            out[i] = softLimit(sample)
        }

        txGateGain = endGate
        txAgcGain = endAgc
        return out
    }

    /**
     * Processes decoded RX PCM samples (160 or 480 samples) before speaker playback.
     */
    @Synchronized
    fun processRxFrame(pcm: ShortArray, spkGain: Float, ncEnabled: Boolean): ShortArray {
        val n = pcm.size
        if (n == 0) return pcm
        val out = ShortArray(n)
        val work = DoubleArray(n)

        var sumSq = 0.0
        for (i in 0 until n) {
            var y = pcm[i].toDouble()
            if (ncEnabled) {
                y = rxHp.process(y)
                y = rxLp.process(y)
                y = rxClarityEq.process(y)
            }
            work[i] = y
            sumSq += y * y
        }

        val rms = sqrt(sumSq / n)
        var targetGate = 1.0
        if (ncEnabled) {
            if (rms < rxNoiseFloorRms) {
                rxNoiseFloorRms = 0.88 * rxNoiseFloorRms + 0.12 * rms.coerceAtLeast(25.0)
            } else if (rms < rxNoiseFloorRms * 1.7) {
                rxNoiseFloorRms = (0.995 * rxNoiseFloorRms + 0.005 * rms).coerceAtMost(300.0)
            }
            val rxGateThresh = (rxNoiseFloorRms * 1.85).coerceIn(75.0, 400.0)
            if (rms < rxGateThresh) {
                val r = (rms / rxGateThresh).coerceIn(0.0, 1.0)
                targetGate = 0.15 + 0.85 * (r * r)
            }
        }

        val gateRate = if (targetGate > rxGateGain) 0.55 else 0.18
        val endGate = rxGateGain + (targetGate - rxGateGain) * gateRate
        val gateStep = (endGate - rxGateGain) / n

        val gain = spkGain.toDouble()
        val fadeSamples = if (rxStreamFresh) 32.coerceAtMost(n) else 0
        val boundarySmoothSamples = if (!rxStreamFresh) 12.coerceAtMost(n) else 0

        for (i in 0 until n) {
            val gGate = rxGateGain + gateStep * i
            var s = work[i] * gain * gGate

            if (i < fadeSamples) {
                // Smooth raised-cosine onset fade-in on new RX burst to prevent speaker pop
                val w = 0.5 - 0.5 * cos(Math.PI * i / fadeSamples)
                s *= w
            } else if (i < boundarySmoothSamples) {
                // Micro-cosine crossfade from previous frame's tail sample
                val w = 0.5 - 0.5 * cos(Math.PI * (i + 1) / (boundarySmoothSamples + 1))
                s = rxLastSample * (1.0 - w) + s * w
            }

            out[i] = softLimit(s)
        }

        rxGateGain = endGate
        rxLastSample = out[n - 1].toDouble()
        rxStreamFresh = false
        return out
    }
}