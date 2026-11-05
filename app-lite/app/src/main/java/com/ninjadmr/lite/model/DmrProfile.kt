package com.ninjadmr.lite.model

import org.json.JSONObject
import java.util.UUID

data class DmrProfile(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var host: String,
    var port: Int = 62031,
    var password: String = "",
    var callsign: String = "",
    var dmrId: Int = 0,
    var talkgroup: Int = 0,
    var slot: Int = 2,
    var essid: Int = 1,
    var options: String = "",
    var rxGroup: String = "", // Multi TG: "*" for All/Promiscuous, or comma-separated e.g. "1025,91,510"
    var location: String = "Mobile, ID",
    var software: String = "NinjaRadio Lite",
    var hardware: String = "Android POC",
    var description: String = "NinjaRadio Lite"
) {
    val peerId: Int
        get() = if (dmrId > 0) {
            if (essid > 0) {
                ((dmrId.toLong() * 100) + (essid.coerceIn(0, 99))).toInt()
            } else {
                dmrId
            }
        } else 0

    fun toJson(): JSONObject {
        val json = JSONObject()
        json.put("id", id)
        json.put("name", name)
        json.put("host", host)
        json.put("port", port)
        json.put("password", password)
        json.put("callsign", callsign)
        json.put("dmrId", dmrId)
        json.put("talkgroup", talkgroup)
        json.put("slot", slot)
        json.put("essid", essid)
        json.put("options", options)
        json.put("rxGroup", rxGroup)
        json.put("location", location)
        json.put("software", software)
        json.put("hardware", hardware)
        json.put("description", description)
        return json
    }

    companion object {
        fun fromJson(json: JSONObject): DmrProfile {
            return DmrProfile(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.optString("name", "Profile"),
                host = json.optString("host", ""),
                port = json.optInt("port", 62031),
                password = json.optString("password", ""),
                callsign = json.optString("callsign", ""),
                dmrId = json.optInt("dmrId", 0),
                talkgroup = json.optInt("talkgroup", 0),
                slot = json.optInt("slot", 2),
                essid = json.optInt("essid", 1),
                options = json.optString("options", ""),
                rxGroup = json.optString("rxGroup", ""),
                location = json.optString("location", "Mobile, ID"),
                software = json.optString("software", "NinjaRadio Lite"),
                hardware = json.optString("hardware", "Android POC"),
                description = json.optString("description", "NinjaRadio Lite")
            )
        }
    }
}
