package com.ninjaradio.ultimate.protocol.ts3

import android.content.Context
import android.util.Base64
import android.util.Log
import com.ninjaradio.ultimate.model.RadioRoom
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
import javax.crypto.KeyAgreement
import kotlin.concurrent.thread

object Ts3ChannelFetcher {
    private const val TAG = "Ts3ChannelFetcher"
    private const val FIREWALL_KEY = "c:\\windows\\system\\firewall32.cpl"

    fun tsEscape(v: String): String {
        return v.replace("\\", "\\\\")
            .replace("/", "\\/")
            .replace(" ", "\\s")
            .replace("|", "\\p")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
    }

    fun fetchRooms(
        context: Context? = null,
        host: String,
        port: Int = 9987,
        nickname: String = "",
        serverPassword: String = "",
        timeoutMs: Int = 4500,
        onResult: (List<RadioRoom>?, String?) -> Unit
    ) {
        Log.e(TAG, "fetchRooms() called for host=$host, port=$port, nickname=$nickname")
        thread(name = "Ts3Fetch-$host", isDaemon = true) {
            var socket: DatagramSocket? = null
            try {
                val cleanHost = host.trim().substringBefore(':').trim()
                val targetPort = if (port > 0) port else 9987
                Log.e(TAG, "Resolving address $cleanHost...")
                val allAddrs = InetAddress.getAllByName(cleanHost)
                val targetAddr = allAddrs.firstOrNull { it is java.net.Inet4Address } ?: allAddrs.first()
                Log.e(TAG, "Resolved $cleanHost -> $targetAddr, sending Step 0 to port $targetPort")

                socket = DatagramSocket()
                socket.soTimeout = 2500

                // 1. Handshake Step 0
                val version = byteArrayOf(93, 101, 54, 48)
                val step0Data = ByteArray(21)
                System.arraycopy(version, 0, step0Data, 0, 4)
                step0Data[4] = 0
                sendInit(socket, targetAddr, targetPort, step0Data)

                // Receive Step 1
                val step1 = receiveInit(socket, 1)
                Log.i(TAG, "Received TS3INIT1 Step 1 (len=${step1.size})")

                // Send Step 2
                val step2Data = ByteArray(25)
                System.arraycopy(version, 0, step2Data, 0, 4)
                step2Data[4] = 2
                System.arraycopy(step1, 1, step2Data, 5, 16)
                System.arraycopy(step1, 17, step2Data, 21, 4)
                sendInit(socket, targetAddr, targetPort, step2Data)

                // Receive Step 3 (puzzle)
                val step3 = receiveInit(socket, 3)
                Log.i(TAG, "Received TS3INIT1 Step 3 puzzle (len=${step3.size})")

                // Persistent Identity or New EC Keypair
                val identity = if (context != null) Ts3IdentityManager.getOrCreateIdentity(context) else null
                val keyPair = identity?.keyPair ?: run {
                    val kpg = KeyPairGenerator.getInstance("EC")
                    kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
                    kpg.generateKeyPair()
                }

                val alpha = ByteArray(10)
                SecureRandom().nextBytes(alpha)

                val pubEC = keyPair.public as ECPublicKey
                val encodedPub = encodeTsPublicKey(pubEC.w)
                val strPubB64 = identity?.pubB64 ?: Base64.encodeToString(encodedPub, Base64.NO_WRAP)
                val identityOffset = identity?.offset ?: findIdentityKeyOffset(strPubB64, 10)
                Log.e(TAG, "Using persistent identity: UID=${identity?.uid ?: "ephemeral"}, offset=$identityOffset")

                val alphaB64 = b64(alpha)
                val omegaB64 = b64(encodedPub)
                val clientInitIvStr = "clientinitiv alpha=$alphaB64 omega=$omegaB64 ot=1 ip="
                val clientInitIvBytes = clientInitIvStr.toByteArray(StandardCharsets.UTF_8)

                // Solve Puzzle
                val puzzleSolution = solveInitPuzzle(step3)

                // Construct Step 4
                val step4Data = ByteArray(clientInitIvBytes.size + 301)
                System.arraycopy(version, 0, step4Data, 0, 4)
                step4Data[4] = 4
                System.arraycopy(step3, 1, step4Data, 5, step3.size - 1)
                System.arraycopy(puzzleSolution, 0, step4Data, 237, puzzleSolution.size)
                System.arraycopy(clientInitIvBytes, 0, step4Data, 301, clientInitIvBytes.size)

                sendInit(socket, targetAddr, targetPort, step4Data)

                // Receive initivexpand
                val initExpandPacket = receive(socket)
                if (initExpandPacket.size < 12) {
                    throw IOException("initivexpand packet too short")
                }

                val fwKeyBytes = FIREWALL_KEY.toByteArray(StandardCharsets.UTF_8)
                val fwK1 = fwKeyBytes.copyOfRange(0, 16)
                val fwK2 = fwKeyBytes.copyOfRange(16, 32)
                val nonce = initExpandPacket.copyOfRange(8, 11)
                val cipher = initExpandPacket.copyOfRange(11, initExpandPacket.size)
                val header = initExpandPacket.copyOfRange(0, 8)

                val decryptedInitExpand = Eax.open(fwK1, fwK2, nonce, cipher, header)
                val initExpandPktId = u16(initExpandPacket[8], initExpandPacket[9])

                // Send Pre-Init ACK
                sendPreInitAck(socket, targetAddr, targetPort, initExpandPktId)

                val initExpandStr = String(decryptedInitExpand, StandardCharsets.UTF_8)
                Log.e(TAG, "Decrypted initivexpand: ${initExpandStr.take(60)}")
                val ivStruct: ByteArray

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
                        if (!sigVerify.verify(proof)) {
                            Log.w(TAG, "TS3 server proof verification warning")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Proof verify error: ${e.message}")
                    }

                    val ephemeral = Ts3ModernCrypto.generateEphemeral()
                    val toSign = ephemeral.publicKey + beta
                    val sigSign = Signature.getInstance("SHA256withECDSA")
                    sigSign.initSign(keyPair.private)
                    sigSign.update(toSign)
                    val myProof = sigSign.sign()

                    val ekB64 = b64(ephemeral.publicKey)
                    val proofB64 = b64(myProof)
                    sendPreInitCommand(socket, targetAddr, targetPort, "clientek ek=$ekB64 proof=$proofB64")

                    val sharedSecret = Ts3ModernCrypto.sharedSecret(
                        Ts3ModernCrypto.deriveLicenseKey(lBytes),
                        ephemeral.privateKey
                    )
                    ivStruct = deriveIv(alpha, beta, sharedSecret)
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
                    ivStruct = deriveIv(alpha, beta, digest)
                } else {
                    throw IOException("Unexpected initivexpand response: $initExpandStr")
                }

                // 2. Session is SECURE! Send clientinit
                var clientCommandId = 1
                val effectiveNick = if (nickname.isNotBlank()) nickname.trim() else "NinjaRadio_${Random().nextInt(999)}"
                val escapedNick = tsEscape(effectiveNick)
                val escapedPw = if (serverPassword.isNotBlank()) tsEscape(serverPassword.trim()) else ""
                val clientInitCmd = "clientinit client_nickname=$escapedNick client_version=3.5.0\\s[Build:\\s1582117478] client_platform=Android client_input_hardware=1 client_output_hardware=1 client_default_channel= client_default_channel_password= client_server_password=$escapedPw client_meta_data= client_version_sign=vASJQFPECG2TpUh5ATsR2c\\/gVrvsuZ9QlMpKN51F+HnanYNH3oCSGoievQX6ACjsKH3OZihNFQZ6f5HRU\\/MAAg== client_key_offset=$identityOffset client_nickname_phonetic= client_default_token= hwid="

                sendCommand(socket, targetAddr, targetPort, ivStruct, clientCommandId++, 0, clientInitCmd)

                // 3. Receive Command Loop until channellistfinished or timeout
                val roomsFound = LinkedHashMap<Long, RadioRoom>()
                val startTime = System.currentTimeMillis()
                var ackSeq = 0
                val streamBuffer = java.io.ByteArrayOutputStream()
                var streamCompressed = false

                while (System.currentTimeMillis() - startTime < timeoutMs) {
                    val pkt: ByteArray
                    try {
                        pkt = receive(socket)
                    } catch (e: SocketTimeoutException) {
                        if (roomsFound.isNotEmpty()) break
                        continue
                    }
                    if (pkt.size < 11) continue

                    val pktType = pkt[10].toInt() and 0x0F
                    val pktId = u16(pkt[8], pkt[9])
                    val flags = pkt[10].toInt() and 0xFF

                    if (pktType == 4) { // Ping
                        sendPong(socket, targetAddr, targetPort, ivStruct, pktId)
                        continue
                    }

                    if (pktType == 2 || pktType == 3) { // Command
                        try {
                            val decrypted = decryptPacket(ivStruct, pkt)
                            sendAck(socket, targetAddr, targetPort, ivStruct, pktId, pktType == 3, ackSeq++)

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
                                Log.e(TAG, "rx TS3 cmd: ${cmdStr.take(120)}")

                                for (rawLine in cmdStr.split('\r', '\n', '\u0000')) {
                                    val line = rawLine.trim()
                                    if (line.isEmpty()) continue

                                    if (line.startsWith("channellist ") || line.contains("channel_name=")) {
                                        parseChannelList(line, roomsFound)
                                    }
                                    if (line.startsWith("notifychannelcreated ")) {
                                        parseSingleChannel(line, roomsFound)
                                    }
                                    if (line.startsWith("channellistfinished") || line.contains("channellistfinished")) {
                                        Log.e(TAG, "Channel list finished! Total rooms: ${roomsFound.size}")
                                        break
                                    }
                                }

                                if (cmdStr.contains("channellistfinished")) {
                                    break
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to decrypt command packet: ${e.message}")
                        }
                    }
                }

                if (roomsFound.isNotEmpty()) {
                    val result = roomsFound.values.toList()
                    Log.e(TAG, "Successfully fetched ${result.size} TS3 rooms from $host:$targetPort")
                    onResult(result, null)
                } else {
                    onResult(null, "Server tidak mengirimkan daftar channel")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Gagal mengambil channel TS3 dari $host:$port", e)
                onResult(null, e.message ?: "Koneksi UDP timeout")
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) {}
            }
        }
    }

