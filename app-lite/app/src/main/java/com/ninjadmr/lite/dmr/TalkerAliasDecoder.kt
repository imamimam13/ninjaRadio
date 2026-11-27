package com.ninjadmr.lite.dmr

import java.nio.charset.StandardCharsets

class TalkerAliasDecoder(private val onAliasDecoded: (srcId: Int, alias: String) -> Unit) {

    private var currentSrcId: Int = 0
    private var burstSeq: Int = -1 // 0=A (Sync), 1=B, 2=C, 3=D, 4=E, 5=F
    private val frags = arrayOfNulls<ByteArray>(4) // frags[0]=B, frags[1]=C, frags[2]=D, frags[3]=E

    // Reassembly buffer for up to 32 characters
    private var expectedLength: Int = 0
    private var aliasFormat: Int = 1 // 1 = 8-bit ISO/IEC 8859-1
    private val charBuffer = ByteArray(32)
    private var hasHeader: Boolean = false
    private var lastEmittedAlias: String? = null

    fun reset(newSrcId: Int = 0) {
        currentSrcId = newSrcId
        burstSeq = -1
        frags.fill(null)
        expectedLength = 0
        aliasFormat = 1
        charBuffer.fill(0)
        hasHeader = false
        lastEmittedAlias = null
    }

    /**
     * Feeds a 33-byte DMR audio burst payload from a DMRD packet.
     */
    fun processBurst(payload: ByteArray, srcId: Int) {
        if (payload.size < 33) return

        if (srcId != currentSrcId || currentSrcId == 0) {
            reset(srcId)
        }

        // Extract 48-bit (6-byte) center signaling
        val mid = extractMid(payload)

        // Check for Base Station (BS) or Mobile Station (MS) Voice Sync pattern
        if (isVoiceSync(mid)) {
            burstSeq = 0 // Burst A
            frags.fill(null)
            return
        }

        if (burstSeq >= 0) {
            burstSeq = (burstSeq + 1) % 6
        } else {
            return
        }

        when (burstSeq) {
            1 -> frags[0] = byteArrayOf(mid[1], mid[2], mid[3], mid[4]) // Burst B
            2 -> frags[1] = byteArrayOf(mid[1], mid[2], mid[3], mid[4]) // Burst C
            3 -> frags[2] = byteArrayOf(mid[1], mid[2], mid[3], mid[4]) // Burst D
            4 -> {
                frags[3] = byteArrayOf(mid[1], mid[2], mid[3], mid[4]) // Burst E
                // We have all 4 fragments B, C, D, E!
                if (frags[0] != null && frags[1] != null && frags[2] != null && frags[3] != null) {
                    val raw16 = ByteArray(16)
                    System.arraycopy(frags[0]!!, 0, raw16, 0, 4)
                    System.arraycopy(frags[1]!!, 0, raw16, 4, 4)
                    System.arraycopy(frags[2]!!, 0, raw16, 8, 4)
                    System.arraycopy(frags[3]!!, 0, raw16, 12, 4)
                    decodeAndHandleFlc(raw16)
                }
            }
            5 -> {
                // Burst F: End of superframe, next will be Burst A (Sync)
                frags.fill(null)
            }
        }
    }

    private fun decodeAndHandleFlc(raw16: ByteArray) {
        val flc = decodeEmblc(raw16)
        val flco = flc[0].toInt() and 0x3F

        when (flco) {
            0x04 -> {
                // Talker Alias Header (ETSI TS 102 361-2 Annex B)
                // flc[1] = Feature Set ID (0x00)
                // flc[2] = (format << 6) | (length & 0x1F)
                val fld = flc[2].toInt() and 0xFF
                aliasFormat = (fld ushr 6) and 0x03
                expectedLength = (fld and 0x1F).coerceIn(1, 31)
                hasHeader = true
                // Chars 0..5 (up to 6 chars)
                val count = minOf(6, expectedLength)
                for (i in 0 until count) {
                    charBuffer[i] = flc[3 + i]
                }
                checkAndEmit()
            }
            0x05 -> {
                // Talker Alias Block 1 (ETSI TS 102 361-2 Annex B)
                // Chars 6..12 (up to 7 chars)
                if (expectedLength > 6) {
                    val count = minOf(7, expectedLength - 6)
                    for (i in 0 until count) {
                        charBuffer[6 + i] = flc[2 + i]
                    }
                }
                checkAndEmit()
            }
            0x06 -> {
                // Talker Alias Block 2 (ETSI TS 102 361-2 Annex B)
                // Chars 13..19 (up to 7 chars)
                if (expectedLength > 13) {
                    val count = minOf(7, expectedLength - 13)
                    for (i in 0 until count) {
                        charBuffer[13 + i] = flc[2 + i]
                    }
                }
                checkAndEmit()
            }
            0x07 -> {
                // Talker Alias Block 3 (ETSI TS 102 361-2 Annex B)
                // Chars 20..26 (up to 7 chars)
                if (expectedLength > 20) {
                    val count = minOf(7, expectedLength - 20)
                    for (i in 0 until count) {
                        charBuffer[20 + i] = flc[2 + i]
                    }
                }
                checkAndEmit()
            }
        }
    }

