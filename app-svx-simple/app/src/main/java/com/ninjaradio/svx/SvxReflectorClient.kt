package com.ninjaradio.svx

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal SvxReflector client (protocol V2, plain TCP + plain UDP).
 *
 * Wire format (from sm0svx/svxlink src/svxlink/reflector/ReflectorMsg.h):
 *  - TCP: Async::FramedTcpConnection -> [uint32 BE frame length][payload]
 *         payload = [uint16 type][members...]
 *  - Async::Msg packing: ints big-endian, string = uint16 len + bytes,
 *    vector/set = uint16 count + elements.
 *  - UDP V2: [uint16 type][uint16 clientId][uint16 seq][members...]
 *
 * Handshake: C->S ProtoVer(2,0) ; S->C AuthChallenge(20B) ;
 *            C->S AuthResponse(callsign, HMAC-SHA1(password, challenge)) ;
 *            S->C AuthOk ; S->C ServerInfo(clientId, nodes, codecs)
 * V2 clients skip the V3 TLS / certificate enrollment entirely.
 */
class SvxReflectorClient(private val listener: Listener) {

    interface Listener {
        fun onLog(msg: String)
        fun onState(state: State)
        fun onNodes(nodes: List<String>)
        fun onTalkerStart(tg: Long, callsign: String)
        fun onTalkerStop(tg: Long, callsign: String)
        fun onAudio(opus: ByteArray)
        fun onFlush()
    }

    enum class State { DISCONNECTED, CONNECTING, AUTHENTICATING, CONNECTED }

    companion object {
        // TCP message types
        const val MSG_HEARTBEAT = 1
        const val MSG_PROTO_VER = 5
        const val MSG_PROTO_VER_DOWNGRADE = 6
        const val MSG_AUTH_CHALLENGE = 10
        const val MSG_AUTH_RESPONSE = 11
        const val MSG_AUTH_OK = 12
        const val MSG_ERROR = 13
        const val MSG_SERVER_INFO = 100
        const val MSG_NODE_LIST = 101
        const val MSG_NODE_JOINED = 102
        const val MSG_NODE_LEFT = 103
        const val MSG_TALKER_START = 104
        const val MSG_TALKER_STOP = 105
        const val MSG_SELECT_TG = 106
        const val MSG_TG_MONITOR = 107
        const val MSG_REQUEST_QSY = 109
        const val MSG_NODE_INFO = 111

        // UDP message types
        const val UDP_HEARTBEAT = 1
        const val UDP_AUDIO = 101
        const val UDP_FLUSH_SAMPLES = 102
        const val UDP_ALL_SAMPLES_FLUSHED = 103

        const val PROTO_MAJOR = 2
        const val PROTO_MINOR = 0
    }

    @Volatile var state = State.DISCONNECTED
        private set
    @Volatile var currentTg: Long = 0
        private set

    private var socket: Socket? = null
    private var out: DataOutputStream? = null
    private val writeLock = Any()

    private var udp: DatagramSocket? = null
    private var serverAddr: InetAddress? = null
    private var serverPort = 0
    private var clientId = 0
    private var udpSeq = 0
    private val udpLock = Any()

    @Volatile private var running = false
    private val nodes = sortedSetOf<String>()

    private var callsign = ""
    private var password = ""

    // ------------------------------------------------------------------ API

    fun connect(host: String, port: Int, callsign: String, password: String, tg: Long) {
        if (running) return
        this.callsign = callsign.trim().uppercase()
        this.password = password
        this.currentTg = tg
        running = true
        Thread({ runTcp(host.trim(), port) }, "svx-tcp").start()
    }

    fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        try { udp?.close() } catch (_: Exception) {}
    }

    fun selectTg(tg: Long) {
        currentTg = tg
        if (state == State.CONNECTED) {
            sendTcp(MSG_SELECT_TG) { it.writeInt(tg.toInt()) }
            listener.onLog("SelectTG #$tg")
        }
    }

    fun sendAudio(opusFrame: ByteArray) {
        sendUdp(UDP_AUDIO) {
            it.writeShort(opusFrame.size)
            it.write(opusFrame)
        }
    }

    fun sendFlush() = sendUdp(UDP_FLUSH_SAMPLES) {}

    // ------------------------------------------------------------ TCP side

    private fun runTcp(host: String, port: Int) {
        setState(State.CONNECTING)
        listener.onLog("Connecting to $host:$port ...")
        try {
            val s = Socket()
            s.tcpNoDelay = true
            s.connect(InetSocketAddress(host, port), 8000)
            s.soTimeout = 30_000 // server sends heartbeat every ~10 s
            socket = s
            serverAddr = s.inetAddress
            serverPort = port
            out = DataOutputStream(s.getOutputStream())
            val input = DataInputStream(s.getInputStream())
            listener.onLog("TCP connected, sending ProtoVer $PROTO_MAJOR.$PROTO_MINOR")
            setState(State.AUTHENTICATING)
            sendProtoVer(PROTO_MAJOR, PROTO_MINOR)

            startHeartbeat()

            while (running) {
                val len = try { input.readInt() } catch (e: SocketTimeoutException) {
                    throw IOException("TCP heartbeat timeout")
                }
                if (len < 2 || len > 65536) throw IOException("Bad frame length $len")
                val buf = ByteArray(len)
                input.readFully(buf)
                handleTcp(DataInputStream(buf.inputStream()))
            }
        } catch (e: Exception) {
            if (running) listener.onLog("Disconnected: ${e.message}")
        } finally {
            running = false
            try { socket?.close() } catch (_: Exception) {}
            try { udp?.close() } catch (_: Exception) {}
            socket = null; udp = null; out = null
            synchronized(nodes) { nodes.clear() }
            listener.onNodes(emptyList())
            setState(State.DISCONNECTED)
        }
    }

    private fun handleTcp(d: DataInputStream) {
        when (val type = d.readUnsignedShort()) {
            MSG_HEARTBEAT -> Unit
            MSG_PROTO_VER_DOWNGRADE -> {
                val maj = d.readUnsignedShort(); val min = d.readUnsignedShort()
                listener.onLog("Server asks downgrade to $maj.$min")
                sendProtoVer(maj, min)
            }
            MSG_AUTH_CHALLENGE -> {
                val challenge = readBytes(d)
                listener.onLog("Auth challenge received (${challenge.size} B), answering as $callsign")
                val digest = hmacSha1(password.toByteArray(Charsets.UTF_8), challenge)
                sendTcp(MSG_AUTH_RESPONSE) {
                    writeString(it, callsign)
                    it.writeShort(digest.size)
                    it.write(digest)
                }
            }
            MSG_AUTH_OK -> listener.onLog("Auth OK ✔")
            MSG_ERROR -> {
                val msg = readString(d)
                listener.onLog("Server error: $msg")
                disconnect()
            }
            MSG_SERVER_INFO -> {
                d.readUnsignedShort() // reserved
                clientId = d.readUnsignedShort()
                val nodeList = readStringList(d)
                val codecs = readStringList(d)
                listener.onLog("ServerInfo: clientId=$clientId codecs=$codecs nodes=${nodeList.size}")
                synchronized(nodes) { nodes.clear(); nodes.addAll(nodeList) }
                publishNodes()
                startUdp()
                setState(State.CONNECTED)
                sendNodeInfo()
                selectTg(currentTg)
                sendTcp(MSG_TG_MONITOR) { it.writeShort(1); it.writeInt(currentTg.toInt()) }
            }
            MSG_NODE_LIST -> {
                val l = readStringList(d)
                synchronized(nodes) { nodes.clear(); nodes.addAll(l) }
                publishNodes()
            }
            MSG_NODE_JOINED -> {
                val cs = readString(d)
                synchronized(nodes) { nodes.add(cs) }
                listener.onLog("+ $cs joined"); publishNodes()
            }
            MSG_NODE_LEFT -> {
                val cs = readString(d)
                synchronized(nodes) { nodes.remove(cs) }
                listener.onLog("- $cs left"); publishNodes()
            }
            MSG_TALKER_START -> {
                val tg = d.readInt().toLong() and 0xffffffffL
                val cs = readString(d)
                listener.onTalkerStart(tg, cs)
            }
            MSG_TALKER_STOP -> {
                val tg = d.readInt().toLong() and 0xffffffffL
                val cs = readString(d)
                listener.onTalkerStop(tg, cs)
            }
            MSG_REQUEST_QSY -> {
                val tg = d.readInt().toLong() and 0xffffffffL
                listener.onLog("QSY request to TG #$tg (ignored)")
            }
            else -> Unit // ignore unknown messages (forward compatible)
        }
    }

    private fun sendProtoVer(major: Int, minor: Int) =
        sendTcp(MSG_PROTO_VER) { it.writeShort(major); it.writeShort(minor) }

    private fun sendNodeInfo() {
        val json = """{"sw":"NinjaSvxAndroid","swVer":"0.1.0","nodeClass":"Android","nodeLocation":"Mobile"}"""
        sendTcp(MSG_NODE_INFO) { writeString(it, json) }
    }

    private fun sendTcp(type: Int, body: (DataOutputStream) -> Unit) {
        val o = out ?: return
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        d.writeShort(type)
        body(d)
        val payload = bos.toByteArray()
        try {
            synchronized(writeLock) {
                o.writeInt(payload.size)
                o.write(payload)
                o.flush()
            }
        } catch (e: IOException) {
            listener.onLog("TCP write failed: ${e.message}")
            disconnect()
        }
    }

    private fun startHeartbeat() {
        Thread({
            var tick = 0
            while (running) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                tick++
                if (tick % 5 == 0) sendTcp(MSG_HEARTBEAT) {}
                if (tick % 10 == 0 && state == State.CONNECTED) sendUdp(UDP_HEARTBEAT) {}
            }
        }, "svx-hb").start()
    }

    // ------------------------------------------------------------ UDP side

    private fun startUdp() {
        val sock = DatagramSocket()
        udp = sock
        synchronized(udpLock) { udpSeq = 0 }
        // First datagram registers our UDP source port with the reflector
        sendUdp(UDP_HEARTBEAT) {}
        Thread({
            val buf = ByteArray(4096)
            val pkt = DatagramPacket(buf, buf.size)
            while (running && !sock.isClosed) {
                try {
                    pkt.length = buf.size
                    sock.receive(pkt)
                    if (pkt.length < 6) continue
                    val d = DataInputStream(buf.inputStream(0, pkt.length))
                    val type = d.readUnsignedShort()
                    d.readUnsignedShort() // client id
                    d.readUnsignedShort() // seq
                    when (type) {
                        UDP_AUDIO -> {
                            val audio = readBytes(d)
                            if (audio.isNotEmpty()) listener.onAudio(audio)
                        }
                        UDP_FLUSH_SAMPLES -> {
                            listener.onFlush()
                            sendUdp(UDP_ALL_SAMPLES_FLUSHED) {}
                        }
                        else -> Unit
                    }
                } catch (e: Exception) {
                    if (running) listener.onLog("UDP rx error: ${e.message}")
                }
            }
        }, "svx-udp").start()
        listener.onLog("UDP ready (local port ${sock.localPort})")
    }

    private fun sendUdp(type: Int, body: (DataOutputStream) -> Unit) {
        val sock = udp ?: return
        val addr = serverAddr ?: return
        val bos = ByteArrayOutputStream()
        val d = DataOutputStream(bos)
        synchronized(udpLock) {
            d.writeShort(type)
            d.writeShort(clientId)
            d.writeShort(udpSeq and 0xffff)
            udpSeq = (udpSeq + 1) and 0xffff
            body(d)
            val data = bos.toByteArray()
            try {
                sock.send(DatagramPacket(data, data.size, addr, serverPort))
            } catch (e: Exception) {
                if (running) listener.onLog("UDP send failed: ${e.message}")
            }
        }
    }

    // --------------------------------------------------------------- utils

    private fun setState(s: State) { state = s; listener.onState(s) }

    private fun publishNodes() = listener.onNodes(synchronized(nodes) { nodes.toList() })

    private fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA1")
        // Empty keys are rejected by SecretKeySpec; svxreflector requires a password anyway
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(1) else key, "HmacSHA1"))
        return mac.doFinal(data)
    }

    private fun writeString(d: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        d.writeShort(b.size); d.write(b)
    }

    private fun readString(d: DataInputStream): String = String(readBytes(d), Charsets.UTF_8)

    private fun readBytes(d: DataInputStream): ByteArray {
        val n = d.readUnsignedShort()
        val b = ByteArray(n); d.readFully(b); return b
    }

    private fun readStringList(d: DataInputStream): List<String> {
        val n = d.readUnsignedShort()
        return List(n) { readString(d) }
    }
}
