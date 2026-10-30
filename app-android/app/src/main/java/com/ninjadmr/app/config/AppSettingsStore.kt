package com.ninjadmr.app.config

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf

/**
 * Stores global radio preferences, audio DSP settings, network options,
 * APRS beaconing, and POC small-screen / appearance configurations.
 * Fully reactive with Compose mutableStateOf properties.
 */
class AppSettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // -- Radio & PTT Settings --
    private val _pttMode = mutableStateOf(prefs.getString(KEY_PTT_MODE, "HOLD") ?: "HOLD")
    var pttMode: String
        get() = _pttMode.value
        set(value) {
            _pttMode.value = value
            prefs.edit().putString(KEY_PTT_MODE, value).apply()
        }

    private val _totSeconds = mutableIntStateOf(prefs.getInt(KEY_TOT_SECONDS, 60))
    var totSeconds: Int
        get() = _totSeconds.intValue
        set(value) {
            _totSeconds.intValue = value
            prefs.edit().putInt(KEY_TOT_SECONDS, value).apply()
        }

    private val _busyLock = mutableStateOf(prefs.getBoolean(KEY_BUSY_LOCK, false))
    var busyLock: Boolean
        get() = _busyLock.value
        set(value) {
            _busyLock.value = value
            prefs.edit().putBoolean(KEY_BUSY_LOCK, value).apply()
        }

    private val _permitTone = mutableStateOf(prefs.getBoolean(KEY_PERMIT_TONE, true))
    var permitTone: Boolean
        get() = _permitTone.value
        set(value) {
            _permitTone.value = value
            prefs.edit().putBoolean(KEY_PERMIT_TONE, value).apply()
        }

    private val _rogerBeep = mutableStateOf(prefs.getBoolean(KEY_ROGER_BEEP, true))
    var rogerBeep: Boolean
        get() = _rogerBeep.value
        set(value) {
            _rogerBeep.value = value
            prefs.edit().putBoolean(KEY_ROGER_BEEP, value).apply()
        }

    private val _keyTone = mutableStateOf(prefs.getBoolean(KEY_KEY_TONE, false))
    var keyTone: Boolean
        get() = _keyTone.value
        set(value) {
            _keyTone.value = value
            prefs.edit().putBoolean(KEY_KEY_TONE, value).apply()
        }

    private val _volumePtt = mutableStateOf(prefs.getBoolean(KEY_VOLUME_PTT, true))
    var volumePtt: Boolean
        get() = _volumePtt.value
        set(value) {
            _volumePtt.value = value
            prefs.edit().putBoolean(KEY_VOLUME_PTT, value).apply()
        }

    private val _talkWhileScreenLocked = mutableStateOf(prefs.getBoolean(KEY_TALK_WHILE_LOCKED, true))
    var talkWhileScreenLocked: Boolean
        get() = _talkWhileScreenLocked.value
        set(value) {
            _talkWhileScreenLocked.value = value
            prefs.edit().putBoolean(KEY_TALK_WHILE_LOCKED, value).apply()
        }

    // Hardware PTT Key configuration (0 = Any, 228 = Dedicated PTT, 25 = Vol-, 24 = Vol+, 79 = Headset, or custom)
    private val _hardwarePttKeyCode = mutableIntStateOf(prefs.getInt(KEY_HARDWARE_PTT_KEY_CODE, 0))
    var hardwarePttKeyCode: Int
        get() = _hardwarePttKeyCode.intValue
        set(value) {
            _hardwarePttKeyCode.intValue = value
            prefs.edit().putInt(KEY_HARDWARE_PTT_KEY_CODE, value).apply()
        }

    private val _hardwarePttKeyName = mutableStateOf(prefs.getString(KEY_HARDWARE_PTT_KEY_NAME, "ALL / ANY KEY") ?: "ALL / ANY KEY")
    var hardwarePttKeyName: String
        get() = _hardwarePttKeyName.value
        set(value) {
            _hardwarePttKeyName.value = value
            prefs.edit().putString(KEY_HARDWARE_PTT_KEY_NAME, value).apply()
        }

    // -- Audio & DSP Settings --
    private val _rxVolume = mutableFloatStateOf(prefs.getFloat(KEY_RX_VOLUME, 1.0f))
    var rxVolume: Float
        get() = _rxVolume.floatValue
        set(value) {
            _rxVolume.floatValue = value
            prefs.edit().putFloat(KEY_RX_VOLUME, value).apply()
        }

    private val _micGain = mutableFloatStateOf(prefs.getFloat(KEY_MIC_GAIN, 1.0f))
    var micGain: Float
        get() = _micGain.floatValue
        set(value) {
            _micGain.floatValue = value
            prefs.edit().putFloat(KEY_MIC_GAIN, value).apply()
        }

    private val _agcEnabled = mutableStateOf(prefs.getBoolean(KEY_AGC_ENABLED, true))
    var agcEnabled: Boolean
        get() = _agcEnabled.value
        set(value) {
            _agcEnabled.value = value
            prefs.edit().putBoolean(KEY_AGC_ENABLED, value).apply()
        }

    private val _noiseReduction = mutableStateOf(prefs.getBoolean(KEY_NOISE_REDUCTION, true))
    var noiseReduction: Boolean
        get() = _noiseReduction.value
        set(value) {
            _noiseReduction.value = value
            prefs.edit().putBoolean(KEY_NOISE_REDUCTION, value).apply()
        }

    private val _audioRouting = mutableStateOf(prefs.getString(KEY_AUDIO_ROUTING, "SPEAKER") ?: "SPEAKER")
    var audioRouting: String
        get() = _audioRouting.value
        set(value) {
            _audioRouting.value = value
            prefs.edit().putString(KEY_AUDIO_ROUTING, value).apply()
        }

    // -- Network Settings --
    private val _autoConnect = mutableStateOf(prefs.getBoolean(KEY_AUTO_CONNECT, false))
    var autoConnect: Boolean
        get() = _autoConnect.value
        set(value) {
            _autoConnect.value = value
            prefs.edit().putBoolean(KEY_AUTO_CONNECT, value).apply()
        }

    private val _autoReconnect = mutableStateOf(prefs.getBoolean(KEY_AUTO_RECONNECT, true))
    var autoReconnect: Boolean
        get() = _autoReconnect.value
        set(value) {
            _autoReconnect.value = value
            prefs.edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()
        }

    private val _reconnectRetries = mutableIntStateOf(prefs.getInt(KEY_RECONNECT_RETRIES, 5))
    var reconnectRetries: Int
        get() = _reconnectRetries.intValue
        set(value) {
            _reconnectRetries.intValue = value
            prefs.edit().putInt(KEY_RECONNECT_RETRIES, value).apply()
        }

    private val _kerchunkConnect = mutableStateOf(prefs.getBoolean(KEY_KERCHUNK_CONNECT, true))
    var kerchunkConnect: Boolean
        get() = _kerchunkConnect.value
        set(value) {
            _kerchunkConnect.value = value
            prefs.edit().putBoolean(KEY_KERCHUNK_CONNECT, value).apply()
        }

    private val _unlinkTg4000 = mutableStateOf(prefs.getBoolean(KEY_UNLINK_TG4000, true))
    var unlinkTg4000: Boolean
        get() = _unlinkTg4000.value
        set(value) {
            _unlinkTg4000.value = value
            prefs.edit().putBoolean(KEY_UNLINK_TG4000, value).apply()
        }

    private val _sendLocationOnConnect = mutableStateOf(prefs.getBoolean(KEY_SEND_LOCATION_CONNECT, true))
    var sendLocationOnConnect: Boolean
        get() = _sendLocationOnConnect.value
        set(value) {
            _sendLocationOnConnect.value = value
            prefs.edit().putBoolean(KEY_SEND_LOCATION_CONNECT, value).apply()
        }

    // -- APRS & GPS Beaconing --
    private val _aprsEnabled = mutableStateOf(prefs.getBoolean(KEY_APRS_ENABLED, false))
    var aprsEnabled: Boolean
        get() = _aprsEnabled.value
        set(value) {
            _aprsEnabled.value = value
            prefs.edit().putBoolean(KEY_APRS_ENABLED, value).apply()
        }

    private val _aprsSsid = mutableStateOf(prefs.getString(KEY_APRS_SSID, "-7") ?: "-7")
    var aprsSsid: String
        get() = _aprsSsid.value
        set(value) {
            _aprsSsid.value = value
            prefs.edit().putString(KEY_APRS_SSID, value).apply()
        }

    private val _aprsIntervalMinutes = mutableIntStateOf(prefs.getInt(KEY_APRS_INTERVAL, 10))
    var aprsIntervalMinutes: Int
        get() = _aprsIntervalMinutes.intValue
        set(value) {
            _aprsIntervalMinutes.intValue = value
            prefs.edit().putInt(KEY_APRS_INTERVAL, value).apply()
        }

    private val _aprsComment = mutableStateOf(prefs.getString(KEY_APRS_COMMENT, "NinjaRadio DMR POC Mobile") ?: "NinjaRadio DMR POC Mobile")
    var aprsComment: String
        get() = _aprsComment.value
        set(value) {
            _aprsComment.value = value
            prefs.edit().putString(KEY_APRS_COMMENT, value).apply()
        }

    private val _aprsSymbol = mutableStateOf(prefs.getString(KEY_APRS_SYMBOL, "/[") ?: "/[")
    var aprsSymbol: String
        get() = _aprsSymbol.value
        set(value) {
            _aprsSymbol.value = value
            prefs.edit().putString(KEY_APRS_SYMBOL, value).apply()
        }

    // -- Appearance & POC Screen Optimization --
    private val _themePalette = mutableStateOf(prefs.getString(KEY_THEME_PALETTE, "CYBER_HOLO") ?: "CYBER_HOLO")
    var themePalette: String
        get() = _themePalette.value
        set(value) {
            _themePalette.value = value
            prefs.edit().putString(KEY_THEME_PALETTE, value).apply()
        }

    private val _pocCompactMode = mutableStateOf(prefs.getBoolean(KEY_POC_COMPACT_MODE, true))
    var pocCompactMode: Boolean
        get() = _pocCompactMode.value
        set(value) {
            _pocCompactMode.value = value
            prefs.edit().putBoolean(KEY_POC_COMPACT_MODE, value).apply()
        }

    private val _showOnScreenPtt = mutableStateOf(prefs.getBoolean(KEY_SHOW_ON_SCREEN_PTT, true))
    var showOnScreenPtt: Boolean
        get() = _showOnScreenPtt.value
        set(value) {
            _showOnScreenPtt.value = value
            prefs.edit().putBoolean(KEY_SHOW_ON_SCREEN_PTT, value).apply()
        }

    private val _compactPttBar = mutableStateOf(prefs.getBoolean(KEY_COMPACT_PTT_BAR, true))
    var compactPttBar: Boolean
        get() = _compactPttBar.value
        set(value) {
            _compactPttBar.value = value
            prefs.edit().putBoolean(KEY_COMPACT_PTT_BAR, value).apply()
        }

    private val _showActivityLog = mutableStateOf(prefs.getBoolean(KEY_SHOW_ACTIVITY_LOG, true))
    var showActivityLog: Boolean
        get() = _showActivityLog.value
        set(value) {
            _showActivityLog.value = value
            prefs.edit().putBoolean(KEY_SHOW_ACTIVITY_LOG, value).apply()
        }

    private val _showSpectrum = mutableStateOf(prefs.getBoolean(KEY_SHOW_SPECTRUM, true))
    var showSpectrum: Boolean
        get() = _showSpectrum.value
        set(value) {
            _showSpectrum.value = value
            prefs.edit().putBoolean(KEY_SHOW_SPECTRUM, value).apply()
        }

    private val _keepScreenAwake = mutableStateOf(prefs.getBoolean(KEY_KEEP_AWAKE, false))
    var keepScreenAwake: Boolean
        get() = _keepScreenAwake.value
        set(value) {
            _keepScreenAwake.value = value
            prefs.edit().putBoolean(KEY_KEEP_AWAKE, value).apply()
        }

    // -- DMR Data / RadioID Database --
    private val _dmrDbUrl = mutableStateOf(prefs.getString(KEY_DMR_DB_URL, "https://43.173.13.117.sslip.io/callbook/bridi.user.json") ?: "https://43.173.13.117.sslip.io/callbook/bridi.user.json")
    var dmrDbUrl: String
        get() = _dmrDbUrl.value
        set(value) {
            _dmrDbUrl.value = value
            prefs.edit().putString(KEY_DMR_DB_URL, value).apply()
        }

    private val _autoUpdateDb = mutableStateOf(prefs.getBoolean(KEY_AUTO_UPDATE_DB, false))
    var autoUpdateDb: Boolean
        get() = _autoUpdateDb.value
        set(value) {
            _autoUpdateDb.value = value
            prefs.edit().putBoolean(KEY_AUTO_UPDATE_DB, value).apply()
        }

    private val _lastDbUpdateTimestamp = mutableLongStateOf(prefs.getLong(KEY_LAST_DB_UPDATE, 0L))
    var lastDbUpdateTimestamp: Long
        get() = _lastDbUpdateTimestamp.longValue
        set(value) {
            _lastDbUpdateTimestamp.longValue = value
            prefs.edit().putLong(KEY_LAST_DB_UPDATE, value).apply()
        }

    // -- DMR Station Broadcast Info --
    private val _stationHardware = mutableStateOf(prefs.getString(KEY_STATION_HARDWARE, "NinjaRadio") ?: "NinjaRadio")
    var stationHardware: String
        get() = _stationHardware.value
        set(value) {
            _stationHardware.value = value
            prefs.edit().putString(KEY_STATION_HARDWARE, value).apply()
        }

    private val _stationSoftware = mutableStateOf(prefs.getString(KEY_STATION_SOFTWARE, "NinjaRadio") ?: "NinjaRadio")
    var stationSoftware: String
        get() = _stationSoftware.value
        set(value) {
            _stationSoftware.value = value
            prefs.edit().putString(KEY_STATION_SOFTWARE, value).apply()
        }

    private val _stationLocation = mutableStateOf(prefs.getString(KEY_STATION_LOCATION, "Mobile, ID") ?: "Mobile, ID")
    var stationLocation: String
        get() = _stationLocation.value
        set(value) {
            _stationLocation.value = value
            prefs.edit().putString(KEY_STATION_LOCATION, value).apply()
        }

    private val _stationDescription = mutableStateOf(prefs.getString(KEY_STATION_DESCRIPTION, "NinjaDMR") ?: "NinjaDMR")
    var stationDescription: String
        get() = _stationDescription.value
        set(value) {
            _stationDescription.value = value
            prefs.edit().putString(KEY_STATION_DESCRIPTION, value).apply()
        }

    private val _stationUrl = mutableStateOf(prefs.getString(KEY_STATION_URL, "https://ninjadmr.app") ?: "https://ninjadmr.app")
    var stationUrl: String
        get() = _stationUrl.value
        set(value) {
            _stationUrl.value = value
            prefs.edit().putString(KEY_STATION_URL, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "ninjadmr_app_settings"

        private const val KEY_PTT_MODE = "ptt_mode"
        private const val KEY_TOT_SECONDS = "tot_seconds"
        private const val KEY_BUSY_LOCK = "busy_lock"
        private const val KEY_PERMIT_TONE = "permit_tone"
        private const val KEY_ROGER_BEEP = "roger_beep"
        private const val KEY_KEY_TONE = "key_tone"
        private const val KEY_VOLUME_PTT = "volume_ptt"
        private const val KEY_TALK_WHILE_LOCKED = "talk_while_locked"
        private const val KEY_HARDWARE_PTT_KEY_CODE = "hardware_ptt_key_code"
        private const val KEY_HARDWARE_PTT_KEY_NAME = "hardware_ptt_key_name"

        private const val KEY_RX_VOLUME = "rx_volume"
        private const val KEY_MIC_GAIN = "mic_gain"
        private const val KEY_AGC_ENABLED = "agc_enabled"
        private const val KEY_NOISE_REDUCTION = "noise_reduction"
        private const val KEY_AUDIO_ROUTING = "audio_routing"

        private const val KEY_AUTO_CONNECT = "auto_connect"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private const val KEY_RECONNECT_RETRIES = "reconnect_retries"
        private const val KEY_KERCHUNK_CONNECT = "kerchunk_connect"
        private const val KEY_UNLINK_TG4000 = "unlink_tg4000"
        private const val KEY_SEND_LOCATION_CONNECT = "send_location_connect"

        private const val KEY_APRS_ENABLED = "aprs_enabled"
        private const val KEY_APRS_SSID = "aprs_ssid"
        private const val KEY_APRS_INTERVAL = "aprs_interval"
        private const val KEY_APRS_COMMENT = "aprs_comment"
        private const val KEY_APRS_SYMBOL = "aprs_symbol"

        private const val KEY_THEME_PALETTE = "theme_palette"
        private const val KEY_POC_COMPACT_MODE = "poc_compact_mode"
        private const val KEY_SHOW_ON_SCREEN_PTT = "show_on_screen_ptt"
        private const val KEY_COMPACT_PTT_BAR = "compact_ptt_bar"
        private const val KEY_SHOW_ACTIVITY_LOG = "show_activity_log"
        private const val KEY_SHOW_SPECTRUM = "show_spectrum"
        private const val KEY_KEEP_AWAKE = "keep_awake"

        private const val KEY_DMR_DB_URL = "dmr_db_url"
        private const val KEY_AUTO_UPDATE_DB = "auto_update_db"
        private const val KEY_LAST_DB_UPDATE = "last_db_update"
        private const val KEY_STATION_HARDWARE = "station_hardware"
        private const val KEY_STATION_SOFTWARE = "station_software"
        private const val KEY_STATION_LOCATION = "station_location"
        private const val KEY_STATION_DESCRIPTION = "station_description"
        private const val KEY_STATION_URL = "station_url"
    }
}
