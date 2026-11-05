package com.ninjadmr.lite.audio

import kotlin.math.log10
import kotlin.math.sqrt

object AudioLevel {
    private const val FLOOR_DB = -60.0f
    private const val MIN = 0.001f

    fun of(pcm: ShortArray): Float {
        if (pcm.isEmpty()) return MIN
        var sum = 0.0
        for (s in pcm) {
            sum += s.toDouble() * s.toDouble()
        }
        val rms = sqrt(sum / pcm.size)
        if (rms < 1.0) return MIN
        val db = (log10(rms / 32768.0) * 20.0).toFloat()
        return ((db - FLOOR_DB) / 54.0f).coerceIn(MIN, 1.0f)
    }
}
