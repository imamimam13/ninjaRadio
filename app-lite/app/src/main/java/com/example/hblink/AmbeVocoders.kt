package com.example.hblink

import android.content.Context
import android.util.Log

object AmbeVocoders {
    private const val TAG = "AmbeVocoders"
    private var instance: AmbeVocoder? = null

    fun get(context: Context): AmbeVocoder {
        val cur = instance
        if (cur is Md380AmbeVocoder) return cur

        return synchronized(this) {
            val curSync = instance
            if (curSync is Md380AmbeVocoder) return curSync

            val md380 = Md380AmbeVocoder.tryLoad(context)
            if (md380 != null) {
                Log.i(TAG, "Selected MD-380 DSP vocoder (crisp)")
                instance = md380
                md380
            } else {
                val fb = NativeAmbeVocoder.tryLoad() ?: NullVocoder()
                Log.w(TAG, "MD-380 unavailable, fallback to ${fb.javaClass.simpleName}")
                instance = fb
                fb
            }
        }
    }

    fun create(context: Context? = null): AmbeVocoder {
        if (context != null) {
            return get(context)
        }
        val fallback = NativeAmbeVocoder.tryLoad()
        if (fallback != null) {
            return fallback
        }
        return NullVocoder()
    }
}
