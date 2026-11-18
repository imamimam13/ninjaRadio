package com.ninjaradio.svx.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

class ServerManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("ninjasvx_servers", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_SERVERS = "servers_list"
        private const val KEY_ACTIVE_ID = "active_server_id"

        val DEFAULT_SERVERS = listOf(
            ServerConfig(
                id = "server-ninja",
                name = "NINJA",
                host = "43.173.13.117",
                port = 5300,
                callsign = "BD62BOT",
                password = "",
                defaultTg = 1025L,
                talkgroups = listOf(
                    TalkgroupConfig(id = "tg-1", label = "bridi", tg = 1025L),
                    TalkgroupConfig(id = "tg-2", label = "ninja", tg = 510990L),
                ),
                followQsy = true,
            ),
            ServerConfig(
                id = "server-cs7ble",
                name = "CS7BLE",
                host = "vps.ngen.org",
                port = 5300,
                callsign = "CS7BLE",
                password = "",
                defaultTg = 268L,
                talkgroups = listOf(
                    TalkgroupConfig(id = "tg-3", label = "CS7BLE", tg = 268L),
                ),
                followQsy = true,
            ),
        )
    }

    fun getServers(): List<ServerConfig> {
        val jsonStr = prefs.getString(KEY_SERVERS, null) ?: return saveServers(DEFAULT_SERVERS)
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<ServerConfig>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)

                val tgs = mutableListOf<TalkgroupConfig>()
                if (obj.has("talkgroups")) {
                    val tgArray = obj.getJSONArray("talkgroups")
                    for (j in 0 until tgArray.length()) {
                        val tgObj = tgArray.getJSONObject(j)
                        tgs.add(
                            TalkgroupConfig(
                                id = tgObj.optString("id"),
                                label = tgObj.optString("label"),
                                tg = tgObj.optLong("tg", 1025L),
                            ),
                        )
                    }
                } else {
                    val defaultTgVal = obj.optLong("defaultTg", 1025L)
                    tgs.add(TalkgroupConfig(label = "Default", tg = defaultTgVal))
                }

                list.add(
                    ServerConfig(
                        id = obj.optString("id"),
                        name = obj.optString("name"),
                        host = obj.optString("host"),
                        port = obj.optInt("port", 5300),
                        callsign = obj.optString("callsign"),
                        password = obj.optString("password"),
                        defaultTg = obj.optLong("defaultTg", 1025L),
                        talkgroups = tgs,
                        followQsy = obj.optBoolean("followQsy", true),
                    ),
                )
            }
            if (list.isEmpty()) saveServers(DEFAULT_SERVERS) else list
        } catch (_: Exception) {
            saveServers(DEFAULT_SERVERS)
        }
    }

    fun saveServers(servers: List<ServerConfig>): List<ServerConfig> {
        val array = JSONArray()
        for (s in servers) {
            val obj = JSONObject()
            obj.put("id", s.id)
            obj.put("name", s.name)
            obj.put("host", s.host)
            obj.put("port", s.port)
            obj.put("callsign", s.callsign)
            obj.put("password", s.password)
            obj.put("defaultTg", s.defaultTg)
            obj.put("followQsy", s.followQsy)

            val tgArray = JSONArray()
            for (tg in s.talkgroups) {
                val tgObj = JSONObject()
                tgObj.put("id", tg.id)
                tgObj.put("label", tg.label)
                tgObj.put("tg", tg.tg)
                tgArray.put(tgObj)
            }
            obj.put("talkgroups", tgArray)

            array.put(obj)
        }
        prefs.edit { putString(KEY_SERVERS, array.toString()) }
        return servers
    }

    fun getActiveServerId(): String {
        return prefs.getString(KEY_ACTIVE_ID, null) ?: DEFAULT_SERVERS.first().id
    }

    fun setActiveServerId(id: String) {
        prefs.edit { putString(KEY_ACTIVE_ID, id) }
    }

    fun getActiveServer(): ServerConfig {
        val servers = getServers()
        val activeId = getActiveServerId()
        return servers.find { it.id == activeId } ?: servers.firstOrNull() ?: DEFAULT_SERVERS.first()
    }

    fun addOrUpdateServer(server: ServerConfig) {
        val servers = getServers().toMutableList()
        val index = servers.indexOfFirst { it.id == server.id }
        if (index >= 0) {
            servers[index] = server
        } else {
            servers.add(server)
        }
        saveServers(servers)
    }

    fun deleteServer(serverId: String) {
        val servers = getServers().filterNot { it.id == serverId }
        saveServers(servers)
        if ((getActiveServerId() == serverId) && servers.isNotEmpty()) {
            setActiveServerId(servers.first().id)
        }
    }
}
