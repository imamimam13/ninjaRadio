package com.ninjadmr.app.util

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.ninjadmr.app.R
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NinjaAudioFx {
    private const val TAG = "NinjaAudioFx"

    @Volatile private var swordBurstsCache: List<ByteArray>? = null
    @Volatile private var shurikenBurstsCache: List<ByteArray>? = null

    /**
     * Local sidetone for Talk Permit Tone (Katana Strike).
     * Volume set to 0.40f so it is clear and crisp without distortion.
     */
    fun playSwordClang(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isSpeakerphoneOn = true
            val mp = MediaPlayer.create(context.applicationContext, R.raw.sword_clang) ?: return
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setVolume(0.40f, 0.40f)
            mp.setOnCompletionListener {
                try { it.release() } catch (_: Exception) {}
            }
            mp.start()
            Log.d(TAG, "Katana sword clang played")
        } catch (e: Exception) {
            Log.e(TAG, "playSwordClang error", e)
        }
    }

    /**
     * Local sidetone for Roger Beep (Shuriken Throw).
     * Volume set to 0.25f so it doesn't blast the user's ears.
     */
    fun playShuriken(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.isSpeakerphoneOn = true
            val mp = MediaPlayer.create(context.applicationContext, R.raw.shuriken) ?: return
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setVolume(0.25f, 0.25f)
            mp.setOnCompletionListener {
                try { it.release() } catch (_: Exception) {}
            }
            mp.start()
            Log.d(TAG, "Ninja shuriken roger beep played")
        } catch (e: Exception) {
            Log.e(TAG, "playShuriken error", e)
        }
    }

    /**
     * Returns 8000 Hz 16-bit PCM bursts (480 samples = 960 bytes = 60ms each)
     * ready to be fed into PyBridge.feedTx for over-the-air DMR transmission.
     */
    fun getSwordClangBursts(context: Context): List<ByteArray> {
        swordBurstsCache?.let { return it }
        val loaded = loadBurstsFromWav(context, R.raw.sword_clang)
        swordBurstsCache = loaded
        return loaded
    }

    fun getShurikenBursts(context: Context): List<ByteArray> {
        shurikenBurstsCache?.let { return it }
        val loaded = loadBurstsFromWav(context, R.raw.shuriken)
        shurikenBurstsCache = loaded
        return loaded
    }

    private fun loadBurstsFromWav(context: Context, resId: Int): List<ByteArray> {
        val bursts = mutableListOf<ByteArray>()
        try {
            val input = context.resources.openRawResource(resId)
            val bytes = input.readBytes()
            input.close()
            // Standard WAV RIFF header is 44 bytes
            if (bytes.size > 44) {
                val pcmBytes = bytes.copyOfRange(44, bytes.size)
                // 480 samples * 2 bytes = 960 bytes per DMR 60ms burst
                val burstSize = 960
                var offset = 0
                while (offset + burstSize <= pcmBytes.size) {
                    bursts.add(pcmBytes.copyOfRange(offset, offset + burstSize))
                    offset += burstSize
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadBurstsFromWav error", e)
        }
        return bursts
    }
}
