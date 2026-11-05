package com.ninjadmr.app.dmr

class DmrCall(
    src: Int,
    private val dst: Int,
    private val colorCode: Int = 1,
    private val isPrivate: Boolean = false
) {
    val cleanSrc: Int = if (src > 16777215) src / 100 else src
    val lc9: ByteArray = ByteArray(9)

    init {
        lc9[0] = if (isPrivate) 3 else 0
        lc9[1] = 0
        lc9[2] = 0
        lc9[3] = (dst ushr 16).toByte()
        lc9[4] = (dst ushr 8).toByte()
        lc9[5] = dst.toByte()
        lc9[6] = (cleanSrc ushr 16).toByte()
        lc9[7] = (cleanSrc ushr 8).toByte()
        lc9[8] = cleanSrc.toByte()
    }

    private val embedded: BooleanArray by lazy {
        encodeEmbedded(lc9)
    }

    fun header(): ByteArray = dataFrame(DT_VOICE_LC_HEADER, 150)

    fun terminator(): ByteArray = dataFrame(DT_TERMINATOR_LC, 153)

    fun voiceMid(n: Int): ByteArray {
        if (n == 0) {
            return Dmr.BS_VOICE_SYNC.copyOf()
        }
        val bits = BooleanArray(32)
        val lcss = when (n) {
            1 -> {
                for (i in 0 until 32) bits[i] = embedded[i]
                1
            }
            2, 3 -> {
                val offset = (n - 1) * 32
                for (i in 0 until 32) bits[i] = embedded[offset + i]
                3
            }
            4 -> {
                val offset = (n - 1) * 32
                for (i in 0 until 32) bits[i] = embedded[offset + i]
                2
            }
            else -> 0
        }
        val emb = embCode(colorCode, lcss)
        val mid = ByteArray(6)
        mid[0] = (emb ushr 8).toByte()
        for (i in 0 until 32) {
            if (bits[i]) {
                mid[(i / 8) + 1] = (mid[(i / 8) + 1].toInt() or (0x80 ushr (i % 8))).toByte()
            }
        }
        mid[5] = emb.toByte()
        return mid
    }

    private fun dataFrame(dataType: Int, mask: Int): ByteArray {
        val p = rs129(lc9)
        val lc12 = ByteArray(12)
        System.arraycopy(lc9, 0, lc12, 0, 9)
        lc12[9] = (p[2] xor mask).toByte()
        lc12[10] = (p[1] xor mask).toByte()
        lc12[11] = (p[0] xor mask).toByte()

        val raw = bptc19696(lc12)
        val bits = BooleanArray(264)
        for (i in 0 until 98) {
            bits[i] = raw[i]
        }
        for (i in 0 until 98) {
            bits[i + 166] = raw[i + 98]
        }
        val st = slotType20(colorCode, dataType)
        for (i in 0 until 10) {
            bits[i + 98] = st[i]
            bits[i + 156] = st[i + 10]
        }
        val sync = Dmr.BS_DATA_SYNC
        for (i in 0 until 48) {
            bits[i + 108] = ((sync[i / 8].toInt() and 0xFF) and (0x80 ushr (i % 8))) != 0
        }
        val out = ByteArray(33)
        for (i in 0 until 264) {
            if (bits[i]) {
                out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
            }
        }
        return out
    }

    companion object {
        const val DT_VOICE_LC_HEADER = 1
        const val DT_TERMINATOR_LC = 2

        private val gfExp = IntArray(512)
        private val gfLog = IntArray(256)
        private val QR_ROWS = intArrayOf(115, 229, 457, 482, 439, 286, 79)

        init {
            var x = 1
            for (i in 0 until 255) {
                gfExp[i] = x
                gfLog[x] = i
                x = x shl 1
                if ((x and 256) != 0) {
                    x = x xor 285
                }
            }
            for (i in 255 until 512) {
                gfExp[i] = gfExp[i - 255]
            }
        }

        private fun gmul(a: Int, b: Int): Int {
            if (a == 0 || b == 0) return 0
            return gfExp[gfLog[a] + gfLog[b]]
        }

        fun rs129(msg: ByteArray): IntArray {
            val p = IntArray(3)
            for (m in msg) {
                val d = (m.toInt() and 0xFF) xor p[2]
                p[2] = p[1] xor gmul(14, d)
                p[1] = p[0] xor gmul(56, d)
                p[0] = gmul(64, d)
            }
            return p
        }

        private fun hamming15113(d: BooleanArray, o: Int) {
            d[o + 11] = d[o + 0] xor d[o + 1] xor d[o + 2] xor d[o + 3] xor d[o + 5] xor d[o + 7] xor d[o + 8]
            d[o + 12] = d[o + 1] xor d[o + 2] xor d[o + 3] xor d[o + 4] xor d[o + 6] xor d[o + 8] xor d[o + 9]
            d[o + 13] = d[o + 2] xor d[o + 3] xor d[o + 4] xor d[o + 5] xor d[o + 7] xor d[o + 9] xor d[o + 10]
            d[o + 14] = d[o + 0] xor d[o + 1] xor d[o + 2] xor d[o + 4] xor d[o + 6] xor d[o + 7] xor d[o + 10]
        }

        private fun hamming1393(d: BooleanArray) {
            d[9] = d[0] xor d[1] xor d[3] xor d[5] xor d[6]
            d[10] = d[0] xor d[1] xor d[2] xor d[4] xor d[6] xor d[7]
            d[11] = d[0] xor d[1] xor d[2] xor d[3] xor d[5] xor d[7] xor d[8]
            d[12] = d[0] xor d[2] xor d[4] xor d[5] xor d[8]
        }

        private fun hamming16114(d: BooleanArray, o: Int) {
            d[o + 11] = d[o + 0] xor d[o + 1] xor d[o + 2] xor d[o + 3] xor d[o + 5] xor d[o + 7] xor d[o + 8]
            d[o + 12] = d[o + 1] xor d[o + 2] xor d[o + 3] xor d[o + 4] xor d[o + 6] xor d[o + 8] xor d[o + 9]
            d[o + 13] = d[o + 2] xor d[o + 3] xor d[o + 4] xor d[o + 5] xor d[o + 7] xor d[o + 9] xor d[o + 10]
            d[o + 14] = d[o + 0] xor d[o + 1] xor d[o + 2] xor d[o + 4] xor d[o + 6] xor d[o + 7] xor d[o + 10]
            d[o + 15] = d[o + 0] xor d[o + 2] xor d[o + 5] xor d[o + 6] xor d[o + 8] xor d[o + 9] xor d[o + 10]
        }

        fun bptc19696(lc12: ByteArray): BooleanArray {
            val info = BooleanArray(96)
            for (i in 0 until 96) {
                info[i] = (lc12[i / 8].toInt() and (0x80 ushr (i % 8))) != 0
            }
            val m = BooleanArray(196)
            var k = 0
            for (r in 0 until 9) {
                val c0 = if (r == 0) 3 else 0
                for (c in c0 until 11) {
                    m[(r * 15) + 1 + c] = info[k++]
                }
            }
            val row = BooleanArray(15)
            for (r in 0 until 9) {
                for (c in 0 until 15) {
                    row[c] = m[(r * 15) + 1 + c]
                }
                hamming15113(row, 0)
                for (c in 11 until 15) {
                    m[(r * 15) + 1 + c] = row[c]
                }
            }
            val col = BooleanArray(13)
            for (c in 0 until 15) {
                for (r in 0 until 9) {
                    col[r] = m[(r * 15) + 1 + c]
                }
                hamming1393(col)
                for (r in 9 until 13) {
                    m[(r * 15) + 1 + c] = col[r]
                }
            }
            val raw = BooleanArray(196)
            for (a in 0 until 196) {
                raw[(a * 181) % 196] = m[a]
            }
            return raw
        }

        private fun polyMod(v0: Int, g: Int, deg: Int): Int {
            var v = v0
            val leading = Integer.numberOfLeadingZeros(v)
            var i = 31 - leading
            if (deg <= i) {
                while (true) {
                    if (((v ushr i) and 1) == 1) {
                        v = v xor (g shl (i - deg))
                    }
                    if (i == deg) break
                    i--
                }
            }
            return v
        }

        fun slotType20(cc: Int, dataType: Int): BooleanArray {
            val d = ((cc and 15) shl 4) or (dataType and 15)
            val r = if (d == 0) 0 else polyMod(d shl 11, 3189, 11)
            val p = (Integer.bitCount(d) + Integer.bitCount(r)) and 1
            val parity = (r shl 1) or p
            val bits = BooleanArray(20)
            for (i in 0 until 8) {
                bits[i] = ((d ushr (7 - i)) and 1) == 1
            }
            for (i in 0 until 12) {
                bits[i + 8] = ((parity ushr (11 - i)) and 1) == 1
            }
            return bits
        }

        fun embCode(cc: Int, lcss: Int): Int {
            val v = ((cc and 15) shl 3) or (lcss and 3)
            var par = 0
            for (i in 0 until 7) {
                if (((v ushr i) and 1) == 1) {
                    par = par xor QR_ROWS[i]
                }
            }
            return (v shl 9) or par
        }

        fun encodeEmbedded(lc9: ByteArray): BooleanArray {
            val src = BooleanArray(72)
            var sum = 0
            for (i in 0 until 72) {
                src[i] = ((lc9[i / 8].toInt() and 0xFF) and (0x80 ushr (i % 8))) != 0
            }
            for (b in lc9) {
                sum += b.toInt() and 0xFF
            }
            val crc = sum % 31
            val data = BooleanArray(128)
            data[106] = (crc and 1) != 0
            data[90] = (crc and 2) != 0
            data[74] = (crc and 4) != 0
            data[58] = (crc and 8) != 0
            data[42] = (crc and 16) != 0

            var bIdx = 0
            fun put(from: Int, toExcl: Int) {
                for (a in from until toExcl) {
                    data[a] = src[bIdx++]
                }
            }
            put(0, 11)
            put(16, 27)
            put(32, 42)
            put(48, 58)
            put(64, 74)
            put(80, 90)
            put(96, 106)

            for (a in 0 until 112 step 16) {
                hamming16114(data, a)
            }
            for (c in 0 until 16) {
                data[c + 112] = data[c] xor data[c + 16] xor data[c + 32] xor data[c + 48] xor data[c + 64] xor data[c + 80] xor data[c + 96]
            }

            val raw = BooleanArray(128)
            var q = 0
            for (i in 0 until 128) {
                raw[i] = data[q]
                q += 16
                if (q > 127) {
                    q -= 127
                }
            }
            return raw
        }
    }
}
