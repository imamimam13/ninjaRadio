package com.ninjadmr.lite.audio

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import com.ninjadmr.lite.R
import java.nio.ByteBuffer
import java.nio.ByteOrder

object NinjaAudioFx {
    private const val TAG = "NinjaAudioFx"

    @Volatile private var swordChunksCache: List<ShortArray>? = null
    @Volatile private var shurikenChunksCache: List<ShortArray>? = null

    fun init(context: Context) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = true
        } catch (_: Exception) {}
    }

    /**
     * Local sidetone for Talk Permit Tone (Katana Strike).
     * Softened volume (0.40f) so it is crisp and pleasant without distortion.
     */
    fun playSwordClang(context: Context, force: Boolean = false) {
        val enabled = AudioPreferences.isNinjaChirpEnabled(context)
        if (!force && !enabled) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = true
            val mp = MediaPlayer.create(context, R.raw.sword_clang) ?: return
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setVolume(0.40f, 0.40f)
            mp.setOnCompletionListener {
                try { it.release() } catch (_: Exception) {}
            }
            mp.start()
        } catch (e: Exception) {
            Log.e(TAG, "playSwordClang error", e)
        }
    }

    /**
     * Local sidetone for Roger Beep (Shuriken Throw).
     * Lower volume (0.25f) so it doesn't blast the user's ears when holding near ear/face.
     */
    fun playShuriken(context: Context, force: Boolean = false) {
        val enabled = AudioPreferences.isNinjaChirpEnabled(context)
        if (!force && !enabled) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.isSpeakerphoneOn = true
            val mp = MediaPlayer.create(context, R.raw.shuriken) ?: return
            mp.setAudioStreamType(AudioManager.STREAM_MUSIC)
            mp.setVolume(0.25f, 0.25f)
            mp.setOnCompletionListener {
                try { it.release() } catch (_: Exception) {}
            }
            mp.start()
        } catch (e: Exception) {
            Log.e(TAG, "playShuriken error", e)
        }
    }

    /**
     * Returns 8000 Hz 16-bit PCM chunks (160 samples = 20ms each) for over-the-air DMR transmission.
     */
    fun getSwordClangPcm(context: Context): List<ShortArray> {
        swordChunksCache?.let { return it }
        val loaded = loadPcmFromWav(context, R.raw.sword_clang)
        swordChunksCache = loaded
        return loaded
    }

    fun getShurikenPcm(context: Context): List<ShortArray> {
        shurikenChunksCache?.let { return it }
        val loaded = loadPcmFromWav(context, R.raw.shuriken)
        shurikenChunksCache = loaded
        return loaded
    }

    private fun loadPcmFromWav(context: Context, resId: Int): List<ShortArray> {
        val chunks = mutableListOf<ShortArray>()
        try {
            val input = context.resources.openRawResource(resId)
            val bytes = input.readBytes()
            input.close()
            // Standard WAV RIFF header is 44 bytes
            if (bytes.size > 44) {
                val pcmBytes = bytes.copyOfRange(44, bytes.size)
                val numShorts = pcmBytes.size / 2
                val shorts = ShortArray(numShorts)
                val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                bb.asShortBuffer().get(shorts)

                var offset = 0
                while (offset + 160 <= shorts.size) {
                    chunks.add(shorts.copyOfRange(offset, offset + 160))
                    offset += 160
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "loadPcmFromWav error", e)
        }
        return chunks
    }
}
