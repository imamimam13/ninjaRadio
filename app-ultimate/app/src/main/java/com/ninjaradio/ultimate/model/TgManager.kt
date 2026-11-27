package com.ninjaradio.ultimate.model

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class Talkgroup(
    var tg: String,
    var name: String,
    var slot: Int = 2
) {
    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("tg", tg)
        o.put("name", name)
        o.put("slot", slot)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Talkgroup {
            val rawTg = o.opt("tg")?.toString()?.trim() ?: "1025"
            val rawName = o.optString("name", "")
            val name = if (rawName.isNotBlank()) rawName else "TG $rawTg"
            return Talkgroup(
                tg = rawTg,
                name = name,
                slot = o.optInt("slot", 2)
            )
        }
    }
}

object TgManager {
    private const val PREFS_NAME = "ninja_ultimate_tgs"
    private const val KEY_TGS = "saved_tgs"
    private const val KEY_CURRENT_TG = "current_tg"

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun loadTalkgroups(context: Context): MutableList<Talkgroup> {
        val prefs = getPrefs(context)
        val jsonStr = prefs.getString(KEY_TGS, null)
        val list = mutableListOf<Talkgroup>()

        if (!jsonStr.isNullOrEmpty()) {
            try {
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    list.add(Talkgroup.fromJson(array.getJSONObject(i)))
                }
            } catch (_: Exception) {}
        }

        // Fully dynamic: no hardcoded presets forced on user
        return list
    }

    fun saveTalkgroups(context: Context, list: List<Talkgroup>) {
        val array = JSONArray()
        for (item in list) {
            array.put(item.toJson())
        }
        getPrefs(context).edit().putString(KEY_TGS, array.toString()).apply()
    }

    fun addOrUpdateTalkgroup(context: Context, tg: String, name: String, slot: Int = 2) {
        val cleanTg = tg.trim()
        if (cleanTg.isEmpty()) return
        val cleanName = if (name.isBlank()) "TG $cleanTg" else name.trim()

        val list = loadTalkgroups(context)
        val existingIdx = list.indexOfFirst { it.tg.equals(cleanTg, ignoreCase = true) }
        if (existingIdx >= 0) {
            list[existingIdx] = Talkgroup(cleanTg, cleanName, slot)
        } else {
            list.add(0, Talkgroup(cleanTg, cleanName, slot))
        }
        saveTalkgroups(context, list)
    }

    fun deleteTalkgroup(context: Context, tg: String) {
        val list = loadTalkgroups(context)
        list.removeAll { it.tg.equals(tg.trim(), ignoreCase = true) }
        saveTalkgroups(context, list)
    }

    fun getCurrentTg(context: Context): String {
        val prefs = getPrefs(context)
        return try {
            prefs.getString(KEY_CURRENT_TG, "") ?: ""
        } catch (_: ClassCastException) {
            val oldLong = prefs.getLong(KEY_CURRENT_TG, 0L)
            val str = if (oldLong > 0L) oldLong.toString() else ""
            prefs.edit().putString(KEY_CURRENT_TG, str).apply()
            str
        }
    }

    fun setCurrentTg(context: Context, tg: String) {
        val clean = tg.trim()
        getPrefs(context).edit().putString(KEY_CURRENT_TG, clean).apply()
    }

    fun getTgName(context: Context, tg: String): String {
        return loadTalkgroups(context).firstOrNull { it.tg.equals(tg.trim(), ignoreCase = true) }?.name ?: ""
    }
}
