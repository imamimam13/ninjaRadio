package com.ninjaradio.ultimate.protocol.poc

import android.os.SystemClock
import io.github.jaredmdobson.concentus.OpusApplication
import io.github.jaredmdobson.concentus.OpusDecoder
import io.github.jaredmdobson.concentus.OpusEncoder
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Custom PoC (Opus UDP) Protocol Driver.
 * 100% server-agnostic, white-label client for custom PoC network radio servers.
 */
class CustomPocClient(
    val host: String,
    val port: Int,
    val user: String,
    val secret: String,
    val tg: Int = 1025,
    val clientModel: String = "Hytera PDC680 BRIDI",
    val listener: Listener
) {
    interface Listener {
        fun onLog(msg: String)
        fun onState(connected: Boolean, info: String)
        fun onTalker(callsign: String, isTalking: Boolean, details: String = "")
        fun onTalkerProfile(profile: com.ninjaradio.ultimate.protocol.dmr.TalkerProfile, isTalking: Boolean)
        fun onPcmAudio(pcm: ShortArray)
        fun onFlush()
    }

    @Volatile private var isRunning = false
    @Volatile private var isTxActive = false
    @Volatile private var clockOffset = 0
    @Volatile private var currentTalkerProfile: com.ninjaradio.ultimate.protocol.dmr.TalkerProfile? = null
    private val senderCache = java.util.concurrent.ConcurrentHashMap<Int, com.ninjaradio.ultimate.protocol.dmr.TalkerProfile>()
    private var socket: DatagramSocket? = null
    private var serverAddr: InetAddress? = null
    private var sessionToken: ByteArray? = null
    private var txSeq = 0

    // Concentus Opus Vocoder
    private val decoder = OpusDecoder(8000, 1)
    private val encoder = OpusEncoder(8000, 1, OpusApplication.OPUS_APPLICATION_VOIP).apply {
        bitrate = 16000
    }

    private val txQueue = LinkedBlockingQueue<ShortArray>(50)
    private var rxThread: Thread? = null
    private var txThread: Thread? = null
    private var pingThread: Thread? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        sessionToken = null
        txSeq = 0

        rxThread = Thread({ rxLoop() }, "poc-udp-rx").apply { isDaemon = true; start() }
        txThread = Thread({ txLoop() }, "poc-udp-tx").apply { isDaemon = true; start() }
        pingThread = Thread({ pingLoop() }, "poc-udp-ping").apply { isDaemon = true; start() }
    }

    fun stop() {
        isRunning = false
        isTxActive = false
        txQueue.clear()
        sessionToken = null

        val s = socket
        socket = null
        try { s?.close() } catch (_: Exception) {}

        try { rxThread?.interrupt() } catch (_: Exception) {}
        try { txThread?.interrupt() } catch (_: Exception) {}
        try { pingThread?.interrupt() } catch (_: Exception) {}

        val last = currentTalkerProfile
        currentTalkerProfile = null
        val idleProfile = last?.copy(name = "", city = "") ?: com.ninjaradio.ultimate.protocol.dmr.TalkerProfile("STANDBY (IDLE)", talkgroup = "$tg", mode = "POC")
        listener.onTalkerProfile(idleProfile, false)
        listener.onTalker("STANDBY (IDLE)", false, "TG $tg")
        listener.onState(false, "Terputus")
        listener.onFlush()
    }

    private val txAccumulator = ArrayList<Short>()

    fun startTx() {
        if (!isRunning || sessionToken == null) {
            listener.onLog("PoC: Belum terhubung atau belum ada token autentikasi")
            return
        }
        isTxActive = true
        synchronized(txAccumulator) { txAccumulator.clear() }
        txQueue.clear()
        listener.onLog("PoC: PTT Mulai Bicara (TG $tg)")
    }

    fun stopTx() {
        if (!isTxActive) return
        isTxActive = false
        synchronized(txAccumulator) { txAccumulator.clear() }
        txQueue.clear()

        // Send unkey terminator packet (0x11 + sessionToken) twice
        val tok = sessionToken
        if (tok != null) {
            try {
                val unkey = byteArrayOf(0x11) + tok
                sendUdp(unkey)
                sendUdp(unkey)
            } catch (_: Exception) {}
        }
        listener.onLog("PoC: PTT Lepas")
    }

    fun feedPcm(pcm: ShortArray) {
        if (!isTxActive || !isRunning) return
        synchronized(txAccumulator) {
            for (s in pcm) txAccumulator.add(s)
            while (txAccumulator.size >= 320) {
                val frame = ShortArray(320)
                for (i in 0 until 320) {
                    frame[i] = txAccumulator[i]
                }
                txAccumulator.subList(0, 320).clear()
                if (txQueue.size < 40) {
                    txQueue.offer(frame)
                }
            }
        }
    }

    private fun sendUdp(data: ByteArray) {
        val s = socket ?: return
        val addr = serverAddr ?: return
        try {
            val pkt = DatagramPacket(data, data.size, addr, port)
            s.send(pkt)
        } catch (_: Exception) {}
    }

    private fun sendLoginHandshake() {
        try {
            val userBytes = user.trim().toByteArray(StandardCharsets.UTF_8)
            val timeSec = ((System.currentTimeMillis() / 1000) + clockOffset).toInt()

            // 6-byte nonce: [TG (2 bytes) + Timestamp (4 bytes)]
            val nonce = byteArrayOf(
                ((tg shr 8) and 0xFF).toByte(),
                (tg and 0xFF).toByte(),
                ((timeSec shr 24) and 0xFF).toByte(),
                ((timeSec shr 16) and 0xFF).toByte(),
                ((timeSec shr 8) and 0xFF).toByte(),
                (timeSec and 0xFF).toByte()
            )

            // HMAC-SHA256 signature using SecretKey
            val mac = Mac.getInstance("HmacSHA256")
            val secretBytes = secret.trim().toByteArray(StandardCharsets.UTF_8)
            mac.init(SecretKeySpec(secretBytes, "HmacSHA256"))
            val hmacInput = userBytes + nonce
            val hmacFull = mac.doFinal(hmacInput)
            val signature = Arrays.copyOf(hmacFull, 8)

            // Header: [0x01, user.length]
            val header = byteArrayOf(0x01.toByte(), userBytes.size.toByte())
            val loginPacket = header + userBytes + nonce + signature

            sendUdp(loginPacket)
            listener.onLog("PoC: Mengirim login jabat tangan ke $host:$port (User: $user, TG: $tg)")
        } catch (e: Exception) {
            listener.onLog("PoC Login Error: ${e.message}")
        }
    }

    private fun rxLoop() {
        try {
            serverAddr = InetAddress.getByName(host.trim())
            val sock = DatagramSocket().apply { soTimeout = 1000 }
            socket = sock
            listener.onState(false, "Menghubungkan ke $host:$port…")

            var lastLoginSent = 0L
            val buf = ByteArray(2048)

            while (isRunning) {
                // If not authenticated, resend login handshake every 3 seconds
                val now = SystemClock.elapsedRealtime()
                if (sessionToken == null) {
                    if (now - lastLoginSent > 3000L) {
                        lastLoginSent = now
                        sendLoginHandshake()
                    }
                }

                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    sock.receive(pkt)
                    handleIncomingPacket(pkt.data, pkt.length)
                } catch (_: java.net.SocketTimeoutException) {
                    // Normal timeout for polling
                }
            }
        } catch (e: Exception) {
            if (isRunning) {
                listener.onLog("PoC RX error: ${e.message}")
                listener.onState(false, "Error: ${e.message}")
            }
        }
    }

    private fun sendPing() {
        val tok = sessionToken ?: return
        try {
            val ping = byteArrayOf(0x02.toByte()) + tok + byteArrayOf(0x00.toByte())
            sendUdp(ping)
        } catch (_: Exception) {}
    }

    private fun sendModelRegistration() {
        val tok = sessionToken ?: return
        try {
            val payloadStr = if (clientModel.contains("|")) clientModel else "$clientModel|Hytera PDC680 A13"
            val modelBytes = payloadStr.toByteArray(StandardCharsets.UTF_8).let {
                if (it.size > 64) Arrays.copyOf(it, 64) else it
            }
            val reg = byteArrayOf(0x02.toByte()) + tok + byteArrayOf(0x01.toByte()) + modelBytes
            sendUdp(reg)
            listener.onLog("PoC: Terdaftar sebagai '$clientModel'")
        } catch (_: Exception) {}
    }

    private fun handleIncomingPacket(data: ByteArray, length: Int) {
        if (length < 1) return
        val opcode = data[0].toInt() and 0xFF

        when (opcode) {
            0x02, 0x81 -> {
                // Login OK: contains Session Token (4 bytes [1..5])
                if (length >= 5) {
                    sessionToken = Arrays.copyOfRange(data, 1, 5)
                    listener.onState(true, "ONLINE")
                    listener.onLog("PoC: Autentikasi Berhasil! Session Token diterima.")
                    sendModelRegistration()
                    sendPing()
                }
            }
            0x03 -> {
                val subCode = if (length > 1) data[1].toInt() and 0xFF else 0
                if (subCode == 2 && length >= 6) {
                    // Server Time Correction
                    val serverTime = ((data[2].toLong() and 0xFF) shl 24) or
                                     ((data[3].toLong() and 0xFF) shl 16) or
                                     ((data[4].toLong() and 0xFF) shl 8) or
                                     (data[5].toLong() and 0xFF)
                    val clientTime = System.currentTimeMillis() / 1000L
                    clockOffset = (serverTime - clientTime).toInt()
                    listener.onLog("PoC: Menyesuaikan jam server (selisih ${clockOffset}s)…")
                    sendLoginHandshake()
                } else if (subCode == 1) {
                    sessionToken = null
                    listener.onLog("PoC Server: Secret / Password salah")
                    listener.onState(false, "Password salah")
                } else {
                    sessionToken = null
                    listener.onLog("PoC Server: Sesi kedaluwarsa, mencoba lagi…")
                    listener.onState(false, "Sesi kedaluwarsa")
                }
            }
            0x90, 0x10 -> {
                // Voice Audio packet (Opus):
                // [0] opcode, [1-2] sender ID, [3-4] sequence, [5..length] Opus payload
                if (length > 5) {
                    val senderId = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                    val opusBytes = Arrays.copyOfRange(data, 5, length)
                    val outPcm = ShortArray(960)
                    try {
                        val decodedSamples = decoder.decode(opusBytes, 0, opusBytes.size, outPcm, 0, 960, false)
                        if (decodedSamples > 0) {
                            val frame = Arrays.copyOf(outPcm, decodedSamples)
                            listener.onPcmAudio(frame)

                            val active = currentTalkerProfile
                            if (active == null || (active.dmrId > 0 && active.dmrId != senderId)) {
                                val cached = senderCache[senderId]
                                if (cached != null) {
                                    currentTalkerProfile = cached
                                    listener.onTalkerProfile(cached, true)
                                } else {
                                    com.ninjaradio.ultimate.protocol.dmr.OnlineTalkerResolver.resolve(senderId, tg = "$tg", mode = "POC") { prof ->
                                        currentTalkerProfile = prof
                                        listener.onTalkerProfile(prof, true)
                                    }
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            0x91, 0x11 -> {
                // Unkey / End of Voice Burst
                val last = currentTalkerProfile
                currentTalkerProfile = null
                val idleProfile = last ?: com.ninjaradio.ultimate.protocol.dmr.TalkerProfile("STANDBY (IDLE)", talkgroup = "$tg", mode = "POC")
                listener.onTalkerProfile(idleProfile, false)
                listener.onFlush()
            }
            0x9D -> {
                // Talker metadata string (cs|name|city|rid|wil)
                if (length > 1) {
                    try {
                        val metaStr = String(data, 1, length - 1, StandardCharsets.UTF_8).trim()
                        val parts = metaStr.split("|")
                        val cs = parts.getOrNull(0)?.trim() ?: ""
                        val name = parts.getOrNull(1)?.trim() ?: ""
                        val city = parts.getOrNull(2)?.trim() ?: ""
                        val ridStr = parts.getOrNull(3)?.trim() ?: ""
                        val wil = parts.getOrNull(4)?.trim() ?: ""
                        val ridInt = ridStr.toIntOrNull() ?: 0

                        if (cs.isNotBlank()) {
                            val location = when {
                                city.isNotBlank() && wil.isNotBlank() && !city.equals(wil, ignoreCase = true) -> "$city, $wil"
                                city.isNotBlank() -> city
                                else -> wil
                            }
                            val profile = com.ninjaradio.ultimate.protocol.dmr.TalkerProfile(
                                callsign = cs,
                                name = name,
                                city = location,
                                dmrId = ridInt,
                                talkgroup = "$tg",
                                mode = "POC"
                            )
                            currentTalkerProfile = profile
                            if (ridInt > 0) {
                                senderCache[ridInt] = profile
                            }
                            com.ninjaradio.ultimate.protocol.dmr.OnlineTalkerResolver.registerProfile(profile)
                            listener.onTalkerProfile(profile, true)
                        }
                    } catch (_: Exception) {}
                }
            }
            0x98 -> {
                // Channel member roster list: "cs|name|city|app|rid|wil\n..."
                if (length > 1) {
                    try {
                        val listStr = String(data, 1, length - 1, StandardCharsets.UTF_8)
                        parseAndRegisterUsers(listStr)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun txLoop() {
        val framePcm = ShortArray(320) // 40ms frame at 8kHz
        val opusBuf = ByteArray(256)

        while (isRunning) {
            try {
                val chunk = txQueue.poll(40, TimeUnit.MILLISECONDS) ?: continue
                val tok = sessionToken ?: continue
                if (!isTxActive) continue

                // Pad or copy to 320 samples
                val copyLen = Math.min(chunk.size, 320)
                System.arraycopy(chunk, 0, framePcm, 0, copyLen)
                if (copyLen < 320) {
                    Arrays.fill(framePcm, copyLen, 320, 0.toShort())
                }

                val encodedBytes = encoder.encode(framePcm, 0, 320, opusBuf, 0, opusBuf.size)
                if (encodedBytes > 0) {
                    // Outgoing packet: [0x10] + sessionToken (4B) + seq (2B) + opusBytes
                    val tokLen = Math.min(tok.size, 4)
                    val pktLen = 1 + tokLen + 2 + encodedBytes
                    val outPkt = ByteArray(pktLen)

                    outPkt[0] = 0x10.toByte()
                    System.arraycopy(tok, 0, outPkt, 1, tokLen)
                    outPkt[1 + tokLen] = ((txSeq shr 8) and 0xFF).toByte()
                    outPkt[1 + tokLen + 1] = (txSeq and 0xFF).toByte()
                    txSeq = (txSeq + 1) and 0xFFFF
                    System.arraycopy(opusBuf, 0, outPkt, 1 + tokLen + 2, encodedBytes)

                    sendUdp(outPkt)
                }
            } catch (_: InterruptedException) {
                break
            } catch (_: Exception) {}
        }
    }

    private fun parseAndRegisterUsers(text: String) {
        val lines = text.split("\n")
        for (line in lines) {
            val parts = line.split("|")
            if (parts.size >= 3) {
                val cs = parts.getOrNull(0)?.trim() ?: ""
                val name = parts.getOrNull(1)?.trim() ?: ""
                val city = parts.getOrNull(2)?.trim() ?: ""
                val rid = parts.getOrNull(4)?.trim()?.toIntOrNull() ?: 0
                val wil = parts.getOrNull(5)?.trim() ?: ""
                if (cs.isNotBlank()) {
                    val loc = if (city.isNotBlank() && wil.isNotBlank() && !city.equals(wil, ignoreCase = true)) "$city, $wil" else if (city.isNotBlank()) city else wil
                    val prof = com.ninjaradio.ultimate.protocol.dmr.TalkerProfile(
                        callsign = cs,
                        name = name,
                        city = loc,
                        dmrId = rid,
                        talkgroup = "$tg",
                        mode = "POC"
                    )
                    com.ninjaradio.ultimate.protocol.dmr.OnlineTalkerResolver.registerProfile(prof)
                    if (rid > 0) {
                        senderCache[rid] = prof
                    }
                }
            }
        }
    }

    private fun pingLoop() {
        while (isRunning) {
            try {
                Thread.sleep(15000)
                if (isRunning && sessionToken != null) {
                    sendPing()
                }
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    companion object {
        /**
         * Optional Portal Pairing Helper (100% dynamic portal URL).
         * Calls POST {portalUrl}/portal/ht/baru with body 'app=poc' to request a 6-digit pairing code.
         */
        fun requestPairingCode(
            portalUrl: String,
            callback: (code: String?, devId: String?, error: String?) -> Unit
        ) {
            Thread {
                try {
                    val cleanBase = portalUrl.trim().removeSuffix("/")
                    val u = URL("$cleanBase/portal/ht/baru")
                    val conn = (u.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 8000
                        readTimeout = 8000
                        requestMethod = "POST"
                        doOutput = true
                        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                        setRequestProperty("User-Agent", "NinjaRadio-PoC/1.0")
                    }
                    val bodyBytes = "app=poc".toByteArray(StandardCharsets.UTF_8)
                    conn.outputStream.use { it.write(bodyBytes) }

                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val code = json.optString("code", "")
                        val dev = json.optString("dev", "")
                        if (code.isNotBlank()) {
                            callback(code, dev, null)
                        } else {
                            callback(null, null, "Respon server tidak memiliki field 'code'")
                        }
                    } else {
                        callback(null, null, "Server HTTP ${conn.responseCode}")
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    callback(null, null, e.message ?: "Koneksi gagal")
                }
            }.start()
        }

        /**
         * Polls {portalUrl}/portal/ht/cek?dev={devId} to check if the code was authorized on portal.
         */
        fun checkPairingStatus(
            portalUrl: String,
            devId: String,
            callback: (ok: Boolean, user: String?, secret: String?, host: String?, port: Int, tg: Int) -> Unit
        ) {
            Thread {
                try {
                    val cleanBase = portalUrl.trim().removeSuffix("/")
                    val u = URL("$cleanBase/portal/ht/cek?dev=$devId")
                    val conn = (u.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 4000
                        setRequestProperty("User-Agent", "NinjaRadio-PoC/1.0")
                    }
                    if (conn.responseCode == 200) {
                        val body = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(body)
                        val ok = json.optBoolean("ok", false)
                        if (ok) {
                            val user = json.optString("user", "")
                            val secret = json.optString("secret", "")
                            val host = json.optString("host", "")
                            val port = json.optInt("port", 12345)
                            val tg = json.optInt("tg", 1025)
                            callback(true, user, secret, host, port, tg)
                        } else {
                            callback(false, null, null, null, 12345, 1025)
                        }
                    } else {
                        callback(false, null, null, null, 12345, 1025)
                    }
                    conn.disconnect()
                } catch (_: Exception) {
                    callback(false, null, null, null, 12345, 1025)
                }
            }.start()
        }
    }
}
