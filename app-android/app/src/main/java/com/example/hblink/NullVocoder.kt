package com.example.hblink

class NullVocoder : AmbeVocoder {
    override val available: Boolean = false

    override fun encode(pcm: ShortArray): ByteArray {
        return NativeAmbeVocoder.AMBE_SILENCE.copyOf()
    }

    override fun decode(ambe: ByteArray): ShortArray {
        return ShortArray(160)
    }

    override fun resetEncoder() {}
    override fun resetDecoder() {}
}