    private fun parseChannelList(command: String, map: LinkedHashMap<Long, RadioRoom>) {
        val payload = if (command.startsWith("channellist ")) command.substringAfter("channellist ") else command
        val channels = payload.split('|')
        for (chan in channels) {
            parseSingleChannel(chan, map)
        }
    }

    private fun parseSingleChannel(entry: String, map: LinkedHashMap<Long, RadioRoom>) {
        val fields = parseTsFields(entry)
        val cid = fields["cid"]?.toLongOrNull() ?: return
        val cpid = fields["cpid"]?.toLongOrNull() ?: 0L
        val rawName = fields["channel_name"] ?: "Channel $cid"
        var cleanName = tsUnescape(rawName).trim()

        if (cleanName.isEmpty()) {
            cleanName = "Channel $cid"
        }

        val desc = if (cpid > 0L) "Sub-Channel (Parent #$cpid)" else "Main Channel #$cid"
        map[cid] = RadioRoom(
            id = "ts3_$cid",
            name = cleanName,
            targetTg = cid,
            totalClients = 0,
            isPasswordProtected = false,
            description = desc
        )
    }

    private fun parseTsFields(text: String): Map<String, String> {
        val map = HashMap<String, String>()
        val parts = text.split(' ')
        for (p in parts) {
            val eq = p.indexOf('=')
            if (eq > 0) {
                map[p.substring(0, eq)] = p.substring(eq + 1)
            }
        }
        return map
    }

