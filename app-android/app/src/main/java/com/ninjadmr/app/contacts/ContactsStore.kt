package com.ninjadmr.app.contacts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved talkgroup / channel preset. */
data class DmrContact(
    val id: String = "",
    val callsign: String = "",
    val name: String = "",
    val talkgroup: String = "",
    val favourite: Boolean = false,
    val isBuddy: Boolean = false,
    val lastSeen: Long = 0L,
) {
    fun label(): String {
        return if (name.isNotBlank()) name else "TG $talkgroup"
    }
}

/**
 * Persists user-defined Talkgroups & Channels.
 * Callbook users are kept separate in CallbookStore for RX Caller ID resolution.
 */
class ContactsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadAll(): MutableList<DmrContact> {
        val raw = prefs.getString(KEY, null)
        if (raw.isNullOrBlank()) {
            val defaults = defaultChannels()
            saveAll(defaults)
            return defaults
        }
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<DmrContact>()
            for (idx in 0 until arr.length()) {
                val c = fromJson(arr.getJSONObject(idx))
                if (c.talkgroup.isNotBlank()) {
                    out.add(c)
                }
            }
            if (out.isEmpty()) {
                val defaults = defaultChannels()
                saveAll(defaults)
                defaults
            } else {
                out
            }
        } catch (_: Exception) {
            defaultChannels()
        }
    }

    fun defaultChannels(): MutableList<DmrContact> = mutableListOf(
        DmrContact(id = "1025", name = "BRIDI DIGITAL", talkgroup = "1025", favourite = true),
        DmrContact(id = "515", name = "NUSANTARA INDONESIA", talkgroup = "515", favourite = true),
        DmrContact(id = "9990", name = "PARROT ECHO TEST", talkgroup = "9990", favourite = false),
        DmrContact(id = "91", name = "WORLDWIDE DMR", talkgroup = "91", favourite = false),
        DmrContact(id = "1", name = "WORLDWIDE (TS1)", talkgroup = "1", favourite = false),
    )

    fun saveAll(list: List<DmrContact>) {
        val validOnly = list.filter { it.talkgroup.isNotBlank() }
        prefs.edit().putString(KEY, exportJson(validOnly)).apply()
    }

    fun clearAll() {
        prefs.edit().remove(KEY).apply()
    }

    fun resetToDefaults(): MutableList<DmrContact> {
        val defaults = defaultChannels()
        saveAll(defaults)
        return defaults
    }

    fun exportJson(list: List<DmrContact>): String {
        val arr = JSONArray()
        for (c in list) {
            if (c.talkgroup.isNotBlank()) {
                arr.put(toJson(c))
            }
        }
        return arr.toString(2)
    }

    private fun toJson(c: DmrContact): JSONObject = JSONObject().apply {
        put("id", c.id)
        put("callsign", c.callsign)
        put("name", c.name)
        put("talkgroup", c.talkgroup)
        put("favourite", c.favourite)
        put("isBuddy", c.isBuddy)
        put("lastSeen", c.lastSeen)
    }

    private fun fromJson(o: JSONObject): DmrContact = DmrContact(
        id = o.optString("id", ""),
        callsign = o.optString("callsign", ""),
        name = o.optString("name", ""),
        talkgroup = o.optString("talkgroup", ""),
        favourite = o.optBoolean("favourite", false),
        isBuddy = o.optBoolean("isBuddy", false),
        lastSeen = o.optLong("lastSeen", 0L),
    )

    companion object {
        private const val PREFS = "ninjadmr_contacts"
        private const val KEY = "contacts"
    }
}
