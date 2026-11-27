package com.ninjaradio.ultimate.protocol.dmr

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Rich Universal Active Talker Profile.
 * Contains callsign, full name, location/city, DMR radio ID, talkgroup, and operating mode.
 */
data class TalkerProfile(
    val callsign: String,
    val name: String = "",
    val city: String = "",
    val dmrId: Int = 0,
    val talkgroup: String = "",
    val mode: String = ""
) {
    fun getDisplayName(): String {
        val cleanCs = callsign.trim().uppercase()
        val cleanName = name.trim().removePrefix("-").trim()
        return if (cleanName.isNotBlank() && !cleanName.equals(cleanCs, ignoreCase = true) && cleanName != "-") {
            "$cleanCs ($cleanName)"
        } else {
            cleanCs
        }
    }

    fun getDetails(fallbackTg: String = ""): String {
        val parts = mutableListOf<String>()
        if (dmrId > 0) parts.add("ID: $dmrId")
        if (city.isNotBlank()) parts.add(city.trim())
        val tgVal = if (talkgroup.isNotBlank()) talkgroup else fallbackTg
        if (tgVal.isNotBlank()) {
            val tgLabel = if (tgVal.startsWith("TG", ignoreCase = true)) tgVal else "TG $tgVal"
            parts.add(tgLabel)
        }
        if (mode.isNotBlank()) parts.add(mode.trim())
        return parts.joinToString(" • ")
    }
}

/**
 * Zero-Download Live Active Talker Resolver.
 * Resolves DMR ID and Callsign to complete identity (Callsign, Name, City, ID)
 * dynamically across ALL modes (DMR Direct, PoC UDP, SvxLink, TS3, Mumble, Zello)
 * with instant in-memory caching and background network resolution.
 */
object OnlineTalkerResolver {
    private val idCache = ConcurrentHashMap<Int, TalkerProfile>()
    private val callsignCache = ConcurrentHashMap<String, TalkerProfile>()
    private val executor = Executors.newCachedThreadPool()

    init {
        // Pre-seed known local network operators from active server roster
        registerProfile(TalkerProfile("BD62BOT", "NINJA GATEWAY", "Jakarta", 1022057))
        registerProfile(TalkerProfile("BD31ZZR", "H. Darwis Cicilia", "Tanjung Pinang", 1022144))
        registerProfile(TalkerProfile("BD31UHK", "RICKY R TJANDRA", "Jakarta Barat", 1022993))
        registerProfile(TalkerProfile("BD32ARI", "ARICHEPOT", "Cirebon", 1022043))
        registerProfile(TalkerProfile("BD33PS", "PARSONO", "Pekalongan", 1022002))
        registerProfile(TalkerProfile("BD34BAN", "BAMBANG", "Sleman", 1022041))
        registerProfile(TalkerProfile("BD35BIX", "Hari yamin", "Bangkalan", 1022093))
        registerProfile(TalkerProfile("BD73BOT", "NINJA DMR", "Pontianak", 1022000))
        registerProfile(TalkerProfile("BD73BOT", "NINJA DMR", "Pontianak", 1022001))
        registerProfile(TalkerProfile("JZ24BOT", "IMAM M", "Jakarta", 1022080))
    }

    private val pendingQueries = ConcurrentHashMap.newKeySet<Int>()

    fun registerProfile(p: TalkerProfile) {
        val cleanCs = p.callsign.trim().uppercase()
        if (cleanCs.isNotBlank() && cleanCs != "STANDBY (IDLE)") {
            callsignCache[cleanCs] = p
        }
        if (p.dmrId > 0) {
            idCache[p.dmrId] = p
        }
    }

    fun registerAlias(id: Int, alias: String) {
        if (id <= 0 || alias.isBlank()) return
        val existing = idCache[id]
        val cleanAlias = alias.trim()
        val parts = cleanAlias.split(Regex("[\\s\\-_|()]+")).filter { it.isNotBlank() }
        val cs = parts.firstOrNull()?.uppercase() ?: cleanAlias.uppercase()
        val name = if (parts.size > 1) parts.drop(1).joinToString(" ") else ""

        val prof = if (existing != null && existing.callsign.isNotBlank() && !existing.callsign.startsWith("ID ")) {
            existing.copy(name = if (name.isNotBlank()) name else existing.name)
        } else {
            TalkerProfile(
                callsign = cs,
                name = name,
                dmrId = id,
                talkgroup = existing?.talkgroup ?: "",
                mode = existing?.mode ?: ""
            )
        }
        registerProfile(prof)
    }

