package com.example.hblink

class NativeAmbeVocoder : AmbeVocoder {
    override val available: Boolean = true

    private external fun nativeEncode(pcm: ShortArray): ByteArray?
    private external fun nativeDecode(ambe: ByteArray): ShortArray?
    private external fun nativeResetEncoder()
    private external fun nativeResetDecoder()

    override fun encode(pcm: ShortArray): ByteArray {
        val result = nativeEncode(pcm)
        return result ?: AMBE_SILENCE.copyOf()
    }

    override fun decode(ambe: ByteArray): ShortArray {
        val result = nativeDecode(ambe)
        return result ?: ShortArray(160)
    }

    override fun resetEncoder() {
        nativeResetEncoder()
    }

    override fun resetDecoder() {
        nativeResetDecoder()
    }

    companion object {
        val AMBE_SILENCE = byteArrayOf(
            0xB9.toByte(), 0xE8.toByte(), 0x81.toByte(), 0x52.toByte(),
            0x61.toByte(), 0x73.toByte(), 0x00.toByte(), 0x2A.toByte(), 0x6B.toByte()
        )

        fun tryLoad(): AmbeVocoder? {
            return try {
                System.loadLibrary("ambe")
                val vocoder = NativeAmbeVocoder()
                vocoder.resetEncoder()
                vocoder.resetDecoder()
                vocoder
            } catch (e: Throwable) {
                null
            }
        }
    }
}
