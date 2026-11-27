package com.ninjaradio.ultimate.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class ServerType(val displayName: String, val defaultPort: Int, val defaultCodec: String) {
    DMR_DIRECT("Direct DMR (HomeBrew)", 62031, "AMBE+2 / OPUS"),
    SVXLINK("SvxLink Reflector (Opus)", 5300, "OPUS 16kHz"),
    ALLSTAR("AllStarLink (USRP RoIP)", 32345, "PCM / USRP"),
    ECHOLINK("EchoLink (RoIP Bridge)", 5198, "GSM / OPUS"),
    TS3("TeamSpeak 3 (Native TS3)", 9987, "OPUS 48kHz"),
    MUMBLE("Mumble (Native TLS + Opus)", 64738, "OPUS 48kHz"),
    ZELLO("Zello (Native WSS)", 443, "OPUS 16kHz"),
    CUSTOM_POC("Custom PoC (Opus UDP)", 12345, "OPUS 8kHz")
}

data class ServerProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    var name: String,
    var type: ServerType,
    var host: String,
    var port: Int,
    var callsign: String,
    var password: String = "",
    var dmrId: Int = 1022057,
    var isDefault: Boolean = false,
    var customModel: String = "Hytera PDC680 BRIDI"
) {
    fun toJson(): JSONObject {
        val obj = JSONObject()
        obj.put("id", id)
        obj.put("name", name)
        obj.put("type", type.name)
        obj.put("host", host)
        obj.put("port", port)
        obj.put("callsign", callsign)
        obj.put("password", password)
        obj.put("dmrId", dmrId)
        obj.put("isDefault", isDefault)
        obj.put("customModel", customModel)
        return obj
    }

    companion object {
        fun fromJson(obj: JSONObject): ServerProfile {
            val typeStr = obj.optString("type", ServerType.DMR_DIRECT.name)
            val type = try { ServerType.valueOf(typeStr) } catch (_: Exception) { ServerType.DMR_DIRECT }
            val rawHost = obj.optString("host", "").replace(" ", "").trim()
            val rawCall = obj.optString("callsign", "").trim().let { if (type == ServerType.DMR_DIRECT) it.uppercase() else it }
            val rawPort = obj.optInt("port", type.defaultPort)
            val effectivePort = if (rawPort > 0) rawPort else type.defaultPort
            return ServerProfile(
                id = obj.optString("id", java.util.UUID.randomUUID().toString()),
                name = obj.optString("name", "Server Baru"),
                type = type,
                host = rawHost,
                port = effectivePort,
                callsign = rawCall,
                password = obj.optString("password", "").trim(),
                dmrId = obj.optInt("dmrId", 0),
                isDefault = obj.optBoolean("isDefault", false),
                customModel = obj.optString("customModel", "Hytera PDC680 BRIDI")
            )
        }
    }
}

object ProfileStore {
    private const val PREFS_NAME = "ninja_ultimate_profiles"
    private const val KEY_PROFILES = "saved_profiles"
    private const val KEY_ACTIVE_ID = "active_profile_id"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun loadProfiles(context: Context): MutableList<ServerProfile> {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_PROFILES, null)
        val list = mutableListOf<ServerProfile>()

        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    list.add(ServerProfile.fromJson(array.getJSONObject(i)))
                }
            } catch (_: Exception) {}
        }

        if (list.isEmpty()) {
            // User fills in their own server details
            val initial = ServerProfile(
                name = "Server 1",
                type = ServerType.DMR_DIRECT,
                host = "",
                port = 62031,
                callsign = "",
                password = "",
                dmrId = 0,
                isDefault = true
            )
            list.add(initial)
            saveProfiles(context, list)
        }
        return list
    }

    fun saveProfiles(context: Context, profiles: List<ServerProfile>) {
        val array = JSONArray()
        for (p in profiles) {
            array.put(p.toJson())
        }
        getPrefs(context).edit().putString(KEY_PROFILES, array.toString()).apply()
    }

    fun addOrUpdateProfile(context: Context, profile: ServerProfile) {
        val list = loadProfiles(context)
        val idx = list.indexOfFirst { it.id == profile.id }
        if (idx >= 0) {
            list[idx] = profile
        } else {
            list.add(profile)
        }
        saveProfiles(context, list)
    }

    fun deleteProfile(context: Context, id: String) {
        val list = loadProfiles(context)
        list.removeAll { it.id == id }
        if (list.isEmpty()) {
            list.add(ServerProfile(name = "Server 1", type = ServerType.DMR_DIRECT, host = "", port = 62031, callsign = ""))
        }
        saveProfiles(context, list)
    }

    fun getActiveProfile(context: Context): ServerProfile {
        val list = loadProfiles(context)
        val activeId = getPrefs(context).getString(KEY_ACTIVE_ID, null)
        return list.firstOrNull { it.id == activeId } ?: list.first()
    }

    fun setActiveProfile(context: Context, profile: ServerProfile) {
        getPrefs(context).edit().putString(KEY_ACTIVE_ID, profile.id).apply()
    }
}
