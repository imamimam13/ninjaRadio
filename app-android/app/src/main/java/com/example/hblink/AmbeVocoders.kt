package com.example.hblink

object AmbeVocoders {
    fun create(): AmbeVocoder {
        return NativeAmbeVocoder.tryLoad() ?: NullVocoder()
    }
}