    private fun tsUnescape(v: String): String {
        return v.replace("\\s", " ")
            .replace("\\p", "|")
            .replace("\\n", "\n")
            .replace("\\/", "/")
            .replace("\\\\", "\\")
    }

    private fun sendInit(socket: DatagramSocket, addr: InetAddress, port: Int, data: ByteArray) {
        val magic = "TS3INIT1".toByteArray(StandardCharsets.UTF_8)
        val header = byteArrayOf(0, 101, 0, 0, -120) // 0x00, 0x65, 0x00, 0x00, 0x88
        val packet = magic + header + data
        socket.send(DatagramPacket(packet, packet.size, addr, port))
    }

    private fun receive(socket: DatagramSocket): ByteArray {
        val buf = ByteArray(2048)
        val dp = DatagramPacket(buf, buf.size)
        socket.receive(dp)
        return dp.data.copyOfRange(0, dp.length)
    }

    private fun receiveInit(socket: DatagramSocket, expectedStep: Int): ByteArray {
        for (i in 0 until 6) {
            val resp = receive(socket)
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

    fun findIdentityKeyOffset(publicKey: String, minimumLevel: Int): Long {
        val md = MessageDigest.getInstance("SHA-1")
        var j = 0L
        while (true) {
            val bytes = (publicKey + j).toByteArray(StandardCharsets.US_ASCII)
            val digest = md.digest(bytes)
            var zeros = 0
            var nonZeroFound = false
            for (b in digest) {
                val v = b.toInt() and 0xFF
                for (i in 7 downTo 0) {
                    if (((1 shl i) and v) != 0) {
                        nonZeroFound = true
                        break
                    }
                    zeros++
                }
                if (nonZeroFound) break
            }
            if (zeros >= minimumLevel) {
                return j
            }
            j++
        }
    }

    private fun sendPreInitAck(socket: DatagramSocket, addr: InetAddress, port: Int, serverPktId: Int) {
        val header = byteArrayOf(0, 0, 0, 0, 6)
        val data = byteArrayOf((serverPktId ushr 8).toByte(), serverPktId.toByte())
        val fwKey = FIREWALL_KEY.toByteArray(StandardCharsets.UTF_8)
        val sealed = Eax.seal(fwKey.copyOfRange(0, 16), fwKey.copyOfRange(16, 32), header, data, 8)
        val packet = sealed.tag + header + sealed.ciphertext
        socket.send(DatagramPacket(packet, packet.size, addr, port))
    }

    private fun sendPreInitCommand(socket: DatagramSocket, addr: InetAddress, port: Int, cmd: String) {
        val header = byteArrayOf(0, 0, 0, 0, 2)
        val fwKey = FIREWALL_KEY.toByteArray(StandardCharsets.UTF_8)
        val data = cmd.toByteArray(StandardCharsets.UTF_8)
        val sealed = Eax.seal(fwKey.copyOfRange(0, 16), fwKey.copyOfRange(16, 32), header, data, 8)
        val packet = sealed.tag + header + sealed.ciphertext
        socket.send(DatagramPacket(packet, packet.size, addr, port))
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

    private fun sendCommand(
        socket: DatagramSocket,
        addr: InetAddress,
        port: Int,
        ivStruct: ByteArray,
        cmdId: Int,
        clientId: Int,
        cmd: String
    ) {
        val id = cmdId and 0xFFFF
        val header = byteArrayOf(
            (id ushr 8).toByte(), id.toByte(),
            (clientId ushr 8).toByte(), clientId.toByte(),
            34 // type 2 + reliable 0x20
        )
        val plaintext = cmd.toByteArray(StandardCharsets.UTF_8)
        val packet = encryptPacket(ivStruct, 49, id, header, plaintext)
        socket.send(DatagramPacket(packet, packet.size, addr, port))
    }

    private fun sendAck(
        socket: DatagramSocket,
        addr: InetAddress,
        port: Int,
        ivStruct: ByteArray,
        pktId: Int,
        lowPriority: Boolean,
        ackSeq: Int
    ) {
        val seq = ackSeq and 0xFFFF
        val header = byteArrayOf(
            (seq ushr 8).toByte(), seq.toByte(),
            0, 0,
            if (lowPriority) 7 else 6
        )
        val data = byteArrayOf((pktId ushr 8).toByte(), pktId.toByte())
        val packet: ByteArray
        if (lowPriority) {
            val digest = MessageDigest.getInstance("SHA-1").digest(ivStruct)
            packet = digest.copyOfRange(0, 8) + header + data
        } else {
            packet = encryptPacket(ivStruct, 49, seq, header, data)
        }
        socket.send(DatagramPacket(packet, packet.size, addr, port))
    }

    private fun sendPong(
        socket: DatagramSocket,
        addr: InetAddress,
        port: Int,
        ivStruct: ByteArray,
        pingId: Int
    ) {
        val digest = MessageDigest.getInstance("SHA-1").digest(ivStruct)
        val header = byteArrayOf(0, 0, 0, 0, -123)
        val data = byteArrayOf((pingId ushr 8).toByte(), pingId.toByte())
        val packet = digest.copyOfRange(0, 8) + header + data
        socket.send(DatagramPacket(packet, packet.size, addr, port))
    }

    private fun decryptPacket(ivStruct: ByteArray, raw: ByteArray): ByteArray {
        val nonce = raw.copyOfRange(8, 11)
        if ((nonce[2].toInt() and 0x80) != 0) { // Unencrypted
            return raw.copyOfRange(11, raw.size)
        }
        val pktId = u16(nonce[0], nonce[1])
        val mat = packetMaterial(ivStruct, 48, nonce[2].toInt() and 15)
        val digest = MessageDigest.getInstance("SHA-256").digest(mat)
        val key = digest.copyOfRange(0, 16)
        key[0] = (key[0].toInt() xor (pktId ushr 8)).toByte()
        key[1] = (key[1].toInt() xor (pktId and 0xFF)).toByte()
        val iv = digest.copyOfRange(16, 32)
        val tag = raw.copyOfRange(0, 8)
        val ciphertext = raw.copyOfRange(11, raw.size)
        return try {
            Eax.open(key, iv, nonce, ciphertext, tag)
        } catch (_: Exception) {
            val fwKey = FIREWALL_KEY.toByteArray(StandardCharsets.UTF_8)
            Eax.open(fwKey.copyOfRange(0, 16), fwKey.copyOfRange(16, 32), nonce, ciphertext, tag)
        }
    }

    private fun encryptPacket(
        ivStruct: ByteArray,
        direction: Int,
        id: Int,
        header: ByteArray,
        plaintext: ByteArray
    ): ByteArray {
        val mat = packetMaterial(ivStruct, direction, header.last().toInt() and 15)
        val digest = MessageDigest.getInstance("SHA-256").digest(mat)
        val key = digest.copyOfRange(0, 16)
        key[0] = (key[0].toInt() xor (id ushr 8)).toByte()
        key[1] = (key[1].toInt() xor (id and 0xFF)).toByte()
        val iv = digest.copyOfRange(16, 32)
        val sealed = Eax.seal(key, iv, header, plaintext, 8)
        return sealed.tag + header + sealed.ciphertext
    }

    private fun packetMaterial(ivStruct: ByteArray, direction: Int, type: Int): ByteArray {
        val out = ByteArray(ivStruct.size + 6)
        out[0] = direction.toByte()
        out[1] = type.toByte()
        System.arraycopy(ivStruct, 0, out, 6, ivStruct.size)
        return out
    }

    private fun u16(b1: Byte, b2: Byte): Int =
        ((b1.toInt() and 0xFF) shl 8) or (b2.toInt() and 0xFF)

    private fun b64(v: ByteArray): String =
        Base64.encodeToString(v, Base64.NO_WRAP).replace("/", "\\/")

    private fun decodeB64(v: String): ByteArray =
        Base64.decode(v.replace("\\/", "/"), Base64.DEFAULT)

    private fun getField(cmd: String, name: String): String {
        for (part in cmd.split(' ')) {
            if (part.startsWith("$name=")) {
                return part.substringAfter("=")
            }
        }
        return ""
    }

    private fun normalizeUnsigned(arr: ByteArray, size: Int): ByteArray {
        return if (arr.size == size) arr
        else if (arr.size > size) arr.copyOfRange(arr.size - size, arr.size)
        else ByteArray(size - arr.size) + arr
    }

    fun encodeTsPublicKey(point: ECPoint): ByteArray {
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
        idx += 2 // skip bitstring payload (7, 0)
        if ((bytes[idx++].toInt() and 0xFF) != 0x02) throw IllegalArgumentException("Invalid TS public key size tag")
        val sizeLen = getDerLength(bytes, idx)
        idx += 1 + sizeLen // skip size
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
            for (i in 0 until numBytes) {
                len = (len shl 8) or (bytes[offset + 1 + i].toInt() and 0xFF)
            }
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
