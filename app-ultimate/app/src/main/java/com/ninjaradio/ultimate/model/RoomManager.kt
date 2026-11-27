package com.ninjaradio.ultimate.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Dynamic per-server room & channel manager.
 * Stores and manages user-customized rooms for TeamSpeak 3, Zello, Mumble, and RoIP servers
 * without forcing third-party hardcoded channel presets.
 */
object RoomManager {
    private const val PREFS_NAME = "ninja_radio_rooms"

    fun loadRooms(context: Context, host: String, type: ServerType): MutableList<RadioRoom> {
        val cleanHost = host.trim().lowercase()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "rooms_${type.name}_$cleanHost"
        val jsonStr = prefs.getString(key, null)
        val list = mutableListOf<RadioRoom>()

        if (!jsonStr.isNullOrEmpty()) {
            try {
                val arr = JSONArray(jsonStr)
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(RadioRoom(
                        id = o.optString("id", "room_$i"),
                        name = o.optString("name", ""),
                        targetTg = o.optLong("targetTg", 0L),
                        totalClients = o.optInt("totalClients", 0),
                        isPasswordProtected = o.optBoolean("isPasswordProtected", false),
                        description = o.optString("description", "")
                    ))
                }
            } catch (_: Exception) {}
        }

        // Clean out legacy hardcoded DMR talkgroup presets mistakenly loaded for TS3/Mumble/Zello
        if (type == ServerType.TS3 || type == ServerType.MUMBLE || type == ServerType.ZELLO) {
            val hadLegacy = list.removeAll { room ->
                room.description.contains("DMR TG", ignoreCase = true) ||
                room.description.contains("Slot ", ignoreCase = true) ||
                (room.targetTg > 0L && room.name.startsWith("TG "))
            }
            if (hadLegacy) {
                saveRooms(context, host, type, list)
            }
        }

        // Default initial room if none stored yet
        if (list.isEmpty()) {
            val defName = when (type) {
                ServerType.ZELLO -> "Main"
                ServerType.TS3 -> "Lobby"
                ServerType.MUMBLE -> "Root"
                else -> "LOBBY"
            }
            list.add(RadioRoom(
                id = "def_1",
                name = defName,
                targetTg = 0L,
                description = "Ruang Utama / Default"
            ))
            saveRooms(context, host, type, list)
        }

        return list
    }

    fun clearRooms(context: Context, host: String, type: ServerType) {
        val cleanHost = host.trim().lowercase()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "rooms_${type.name}_$cleanHost"
        prefs.edit().remove(key).apply()
    }

    fun saveRooms(context: Context, host: String, type: ServerType, rooms: List<RadioRoom>) {
        val cleanHost = host.trim().lowercase()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = "rooms_${type.name}_$cleanHost"
        val arr = JSONArray()
        for (r in rooms) {
            val o = JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("targetTg", r.targetTg)
                put("totalClients", r.totalClients)
                put("isPasswordProtected", r.isPasswordProtected)
                put("description", r.description)
            }
            arr.put(o)
        }
        prefs.edit().putString(key, arr.toString()).apply()
    }

    fun addOrUpdateRoom(context: Context, host: String, type: ServerType, room: RadioRoom) {
        val list = loadRooms(context, host, type)
        val existingIdx = list.indexOfFirst { it.name.equals(room.name.trim(), ignoreCase = true) }
        if (existingIdx >= 0) {
            list[existingIdx] = room
        } else {
            list.add(0, room)
        }
        saveRooms(context, host, type, list)
    }

    fun deleteRoom(context: Context, host: String, type: ServerType, roomName: String) {
        val list = loadRooms(context, host, type)
        list.removeAll { it.name.equals(roomName.trim(), ignoreCase = true) }
        saveRooms(context, host, type, list)
    }
}
