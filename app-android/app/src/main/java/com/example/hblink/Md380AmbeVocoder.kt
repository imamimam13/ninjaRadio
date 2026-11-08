package com.example.hblink

import android.content.Context
import android.util.Log
import com.z3.dmr.net.vocoder.Md380Vocoder

class Md380AmbeVocoder private constructor(private val vocoder: Md380Vocoder) : AmbeVocoder {
    override val available: Boolean = true

    override fun encode(pcm: ShortArray): ByteArray {
        val result = vocoder.encodeFec(pcm)
        return result ?: NativeAmbeVocoder.AMBE_SILENCE.copyOf()
    }

    override fun decode(ambe: ByteArray): ShortArray {
        if (ambe.size >= 33) {
            val result = vocoder.decodeDmrdPayload(ambe)
            if (result != null && result.isNotEmpty()) return result
        }
        val result = vocoder.decode(ambe)
        return result ?: ShortArray(160)
    }

    fun decodeDmrd(payload: ByteArray): ShortArray? {
        if (payload.size >= 33) {
            return vocoder.decodeDmrdPayload(payload)
        }
        return null
    }

    override fun resetEncoder() {}
    override fun resetDecoder() {}

    companion object {
        private const val TAG = "Md380AmbeVocoder"

        fun tryLoad(context: Context): AmbeVocoder? {
            return try {
                if (!Md380Vocoder.isLoaded) {
                    Log.w(TAG, "Md380Vocoder native library not loaded")
                    return null
                }
                val ok = Md380Vocoder.init(context)
                if (!ok) {
                    Log.w(TAG, "Md380Vocoder.init returned false")
                    return null
                }
                Log.i(TAG, "Md380AmbeVocoder active and ready (crisp audio)!")
                Md380AmbeVocoder(Md380Vocoder())
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to load Md380AmbeVocoder", t)
                null
            }
        }
    }
}
