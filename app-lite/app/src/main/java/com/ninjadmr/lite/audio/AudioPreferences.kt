package com.ninjadmr.lite.audio

import android.content.Context
import android.content.SharedPreferences

object AudioPreferences {
    private const val PREF_NAME = "ninjadmr_audio_prefs"
    private const val KEY_MIC_GAIN = "mic_gain"
    private const val KEY_SPK_GAIN = "spk_gain"
    private const val KEY_NINJA_CHIRP = "ninja_chirp_enabled"
    private const val KEY_PTT_TOGGLE = "ptt_toggle_mode"
    private const val KEY_VOX_ENABLED = "vox_ht_enabled"
    private const val KEY_HEADSET_PRIORITY = "headset_jack_priority"
    private const val KEY_TOT_SECONDS = "tot_seconds"

    // Default 2.5x (250%) boost for PoC pinhole mics; can go down to 0.10f (10%) for HT Jack Line-In
    const val DEFAULT_MIC_GAIN = 2.5f
    const val DEFAULT_SPK_GAIN = 1.2f
    const val DEFAULT_NINJA_CHIRP = true
    const val DEFAULT_PTT_TOGGLE = false
    const val DEFAULT_VOX_ENABLED = false
    const val DEFAULT_HEADSET_PRIORITY = true
    const val DEFAULT_TOT_SECONDS = 180 // 180s (3 minutes) default TOT

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun isNinjaChirpEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_NINJA_CHIRP, DEFAULT_NINJA_CHIRP)
    }

    fun setNinjaChirpEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_NINJA_CHIRP, enabled).apply()
    }

    fun isPttToggle(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_PTT_TOGGLE, DEFAULT_PTT_TOGGLE)
    }

    fun setPttToggle(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_PTT_TOGGLE, enabled).apply()
    }

    fun isVoxEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VOX_ENABLED, DEFAULT_VOX_ENABLED)
    }

    fun setVoxEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VOX_ENABLED, enabled).apply()
    }

    fun isHeadsetPriority(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HEADSET_PRIORITY, DEFAULT_HEADSET_PRIORITY)
    }

    fun setHeadsetPriority(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HEADSET_PRIORITY, enabled).apply()
    }

    fun getMicGain(context: Context): Float {
        return getPrefs(context).getFloat(KEY_MIC_GAIN, DEFAULT_MIC_GAIN).coerceIn(0.10f, 5.0f)
    }

    fun getSpkGain(context: Context): Float {
        return getPrefs(context).getFloat(KEY_SPK_GAIN, DEFAULT_SPK_GAIN).coerceIn(0.20f, 2.5f)
    }

    fun setMicGain(context: Context, gain: Float) {
        getPrefs(context).edit().putFloat(KEY_MIC_GAIN, gain.coerceIn(0.10f, 5.0f)).apply()
    }

    fun setSpkGain(context: Context, gain: Float) {
        getPrefs(context).edit().putFloat(KEY_SPK_GAIN, gain.coerceIn(0.20f, 2.5f)).apply()
    }

    fun getTotSeconds(context: Context): Int {
        return getPrefs(context).getInt(KEY_TOT_SECONDS, DEFAULT_TOT_SECONDS)
    }

    fun setTotSeconds(context: Context, seconds: Int) {
        getPrefs(context).edit().putInt(KEY_TOT_SECONDS, seconds.coerceAtLeast(0)).apply()
    }
}
