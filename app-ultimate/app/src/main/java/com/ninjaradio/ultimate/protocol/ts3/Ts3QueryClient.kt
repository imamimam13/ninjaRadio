package com.ninjaradio.ultimate.protocol.ts3

import android.util.Log
import com.ninjaradio.ultimate.model.RadioRoom
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread

/**
 * TeamSpeak 3 ServerQuery Client for fetching channels dynamically.
 * Interacts with TS3 ServerQuery on TCP port 10011 (or custom query port).
 */
object Ts3QueryClient {
    private const val TAG = "Ts3QueryClient"

    fun unescape(s: String): String {
        return s.replace("\\\\", "\\")
            .replace("\\/", "/")
            .replace("\\s", " ")
            .replace("\\p", "|")
            .replace("\\a", "\u0007")
            .replace("\\b", "\b")
            .replace("\\f", "\u000C")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
    }

    fun escape(s: String): String {
        return s.replace("\\", "\\\\")
            .replace("/", "\\/")
            .replace(" ", "\\s")
            .replace("|", "\\p")
    }

    /**
     * Attempts to query channel list from TS3 ServerQuery interface.
     */
    fun fetchChannels(
        host: String,
        voicePort: Int = 9987,
        queryPort: Int = 10011,
        username: String = "",
        password: String = "",
        callback: (List<RadioRoom>?, String?) -> Unit
    ) {
        val cleanHost = host.replace(" ", "").trim()
        if (cleanHost.isEmpty()) {
            callback(null, "Host server kosong")
            return
        }

        thread(name = "ts3-query", isDaemon = true) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(InetSocketAddress(cleanHost, queryPort), 3500)
                socket.soTimeout = 3000

                val inStream = socket.getInputStream()
                val outStream = socket.getOutputStream()

                fun readUntil(stopWord: String): String {
                    val sb = StringBuilder()
                    val buf = ByteArray(2048)
                    while (!sb.contains(stopWord)) {
                        val n = inStream.read(buf)
                        if (n <= 0) break
                        sb.append(String(buf, 0, n, StandardCharsets.UTF_8))
                    }
                    return sb.toString()
                }

                fun sendCmd(cmd: String): String {
                    outStream.write((cmd + "\n\r").toByteArray(StandardCharsets.UTF_8))
                    outStream.flush()
                    return readUntil("error id=")
                }

                // 1. Read welcome banner lines
                readUntil("Welcome")

                // 2. Optional Login if credentials provided
                if (username.isNotBlank() && password.isNotBlank()) {
                    val loginRes = sendCmd("login client_login_name=${escape(username)} client_login_password=${escape(password)}")
                    if (!loginRes.contains("error id=0")) {
                        Log.w(TAG, "TS3 login note: $loginRes")
                    }
                }

                // 3. Select virtualserver by voice port
                val vPort = if (voicePort > 0) voicePort else 9987
                var useRes = sendCmd("use port=$vPort")
                if (!useRes.contains("error id=0")) {
                    // Fallback to use 1
                    useRes = sendCmd("use 1")
                }
                if (!useRes.contains("error id=0")) {
                    callback(null, "Gagal memilih virtualserver port $vPort: $useRes")
                    return@thread
                }

                // 4. Request channel list
                val chListRes = sendCmd("channellist -topic -flags")
                if (!chListRes.contains("error id=0")) {
                    if (chListRes.contains("error id=2568") || chListRes.contains("insufficient client permissions")) {
                        callback(null, "Server TS3 mengunci izin daftar room untuk tamu (Permission 25: b_virtualserver_channel_list). Silakan ketik nama room manual di bawah.")
                    } else {
                        callback(null, "TS3 ServerQuery error: $chListRes")
                    }
                    return@thread
                }

                // 5. Parse channel list
                val result = mutableListOf<RadioRoom>()
                val payload = chListRes.substringBefore("error id=").trim()
                val channelEntries = payload.split("|")

                for ((idx, entry) in channelEntries.withIndex()) {
                    if (entry.isBlank()) continue
                    val map = mutableMapOf<String, String>()
                    val tokens = entry.split(" ")
                    for (tok in tokens) {
                        val eqIdx = tok.indexOf('=')
                        if (eqIdx > 0) {
                            val k = tok.substring(0, eqIdx)
                            val v = tok.substring(eqIdx + 1)
                            map[k] = unescape(v)
                        }
                    }

                    val cidStr = map["cid"] ?: "ch_$idx"
                    val cname = map["channel_name"] ?: "Channel $cidStr"
                    val totalClients = map["total_clients"]?.toIntOrNull() ?: 0
                    val topic = map["channel_topic"] ?: ""
                    val isPassword = map["channel_flag_password"] == "1"

                    val desc = if (topic.isNotBlank()) topic else "TS3 CID #$cidStr"
                    result.add(
                        RadioRoom(
                            id = "ts3_$cidStr",
                            name = cname,
                            targetTg = cname.filter { it.isDigit() }.toLongOrNull() ?: 0L,
                            totalClients = totalClients,
                            isPasswordProtected = isPassword,
                            description = desc
                        )
                    )
                }

                if (result.isNotEmpty()) {
                    callback(result, null)
                } else {
                    callback(null, "Tidak ada channel ditemukan pada server TS3.")
                }

            } catch (e: Exception) {
                Log.w(TAG, "TS3 Query failed on $cleanHost:$queryPort: ${e.message}")
                val hint = if (e is java.net.ConnectException || e is java.net.SocketTimeoutException) {
                    "Port Query TS3 ($queryPort) tidak dapat dijangkau di $cleanHost. Silakan ketik nama room manual di bawah."
                } else {
                    "Koneksi query TS3 gagal: ${e.message}"
                }
                callback(null, hint)
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }
}
