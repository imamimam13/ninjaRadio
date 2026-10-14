package com.ninjadmr.app.contacts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved contact/talkgroup shortcut. */
data class DmrContact(
    val id: String = "",
    val callsign: String = "",
    val name: String = "",
    val talkgroup: String = "",
    val favourite: Boolean = false,
) {
    fun label(): String {
        val base = if (name.isNotBlank()) "$name ($callsign)" else callsign
        return if (talkgroup.isNotBlank()) "$base → TG $talkgroup" else base
    }
}

/**
 * Persists contacts and favourites as JSON in SharedPreferences. A favourite is
 * also used as a scan-list entry when scanning is enabled.
 */
class ContactsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadAll(): MutableList<DmrContact> {
        val raw = prefs.getString(KEY, null) ?: return mutableListOf()
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<DmrContact>()
            for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    fun saveAll(list: List<DmrContact>) {
        prefs.edit().putString(KEY, exportJson(list)).apply()
    }

    companion object {
        private const val PREFS = "ninjadmr_contacts"
        private const val KEY = "contacts"

        fun exportJson(list: List<DmrContact>): String {
            val arr = JSONArray()
            for (c in list) {
                arr.put(
                    JSONObject().apply {
                        put("id", c.id)
                        put("callsign", c.callsign)
                        put("name", c.name)
                        put("talkgroup", c.talkgroup)
                        put("favourite", c.favourite)
                    },
                )
            }
            return arr.toString(2)
        }

        fun importJson(raw: String): List<DmrContact> {
            val arr = JSONArray(raw)
            val out = mutableListOf<DmrContact>()
            for (i in 0 until arr.length()) out.add(fromJson(arr.getJSONObject(i)))
            return out
        }

        private fun fromJson(o: JSONObject): DmrContact = DmrContact(
            id = o.optString("id", ""),
            callsign = o.optString("callsign", ""),
            name = o.optString("name", ""),
            talkgroup = o.optString("talkgroup", ""),
            favourite = o.optBoolean("favourite", false),
        )
    }
}
