package com.ninjaradio.svx.data

import android.content.Context
import android.content.SharedPreferences

data class AppSettingsData(
    val pttMode: String = "Latch",             // "Latch" or "Push-to-Talk"
    val maxTxTimeSeconds: Int = 90,           // 30, 60, 90, 120, 180, 0 (Disabled)
    val timeoutWarning: Boolean = true,
    val warningTimeSeconds: Int = 15,         // 5, 10, 15, 30
    val audioOutput: String = "Loudspeaker",  // "Loudspeaker" or "Earpiece"
    val micVolume: Float = 1.0f,              // 0.0 to 2.0
    val rxVolume: Float = 1.0f,               // 0.0 to 2.0
    val autoConnect: Boolean = false,
    val keepScreenOn: Boolean = true,
    val accentColor: String = "Blue",          // "Blue", "Green", "Red", "Purple"
)

class AppSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("voxlink_settings", Context.MODE_PRIVATE)

    fun load(): AppSettingsData {
        return AppSettingsData(
            pttMode = prefs.getString("pttMode", "Latch") ?: "Latch",
            maxTxTimeSeconds = prefs.getInt("maxTxTimeSeconds", 90),
            timeoutWarning = prefs.getBoolean("timeoutWarning", true),
            warningTimeSeconds = prefs.getInt("warningTimeSeconds", 15),
            audioOutput = prefs.getString("audioOutput", "Loudspeaker") ?: "Loudspeaker",
            micVolume = prefs.getFloat("micVolume", 1.0f),
            rxVolume = prefs.getFloat("rxVolume", 1.0f),
            autoConnect = prefs.getBoolean("autoConnect", false),
            keepScreenOn = prefs.getBoolean("keepScreenOn", true),
            accentColor = prefs.getString("accentColor", "Blue") ?: "Blue",
        )
    }

    fun save(settings: AppSettingsData) {
        prefs.edit()
            .putString("pttMode", settings.pttMode)
            .putInt("maxTxTimeSeconds", settings.maxTxTimeSeconds)
            .putBoolean("timeoutWarning", settings.timeoutWarning)
            .putInt("warningTimeSeconds", settings.warningTimeSeconds)
            .putString("audioOutput", settings.audioOutput)
            .putFloat("micVolume", settings.micVolume)
            .putFloat("rxVolume", settings.rxVolume)
            .putBoolean("autoConnect", settings.autoConnect)
            .putBoolean("keepScreenOn", settings.keepScreenOn)
            .putString("accentColor", settings.accentColor)
            .apply()
    }
}