    private fun checkAndEmit() {
        if (!hasHeader || expectedLength <= 0) return

        val text = parseString(charBuffer, expectedLength, aliasFormat).trim()
        if (text.isNotEmpty() && text != lastEmittedAlias) {
            lastEmittedAlias = text
            onAliasDecoded(currentSrcId, text)
        }
    }

    private fun parseString(buf: ByteArray, len: Int, format: Int): String {
        return try {
            when (format) {
                0 -> {
                    // 7-bit ASCII
                    parse7BitAscii(buf, len)
                }
                1 -> {
                    // 8-bit ISO/IEC 8859-1 (Latin-1)
                    var validLen = 0
                    while (validLen < len && validLen < buf.size && buf[validLen] != 0.toByte()) {
                        validLen++
                    }
                    String(buf, 0, validLen, StandardCharsets.ISO_8859_1)
                }
                2 -> {
                    // UTF-8
                    var validLen = 0
                    while (validLen < len && validLen < buf.size && buf[validLen] != 0.toByte()) {
                        validLen++
                    }
                    String(buf, 0, validLen, StandardCharsets.UTF_8)
                }
                3 -> {
                    // UTF-16BE
                    val evenLen = (len and 1.inv()).coerceAtMost(buf.size)
                    String(buf, 0, evenLen, StandardCharsets.UTF_16BE)
                }
                else -> {
                    String(buf, 0, minOf(len, buf.size), StandardCharsets.ISO_8859_1)
                }
            }
        } catch (_: Exception) {
            ""
        }
    }

    private fun parse7BitAscii(buf: ByteArray, len: Int): String {
        val sb = StringBuilder()
        for (i in 0 until len) {
            if (i >= buf.size) break
            val b = buf[i].toInt() and 0x7F
            if (b == 0) break
            sb.append(b.toChar())
        }
        return sb.toString()
    }

    companion object {
        // BS Voice Sync: 0x75, 0x5F, 0xD7, 0xDF, 0x75, 0xF7
        private val BS_VOICE_SYNC = byteArrayOf(
            0x75.toByte(), 0x5F.toByte(), 0xD7.toByte(), 0xDF.toByte(), 0x75.toByte(), 0xF7.toByte()
        )
        // MS Voice Sync: 0xD5, 0xD7, 0xF7, 0x7F, 0xD7, 0x57
        private val MS_VOICE_SYNC = byteArrayOf(
            0xD5.toByte(), 0xD7.toByte(), 0xF7.toByte(), 0x7F.toByte(), 0xD7.toByte(), 0x57.toByte()
        )

        private val DECODE_INDICES = intArrayOf(
            0, 8, 16, 24, 32, 40, 48, 56, 64, 72, 80,
            1, 9, 17, 25, 33, 41, 49, 57, 65, 73, 81,
            2, 10, 18, 26, 34, 42, 50, 58, 66, 74,
            3, 11, 19, 27, 35, 43, 51, 59, 67, 75,
            4, 12, 20, 28, 36, 44, 52, 60, 68, 76,
            5, 13, 21, 29, 37, 45, 53, 61, 69, 77,
            6, 14, 22, 30, 38, 46, 54, 62, 70, 78
        )

        fun extractMid(payload: ByteArray): ByteArray {
            val mid = ByteArray(6)
            mid[0] = (((payload[13].toInt() and 0x0F) shl 4) or ((payload[14].toInt() and 0xF0) ushr 4)).toByte()
            mid[1] = (((payload[14].toInt() and 0x0F) shl 4) or ((payload[15].toInt() and 0xF0) ushr 4)).toByte()
            mid[2] = (((payload[15].toInt() and 0x0F) shl 4) or ((payload[16].toInt() and 0xF0) ushr 4)).toByte()
            mid[3] = (((payload[16].toInt() and 0x0F) shl 4) or ((payload[17].toInt() and 0xF0) ushr 4)).toByte()
            mid[4] = (((payload[17].toInt() and 0x0F) shl 4) or ((payload[18].toInt() and 0xF0) ushr 4)).toByte()
            mid[5] = (((payload[18].toInt() and 0x0F) shl 4) or ((payload[19].toInt() and 0xF0) ushr 4)).toByte()
            return mid
        }

        fun isVoiceSync(mid: ByteArray): Boolean {
            if (mid.size < 6) return false
            var diffBs = 0
            var diffMs = 0
            for (i in 0 until 6) {
                diffBs += java.lang.Integer.bitCount((mid[i].toInt() xor BS_VOICE_SYNC[i].toInt()) and 0xFF)
                diffMs += java.lang.Integer.bitCount((mid[i].toInt() xor MS_VOICE_SYNC[i].toInt()) and 0xFF)
            }
            return diffBs <= 3 || diffMs <= 3
        }

        fun decodeEmblc(raw16: ByteArray): ByteArray {
            val out = ByteArray(9)
            for (i in 0 until 72) {
                val bitIdx = DECODE_INDICES[i]
                val bytePos = bitIdx shr 3
                val bitPos = 7 - (bitIdx and 7)
                val bitVal = (raw16[bytePos].toInt() ushr bitPos) and 1

                val outBytePos = i shr 3
                val outBitPos = 7 - (i and 7)
                out[outBytePos] = (out[outBytePos].toInt() or (bitVal shl outBitPos)).toByte()
            }
            return out
        }
    }
}
