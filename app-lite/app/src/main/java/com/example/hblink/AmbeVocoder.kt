package com.example.hblink

interface AmbeVocoder {
    val available: Boolean
    fun encode(pcm: ShortArray): ByteArray
    fun decode(ambe: ByteArray): ShortArray
    fun resetEncoder() {}
    fun resetDecoder() {}
}
