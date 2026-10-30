package com.ninjadmr.app.contacts

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class CallbookUser(
    val id: String = "",
    val callsign: String = "",
    val name: String = "",
)

/**
 * Dedicated DMR Callbook / User Database.
 * Holds large lookup tables (thousands of users) in an in-memory index
 * for instantaneous O(1) Caller ID resolution during RX without
 * polluting the Channels / Talkgroups list.
 */
class CallbookStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val cacheFile by lazy { appContext.filesDir.resolve("callbook_cache.json") }

    init {
        if (!isLoaded) {
            loadIndex()
        }
    }

    fun count(): Int = index.size

    fun find(dmrId: String): CallbookUser? {
        val clean = dmrId.trim()
        if (clean.isEmpty()) return null
        index[clean]?.let { return it }
        val num = clean.toLongOrNull()
        if (num != null) {
            index[num.toString()]?.let { return it }
        }
        return null
    }

    fun loadIndex() {
        try {
            if (cacheFile.exists()) {
                cacheFile.forEachLine { line ->
                    val parts = line.split('\t')
                    if (parts.size >= 2) {
                        val id = parts[0].trim()
                        val callsign = parts[1].trim()
                        val name = if (parts.size >= 3) parts[2].trim() else ""
                        if (id.isNotEmpty()) {
                            val user = CallbookUser(id, callsign, name)
                            index[id] = user
                            id.toLongOrNull()?.let { index[it.toString()] = user }
                        }
                    }
                }
                isLoaded = true
                return
            }
            val content = prefs.getString(KEY_DATA, null) ?: ""
            if (content.isNotBlank()) {
                val arr = JSONArray(content)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val id = obj.optString("id", "").trim()
                    val callsign = obj.optString("callsign", "").trim()
                    val name = obj.optString("name", "").trim()
                    if (id.isNotBlank()) {
                        val user = CallbookUser(id, callsign, name)
                        index[id] = user
                        id.toLongOrNull()?.let { index[it.toString()] = user }
                    }
                }
                isLoaded = true
            }
        } catch (_: Exception) {}
    }

    fun saveIndex() {
        try {
            val sb = java.lang.StringBuilder()
            for ((_, user) in index) {
                sb.append(user.id).append('\t').append(user.callsign).append('\t').append(user.name).append('\n')
            }
            cacheFile.writeText(sb.toString())
            isLoaded = true
        } catch (_: Exception) {}
    }

    fun clear() {
        index.clear()
        try { cacheFile.delete() } catch (_: Exception) {}
        prefs.edit().remove(KEY_DATA).apply()
        isLoaded = false
    }

    private fun openAndRead(urlString: String): String {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        if (connection is HttpsURLConnection) {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            })
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, trustAllCerts, java.security.SecureRandom())
            connection.sslSocketFactory = sc.socketFactory
            connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        }
        val stream = if (connection.responseCode >= 400) {
            connection.errorStream ?: connection.inputStream
        } else {
            connection.inputStream
        }
        val text = stream.bufferedReader().use { it.readText() }.trim()
        if (connection.responseCode >= 400) {
            throw IOException("HTTP ${connection.responseCode}: $text")
        }
        return text
    }

    private fun fetchRawHttps(ip: String, port: Int, sniHost: String, path: String): String {
        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        })
        val sc = SSLContext.getInstance("TLS")
        sc.init(null, trustAllCerts, java.security.SecureRandom())
        val rawSocket = Socket(ip, port)
        rawSocket.soTimeout = 15000
        val sslSocket = sc.socketFactory.createSocket(rawSocket, sniHost, port, true) as SSLSocket
        try {
            val params = sslSocket.sslParameters
            params.serverNames = listOf(SNIHostName(sniHost))
            sslSocket.sslParameters = params
        } catch (_: Exception) {}
        try {
            sslSocket.javaClass.getMethod("setHostname", String::class.java).invoke(sslSocket, sniHost)
        } catch (_: Exception) {}
        sslSocket.startHandshake()
        val writer = java.io.OutputStreamWriter(sslSocket.outputStream, "UTF-8")
        writer.write("GET $path HTTP/1.0\r\nHost: $sniHost\r\nConnection: close\r\nAccept: */*\r\n\r\n")
        writer.flush()
        val reader = sslSocket.inputStream.bufferedReader(Charsets.UTF_8)
        val statusLine = reader.readLine() ?: throw IOException("Empty response")
        if (!statusLine.contains("200")) {
            throw IOException("HTTP status: $statusLine")
        }
        var line = reader.readLine()
        while (line != null && line.isNotEmpty()) {
            line = reader.readLine()
        }
        val body = reader.readText()
        sslSocket.close()
        return body.trim()
    }

    private fun fetchUrlContent(urlString: String): String {
        try {
            return openAndRead(urlString)
        } catch (e: Exception) {
            val uri = URI(urlString)
            val host = uri.host ?: throw e
            val ipMatch = Regex("""^(\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3})\.(?:sslip|nip)\.io$""").find(host)
            if (ipMatch != null) {
                val ip = ipMatch.groupValues[1]
                val port = if (uri.port != -1) uri.port else 443
                val pathStr = (uri.rawPath ?: "/") + if (uri.rawQuery != null) "?${uri.rawQuery}" else ""
                return fetchRawHttps(ip, port, host, pathStr)
            }
            throw e
        }
    }

    fun importFromUrl(urlString: String, onComplete: (Int, String?) -> Unit) {
        Thread({
            try {
                val rawContent = fetchUrlContent(urlString)
                var count = 0

                val jsonStart = rawContent.indexOfFirst { it == '{' || it == '[' }
                val isJson = jsonStart != -1 && (rawContent[jsonStart] == '{' || rawContent[jsonStart] == '[')

                if (isJson) {
                    val cleanJson = rawContent.substring(jsonStart).trim()
                    val userArray = if (cleanJson.startsWith("{")) {
                        val root = JSONObject(cleanJson)
                        root.optJSONArray("users") ?: root.optJSONArray("data") ?: JSONArray()
                    } else {
                        JSONArray(cleanJson)
                    }
                    for (i in 0 until userArray.length()) {
                        val obj = userArray.optJSONObject(i) ?: continue
                        val id = listOf("radio_id", "id", "dmr_id", "dmrid", "radioId", "RADIO_ID", "ID", "DMR_ID")
                            .firstNotNullOfOrNull { k -> obj.opt(k)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } } ?: ""
                        val callsign = listOf("callsign", "call_sign", "call", "CALLSIGN", "CALL", "Callsign")
                            .firstNotNullOfOrNull { k -> obj.opt(k)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } }?.uppercase() ?: ""
                        val name = listOf("name", "full_name", "NAME")
                            .firstNotNullOfOrNull { k -> obj.opt(k)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } } ?: run {
                                val f = listOf("fname", "first_name", "FNAME", "FIRST_NAME").firstNotNullOfOrNull { k -> obj.opt(k)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } } ?: ""
                                val s = listOf("surname", "last_name", "SURNAME", "LAST_NAME").firstNotNullOfOrNull { k -> obj.opt(k)?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" } } ?: ""
                                "$f $s".trim()
                            }
                        if (id.isNotBlank() && (callsign.isNotBlank() || name.isNotBlank())) {
                            val user = CallbookUser(id, callsign, name)
                            index[id] = user
                            id.toLongOrNull()?.let { index[it.toString()] = user }
                            count++
                        }
                    }
                } else {
                    val textLines = rawContent.lines()
                    for (line in textLines) {
                        val trimmed = line.trim()
                        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("//")) continue
                        val delimiter = when {
                            trimmed.contains('\t') -> "\t"
                            trimmed.contains(';') -> ";"
                            else -> ","
                        }
                        val parts = trimmed.split(delimiter)
                        if (parts.size >= 2) {
                            val id = parts[0].trim().replace("\"", "").replace("'", "")
                            if (id.equals("radio_id", ignoreCase = true) || id.equals("id", ignoreCase = true)) continue
                            val callsign = parts[1].trim().replace("\"", "").replace("'", "").uppercase()
                            val name = if (parts.size >= 3) parts[2].trim().replace("\"", "").replace("'", "") else ""
                            if (id.isNotEmpty() && (callsign.isNotEmpty() || name.isNotEmpty())) {
                                val user = CallbookUser(id, callsign, name)
                                index[id] = user
                                id.toLongOrNull()?.let { index[it.toString()] = user }
                                count++
                            }
                        }
                    }
                }
                saveIndex()
                onComplete(count, null)
            } catch (exc: Exception) {
                onComplete(0, exc.message)
            }
        }, "callbook-import").start()
    }

    companion object {
        private const val PREFS = "ninjadmr_callbook"
        private const val KEY_DATA = "callbook_users"
        val index = ConcurrentHashMap<String, CallbookUser>()
        @Volatile var isLoaded = false
    }
}
