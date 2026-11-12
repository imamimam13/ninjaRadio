package com.ninjadmr.lite.contacts

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection

object CallbookManager {
    private val memoryCache = ConcurrentHashMap<Int, String>()
    private val executor = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var isLoaded = false

    private const val PREF_NAME = "ninjadmr_callbook_cache"
    private const val KEY_CUSTOM_URL = "custom_callbook_url"
    const val DEFAULT_URL = "https://43.173.13.117.sslip.io/callbook/all.user.json"

    fun getCustomUrl(context: Context): String {
        val p = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val saved = p.getString(KEY_CUSTOM_URL, null)
        return if (saved.isNullOrBlank() || saved.contains("radioid.net")) DEFAULT_URL else saved
    }

    fun setCustomUrl(context: Context, url: String) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CUSTOM_URL, url.trim())
            .apply()
    }

    private fun getCacheFile(context: Context): File {
        return File(context.filesDir, "callbook_entries.tsv")
    }

    private fun ensureLoaded(context: Context) {
        if (isLoaded) return
        synchronized(this) {
            if (isLoaded) return
            val file = getCacheFile(context)
            if (file.exists()) {
                try {
                    file.forEachLine { line ->
                        val parts = line.split('\t')
                        if (parts.size >= 2) {
                            val id = parts[0].trim().toIntOrNull()
                            val call = parts[1].trim()
                            if (id != null && call.isNotEmpty()) {
                                memoryCache[id] = call
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // Also load any single lookups from SharedPreferences without overwriting full "CALLSIGN (Name)" entries
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            var staleKeysRemoved = false
            val editor = prefs.edit()
            for ((key, value) in prefs.all) {
                val id = key.toIntOrNull()
                if (id != null && value is String && value.isNotBlank()) {
                    val existing = memoryCache[id]
                    if (existing == null || !existing.contains("(") || value.contains("(")) {
                        memoryCache[id] = value
                    } else {
                        editor.remove(key)
                        staleKeysRemoved = true
                    }
                }
            }
            if (staleKeysRemoved) {
                editor.apply()
            }
            isLoaded = true
        }
    }

    fun count(context: Context): Int {
        ensureLoaded(context)
        return memoryCache.size
    }

    fun clear(context: Context) {
        memoryCache.clear()
        try {
            getCacheFile(context).delete()
        } catch (_: Exception) {}
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        isLoaded = false
    }

    fun getCallsign(context: Context, dmrId: Int): String? {
        if (dmrId <= 0) return null
        ensureLoaded(context)
        return memoryCache[dmrId] ?: if (dmrId > 9999999) memoryCache[dmrId / 100] else null
    }

    fun resolveStationLabel(context: Context, dmrId: Int, fallbackCallsign: String = ""): String {
        val fromBook = getCallsign(context, dmrId)
        if (!fromBook.isNullOrBlank()) {
            if (fromBook.contains("(")) return fromBook
            return "$fromBook ($dmrId)"
        }
        val cleanFallback = fallbackCallsign.trim().uppercase()
        return if (cleanFallback.isNotEmpty()) {
            if (dmrId > 0) "$cleanFallback ($dmrId)" else cleanFallback
        } else {
            "$dmrId"
        }
    }

    fun cacheCallsign(context: Context, dmrId: Int, callsign: String) {
        if (dmrId <= 0 || callsign.isBlank()) return
        ensureLoaded(context)
        val trimmed = callsign.trim()
        val existing = memoryCache[dmrId]
        // Never overwrite a rich "CALLSIGN (Name)" entry with a bare callsign
        if (existing != null && existing.contains("(") && !trimmed.contains("(")) {
            return
        }
        val normalized = if (trimmed.contains("(")) trimmed else trimmed.uppercase()
        memoryCache[dmrId] = normalized
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(dmrId.toString(), normalized)
            .apply()
    }

    private fun configureSsl(conn: HttpURLConnection) {
        if (conn is HttpsURLConnection) {
            try {
                val trustAllCerts = arrayOf<javax.net.ssl.TrustManager>(
                    object : javax.net.ssl.X509TrustManager {
                        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                    }
                )
                val sc = javax.net.ssl.SSLContext.getInstance("TLS")
                sc.init(null, trustAllCerts, java.security.SecureRandom())
                conn.sslSocketFactory = sc.socketFactory
                conn.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            } catch (_: Exception) {}
        }
    }

    fun resolveAsync(context: Context, dmrId: Int, onResolved: (String) -> Unit) {
        if (dmrId <= 0) return
        ensureLoaded(context)

        // 1. Check memory cache first
        val cached = memoryCache[dmrId]
        if (!cached.isNullOrBlank()) {
            onResolved(cached)
            return
        }

        // 2. Fetch online via RadioID API asynchronously
        executor.execute {
            var resolvedCall: String? = null
            try {
                // Strip ESSID if 9 digits (e.g. 510205701 -> 5102057)
                val cleanId = if (dmrId > 9999999) dmrId / 100 else dmrId
                val url = URL("https://database.radioid.net/api/dmr/user/?id=$cleanId")
                val conn = url.openConnection() as HttpURLConnection
                configureSsl(conn)
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "NinjaRadioLite/1.0")

                if (conn.responseCode == 200) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = reader.readText()
                    reader.close()

                    val json = JSONObject(response)
                    val count = json.optInt("count", 0)
                    if (count > 0) {
                        val results = json.optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            val user = results.getJSONObject(0)
                            val call = user.optString("callsign", "").trim().uppercase()
                            val fname = user.optString("fname", "").trim()
                            if (call.isNotEmpty()) {
                                resolvedCall = if (fname.isNotEmpty()) "$call ($fname)" else call
                            }
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) {}

            if (!resolvedCall.isNullOrBlank()) {
                val call = resolvedCall!!
                memoryCache[dmrId] = call
                context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(dmrId.toString(), call)
                    .apply()

                mainHandler.post {
                    onResolved(call)
                }
            }
        }
    }

    /**
     * Downloads and imports bulk DMR Callbook from a custom user link (CSV, TSV, or JSON).
     */
    fun importFromUrl(
        context: Context,
        urlString: String,
        onProgress: (String) -> Unit,
        onComplete: (Int, String?) -> Unit
    ) {
        executor.execute {
            var count = 0
            val file = getCacheFile(context)
            try {
                mainHandler.post { onProgress("Menghubungi server...") }
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                configureSsl(conn)
                conn.connectTimeout = 15000
                conn.readTimeout = 25000
                conn.setRequestProperty("User-Agent", "NinjaRadioLite/1.0")

                val code = conn.responseCode
                if (code != 200) {
                    mainHandler.post { onComplete(0, "HTTP Error $code") }
                    return@execute
                }

                mainHandler.post { onProgress("Mengunduh data...") }
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val tempFile = File(context.cacheDir, "cb_temp.tsv")
                val writer = tempFile.bufferedWriter()

                var isFirst = true
                var isJson = false
                val firstLine = reader.readLine()
                if (firstLine != null) {
                    val trimmedFirst = firstLine.trim()
                    if (trimmedFirst.startsWith("{") || trimmedFirst.startsWith("[")) {
                        isJson = true
                    }
                }

                if (isJson) {
                    val fullSb = java.lang.StringBuilder()
                    if (firstLine != null) fullSb.append(firstLine)
                    var l = reader.readLine()
                    while (l != null) {
                        fullSb.append(l)
                        l = reader.readLine()
                    }
                    val jsonStr = fullSb.toString().trim()
                    val arr = if (jsonStr.startsWith("[")) {
                        JSONArray(jsonStr)
                    } else {
                        val root = JSONObject(jsonStr)
                        root.optJSONArray("users") ?: root.optJSONArray("subscribers") ?: root.optJSONArray("subscriber_ids") ?: root.optJSONArray("results") ?: root.optJSONArray("data") ?: root.optJSONArray("records") ?: root.optJSONArray("items") ?: JSONArray()
                    }
                    if (arr.length() > 0) {
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val idStr = listOf("radio_id", "id", "dmr_id", "dmrid", "radioId", "RADIO_ID", "ID", "DMR_ID").firstNotNullOfOrNull { k -> obj.opt(k)?.toString() } ?: ""
                            val callStr = listOf("callsign", "call_sign", "call", "CALLSIGN", "CALL", "Callsign").firstNotNullOfOrNull { k -> obj.opt(k)?.toString() } ?: ""
                            val fname = listOf("fname", "name", "first_name", "FNAME", "NAME", "FIRST_NAME", "full_name").firstNotNullOfOrNull { k -> obj.opt(k)?.toString() } ?: ""

                            val id = idStr.trim().toIntOrNull()
                            val call = callStr.replace("-", "").trim().uppercase()
                            val name = fname.replace("-", "").trim()
                            if (id != null && call.isNotEmpty()) {
                                val label = if (name.isNotEmpty()) "$call ($name)" else call
                                writer.write("$id\t$label\n")
                                memoryCache[id] = label
                                count++
                            }
                        }
                    } else if (jsonStr.startsWith("{")) {
                        // Keyed dictionary format (e.g. {"1022001": ["BD62AA", "ALI AKBAR", ...]} or {"1022001": {...}})
                        val root = JSONObject(jsonStr)
                        val keys = root.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val id = k.trim().toIntOrNull() ?: continue
                            val value = root.opt(k)
                            var call = ""
                            var name = ""
                            if (value is JSONArray) {
                                call = value.optString(0, "").replace("-", "").trim().uppercase()
                                if (value.length() > 1) {
                                    name = value.optString(1, "").replace("-", "").trim()
                                }
                            } else if (value is JSONObject) {
                                val callStr = listOf("callsign", "call_sign", "call").firstNotNullOfOrNull { key -> value.opt(key)?.toString() } ?: ""
                                val fname = listOf("fname", "name", "first_name").firstNotNullOfOrNull { key -> value.opt(key)?.toString() } ?: ""
                                call = callStr.replace("-", "").trim().uppercase()
                                name = fname.replace("-", "").trim()
                            } else if (value is String) {
                                call = value.replace("-", "").trim().uppercase()
                            }
                            if (call.isNotEmpty()) {
                                val label = if (name.isNotEmpty()) "$call ($name)" else call
                                writer.write("$id\t$label\n")
                                memoryCache[id] = label
                                count++
                            }
                        }
                    }
                } else {
                    // Line-by-line CSV / TSV streaming
                    var line: String? = firstLine
                    while (line != null) {
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("//")) {
                            val delimiter = when {
                                trimmed.contains('\t') -> "\t"
                                trimmed.contains(';') -> ";"
                                else -> ","
                            }
                            val parts = trimmed.split(delimiter)
                            if (parts.size >= 2) {
                                val idStr = parts[0].trim().replace("\"", "").replace("'", "")
                                val id = idStr.toIntOrNull()
                                val call = parts[1].trim().replace("\"", "").replace("'", "").uppercase()
                                val fname = if (parts.size >= 3) parts[2].trim().replace("\"", "").replace("'", "") else ""
                                if (id != null && call.isNotEmpty() && call != "CALLSIGN") {
                                    val label = if (fname.isNotEmpty()) "$call ($fname)" else call
                                    writer.write("$id\t$label\n")
                                    memoryCache[id] = label
                                    count++
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }

                writer.flush()
                writer.close()
                reader.close()
                conn.disconnect()

                tempFile.copyTo(file, overwrite = true)
                tempFile.delete()
                isLoaded = true

                mainHandler.post {
                    onComplete(count, null)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    onComplete(0, e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
}
