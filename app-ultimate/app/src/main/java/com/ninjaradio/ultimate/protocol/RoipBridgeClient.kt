package com.ninjaradio.ultimate.protocol

import android.util.Log
import com.ninjaradio.ultimate.model.ServerType
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * High-performance RoIP DVSwitch USRP client for TeamSpeak 3, Zello, AllStar, and RoIP gateways.
 * Fully complies with DVSwitch USRP Protocol Specifications (GEMINI.md Rule 1).
 */
class RoipBridgeClient(
    val host: String,
    val port: Int,
    val callsign: String,
    val dmrId: Int = 1022057,
    val initialTg: Long = 1025L,
    val serverType: ServerType,
    private val listener: Listener
) {
    interface Listener {
        fun onLog(msg: String)
        fun onState(connected: Boolean, info: String)
        fun onTalker(callsign: String, tg: Long)
        fun onPcmAudio(pcm: ShortArray)
        fun onFlush()
    }

    companion object {
        private const val TAG = "RoipBridgeClient"
        private const val USRP_TYPE_VOICE = 0
        private const val USRP_TYPE_TEXT = 2
    }

    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var rxThread: Thread? = null
    private var txThread: Thread? = null
    private var keepaliveThread: Thread? = null
    private var seq: Int = 0
    @Volatile private var currentTg: Long = initialTg

    private val txQueue = LinkedBlockingQueue<ByteArray>(50)
    private val cleanHost = host.replace(" ", "").trim()

    fun start() {
        if (running) return
        running = true
        listener.onState(false, "Menghubungkan ke ${serverType.displayName} ($cleanHost:$port)...")

        // 1. TX Worker Thread (prevents NetworkOnMainThreadException and ensures ordered packet pacing)
        txThread = thread(name = "roip-tx", isDaemon = true) {
            val targetAddr = try {
                InetAddress.getByName(cleanHost)
            } catch (e: Exception) {
                Log.e(TAG, "DNS resolution failed for $cleanHost: ${e.message}")
                listener.onState(false, "Host tidak ditemukan: ${e.message}")
                return@thread
            }

            while (running) {
                try {
                    val pktData = txQueue.take()
                    val s = socket
                    if (s != null && !s.isClosed) {
                        s.send(DatagramPacket(pktData, pktData.size, targetAddr, port))
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (running) Log.w(TAG, "TX send error: ${e.message}")
                }
            }
        }

        // 2. RX Worker Thread
        rxThread = thread(name = "roip-rx", isDaemon = true) {
            try {
                val s = DatagramSocket()
                socket = s
                s.soTimeout = 2500
                val targetAddr = InetAddress.getByName(cleanHost)

                // Send DVSwitch USRP Registration
                val regPayload = "REG:$callsign $dmrId\u0000".toByteArray(StandardCharsets.US_ASCII)
                val regPacket = buildUsrpPacket(seq++, 0, USRP_TYPE_TEXT, regPayload)
                txQueue.offer(regPacket)
                listener.onLog("Mengirim USRP REG: $callsign $dmrId ke $cleanHost:$port...")

                // Send initial Talkgroup subscription
                selectTalkgroup(currentTg)

                // Mark UDP socket connected & ready immediately
                listener.onState(true, "Terhubung ke ${serverType.displayName} ($cleanHost:$port)")

                val buf = ByteArray(2048)
                val pkt = DatagramPacket(buf, buf.size)
                var handshakeReceived = false

                while (running) {
                    try {
                        s.receive(pkt)
                        val len = pkt.length
                        if (len < 32) continue

                        // Validate USRP Magic Header (first 4 bytes == "USRP")
                        if (buf[0] != 'U'.code.toByte() || buf[1] != 'S'.code.toByte() ||
                            buf[2] != 'R'.code.toByte() || buf[3] != 'P'.code.toByte()) {
                            continue
                        }

                        // Parse packetType from byte 16: (packetType << 24)
                        val packetType = buf[16].toInt() and 0xFF

                        if (!handshakeReceived) {
                            handshakeReceived = true
                            listener.onState(true, "Terhubung ke ${serverType.displayName} (USRP OK)")
                            listener.onLog("Handshake RoIP USRP OK ✔")
                        }

                        when (packetType) {
                            USRP_TYPE_VOICE -> {
                                // 8kHz 16-bit mono Little-Endian PCM payload (S16_LE, typically 320 bytes per 20ms)
                                val payloadLen = len - 32
                                if (payloadLen >= 2) {
                                    val shortsCount = payloadLen / 2
                                    val pcmShorts = ShortArray(shortsCount)
                                    ByteBuffer.wrap(buf, 32, payloadLen)
                                        .order(ByteOrder.LITTLE_ENDIAN)
                                        .asShortBuffer()
                                        .get(pcmShorts)
                                    listener.onPcmAudio(pcmShorts)
                                }
                            }
                            USRP_TYPE_TEXT -> {
                                val textStr = String(buf, 32, len - 32, StandardCharsets.US_ASCII)
                                    .trim('\u0000', ' ', '\r', '\n')

                                if (textStr.startsWith("REG:OK")) {
                                    listener.onState(true, "Terhubung ke ${serverType.displayName} (USRP OK)")
                                } else if (textStr.startsWith("INFO:")) {
                                    val parts = textStr.removePrefix("INFO:").split(" ")
                                    var talker = ""
                                    var tg = currentTg
                                    for (p in parts) {
                                        val kv = p.split("=", ":")
                                        if (kv.size == 2) {
                                            if (kv[0].equals("call", true) || kv[0].equals("callsign", true)) {
                                                talker = kv[1]
                                            } else if (kv[0].equals("tg", true)) {
                                                tg = kv[1].toLongOrNull() ?: tg
                                            }
                                        }
                                    }
                                    if (talker.isNotEmpty()) {
                                        listener.onTalker(talker, tg)
                                    }
                                }
                            }
                        }
                    } catch (_: java.net.SocketTimeoutException) {
                        // Expected socket timeout
                    } catch (e: Exception) {
                        if (running) {
                            Log.w(TAG, "RX loop error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                listener.onLog("Gagal koneksi RoIP: ${e.message}")
                listener.onState(false, "Koneksi gagal: ${e.message}")
            } finally {
                stop()
            }
        }

        // 3. Keepalive Ping Thread (sends PING every 4 seconds)
        keepaliveThread = thread(name = "roip-ping", isDaemon = true) {
            while (running) {
                try {
                    Thread.sleep(4000)
                    if (running) {
                        val pingPayload = "PING\u0000".toByteArray(StandardCharsets.US_ASCII)
                        val pkt = buildUsrpPacket(seq++, 0, USRP_TYPE_TEXT, pingPayload)
                        txQueue.offer(pkt)
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (_: Exception) {}
            }
        }
    }

    fun selectTalkgroup(tg: Long) {
        currentTg = tg
        if (!running) return
        val infoPayload = "INFO:tg=$tg call=$callsign src=$dmrId\u0000".toByteArray(StandardCharsets.US_ASCII)
        val pkt = buildUsrpPacket(seq++, 0, USRP_TYPE_TEXT, infoPayload)
        txQueue.offer(pkt)
        Log.i(TAG, "Sent USRP TG tune to $tg for callsign $callsign (ID $dmrId)")
    }

    fun sendAudio(opusFrame: ByteArray) {
        // RoIP DVSwitch uses native 8kHz PCM (sendPcmAudio)
    }

    fun sendPcmAudio(pcm: ShortArray, isKeyed: Boolean) {
        if (!running) return
        // DVSwitch USRP audio payload is signed 16-bit Little-Endian PCM (S16_LE)
        val byteBuf = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in pcm) {
            byteBuf.putShort(sample)
        }
        val pkt = buildUsrpPacket(
            seq = seq++,
            keyFlag = if (isKeyed) 1 else 0,
            packetType = USRP_TYPE_VOICE,
            payload = byteBuf.array()
        )
        txQueue.offer(pkt)
    }

    fun stop() {
        if (!running) return
        running = false
        txThread?.interrupt()
        rxThread?.interrupt()
        keepaliveThread?.interrupt()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        txQueue.clear()
        listener.onState(false, "Terputus.")
    }

    private fun buildUsrpHeader(seq: Int, keyFlag: Int, packetType: Int): ByteArray {
        val buf = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        buf.put("USRP".toByteArray(StandardCharsets.US_ASCII))
        buf.putInt(seq)
        buf.putInt(0)
        buf.putInt(keyFlag)
        buf.putInt(0)
        buf.putInt(packetType shl 24)
        buf.putInt(0)
        buf.putInt(0)
        return buf.array()
    }

    private fun buildUsrpPacket(seq: Int, keyFlag: Int, packetType: Int, payload: ByteArray): ByteArray {
        val header = buildUsrpHeader(seq, keyFlag, packetType)
        val out = ByteArray(32 + payload.size)
        System.arraycopy(header, 0, out, 0, 32)
        System.arraycopy(payload, 0, out, 32, payload.size)
        return out
    }
}
