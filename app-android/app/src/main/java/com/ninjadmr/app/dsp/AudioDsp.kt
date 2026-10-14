package com.ninjadmr.app.dsp

/**
 * Receive path band-limiting: high-pass + low-pass, mirroring the web client's
 * voice-band filters. The TX path uses [Agc]; the RX path uses this plus the
 * [JitterBuffer] and [Envelope] onset fade.
 */
class AudioDsp(sampleRate: Double = 8000.0) {
    private val highPass = Biquad().highPass(sampleRate, 300.0)
    private val lowPass = Biquad().lowPass(sampleRate, 3400.0)

    fun processRx(buf: ShortArray) {
        for (i in buf.indices) {
            var x = buf[i].toDouble()
            x = highPass.process(x)
            x = lowPass.process(x)
            buf[i] = when {
                x > 32767.0 -> 32767
                x < -32768.0 -> -32768
                else -> x.toInt().toShort()
            }
        }
    }

    fun reset() {
        highPass.reset()
        lowPass.reset()
    }
}
