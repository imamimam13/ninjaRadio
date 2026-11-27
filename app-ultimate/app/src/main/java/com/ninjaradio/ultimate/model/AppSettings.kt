package com.ninjaradio.ultimate.model

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    private const val PREFS_NAME = "ninja_ultimate_settings"

    const val KEY_MIC_GAIN_DB = "mic_gain_db"
    const val KEY_RX_GAIN_DB = "rx_gain_db"
    const val KEY_AUDIO_ROUTE = "audio_route"
    const val KEY_VOX_ENABLED = "vox_enabled"
    const val KEY_VOX_SENSITIVITY = "vox_sensitivity"
    const val KEY_VOX_HANGOVER = "vox_hangover"
    const val KEY_ROGER_BEEP = "roger_beep"
    const val KEY_KEY_BEEP = "key_beep"
    const val KEY_TOT_SECONDS = "tot_seconds"
    const val KEY_HIDE_SCREEN_PTT = "hide_screen_ptt"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isHideScreenPtt(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HIDE_SCREEN_PTT, false)
    }

    fun setHideScreenPtt(context: Context, hide: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HIDE_SCREEN_PTT, hide).apply()
    }

    fun getMicGainDb(context: Context): Int {
        return getPrefs(context).getInt(KEY_MIC_GAIN_DB, 6) // Default +6dB boost
    }

    fun setMicGainDb(context: Context, value: Int) {
        getPrefs(context).edit().putInt(KEY_MIC_GAIN_DB, value).apply()
    }

    fun getRxGainDb(context: Context): Int {
        return getPrefs(context).getInt(KEY_RX_GAIN_DB, 0)
    }

    fun setRxGainDb(context: Context, value: Int) {
        getPrefs(context).edit().putInt(KEY_RX_GAIN_DB, value).apply()
    }

    fun getAudioRoute(context: Context): String {
        return getPrefs(context).getString(KEY_AUDIO_ROUTE, "SPEAKERPHONE") ?: "SPEAKERPHONE"
    }

    fun setAudioRoute(context: Context, route: String) {
        getPrefs(context).edit().putString(KEY_AUDIO_ROUTE, route).apply()
    }

    fun isVoxEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_VOX_ENABLED, false)
    }

    fun setVoxEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VOX_ENABLED, enabled).apply()
    }

    fun getVoxSensitivity(context: Context): Int {
        return getPrefs(context).getInt(KEY_VOX_SENSITIVITY, 25) // 25% default
    }

    fun setVoxSensitivity(context: Context, threshold: Int) {
        getPrefs(context).edit().putInt(KEY_VOX_SENSITIVITY, threshold).apply()
    }

    fun getVoxHangoverMs(context: Context): Long {
        return getPrefs(context).getLong(KEY_VOX_HANGOVER, 450L)
    }

    fun setVoxHangoverMs(context: Context, ms: Long) {
        getPrefs(context).edit().putLong(KEY_VOX_HANGOVER, ms).apply()
    }

    fun isRogerBeep(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ROGER_BEEP, true)
    }

    fun setRogerBeep(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_ROGER_BEEP, enabled).apply()
    }

    fun isKeyBeep(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_KEY_BEEP, true)
    }

    fun setKeyBeep(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_KEY_BEEP, enabled).apply()
    }

    fun getTotSeconds(context: Context): Int {
        return getPrefs(context).getInt(KEY_TOT_SECONDS, 120) // Default 120 detik
    }

    fun setTotSeconds(context: Context, seconds: Int) {
        getPrefs(context).edit().putInt(KEY_TOT_SECONDS, seconds).apply()
    }
}
