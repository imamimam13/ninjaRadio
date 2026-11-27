package com.ninjaradio.ultimate.protocol.mumble

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.*

/**
 * Pure Kotlin native Mumble client (TLS + Protobuf + Opus audio).
 * Connects directly to Mumble / Murmur server without external binaries.
 */
class MumbleClient(
    val context: Context? = null,
    val host: String,
    val port: Int,
    val username: String,
    val password: String = "",
    val targetRoom: String = "",
    val token: String = "",
    listener: Listener
) {
    private var listener: Listener? = listener
    enum class State {
        DISCONNECTED,
        CONNECTING,
        AUTHENTICATING,
        CONNECTED
    }

    interface Listener {
        fun onMumbleStateChanged(state: State, info: String)
        fun onMumbleAudioReceived(opusPacket: ByteArray)
        fun onMumbleUserTalking(username: String, isTalking: Boolean)
        fun onMumbleChannelListUpdated(channels: Map<Int, String>)
    }

    private var sslSocket: SSLSocket? = null
    private var rawSocket: SSLSocket? = null
    private var inStream: DataInputStream? = null
    private var outStream: DataOutputStream? = null
    private val isRunning = AtomicBoolean(false)
    private var connectThread: Thread? = null
    private var pingThread: Thread? = null
    private var readThread: Thread? = null
    private var writeThread: Thread? = null
    private val sendQueue = java.util.concurrent.LinkedBlockingQueue<Pair<Int, ByteArray>>(150)
    private var voiceSequence: Long = 0L

    @Volatile var state: State = State.DISCONNECTED
        private set

    val isConnected: Boolean get() = state == State.CONNECTED

    // Channel & Session state
    val channels = ConcurrentHashMap<Int, String>()
    var currentChannelId: Int = 0
        private set
    var mySessionId: Int = 0
        private set
    @Volatile private var isServerSyncReceived = false
    @Volatile private var hasJoinedRoom = false

    fun connect() {
        if (isRunning.get()) return
        isRunning.set(true)
        state = State.CONNECTING
        isServerSyncReceived = false
        hasJoinedRoom = false
        channels.clear()
        sendQueue.clear()
        mySessionId = 0
        currentChannelId = 0
        listener?.onMumbleStateChanged(State.CONNECTING, "Menghubungi $host:$port...")

        connectThread = Thread({
            var tempSock: SSLSocket? = null
            try {
                Log.i(TAG, "Connecting to Mumble TLS socket: $host:$port...")
                val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                })

                var keyManagers: Array<KeyManager>? = null
                try {
                    context?.let { ctx ->
                        val keyBytes = ctx.assets.open("mumble_key.der").use { it.readBytes() }
                        val certBytes = ctx.assets.open("mumble_cert.der").use { it.readBytes() }

                        val kf = java.security.KeyFactory.getInstance("RSA")
                        val privKey = kf.generatePrivate(java.security.spec.PKCS8EncodedKeySpec(keyBytes))

                        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
                        val cert = cf.generateCertificate(java.io.ByteArrayInputStream(certBytes)) as java.security.cert.X509Certificate

                        val km = object : javax.net.ssl.X509ExtendedKeyManager() {
                            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String> = arrayOf("mumble")
                            override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String = "mumble"
                            override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out java.security.Principal>?, engine: javax.net.ssl.SSLEngine?): String = "mumble"
                            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
                            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: java.net.Socket?): String? = null
                            override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, engine: javax.net.ssl.SSLEngine?): String? = null
                            override fun getCertificateChain(alias: String?): Array<java.security.cert.X509Certificate> = arrayOf(cert)
                            override fun getPrivateKey(alias: String?): java.security.PrivateKey = privKey
                        }
                        keyManagers = arrayOf(km)
                        Log.i(TAG, "Mumble native TLS client certificate loaded successfully ✔")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Note: Client certificate load note: ${e.message}")
                }

                val sslContext = SSLContext.getInstance("TLS")
                sslContext.init(keyManagers, trustAllCerts, SecureRandom())
                val factory = sslContext.socketFactory

                val socket = factory.createSocket() as SSLSocket
                rawSocket = socket
                tempSock = socket
                socket.tcpNoDelay = true
                socket.soTimeout = 25000
                socket.connect(InetSocketAddress(host, port), 8000)

                if (!isRunning.get()) {
                    try { socket.close() } catch (_: Exception) {}
                    return@Thread
                }

                socket.startHandshake()

                if (!isRunning.get()) {
                    try { socket.close() } catch (_: Exception) {}
                    return@Thread
                }

                sslSocket = socket
                inStream = DataInputStream(socket.inputStream)
                outStream = DataOutputStream(socket.outputStream)

                startWriteLoop()

                state = State.AUTHENTICATING
                if (isRunning.get()) {
                    listener?.onMumbleStateChanged(State.AUTHENTICATING, "Autentikasi ke Mumble ($username)...")
                }
                Log.i(TAG, "TLS Handshake OK! Sending Version & Authenticate...")

                sendVersion()
                sendAuthenticate()

                startPingLoop()
                startReadLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Mumble connection failed: ${e.message}")
                if (isRunning.get()) {
                    disconnect("Gagal terhubung ke Mumble: ${e.message}")
                }
            } finally {
                if (!isRunning.get()) {
                    try { tempSock?.close() } catch (_: Exception) {}
                }
            }
        }, "mumble-connect").apply { isDaemon = true; start() }
    }

    fun disconnect(reason: String = "Terputus") {
        if (!isRunning.getAndSet(false)) return
        val lis = listener
        listener = null
        state = State.DISCONNECTED
        try { rawSocket?.close() } catch (_: Exception) {}
        try { sslSocket?.close() } catch (_: Exception) {}
        rawSocket = null
        sslSocket = null
        try { inStream?.close() } catch (_: Exception) {}
        try { outStream?.close() } catch (_: Exception) {}
        inStream = null
        outStream = null
        try { connectThread?.interrupt() } catch (_: Exception) {}
        try { pingThread?.interrupt() } catch (_: Exception) {}
        try { readThread?.interrupt() } catch (_: Exception) {}
        try { writeThread?.interrupt() } catch (_: Exception) {}
        connectThread = null
        pingThread = null
        readThread = null
        writeThread = null
        sendQueue.clear()
        if (reason != "DISCONNECT_SILENT") {
            lis?.onMumbleStateChanged(State.DISCONNECTED, reason)
        }
    }

    companion object {
        private const val TAG = "MumbleClient"
        const val TYPE_VERSION = 0
        const val TYPE_UDPTUNNEL = 1
        const val TYPE_AUTHENTICATE = 2
        const val TYPE_PING = 3
        const val TYPE_REJECT = 4
        const val TYPE_SERVERSYNC = 5
        const val TYPE_CHANNEL_REMOVE = 6
        const val TYPE_CHANNEL_STATE = 7
        const val TYPE_USER_REMOVE = 8
        const val TYPE_USER_STATE = 9
    }

    private fun startWriteLoop() {
        writeThread = Thread({
            val stream = outStream ?: return@Thread
            while (isRunning.get()) {
                try {
                    val item = sendQueue.take()
                    stream.writeShort(item.first)
                    stream.writeInt(item.second.size)
                    stream.write(item.second)
                    stream.flush()
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.w(TAG, "Write loop error: ${e.message}")
                    }
                    break
                }
            }
        }, "mumble-tx").apply { isDaemon = true; start() }
    }

    private fun sendPacket(type: Int, payload: ByteArray) {
        if (!isRunning.get()) return
        sendQueue.offer(Pair(type, payload))
    }

    private fun sendVersion() {
        val payload = buildVersionProtobuf(1, 3, 4, "NinjaRTX 1.0", "Android", "13")
        sendPacket(TYPE_VERSION, payload)
    }

    private fun sendAuthenticate() {
        val payload = buildAuthProtobuf(username, password, token)
        sendPacket(TYPE_AUTHENTICATE, payload)
    }

    private fun startPingLoop() {
        pingThread = Thread {
            var pingTimestamp = 0L
            while (isRunning.get()) {
                try {
                    Thread.sleep(5000)
                    pingTimestamp++
                    val pingBytes = byteArrayOf(0x08, (pingTimestamp and 0x7F).toByte())
                    sendPacket(TYPE_PING, pingBytes)
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Ping error: ${e.message}")
                    break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun startReadLoop() {
        readThread = Thread {
            val stream = inStream ?: return@Thread
            while (isRunning.get()) {
                try {
                    val type = stream.readUnsignedShort()
                    val length = stream.readInt()
                    if (length < 0 || length > 1024 * 1024) {
                        Log.e(TAG, "Invalid packet length: $length")
                        break
                    }
                    val data = ByteArray(length)
                    stream.readFully(data)

                    when (type) {
                        TYPE_CHANNEL_STATE -> {
                            val parsed = parseChannelState(data)
                            if (parsed != null) {
                                val (cid, cname) = parsed
                                if (cname.isNotEmpty()) {
                                    channels[cid] = cname
                                    Log.i(TAG, "Channel discovered: ID $cid -> '$cname'")
                                    if (isServerSyncReceived) {
                                        scheduleChannelListUpdate()
                                    }
                                }
                                checkAndJoinTargetRoom()
                            }
                        }
                        TYPE_USER_STATE -> {
                            val parsed = parseUserState(data)
                            if (parsed != null && parsed.first == mySessionId && parsed.second >= 0) {
                                val cid = parsed.second
                                currentChannelId = cid
                                val cname = channels[cid] ?: "Room #$cid"
                                Log.i(TAG, "Server confirmed: User is in channel '$cname' (ID $cid)")
                                listener?.onMumbleStateChanged(State.CONNECTED, "Di room: $cname")
                            }
                        }
                        TYPE_SERVERSYNC -> {
                            val sid = parseServerSyncSession(data)
                            if (sid > 0) mySessionId = sid
                            Log.i(TAG, "ServerSync received! My session ID: $mySessionId")
                            state = State.CONNECTED
                            isServerSyncReceived = true
                            listener?.onMumbleStateChanged(State.CONNECTED, "Terkoneksi ke Mumble")
                            listener?.onMumbleChannelListUpdated(channels)
                            checkAndJoinTargetRoom()

                            // Fallback channel search / create in 1.5s
                            Thread {
                                try {
                                    Thread.sleep(1500)
                                    if (isRunning.get() && !hasJoinedRoom && targetRoom.isNotBlank()) {
                                        val match = findMatchingChannel(targetRoom)
                                        if (match != null) {
                                            joinChannel(match.first)
                                            hasJoinedRoom = true
                                        } else {
                                            Log.i(TAG, "Target room '$targetRoom' not found on server.")
                                        }
                                    }
                                } catch (_: Exception) {}
                            }.start()
                        }
                        TYPE_UDPTUNNEL -> {
                            handleIncomingVoice(data)
                        }
                        TYPE_REJECT -> {
                            val reason = parseRejectReason(data)
                            Log.w(TAG, "Mumble rejected authentication: $reason")
                            disconnect("Ditolak server: $reason")
                        }
                        TYPE_PING -> {
                            // Server ping heartbeat
                        }
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Read loop error: ${e.message}")
                        disconnect("Koneksi terputus: ${e.message}")
                    }
                    break
                }
            }
        }.apply { isDaemon = true; start() }
    }

    private fun cleanName(s: String): String =
        s.trim().removePrefix("#").removePrefix("/").trim().lowercase()

    private fun findMatchingChannel(target: String): Pair<Int, String>? {
        val clean = cleanName(target)
        if (clean.isEmpty()) return null
        for ((id, name) in channels) {
            val cName = cleanName(name)
            if (cName == clean || clean.endsWith("/$cName") || cName.endsWith("/$clean") || cName == clean.substringAfterLast("/")) {
                return Pair(id, name)
            }
        }
        return null
    }

    @Synchronized
    private fun checkAndJoinTargetRoom() {
        if (!isServerSyncReceived || hasJoinedRoom) return
        val match = findMatchingChannel(targetRoom)
        if (match != null) {
            val cid = match.first
            val cname = match.second
            Log.i(TAG, "Target room '$targetRoom' matched channel ID $cid ('$cname'). Moving now...")
            joinChannel(cid)
            hasJoinedRoom = true
        }
    }

    fun joinChannel(channelId: Int) {
        currentChannelId = channelId
        val out = mutableListOf<Byte>()
        if (mySessionId > 0) {
            out.add(0x08.toByte()) // tag 1 (session), wire 0
            out.addAll(encodeVarint(mySessionId.toLong()).toList())
        }
        out.add(0x28.toByte()) // tag 5 (channel_id), wire 0 -> (5 << 3) | 0 = 0x28
        out.addAll(encodeVarint(channelId.toLong()).toList())
        sendPacket(TYPE_USER_STATE, out.toByteArray())

        val name = channels[channelId] ?: "ID $channelId"
        Log.i(TAG, "Sent UserState to join channel '$name' (id $channelId, session $mySessionId)")
        listener?.onMumbleStateChanged(State.CONNECTED, "Di room: $name")
    }

    @Volatile private var channelUpdateScheduled = false
    private fun scheduleChannelListUpdate() {
        if (channelUpdateScheduled) return
        channelUpdateScheduled = true
        Thread {
            try {
                Thread.sleep(400)
                channelUpdateScheduled = false
                if (isRunning.get()) {
                    listener?.onMumbleChannelListUpdated(channels)
                }
            } catch (_: Exception) {
                channelUpdateScheduled = false
            }
        }.start()
    }

    private fun parseRejectReason(data: ByteArray): String {
        try {
            var i = 0
            var rejType = 0
            var reason = ""
            while (i < data.size) {
                val tag = data[i++].toInt() and 0xFF
                val fieldNum = tag shr 3
                val wireType = tag and 0x07
                when (wireType) {
                    0 -> {
                        var v = 0
                        var shift = 0
                        while (i < data.size) {
                            val b = data[i++].toInt() and 0xFF
                            v = v or ((b and 0x7F) shl shift)
                            if (b and 0x80 == 0) break
                            shift += 7
                        }
                        if (fieldNum == 1) rejType = v
                    }
                    2 -> {
                        var len = 0
                        var shift = 0
                        while (i < data.size) {
                            val b = data[i++].toInt() and 0xFF
                            len = len or ((b and 0x7F) shl shift)
                            if (b and 0x80 == 0) break
                            shift += 7
                        }
                        if (fieldNum == 2 && i + len <= data.size) {
                            reason = String(data, i, len, Charsets.UTF_8)
                        }
                        i += len
                    }
                    else -> break
                }
            }
            if (reason.isNotBlank()) return reason
            return when (rejType) {
                1 -> "Versi Mumble tidak cocok"
                2 -> "Username tidak valid"
                3 -> "Password pengguna salah"
                4 -> "Password server salah"
                5 -> "Username sedang digunakan"
                6 -> "Server penuh"
                7 -> "Sertifikat klien dibutuhkan oleh server"
                8 -> "Autentikasi gagal"
                else -> "Koneksi ditolak server Mumble"
            }
        } catch (_: Exception) {
            return "Koneksi ditolak server Mumble"
        }
    }

    private fun parseChannelState(data: ByteArray): Pair<Int, String>? {
        var channelId = -1
        var name = ""
        var offset = 0
        while (offset < data.size) {
            val keyInfo = readVarint(data, offset)
            val key = keyInfo.first.toInt()
            offset = keyInfo.second
            val fieldNumber = key ushr 3
            val wireType = key and 0x07

            when (wireType) {
                0 -> {
                    val valInfo = readVarint(data, offset)
                    offset = valInfo.second
                    if (fieldNumber == 1) channelId = valInfo.first.toInt()
                }
                1 -> offset += 8
                2 -> {
                    val lenInfo = readVarint(data, offset)
                    val len = lenInfo.first.toInt()
                    offset = lenInfo.second
                    if (offset + len <= data.size) {
                        if (fieldNumber == 3) {
                            name = String(data, offset, len, Charsets.UTF_8)
                        }
                        offset += len
                    } else {
                        break
                    }
                }
                5 -> offset += 4
                else -> break
            }
        }
        return if (channelId >= 0) Pair(channelId, name) else null
    }

    // Session ID -> Mumble username (filled from UserState messages)
    val userNames = ConcurrentHashMap<Int, String>()

    private fun parseUserState(data: ByteArray): Pair<Int, Int>? {
        var session = -1
        var channelId = -1
        var name = ""
        var offset = 0
        while (offset < data.size) {
            val keyInfo = readVarint(data, offset)
            val key = keyInfo.first.toInt()
            offset = keyInfo.second
            val fieldNumber = key ushr 3
            val wireType = key and 0x07

            when (wireType) {
                0 -> {
                    val valInfo = readVarint(data, offset)
                    offset = valInfo.second
                    if (fieldNumber == 1) session = valInfo.first.toInt()
                    if (fieldNumber == 5) channelId = valInfo.first.toInt()
                }
                1 -> offset += 8
                2 -> {
                    val lenInfo = readVarint(data, offset)
                    val len = lenInfo.first.toInt()
                    offset = lenInfo.second
                    if (offset + len <= data.size) {
                        if (fieldNumber == 3) {
                            name = String(data, offset, len, Charsets.UTF_8)
                        }
                        offset += len
                    } else {
                        break
                    }
                }
                5 -> offset += 4
                else -> break
            }
        }
        if (session > 0 && name.isNotEmpty()) userNames[session] = name
        return if (session > 0) Pair(session, channelId) else null
    }

    private fun parseServerSyncSession(data: ByteArray): Int {
        var session = -1
        var offset = 0
        while (offset < data.size) {
            val keyInfo = readVarint(data, offset)
            val key = keyInfo.first.toInt()
            offset = keyInfo.second
            val fieldNumber = key ushr 3
            val wireType = key and 0x07

            when (wireType) {
                0 -> {
                    val valInfo = readVarint(data, offset)
                    offset = valInfo.second
                    if (fieldNumber == 1) session = valInfo.first.toInt()
                }
                1 -> offset += 8
                2 -> {
                    val lenInfo = readVarint(data, offset)
                    offset = lenInfo.second + lenInfo.first.toInt()
                }
                5 -> offset += 4
                else -> break
            }
        }
        return session
    }

    private fun handleIncomingVoice(packet: ByteArray) {
        if (packet.isEmpty()) return
        val header = packet[0].toInt() and 0xFF
        val codec = (header shr 5) and 0x07
        if (codec == 4 && packet.size > 3) {
            var offset = 1
            val sessionInfo = readMumbleVarint(packet, offset)
            val senderSession = sessionInfo.first.toInt()
            offset = sessionInfo.second

            val seqInfo = readMumbleVarint(packet, offset)
            offset = seqInfo.second

            if (offset < packet.size) {
                val lenInfo = readMumbleVarint(packet, offset)
                val rawLen = lenInfo.first
                val isTerminator = (rawLen and 0x2000L) != 0L
                val opusLen = (rawLen and 0x1FFFL).toInt()
                offset = lenInfo.second

                if (opusLen in 1..(packet.size - offset)) {
                    val opusData = packet.copyOfRange(offset, offset + opusLen)
                    listener?.onMumbleAudioReceived(opusData)
                    listener?.onMumbleUserTalking(userNames[senderSession] ?: "Mumble-$senderSession", !isTerminator)
                }
            }
        }
    }

    fun sendOpusAudio(opusFrame: ByteArray, isLastFrame: Boolean = false) {
        if (!isConnected || outStream == null) return
        try {
            voiceSequence++
            val out = java.io.ByteArrayOutputStream()
            // Header byte: Codec 4 (Opus) -> (4 << 5) | Target 0 (normal talking) = 0x80
            out.write(0x80)

            // Sequence Varint
            out.write(encodeMumbleVarint(voiceSequence))

            // Length Varint with Terminator bit (bit 13 / 0x2000)
            val lenVal = (opusFrame.size.toLong() and 0x1FFFL) or (if (isLastFrame) 0x2000L else 0L)
            out.write(encodeMumbleVarint(lenVal))

            // Opus payload
            if (opusFrame.isNotEmpty()) {
                out.write(opusFrame)
            }

            val voicePacket = out.toByteArray()
            sendPacket(TYPE_UDPTUNNEL, voicePacket)
        } catch (e: Exception) {
            Log.w(TAG, "Voice send error: ${e.message}")
        }
    }

    // --- Varint Utilities ---
    private fun encodeVarint(value: Long): ByteArray {
        var v = value
        val out = mutableListOf<Byte>()
        while (true) {
            if ((v and 0x7FL.inv()) == 0L) {
                out.add(v.toByte())
                break
            } else {
                out.add(((v and 0x7F) or 0x80).toByte())
                v = v ushr 7
            }
        }
        return out.toByteArray()
    }

    private fun readVarint(data: ByteArray, startOffset: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var offset = startOffset
        while (offset < data.size) {
            val b = data[offset++].toInt()
            result = result or ((b.toLong() and 0x7F) shl shift)
            if ((b and 0x80) == 0) break
            shift += 7
        }
        return Pair(result, offset)
    }

    private fun encodeMumbleVarint(value: Long): ByteArray {
        val i = value
        return when {
            i < 0x80L -> byteArrayOf(i.toByte())
            i < 0x4000L -> byteArrayOf((((i shr 8) and 0x3F) or 0x80).toByte(), (i and 0xFF).toByte())
            i < 0x200000L -> byteArrayOf((((i shr 16) and 0x1F) or 0xC0).toByte(), (((i shr 8) and 0xFF)).toByte(), (i and 0xFF).toByte())
            i < 0x10000000L -> byteArrayOf((((i shr 24) and 0x0F) or 0xE0).toByte(), (((i shr 16) and 0xFF)).toByte(), (((i shr 8) and 0xFF)).toByte(), (i and 0xFF).toByte())
            else -> byteArrayOf(0xF0.toByte(), (((i shr 24) and 0xFF)).toByte(), (((i shr 16) and 0xFF)).toByte(), (((i shr 8) and 0xFF)).toByte(), (i and 0xFF).toByte())
        }
    }

    private fun readMumbleVarint(buf: ByteArray, offset: Int): Pair<Long, Int> {
        if (offset >= buf.size) return Pair(0L, offset)
        val v = buf[offset].toLong() and 0xFFL
        return when {
            (v and 0x80L) == 0x00L -> Pair(v and 0x7FL, offset + 1)
            (v and 0xC0L) == 0x80L -> {
                if (offset + 1 >= buf.size) Pair(0L, buf.size)
                else Pair(((v and 0x3FL) shl 8) or (buf[offset + 1].toLong() and 0xFFL), offset + 2)
            }
            (v and 0xE0L) == 0xC0L -> {
                if (offset + 2 >= buf.size) Pair(0L, buf.size)
                else Pair(((v and 0x1FL) shl 16) or ((buf[offset + 1].toLong() and 0xFFL) shl 8) or (buf[offset + 2].toLong() and 0xFFL), offset + 3)
            }
            (v and 0xF0L) == 0xE0L -> {
                if (offset + 3 >= buf.size) Pair(0L, buf.size)
                else Pair(((v and 0x0FL) shl 24) or ((buf[offset + 1].toLong() and 0xFFL) shl 16) or ((buf[offset + 2].toLong() and 0xFFL) shl 8) or (buf[offset + 3].toLong() and 0xFFL), offset + 4)
            }
            (v and 0xFCL) == 0xF0L -> {
                if (offset + 4 >= buf.size) Pair(0L, buf.size)
                else Pair(((buf[offset + 1].toLong() and 0xFFL) shl 24) or ((buf[offset + 2].toLong() and 0xFFL) shl 16) or ((buf[offset + 3].toLong() and 0xFFL) shl 8) or (buf[offset + 4].toLong() and 0xFFL), offset + 5)
            }
            else -> Pair(0L, offset + 1)
        }
    }

    private fun buildVersionProtobuf(major: Int, minor: Int, patch: Int, release: String, os: String, osVersion: String): ByteArray {
        val out = mutableListOf<Byte>()
        val ver = (major shl 16) or (minor shl 8) or patch
        out.add(0x08.toByte()) // field 1
        out.addAll(encodeVarint(ver.toLong()).toList())

        val rBytes = release.toByteArray(Charsets.UTF_8)
        out.add(0x12.toByte()) // field 2
        out.addAll(encodeVarint(rBytes.size.toLong()).toList())
        out.addAll(rBytes.toList())

        val osBytes = os.toByteArray(Charsets.UTF_8)
        out.add(0x1A.toByte()) // field 3
        out.addAll(encodeVarint(osBytes.size.toLong()).toList())
        out.addAll(osBytes.toList())

        val osvBytes = osVersion.toByteArray(Charsets.UTF_8)
        out.add(0x22.toByte()) // field 4
        out.addAll(encodeVarint(osvBytes.size.toLong()).toList())
        out.addAll(osvBytes.toList())

        return out.toByteArray()
    }

    private fun buildAuthProtobuf(username: String, pass: String, tok: String): ByteArray {
        val out = mutableListOf<Byte>()
        val uBytes = username.toByteArray(Charsets.UTF_8)
        out.add(0x0A.toByte()) // field 1
        out.addAll(encodeVarint(uBytes.size.toLong()).toList())
        out.addAll(uBytes.toList())

        if (pass.isNotEmpty()) {
            val pBytes = pass.toByteArray(Charsets.UTF_8)
            out.add(0x12.toByte()) // field 2
            out.addAll(encodeVarint(pBytes.size.toLong()).toList())
            out.addAll(pBytes.toList())
        }

        if (tok.isNotEmpty()) {
            val tBytes = tok.toByteArray(Charsets.UTF_8)
            out.add(0x1A.toByte()) // field 3
            out.addAll(encodeVarint(tBytes.size.toLong()).toList())
            out.addAll(tBytes.toList())
        }

        // Field 5: optional bool opus = true (tag 5, wire 0 -> (5 shl 3) | 0 = 0x28)
        out.add(0x28.toByte())
        out.add(0x01.toByte())

        return out.toByteArray()
    }
}