    fun resolve(id: Int, tg: String = "", mode: String = "", onResult: (TalkerProfile) -> Unit) {
        if (id <= 0) return
        val cached = idCache[id]
        if (cached != null) {
            onResult(cached.copy(
                talkgroup = if (tg.isNotBlank()) tg else cached.talkgroup,
                mode = if (mode.isNotBlank()) mode else cached.mode
            ))
            return
        }

        // Cache immediate fallback profile so subsequent bursts do NOT spam queries or emit duplicates!
        val fallback = TalkerProfile(callsign = "ID $id", dmrId = id, talkgroup = tg, mode = mode)
        idCache[id] = fallback
        onResult(fallback)

        if (!pendingQueries.add(id)) return

        executor.execute {
            try {
                val url = URL("https://radioid.net/api/dmr/user/?id=$id")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", "NinjaRadio-PoC/1.0")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val user = results.getJSONObject(0)
                        val cs = user.optString("callsign", "").trim().uppercase()
                        val fname = user.optString("fname", "").trim()
                        val surname = user.optString("surname", "").trim()
                        val city = user.optString("city", "").trim()
                        val state = user.optString("state", "").trim()
                        val fullName = "$fname $surname".trim()
                        val loc = if (city.isNotBlank() && state.isNotBlank() && city != state) "$city, $state" else if (city.isNotBlank()) city else state
                        if (cs.isNotEmpty()) {
                            val prof = TalkerProfile(
                                callsign = cs,
                                name = fullName,
                                city = loc,
                                dmrId = id,
                                talkgroup = tg,
                                mode = mode
                            )
                            registerProfile(prof)
                            onResult(prof)
                        }
                    }
                }
            } catch (_: Exception) {
            } finally {
                pendingQueries.remove(id)
            }
        }
    }

    fun resolveCallsign(rawCallsign: String, tg: String = "", mode: String = "", onResult: (TalkerProfile) -> Unit) {
        val trimmed = rawCallsign.trim()
        if (trimmed.isBlank() || trimmed.equals("STANDBY (IDLE)", ignoreCase = true)) return

        // Parse formats like "BD31ZZR (Darwis)", "BD31ZZR - Darwis", "[1022144] BD31ZZR", "BD31ZZR_Darwis"
        var parsedCs = trimmed
        var parsedName = ""
        var parsedId = 0

        val idMatch = Regex("\\[(\\d+)\\]").find(trimmed)
        if (idMatch != null) {
            parsedId = idMatch.groupValues[1].toIntOrNull() ?: 0
            parsedCs = trimmed.replace(idMatch.value, "").trim()
        }

        if (parsedCs.contains("(") && parsedCs.contains(")")) {
            val paren = Regex("([A-Za-z0-9/]+)\\s*\\(([^)]+)\\)").find(parsedCs)
            if (paren != null) {
                parsedCs = paren.groupValues[1].trim()
                parsedName = paren.groupValues[2].trim()
            }
        } else if (parsedCs.contains(" - ")) {
            val p = parsedCs.split(" - ")
            parsedCs = p[0].trim()
            parsedName = p.getOrNull(1)?.trim() ?: ""
        } else if (parsedCs.contains("_")) {
            val p = parsedCs.split("_")
            parsedCs = p[0].trim()
            parsedName = p.getOrNull(1)?.trim() ?: ""
        }

        val cleanCs = parsedCs.uppercase()
        val cached = callsignCache[cleanCs] ?: if (parsedId > 0) idCache[parsedId] else null
        if (cached != null) {
            val res = cached.copy(
                name = if (parsedName.isNotBlank()) parsedName else cached.name,
                dmrId = if (parsedId > 0) parsedId else cached.dmrId,
                talkgroup = if (tg.isNotBlank()) tg else cached.talkgroup,
                mode = if (mode.isNotBlank()) mode else cached.mode
            )
            onResult(res)
            return
        }

        val initial = TalkerProfile(
            callsign = cleanCs,
            name = parsedName,
            dmrId = parsedId,
            talkgroup = tg,
            mode = mode
        )
        registerProfile(initial)
        onResult(initial)

        // Try RadioID callsign query in background
        executor.execute {
            try {
                val url = URL("https://radioid.net/api/dmr/user/?callsign=$cleanCs")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("User-Agent", "NinjaRadio-PoC/1.0")
                }
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    if (results != null && results.length() > 0) {
                        val user = results.getJSONObject(0)
                        val fname = user.optString("fname", "").trim()
                        val surname = user.optString("surname", "").trim()
                        val city = user.optString("city", "").trim()
                        val state = user.optString("state", "").trim()
                        val dId = user.optInt("id", parsedId)
                        val fullName = if (parsedName.isNotBlank()) parsedName else "$fname $surname".trim()
                        val loc = if (city.isNotBlank() && state.isNotBlank() && city != state) "$city, $state" else if (city.isNotBlank()) city else state
                        val updated = TalkerProfile(
                            callsign = cleanCs,
                            name = fullName,
                            city = loc,
                            dmrId = dId,
                            talkgroup = tg,
                            mode = mode
                        )
                        registerProfile(updated)
                        onResult(updated)
                    }
                }
            } catch (_: Exception) {}
        }
    }
}
