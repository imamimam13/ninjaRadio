package com.ninjadmr.lite.dmr

import java.nio.charset.StandardCharsets

object Dmr {
    // ETSI TS 102 361-1 Base Station (BS) Voice & Data Sync for HomeBrew / MMDVM repeaters
    val BS_VOICE_SYNC = byteArrayOf(
        0x75.toByte(), 0x5F.toByte(), 0xD7.toByte(), 0xDF.toByte(), 0x75.toByte(), 0xF7.toByte()
    )
    val BS_DATA_SYNC = byteArrayOf(
        0xDF.toByte(), 0xF5.toByte(), 0x7D.toByte(), 0x75.toByte(), 0xDF.toByte(), 0x5D.toByte()
    )

    val MS_VOICE_SYNC = byteArrayOf(
        0x7F.toByte(), 0x7D.toByte(), 0x5D.toByte(), 0xD5.toByte(), 0x7D.toByte(), 0xFD.toByte()
    )
    val MS_DATA_SYNC = byteArrayOf(
        0xD5.toByte(), 0xD7.toByte(), 0xF7.toByte(), 0x7F.toByte(), 0xD7.toByte(), 0x57.toByte()
    )
    val ZERO_MID = ByteArray(6)
    val AMBE_SILENCE = byteArrayOf(
        0xB9.toByte(), 0xE8.toByte(), 0x81.toByte(), 0x52.toByte(),
        0x61.toByte(), 0x73.toByte(), 0x00.toByte(), 0x2A.toByte(), 0x6B.toByte()
    )

    fun ascii(s: String): ByteArray = s.toByteArray(StandardCharsets.US_ASCII)

    fun pad(s: String, n: Int, padChar: Char = ' '): ByteArray {
        val padded = s.padEnd(n, padChar).take(n)
        return ascii(padded)
    }

    fun int32(v: Int): ByteArray {
        return byteArrayOf(
            (v ushr 24).toByte(),
            (v ushr 16).toByte(),
            (v ushr 8).toByte(),
            v.toByte()
        )
    }

    private fun rd24(d: ByteArray, o: Int): Int {
        return ((d[o].toInt() and 0xFF) shl 16) or
                ((d[o + 1].toInt() and 0xFF) shl 8) or
                (d[o + 2].toInt() and 0xFF)
    }

    private fun rd32(d: ByteArray, o: Int): Int {
        return (rd24(d, o) shl 8) or (d[o + 3].toInt() and 0xFF)
    }

    fun parseDmrd(d: ByteArray): Dmrd? {
        if (d.size < 53) return null
        val bits = d[15].toInt() and 0xFF
        val slot = if ((bits and 128) != 0) 2 else 1
        val isPrivate = (bits and 64) != 0
        val frameType = (bits ushr 4) and 3
        val dtype = bits and 15

        return Dmrd(
            seq = d[4].toInt() and 0xFF,
            src = rd24(d, 5),
            dst = rd24(d, 8),
            peer = rd32(d, 11),
            slot = slot,
            isPrivate = isPrivate,
            frameType = frameType,
            dtype = dtype,
            streamId = rd32(d, 16),
            payload = d.copyOfRange(20, 53)
        )
    }

    fun buildDmrd(
        seq: Int,
        src: Int,
        dst: Int,
        peer: Int,
        slot: Int,
        isPrivate: Boolean,
        frameType: Int,
        dtype: Int,
        streamId: Int,
        payload: ByteArray
    ): ByteArray {
        val d = ByteArray(55)
        System.arraycopy(ascii("DMRD"), 0, d, 0, 4)
        d[4] = seq.toByte()
        d[5] = (src ushr 16).toByte()
        d[6] = (src ushr 8).toByte()
        d[7] = src.toByte()
        d[8] = (dst ushr 16).toByte()
        d[9] = (dst ushr 8).toByte()
        d[10] = dst.toByte()
        System.arraycopy(int32(peer), 0, d, 11, 4)

        var bits = ((frameType and 3) shl 4) or (dtype and 15)
        if (slot == 2) bits = bits or 128
        if (isPrivate) bits = bits or 64
        d[15] = bits.toByte()

        System.arraycopy(int32(streamId), 0, d, 16, 4)
        System.arraycopy(payload, 0, d, 20, 33.coerceAtMost(payload.size))
        return d
    }

    fun extractAmbe(p: ByteArray): List<ByteArray> {
        val f1 = p.copyOfRange(0, 9)
        val f2 = ByteArray(9)
        System.arraycopy(p, 9, f2, 0, 4)
        f2[4] = ((p[13].toInt() and 0xF0) or (p[19].toInt() and 0x0F)).toByte()
        System.arraycopy(p, 20, f2, 5, 4)
        val f3 = p.copyOfRange(24, 33)
        return listOf(f1, f2, f3)
    }

    fun buildPayload(f1: ByteArray, f2: ByteArray, f3: ByteArray, mid: ByteArray): ByteArray {
        val p = ByteArray(33)
        System.arraycopy(f1, 0, p, 0, 9)
        System.arraycopy(f2, 0, p, 9, 4)
        p[13] = ((f2[4].toInt() and 0xF0) or ((mid[0].toInt() ushr 4) and 0x0F)).toByte()
        for (i in 0 until 5) {
            p[i + 14] = (((mid[i].toInt() and 0x0F) shl 4) or ((mid[i + 1].toInt() ushr 4) and 0x0F)).toByte()
        }
        val lastMid = mid[5].toInt()
        p[19] = ((f2[4].toInt() and 0x0F) or ((lastMid and 0x0F) shl 4)).toByte()
        System.arraycopy(f2, 5, p, 20, 4)
        System.arraycopy(f3, 0, p, 24, 9)
        return p
    }
}
