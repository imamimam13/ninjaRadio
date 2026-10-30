package com.ninjadmr.app.config

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the list of [RadioProfile]s (and the selected index) in
 * SharedPreferences as JSON.
 */
class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadAll(): MutableList<RadioProfile> {
        val raw = prefs.getString(KEY_PROFILES, null) ?: return mutableListOf(RadioProfile())
        return try {
            val list = importJson(raw)
            if (list.isEmpty()) mutableListOf(RadioProfile()) else list.toMutableList()
        } catch (_: Exception) {
            mutableListOf(RadioProfile())
        }
    }

    fun saveAll(list: List<RadioProfile>) {
        prefs.edit().putString(KEY_PROFILES, exportJson(list)).apply()
    }

    var selectedIndex: Int
        get() = prefs.getInt(KEY_SELECTED, 0)
        set(value) = prefs.edit().putInt(KEY_SELECTED, value).apply()

    companion object {
        private const val PREFS = "ninjadmr_profiles"
        private const val KEY_PROFILES = "profiles"
        private const val KEY_SELECTED = "selected"

        fun exportJson(list: List<RadioProfile>): String {
            val arr = JSONArray()
            for (p in list) arr.put(toJson(p))
            return arr.toString(2)
        }

        fun importJson(raw: String): List<RadioProfile> {
            val arr = JSONArray(raw)
            val out = mutableListOf<RadioProfile>()
            for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
            return out
        }

        private fun toJson(p: RadioProfile): JSONObject = JSONObject().apply {
            put("name", p.name)
            put("channelName", p.channelName)
            put("protocol", p.protocol)
            put("server", p.server)
            put("port", p.port)
            put("dmrId", p.dmrId)
            put("callsign", p.callsign)
            put("essid", p.essid)
            put("talkgroup", p.talkgroup)
            put("slot", p.slot)
            put("colorCode", p.colorCode)
            put("zone", p.zone)
            put("frequency", p.frequency)
            put("quickTgs", p.quickTgs)
        }

        private fun fromJson(o: JSONObject): RadioProfile = RadioProfile(
            name = o.optString("name", "default"),
            channelName = o.optString("channelName", "LOCAL TACTICAL"),
            protocol = o.optString("protocol", "homebrew"),
            server = o.optString("server", "43.173.13.117").let {
                if (it.contains("sslip.io")) "43.173.13.117" else it
            },
            port = o.optString("port", "62301"),
            dmrId = o.optString("dmrId", "1022057"),
            callsign = o.optString("callsign", "BD62BOT"),
            essid = o.optString("essid", ""),
            talkgroup = o.optString("talkgroup", "1025"),
            slot = o.optString("slot", "2"),
            colorCode = o.optString("colorCode", "1"),
            zone = o.optString("zone", "NUSANTARA LINK A"),
            frequency = o.optString("frequency", "438.50000"),
            quickTgs = o.optString("quickTgs", "9990,1,515,1025"),
        )
    }
}
