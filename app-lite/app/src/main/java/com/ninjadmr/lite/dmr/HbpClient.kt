package com.ninjadmr.lite.dmr

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.concurrent.thread

class HbpClient(
    val host: String,
    val port: Int,
    val peerId: Int,
    val password: String,
    val callsign: String,
    val options: String,
    private val cb: Callback,
    val location: String = "Mobile, ID",
    val software: String = "NinjaRadio Lite",
    val hardware: String = "Android POC",
    val description: String = "NinjaRadio Lite",
    val slot: Int = 2,
    val talkgroup: Int = 0,
    val slotMode: String = "3",
    val rxFreq: String = "145.5000",
    val txFreq: String = "145.5000",
    val colorCode: Int = 1
) {
    companion object {
        private const val TAG = "HbpClient"
    }
    interface Callback {
        fun onDmrd(p: Dmrd)
        fun onState(s: State, info: String)
    }

    enum class State {
        STOPPED,
        LOGIN,
        AUTH,
        CONFIG,
        CONNECTED,
        RETRY
    }

    private val id: ByteArray = Dmr.int32(peerId)
    @Volatile var state: State = State.STOPPED
        private set
    @Volatile private var running = false
    @Volatile private var socket: DatagramSocket? = null
    @Volatile private var errorInfo = ""
    @Volatile private var kickReason = ""
    @Volatile private var wake = false
    private var attempt = 0

    fun start() {
        if (running) return
        running = true
        thread(name = "hbp-client", isDaemon = true) {
            loop()
        }
    }

    fun stop() {
        running = false
        val s = socket
        thread(isDaemon = true) {
            try {
                val bye = Dmr.ascii("RPTCL") + id
                s?.send(DatagramPacket(bye, bye.size))
            } catch (_: Exception) {}
            try {
                s?.close()
            } catch (_: Exception) {}
        }
        setState(State.STOPPED, "Terputus dari server.")
    }

    fun send(data: ByteArray) {
        try {
            val s = socket
            if (s == null) {
                Log.w(TAG, "send() called but socket is null!")
                return
            }
            val hdr = if (data.size >= 4) String(data, 0, 4, StandardCharsets.US_ASCII) else ""
            Log.d(TAG, "SEND len=${data.size} header='$hdr' hex=${data.take(16).joinToString("") { "%02x".format(it) }}")
            s.send(DatagramPacket(data, data.size))
        } catch (e: Exception) {
            Log.e(TAG, "send() exception: ${e.message}", e)
        }
    }

    fun kick(reason: String) {
        if (running) {
            Log.w(TAG, "kick called with reason: $reason")
            kickReason = reason
            wake = true
            try {
                socket?.close()
            } catch (_: Exception) {}
        }
    }

    private fun setState(s: State, info: String) {
        Log.i(TAG, "setState: $s, info: $info")
        state = s
        cb.onState(s, info)
    }

    private fun loop() {
        while (running) {
            var s: DatagramSocket? = null
            errorInfo = ""
            try {
                Log.i(TAG, "Resolving host $host:$port...")
                val addr = InetAddress.getByName(host)
                s = DatagramSocket()
                s.soTimeout = 1000
                s.connect(addr, port)
                socket = s

                setState(State.LOGIN, "Login $host:$port (Peer ID $peerId)...")
                send(Dmr.ascii("RPTL") + id)

                var lastRx = System.currentTimeMillis()
                var lastPing = lastRx
                val buf = ByteArray(1024)
                var ok = true

                while (running && ok) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        s.receive(packet)
                        lastRx = System.currentTimeMillis()
                        val data = packet.data.copyOf(packet.length)
                        ok = handle(data)
                    } catch (_: SocketTimeoutException) {
                        // ignore timeout, check keepalives
                    }

                    val now = System.currentTimeMillis()
                    if (state == State.CONNECTED && now - lastPing >= 5000) {
                        send(Dmr.ascii("RPTPING") + id)
                        lastPing = now
                    }
                    if (now - lastRx > 20000) {
                        errorInfo = "Server timeout (20s)"
                        Log.w(TAG, "Server timeout (20s) without packets")
                        ok = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Socket loop caught exception: ${e.message}", e)
                if (running) {
                    errorInfo = if (kickReason.isNotEmpty()) {
                        kickReason
                    } else {
                        e.message ?: e.javaClass.simpleName
                    }
                }
            } finally {
                try {
                    s?.close()
                } catch (_: Exception) {}
                socket = null
            }

            if (running) {
                val kicked = wake
                if (errorInfo.isEmpty()) {
                    errorInfo = if (kickReason.isNotEmpty()) kickReason else "Koneksi terputus"
                }
                Log.w(TAG, "Connection lost! errorInfo: '$errorInfo', kicked: $kicked")
                val delay = if (kicked) 500L else (3000L shl (attempt.coerceAtMost(5))).coerceAtMost(60000L)
                attempt++
                setState(State.RETRY, "$errorInfo - Hubungi kembali ${delay / 1000}s (#$attempt)")

                var w = 0L
                while (running && !wake && w < delay) {
                    Thread.sleep(100L)
                    w += 100L
                }
                wake = false
                kickReason = ""
            }
        }
    }

    private fun starts(d: ByteArray, t: String): Boolean {
        if (d.size < t.length) return false
        val prefix = String(d, 0, t.length, StandardCharsets.US_ASCII)
        return prefix == t
    }

    private fun getEffectiveOptions(): String {
        if (options.isNotBlank()) {
            val opt = options.trim()
            return if (opt.endsWith(";")) opt else "$opt;"
        }
        return ""
    }

    private fun sendRpto() {
        val tgOpt = getEffectiveOptions()
        if (tgOpt.isNotBlank()) {
            Log.i(TAG, "Sending RPTO options: $tgOpt")
            send(Dmr.ascii("RPTO") + id + Dmr.ascii(tgOpt))
        }
    }

    private fun handle(d: ByteArray): Boolean {
        val headerText = if (d.size >= 4) String(d, 0, 4, StandardCharsets.US_ASCII) else ""
        Log.d(TAG, "RECV packet len=${d.size}, header='$headerText', hex=${d.take(16).joinToString("") { "%02x".format(it) }}")

        if (starts(d, "DMRD")) {
            val dmrd = Dmr.parseDmrd(d)
            if (dmrd != null) {
                cb.onDmrd(dmrd)
            }
            return true
        }

        if (starts(d, "MSTPING") || starts(d, "MSTP")) {
            // Immediate reply to master ping to prevent MSTNAK disconnect
            send(Dmr.ascii("RPTPONG") + id)
            if (state == State.AUTH || state == State.CONFIG) {
                if (options.isNotBlank()) {
                    sendRpto()
                }
                attempt = 0
                setState(State.CONNECTED, "Tersambung ke DMR master")
            }
            return true
        }

        if (starts(d, "MSTPONG")) {
            // Master acknowledged our RPTPING keepalive
            return true
        }

        if (starts(d, "RPTACK") || starts(d, "MSTACK")) {
            when (state) {
                State.LOGIN -> {
                    if (d.size < 10) {
                        errorInfo = "Salt tidak valid"
                        return false
                    }
                    val salt = if (d.size >= 14) d.copyOfRange(d.size - 4, d.size) else d.copyOfRange(6, 10)
                    val md = MessageDigest.getInstance("SHA-256")
                    val pwBytes = password.toByteArray(StandardCharsets.UTF_8)
                    val hash = md.digest(salt + pwBytes)
                    send(Dmr.ascii("RPTK") + id + hash)
                    setState(State.AUTH, "Autentikasi...")
                    return true
                }
                State.AUTH -> {
                    send(configPacket())
                    if (options.isNotBlank()) {
                        sendRpto()
                    }
                    attempt = 0
                    setState(State.CONNECTED, "Tersambung ke DMR master")
                    return true
                }
                State.CONFIG, State.CONNECTED -> {
                    // Extra ACK from server (e.g. for RPTC or RPTO)
                    return true
                }
                else -> return true
            }
        }

        if (starts(d, "MSTNAK")) {
            Log.e(TAG, "Server returned MSTNAK in state $state!")
            errorInfo = "Ditolak master (MSTNAK): Cek DMR ID / Password"
            return false
        }

        if (starts(d, "MSTCL")) {
            Log.e(TAG, "Server returned MSTCL (Master Closed Connection)!")
            errorInfo = "Server menutup koneksi (MSTCL)"
            return false
        }

        return true
    }

    private fun parseFreqToHz(freqStr: String, defaultMhz: Double): Long {
        val clean = freqStr.trim().replace(",", ".")
        val d = clean.toDoubleOrNull() ?: defaultMhz
        return if (d > 1_000_000) {
            d.toLong()
        } else {
            (d * 1_000_000).toLong()
        }
    }

    private fun configPacket(): ByteArray {
        val cleanCall = callsign.ifBlank { "NOCALL" }.uppercase()
        val locStr = location.ifBlank { "Mobile, ID" }
        val descStr = description.ifBlank { "NinjaRadio" }
        val softStr = if (software.isNotBlank() && software != "NinjaRadio Lite") software else "20260911_WPSD"
        val pkgStr = if (hardware.isNotBlank() && hardware != "Android POC") hardware else "MMDVM_MMDVM_HS_Hat"

        val rxHz = parseFreqToHz(rxFreq, 145.5000)
        val txHz = parseFreqToHz(txFreq, 145.5000)
        val rxFreqStr = String.format(java.util.Locale.US, "%09d", rxHz)
        val txFreqStr = String.format(java.util.Locale.US, "%09d", txHz)
        val ccStr = String.format(java.util.Locale.US, "%02d", colorCode.coerceIn(1, 15))
        val modeChar = when (slotMode.trim()) {
            "3" -> '3' // Duplex Repeater
            "2" -> '2' // IP Network
            else -> '4' // Simplex Radio / Hotspot (default)
        }

        return Dmr.ascii("RPTC") + id +
                Dmr.pad(cleanCall, 8, ' ') +
                Dmr.pad(rxFreqStr, 9, '0') +
                Dmr.pad(txFreqStr, 9, '0') +
                Dmr.pad("01", 2, '0') +
                Dmr.pad(ccStr, 2, '0') +
                Dmr.pad("-06.2088", 8, ' ') +
                Dmr.pad("+106.8450", 9, ' ') +
                Dmr.pad("050", 3, '0') +
                Dmr.pad(locStr, 20, ' ') +
                Dmr.pad(descStr, 19, ' ') +
                Dmr.pad(modeChar.toString(), 1, ' ') +
                Dmr.pad("https://ninjadmr.app", 124, ' ') +
                Dmr.pad(softStr, 40, ' ') +
                Dmr.pad(pkgStr, 40, ' ')
    }
}
