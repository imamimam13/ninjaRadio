package com.ninjaradio.ultimate.protocol.ts3

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.ninjaradio.ultimate.model.RadioRoom
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.KeyAgreement
import kotlin.concurrent.thread

/**
 * Pure Kotlin native TeamSpeak 3 client with full encrypted session,
 * continuous keepalive ping/pong, live channel browsing, and native Opus voice RX/TX.
 */
class Ts3Client(
    val context: Context,
    val host: String,
    val port: Int = 9987,
    val nickname: String,
    val serverPassword: String = "",
    private val listener: Listener
) {
    interface Listener {
        fun onState(connected: Boolean, info: String)
        fun onTalker(callsign: String, isTalking: Boolean)
        fun onVoiceAudio(opusPacket: ByteArray)
        fun onRoomsLoaded(rooms: List<RadioRoom>)
        fun onLog(msg: String)
    }

    companion object {
        private const val TAG = "Ts3Client"
        private const val FIREWALL_KEY = "c:\\windows\\system\\firewall32.cpl"
    }

    private val running = AtomicBoolean(false)
    private var socket: DatagramSocket? = null
    private var targetAddr: InetAddress? = null
    private var targetPort: Int = if (port > 0) port else 9987
    private var ivStruct: ByteArray? = null
    private var clientId: Int = 0
    private var commandPacketId: Int = 1
    private var voicePacketId: Int = 0
    private var pingPacketId: Int = 0
    private var ackSeq: Int = 0

    private var executor: ScheduledExecutorService? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val roomsFound = LinkedHashMap<Long, RadioRoom>()
    private val clientNicknames = ConcurrentHashMap<Int, String>()
    private val pendingReliable = ConcurrentHashMap<Int, ByteArray>()

    private var lastTalkerId: Int = 0
    private var talkerIdleRunnable: Runnable? = null

    fun connect() {
        if (running.get()) return
        running.set(true)
        listener.onState(false, "Menghubungkan ke TS3 ($host:$targetPort)...")

        thread(name = "ts3-transport", isDaemon = true) {
            try {
                val cleanHost = host.trim().substringBefore(':').trim()
                val allAddrs = InetAddress.getAllByName(cleanHost)
                val addr = allAddrs.firstOrNull { it is java.net.Inet4Address } ?: allAddrs.first()
                targetAddr = addr
                val s = DatagramSocket()
                s.soTimeout = 2500
                socket = s

                Log.i(TAG, "Mulai TS3 handshake dengan $cleanHost (${addr.hostAddress}):$targetPort...")
                performHandshake(s, targetAddr!!, targetPort)

                Log.i(TAG, "TS3 session SECURE! Client ID: $clientId. Memulai receiveLoop & ping executor...")
                listener.onState(true, "Terhubung ke TS3 ($cleanHost:$targetPort)")

                // Request initial channel list
                sendCommand("channellist")
                sendCommand("clientlist")

                // Start periodic ping & retransmit timer
                val exec = Executors.newSingleThreadScheduledExecutor()
                executor = exec
                exec.scheduleWithFixedDelay({
                    if (running.get() && ivStruct != null && socket != null) {
                        sendPingSafely()
                    }
                }, 3, 3, TimeUnit.SECONDS)

                // Receive Loop (continuous keepalive, commands, voice)
                receiveLoop(s)

            } catch (e: Exception) {
                if (running.get()) {
                    Log.e(TAG, "TS3 Connection error: ${e.message}", e)
                    listener.onState(false, "Koneksi TS3 terputus: ${e.message}")
                }
            } finally {
                cleanup()
            }
        }
    }

    fun sendVoice(opus: ByteArray) {
        if (!running.get() || opus.isEmpty()) return
        val s = socket ?: return
        val addr = targetAddr ?: return
        val curIv = ivStruct ?: return
        try {
            val seq = (voicePacketId++) and 0xFFFF
            val b0 = (seq ushr 8).toByte()
            val b1 = seq.toByte()
            val cid = clientId
            val header = byteArrayOf(b0, b1, (cid ushr 8).toByte(), cid.toByte(), 0x80.toByte()) // 0x80 = unencrypted voice
            val body = byteArrayOf(b0, b1, 4) + opus // 4 = Opus Codec
            val digest = MessageDigest.getInstance("SHA-1").digest(curIv)
            val tag = digest.copyOfRange(0, 8)
            val packet = tag + header + body
            s.send(DatagramPacket(packet, packet.size, addr, targetPort))
        } catch (e: Exception) {
            if (running.get()) Log.w(TAG, "sendVoice error: ${e.message}")
        }
    }

    fun moveToChannel(targetCid: Long, password: String = "") {
        if (!running.get()) return
        val escapedPw = if (password.isNotBlank()) tsEscape(password.trim()) else ""
        val cmd = "clientmove clid=$clientId cid=$targetCid cpw=$escapedPw return_code=ninja-move"
        Log.i(TAG, "Pindah channel ke cid=$targetCid: $cmd")
        sendCommand(cmd)
    }

    fun requestChannelList() {
        if (!running.get()) return
        Log.i(TAG, "Meminta ulang daftar channel TS3...")
        sendCommand("channellist")
    }

    fun getCachedRooms(): List<RadioRoom> {
        return roomsFound.values.toList()
    }

    fun disconnect() {
        if (!running.get()) return
        running.set(false)
        thread(name = "ts3-disconnect", isDaemon = true) {
            try {
                sendCommand("clientdisconnect reasonmsg=NinjaRadio")
            } catch (_: Exception) {}
            cleanup()
        }
        listener.onState(false, "Terputus dari TS3")
    }

    private fun cleanup() {
        running.set(false)
        try { executor?.shutdownNow() } catch (_: Exception) {}
        executor = null
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        ivStruct = null
    }

    private fun performHandshake(s: DatagramSocket, addr: InetAddress, port: Int) {
        // Step 0
        val version = byteArrayOf(93, 101, 54, 48)
        val step0Data = ByteArray(21)
        System.arraycopy(version, 0, step0Data, 0, 4)
        step0Data[4] = 0
        sendInit(s, addr, port, step0Data)

        // Step 1
        val step1 = receiveInit(s, 1)

        // Step 2
        val step2Data = ByteArray(25)
        System.arraycopy(version, 0, step2Data, 0, 4)
        step2Data[4] = 2
        System.arraycopy(step1, 1, step2Data, 5, 16)
        System.arraycopy(step1, 17, step2Data, 21, 4)
        sendInit(s, addr, port, step2Data)

        // Step 3 (Puzzle)
        val step3 = receiveInit(s, 3)

        // Identity
        val identity = Ts3IdentityManager.getOrCreateIdentity(context)
        val keyPair = identity.keyPair
        val alpha = ByteArray(10)
        SecureRandom().nextBytes(alpha)

        val pubEC = keyPair.public as ECPublicKey
        val encodedPub = encodeTsPublicKey(pubEC.w)
        val strPubB64 = identity.pubB64
        val identityOffset = identity.offset

        val alphaB64 = b64(alpha)
        val omegaB64 = b64(encodedPub)
        val clientInitIvStr = "clientinitiv alpha=$alphaB64 omega=$omegaB64 ot=1 ip="
        val clientInitIvBytes = clientInitIvStr.toByteArray(StandardCharsets.UTF_8)

        // Solve Puzzle
        val puzzleSolution = solveInitPuzzle(step3)

        // Step 4
        val step4Data = ByteArray(clientInitIvBytes.size + 301)
        System.arraycopy(version, 0, step4Data, 0, 4)
        step4Data[4] = 4
        System.arraycopy(step3, 1, step4Data, 5, step3.size - 1)
        System.arraycopy(puzzleSolution, 0, step4Data, 237, puzzleSolution.size)
        System.arraycopy(clientInitIvBytes, 0, step4Data, 301, clientInitIvBytes.size)
        sendInit(s, addr, port, step4Data)

        // initivexpand
        val initExpandPacket = receive(s)
        if (initExpandPacket.size < 12) throw IOException("initivexpand packet too short")

        val fwKeyBytes = FIREWALL_KEY.toByteArray(StandardCharsets.UTF_8)
        val fwK1 = fwKeyBytes.copyOfRange(0, 16)
        val fwK2 = fwKeyBytes.copyOfRange(16, 32)
        val nonce = initExpandPacket.copyOfRange(8, 11)
        val cipher = initExpandPacket.copyOfRange(11, initExpandPacket.size)
        val header = initExpandPacket.copyOfRange(0, 8)

        val decryptedInitExpand = Eax.open(fwK1, fwK2, nonce, cipher, header)
        val initExpandPktId = u16(initExpandPacket[8], initExpandPacket[9])
        sendPreInitAck(s, addr, port, initExpandPktId)

        val initExpandStr = String(decryptedInitExpand, StandardCharsets.UTF_8)
        Log.i(TAG, "Decrypted initivexpand: ${initExpandStr.take(60)}")

        val derivedIv: ByteArray
        if (initExpandStr.startsWith("initivexpand2 ")) {
            val betaB64 = getField(initExpandStr, "beta")
            val omegaStr = getField(initExpandStr, "omega")
            val lB64 = getField(initExpandStr, "l")
            val proofStr = getField(initExpandStr, "proof")
            val serverPub = decodeTsPublicKey(omegaStr)

            val beta = decodeB64(betaB64)
            val lBytes = decodeB64(lB64)
            val proof = decodeB64(proofStr)

            try {
                val sigVerify = Signature.getInstance("SHA256withECDSA")
                sigVerify.initVerify(serverPub)
                sigVerify.update(lBytes)
                sigVerify.verify(proof)
            } catch (_: Exception) {}

            val ephemeral = Ts3ModernCrypto.generateEphemeral()
            val toSign = ephemeral.publicKey + beta
            val sigSign = Signature.getInstance("SHA256withECDSA")
            sigSign.initSign(keyPair.private)
            sigSign.update(toSign)
            val myProof = sigSign.sign()

            val ekB64 = b64(ephemeral.publicKey)
            val proofB64 = b64(myProof)
            sendPreInitCommand(s, addr, port, "clientek ek=$ekB64 proof=$proofB64")

            val sharedSecret = Ts3ModernCrypto.sharedSecret(
                Ts3ModernCrypto.deriveLicenseKey(lBytes),
                ephemeral.privateKey
            )
            derivedIv = deriveIv(alpha, beta, sharedSecret)
        } else if (initExpandStr.startsWith("initivexpand ")) {
            val betaB64 = getField(initExpandStr, "beta")
            val omegaStr = getField(initExpandStr, "omega")
            val serverPub = decodeTsPublicKey(omegaStr)

            val ka = KeyAgreement.getInstance("ECDH")
            ka.init(keyPair.private)
            ka.doPhase(serverPub, true)
            val secret = ka.generateSecret()
            val digest = MessageDigest.getInstance("SHA-1").digest(normalizeUnsigned(secret, 32))
            val beta = decodeB64(betaB64)
            derivedIv = deriveIv(alpha, beta, digest)
        } else {
            throw IOException("Unexpected initivexpand response: $initExpandStr")
        }

        ivStruct = derivedIv

        // Send clientinit
        val effectiveNick = if (nickname.isNotBlank()) nickname.trim() else "NinjaRadio_${Random().nextInt(999)}"
        val escapedNick = tsEscape(effectiveNick)
        val escapedPw = if (serverPassword.isNotBlank()) tsEscape(serverPassword.trim()) else ""
        val clientInitCmd = "clientinit client_nickname=$escapedNick client_version=3.5.0\\s[Build:\\s1582117478] client_platform=Android client_input_hardware=1 client_output_hardware=1 client_default_channel= client_default_channel_password= client_server_password=$escapedPw client_meta_data= client_version_sign=vASJQFPECG2TpUh5ATsR2c\\/gVrvsuZ9QlMpKN51F+HnanYNH3oCSGoievQX6ACjsKH3OZihNFQZ6f5HRU\\/MAAg== client_key_offset=$identityOffset client_nickname_phonetic= client_default_token= hwid="

        sendCommandDirect(s, addr, port, derivedIv, commandPacketId++, 0, clientInitCmd)
    }

    private fun receiveLoop(s: DatagramSocket) {
        val streamBuffer = ByteArrayOutputStream()
        var streamCompressed = false

        while (running.get() && !s.isClosed) {
            val pkt: ByteArray
            try {
                pkt = receive(s)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "Socket receive error: ${e.message}")
                break
            }
            if (pkt.size < 11) continue

            val pktType = pkt[10].toInt() and 0x0F
            val pktId = u16(pkt[8], pkt[9])
            val flags = pkt[10].toInt() and 0xFF
            val curIv = ivStruct ?: continue

            // 1. PING PACKET (Type 4) -> Reply immediately with PONG!
            if (pktType == 4) {
                sendPong(s, targetAddr!!, targetPort, curIv, pktId)
                continue
            }

            // 2. PONG PACKET (Type 5)
            if (pktType == 5) {
                continue
            }

            // 3. ACK PACKETS (Type 6 / 7)
            if (pktType == 6 || pktType == 7) {
                val decryptedAck = decryptPacket(curIv, pkt)
                if (decryptedAck.size >= 2) {
                    val ackPktId = u16(decryptedAck[0], decryptedAck[1])
                    pendingReliable.remove(ackPktId)
                }
                continue
            }

            // 4. VOICE PACKET (Type 0) -> DIRECT OPUS RX AUDIO
            if (pktType == 0) {
                try {
                    val decryptedVoice = decryptPacket(curIv, pkt)
                    if (decryptedVoice.size >= 5) {
                        val senderId = u16(decryptedVoice[2], decryptedVoice[3])
                        val codec = decryptedVoice[4].toInt() and 0xFF
                        val opus = decryptedVoice.copyOfRange(5, decryptedVoice.size)

                        if (codec == 4 || codec == 5) { // Opus Voice or Opus Music
                            listener.onVoiceAudio(opus)

                            val senderName = clientNicknames[senderId] ?: "TS3 User #$senderId"
                            triggerTalkerActive(senderId, senderName)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error decoding TS3 voice packet: ${e.message}")
                }
                continue
            }

            // 5. COMMAND PACKETS (Type 2 / 3)
            if (pktType == 2 || pktType == 3) {
                try {
                    val decrypted = decryptPacket(curIv, pkt)
                    sendAck(s, targetAddr!!, targetPort, curIv, pktId, pktType == 3, ackSeq++)

                    val isFragmented = (flags and 0x10) != 0
                    val isCompressed = (flags and 0x40) != 0

                    if (streamBuffer.size() == 0) {
                        streamCompressed = isCompressed
                    }
                    streamBuffer.write(decrypted)

                    if (!isFragmented) {
                        val fullPayload = streamBuffer.toByteArray()
                        streamBuffer.reset()

                        val decompressed = if (streamCompressed) {
                            try { QuickLz.decompress(fullPayload) } catch (_: Exception) { fullPayload }
                        } else {
                            fullPayload
                        }
                        val cmdStr = String(decompressed, StandardCharsets.UTF_8)
                        handleCommand(cmdStr)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to decrypt command: ${e.message}")
                }
            }
        }
    }

    private fun handleCommand(cmdStr: String) {
        for (rawLine in cmdStr.split('\r', '\n', '\u0000')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            if (line.startsWith("initserver ")) {
                val fields = parseTsFields(line)
                val aclid = fields["aclid"]?.toIntOrNull()
                if (aclid != null && aclid > 0) {
                    clientId = aclid
                    Log.i(TAG, "TS3 initserver received! My Client ID: $clientId")
                }
            } else if (line.startsWith("channellist ") || line.contains("channel_name=")) {
                parseChannelList(line)
            } else if (line.startsWith("notifychannelcreated ")) {
                parseSingleChannel(line)
                notifyRoomsUpdated()
            } else if (line.startsWith("channellistfinished") || line.contains("channellistfinished")) {
                Log.i(TAG, "TS3 channel list finished! Total rooms: ${roomsFound.size}")
                notifyRoomsUpdated()
            } else if (line.startsWith("clientlist ") || line.contains("client_nickname=")) {
                parseClientList(line)
            } else if (line.startsWith("notifycliententerview ")) {
                val fields = parseTsFields(line)
                val clid = fields["clid"]?.toIntOrNull()
                val nick = fields["client_nickname"]
                if (clid != null && !nick.isNullOrEmpty()) {
                    clientNicknames[clid] = tsUnescape(nick)
                }
            } else if (line.startsWith("notifyclientleftview ")) {
                val fields = parseTsFields(line)
                val clid = fields["clid"]?.toIntOrNull()
                if (clid != null) {
                    clientNicknames.remove(clid)
                }
            }
        }
    }

    private fun triggerTalkerActive(senderId: Int, senderName: String) {
        lastTalkerId = senderId
        talkerIdleRunnable?.let { mainHandler.removeCallbacks(it) }
        listener.onTalker(senderName, true)

        val runnable = Runnable {
            if (lastTalkerId == senderId) {
                listener.onTalker("", false)
            }
        }
        talkerIdleRunnable = runnable
        mainHandler.postDelayed(runnable, 500)
    }

    private fun notifyRoomsUpdated() {
        if (roomsFound.isNotEmpty()) {
            val list = roomsFound.values.toList()
            listener.onRoomsLoaded(list)
        }
    }

    private fun parseChannelList(command: String) {
        val payload = if (command.startsWith("channellist ")) command.substringAfter("channellist ") else command
        for (chan in payload.split('|')) {
            parseSingleChannel(chan)
        }
    }

    private fun parseSingleChannel(entry: String) {
        val fields = parseTsFields(entry)
        val cid = fields["cid"]?.toLongOrNull() ?: return
        val cpid = fields["cpid"]?.toLongOrNull() ?: 0L
        val rawName = fields["channel_name"] ?: "Channel $cid"
        var cleanName = tsUnescape(rawName).trim()
        if (cleanName.isEmpty()) cleanName = "Channel $cid"

        val desc = if (cpid > 0L) "Sub-Channel (Parent #$cpid)" else "Main Channel #$cid"
        roomsFound[cid] = RadioRoom(
            id = "ts3_$cid",
            name = cleanName,
            targetTg = cid,
            totalClients = 0,
            isPasswordProtected = false,
            description = desc
        )
    }

    private fun parseClientList(command: String) {
        val payload = if (command.startsWith("clientlist ")) command.substringAfter("clientlist ") else command
        for (entry in payload.split('|')) {
            val fields = parseTsFields(entry)
            val clid = fields["clid"]?.toIntOrNull()
            val nick = fields["client_nickname"]
            if (clid != null && !nick.isNullOrEmpty()) {
                clientNicknames[clid] = tsUnescape(nick)
            }
        }
    }

    private fun sendCommand(cmd: String) {
        val s = socket ?: return
        val addr = targetAddr ?: return
        val curIv = ivStruct ?: return
        thread(name = "ts3-cmd-worker", isDaemon = true) {
            try {
                sendCommandDirect(s, addr, targetPort, curIv, commandPacketId++, clientId, cmd)
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "sendCommand error: ${e.message}")
            }
        }
    }

    private fun sendCommandDirect(
        s: DatagramSocket,
        addr: InetAddress,
        port: Int,
        iv: ByteArray,
        cmdId: Int,
        cId: Int,
        cmd: String
    ) {
        val id = cmdId and 0xFFFF
        val header = byteArrayOf(
            (id ushr 8).toByte(), id.toByte(),
            (cId ushr 8).toByte(), cId.toByte(),
            34 // type 2 Command + 0x20 Reliable
        )
        val plaintext = cmd.toByteArray(StandardCharsets.UTF_8)
        val packet = encryptPacket(iv, 49, id, header, plaintext)
        pendingReliable[id] = packet
        s.send(DatagramPacket(packet, packet.size, addr, port))
    }

    private fun sendPingSafely() {
        val s = socket ?: return
        val addr = targetAddr ?: return
        val curIv = ivStruct ?: return
        try {
            val i = (pingPacketId++) and 0xFFFF
            val digest = MessageDigest.getInstance("SHA-1").digest(curIv)
            val header = byteArrayOf((i ushr 8).toByte(), i.toByte(), 0, 0, -124) // 0x84 = type 4 Ping, unencrypted
            val packet = digest.copyOfRange(0, 8) + header
            s.send(DatagramPacket(packet, packet.size, addr, targetPort))
        } catch (_: Exception) {}
    }

    private fun sendPong(s: DatagramSocket, addr: InetAddress, port: Int, iv: ByteArray, pingId: Int) {
        val digest = MessageDigest.getInstance("SHA-1").digest(iv)
        val header = byteArrayOf(0, 0, 0, 0, -123) // 0x85 = type 5 Pong
        val data = byteArrayOf((pingId ushr 8).toByte(), pingId.toByte())
        val packet = digest.copyOfRange(0, 8) + header + data
        s.send(DatagramPacket(packet, packet.size, addr, port))
    }

    private fun sendAck(s: DatagramSocket, addr: InetAddress, port: Int, iv: ByteArray, pktId: Int, lowPriority: Boolean, ackSeqNum: Int) {
        val seq = ackSeqNum and 0xFFFF
        val header = byteArrayOf((seq ushr 8).toByte(), seq.toByte(), 0, 0, if (lowPriority) 7 else 6)
        val data = byteArrayOf((pktId ushr 8).toByte(), pktId.toByte())
        val packet: ByteArray
        if (lowPriority) {
            val digest = MessageDigest.getInstance("SHA-1").digest(iv)
            packet = digest.copyOfRange(0, 8) + header + data
        } else {
            packet = encryptPacket(iv, 49, seq, header, data)
        }
        s.send(DatagramPacket(packet, packet.size, addr, port))
    }

    private fun sendPreInitAck(s: DatagramSocket, addr: InetAddress, port: Int, serverPktId: Int) {
        val header = byteArrayOf(0, 0, 0, 0, 6)
        val data = byteArrayOf((serverPktId ushr 8).toByte(), serverPktId.toByte())
        val fwKey = FIREWALL_KEY.toByteArray(StandardCharsets.UTF_8)
        val sealed = Eax.seal(fwKey.copyOfRange(0, 16), fwKey.copyOfRange(16, 32), header, data, 8)
        val packet = sealed.tag + header + sealed.ciphertext
        s.send(DatagramPacket(packet, packet.size, addr, port))
    }

    private fun sendPreInitCommand(s: DatagramSocket, addr: InetAddress, port: Int, cmd: String) {
        val header = byteArrayOf(0, 0, 0, 0, 2)
        val fwKey = FIREWALL_KEY.toByteArray(StandardCharsets.UTF_8)
        val data = cmd.toByteArray(StandardCharsets.UTF_8)
        val sealed = Eax.seal(fwKey.copyOfRange(0, 16), fwKey.copyOfRange(16, 32), header, data, 8)
        val packet = sealed.tag + header + sealed.ciphertext
        s.send(DatagramPacket(packet, packet.size, addr, port))
    }

    private fun sendInit(s: DatagramSocket, addr: InetAddress, port: Int, data: ByteArray) {
        val magic = "TS3INIT1".toByteArray(StandardCharsets.UTF_8)
        val header = byteArrayOf(0, 101, 0, 0, -120)
        val packet = magic + header + data
        s.send(DatagramPacket(packet, packet.size, addr, port))
    }

    private fun receive(s: DatagramSocket): ByteArray {
        val buf = ByteArray(2048)
        val dp = DatagramPacket(buf, buf.size)
        s.receive(dp)
        return dp.data.copyOfRange(0, dp.length)
    }

    private fun receiveInit(s: DatagramSocket, expectedStep: Int): ByteArray {
        for (i in 0 until 6) {
            val resp = receive(s)
            if (resp.size >= 12 && resp.copyOfRange(0, 8).contentEquals("TS3INIT1".toByteArray(StandardCharsets.UTF_8))) {
                val payload = resp.copyOfRange(11, resp.size)
                if (payload.isNotEmpty() && payload[0].toInt() == expectedStep) {
                    return payload
                }
            }
        }
        throw IOException("Expected TS3INIT1 step $expectedStep")
    }

    private fun solveInitPuzzle(stepThree: ByteArray): ByteArray {
        if (stepThree.size != 233 || stepThree[0].toInt() != 3) {
            throw IllegalArgumentException("Invalid TS3INIT1 step 3 payload")
        }
        val x = BigInteger(1, stepThree.copyOfRange(1, 65))
        val n = BigInteger(1, stepThree.copyOfRange(65, 129))
        val level = ((stepThree[129].toInt() and 0xFF) shl 24) or
                ((stepThree[130].toInt() and 0xFF) shl 16) or
                ((stepThree[131].toInt() and 0xFF) shl 8) or
                (stepThree[132].toInt() and 0xFF)

        if (level < 0 || level > 10000000) throw IllegalArgumentException("Unsupported TS3 puzzle level: $level")
        val ans = x.modPow(BigInteger.ONE.shiftLeft(level), n)
        return normalizeUnsigned(ans.toByteArray(), 64)
    }

    private fun deriveIv(alpha: ByteArray, beta: ByteArray, shared: ByteArray): ByteArray {
        val out = ByteArray(beta.size + 10)
        for (i in alpha.indices) {
            out[i] = (shared[i].toInt() xor alpha[i].toInt()).toByte()
        }
        for (i in beta.indices) {
            val idx = i + 10
            out[idx] = (shared[idx].toInt() xor beta[i].toInt()).toByte()
        }
        return out
    }

    private fun decryptPacket(iv: ByteArray, raw: ByteArray): ByteArray {
        val nonce = raw.copyOfRange(8, 11)
        if ((nonce[2].toInt() and 0x80) != 0) { // Unencrypted flag
            return raw.copyOfRange(11, raw.size)
        }
        val pktId = u16(nonce[0], nonce[1])
        val mat = packetMaterial(iv, 48, nonce[2].toInt() and 15)
        val digest = MessageDigest.getInstance("SHA-256").digest(mat)
        val key = digest.copyOfRange(0, 16)
        key[0] = (key[0].toInt() xor (pktId ushr 8)).toByte()
        key[1] = (key[1].toInt() xor (pktId and 0xFF)).toByte()
        val ivVec = digest.copyOfRange(16, 32)
        val tag = raw.copyOfRange(0, 8)
        val ciphertext = raw.copyOfRange(11, raw.size)
        return try {
            Eax.open(key, ivVec, nonce, ciphertext, tag)
        } catch (_: Exception) {
            val fwKey = FIREWALL_KEY.toByteArray(StandardCharsets.UTF_8)
            Eax.open(fwKey.copyOfRange(0, 16), fwKey.copyOfRange(16, 32), nonce, ciphertext, tag)
        }
    }

    private fun encryptPacket(iv: ByteArray, direction: Int, id: Int, header: ByteArray, plaintext: ByteArray): ByteArray {
        val mat = packetMaterial(iv, direction, header.last().toInt() and 15)
        val digest = MessageDigest.getInstance("SHA-256").digest(mat)
        val key = digest.copyOfRange(0, 16)
        key[0] = (key[0].toInt() xor (id ushr 8)).toByte()
        key[1] = (key[1].toInt() xor (id and 0xFF)).toByte()
        val ivVec = digest.copyOfRange(16, 32)
        val sealed = Eax.seal(key, ivVec, header, plaintext, 8)
        return sealed.tag + header + sealed.ciphertext
    }

    private fun packetMaterial(iv: ByteArray, direction: Int, type: Int): ByteArray {
        val out = ByteArray(iv.size + 6)
        out[0] = direction.toByte()
        out[1] = type.toByte()
        System.arraycopy(iv, 0, out, 6, iv.size)
        return out
    }

    private fun parseTsFields(text: String): Map<String, String> {
        val map = HashMap<String, String>()
        for (p in text.split(' ')) {
            val eq = p.indexOf('=')
            if (eq > 0) {
                map[p.substring(0, eq)] = p.substring(eq + 1)
            }
        }
        return map
    }

    private fun tsEscape(v: String): String {
        return v.replace("\\", "\\\\")
            .replace("/", "\\/")
            .replace(" ", "\\s")
            .replace("|", "\\p")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    private fun tsUnescape(v: String): String {
        return v.replace("\\s", " ")
            .replace("\\p", "|")
            .replace("\\n", "\n")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
    }

    private fun u16(b1: Byte, b2: Byte): Int =
        ((b1.toInt() and 0xFF) shl 8) or (b2.toInt() and 0xFF)

    private fun b64(v: ByteArray): String =
        Base64.encodeToString(v, Base64.NO_WRAP).replace("/", "\\/")

    private fun decodeB64(v: String): ByteArray =
        Base64.decode(v.replace("\\/", "/"), Base64.DEFAULT)

    private fun getField(cmd: String, name: String): String {
        for (part in cmd.split(' ')) {
            if (part.startsWith("$name=")) return part.substringAfter("=")
        }
        return ""
    }

    private fun normalizeUnsigned(arr: ByteArray, size: Int): ByteArray {
        return if (arr.size == size) arr
        else if (arr.size > size) arr.copyOfRange(arr.size - size, arr.size)
        else ByteArray(size - arr.size) + arr
    }

    private fun encodeTsPublicKey(point: ECPoint): ByteArray {
        val item3 = encodeItem(3, byteArrayOf(7, 0))
        val len32 = encodeInt(BigInteger.valueOf(32L))
        val xVal = encodeInt(point.affineX)
        val yVal = encodeInt(point.affineY)
        val inner = item3 + len32 + xVal + yVal
        return encodeItem(48, inner)
    }

    private fun encodeItem(type: Int, data: ByteArray): ByteArray {
        val lenBytes = if (data.size < 128) byteArrayOf(data.size.toByte()) else byteArrayOf(-127, data.size.toByte())
        return byteArrayOf(type.toByte()) + lenBytes + data
    }

    private fun encodeInt(value: BigInteger): ByteArray {
        return encodeItem(2, value.toByteArray())
    }

    private fun decodeTsPublicKey(encoded: String): PublicKey {
        val bytes = decodeB64(encoded)
        var idx = 0
        if ((bytes[idx++].toInt() and 0xFF) != 0x30) throw IllegalArgumentException("Invalid TS public key DER header")
        idx = skipLength(bytes, idx)
        if ((bytes[idx++].toInt() and 0xFF) != 0x03) throw IllegalArgumentException("Invalid TS public key bitstring")
        idx = skipLength(bytes, idx)
        idx += 2
        if ((bytes[idx++].toInt() and 0xFF) != 0x02) throw IllegalArgumentException("Invalid TS public key size tag")
        val sizeLen = getDerLength(bytes, idx)
        idx += 1 + sizeLen
        val xVal = getDerInteger(bytes, idx)
        idx += 2 + xVal.toByteArray().size
        val yVal = getDerInteger(bytes, idx)
        val spec = ECPublicKeySpec(ECPoint(xVal, yVal), ecParameters())
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private fun skipLength(bytes: ByteArray, offset: Int): Int {
        val b = bytes[offset].toInt() and 0xFF
        return if (b < 128) offset + 1 else offset + 1 + (b and 0x7F)
    }

    private fun getDerLength(bytes: ByteArray, offset: Int): Int {
        val b = bytes[offset].toInt() and 0xFF
        return if (b < 128) b else {
            val numBytes = b and 0x7F
            var len = 0
            for (i in 0 until numBytes) len = (len shl 8) or (bytes[offset + 1 + i].toInt() and 0xFF)
            len
        }
    }

    private fun getDerInteger(bytes: ByteArray, offset: Int): BigInteger {
        val len = bytes[offset + 1].toInt() and 0xFF
        val intBytes = bytes.copyOfRange(offset + 2, offset + 2 + len)
        return BigInteger(intBytes)
    }

    private fun ecParameters(): ECParameterSpec {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        return ap.getParameterSpec(ECParameterSpec::class.java)
    }
}
