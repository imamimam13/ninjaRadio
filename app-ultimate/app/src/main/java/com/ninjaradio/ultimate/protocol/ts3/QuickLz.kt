package com.ninjaradio.ultimate.protocol.ts3

object QuickLz {
    private fun hash(value: Int): Int = (value xor (value ushr 12)) and 4095

    fun decompress(source: ByteArray, maximumSize: Int = 1048576): ByteArray {
        if (source.size < 3) throw IllegalArgumentException("QuickLZ payload is too short")
        val b = source[0].toInt() and 0xFF
        if (((b ushr 2) and 3) != 1) throw IllegalArgumentException("Unsupported QuickLZ level")
        val headerSize = if ((b and 2) != 0) 9 else 3
        val compressedSize = if (headerSize == 9) le32(source, 1) else source[1].toInt() and 0xFF
        val uncompressedSize = if (headerSize == 9) le32(source, 5) else source[2].toInt() and 0xFF
        if (compressedSize > source.size || uncompressedSize < 0 || uncompressedSize >= maximumSize) {
            throw IllegalArgumentException("Invalid QuickLZ size")
        }
        if ((b and 1) == 0) {
            return source.copyOfRange(headerSize, headerSize + uncompressedSize)
        }
        val result = ByteArray(uncompressedSize)
        val table = IntArray(4096)
        var srcIdx = headerSize
        var dstIdx = 0
        var control = 1L
        var lastHashed = 0

        while (true) {
            if (control == 1L) {
                control = le32(source, srcIdx).toLong() and 0xFFFFFFFFL
                srcIdx += 4
            }
            if ((control and 1L) != 0L) {
                control = control ushr 1
                val b2 = source[srcIdx].toInt() and 0xFF
                val offset: Int
                val matchLen: Int
                val r = b2 and 15
                if (r != 0) {
                    matchLen = r + 2
                    offset = (b2 ushr 4) or ((source[srcIdx + 1].toInt() and 0xFF) shl 4)
                    srcIdx += 2
                } else {
                    offset = (b2 ushr 4) or ((source[srcIdx + 1].toInt() and 0xFF) shl 4)
                    matchLen = source[srcIdx + 2].toInt() and 0xFF
                    srcIdx += 3
                }
                val matchSrc = table[offset]
                for (i in 0 until matchLen) {
                    result[dstIdx + i] = result[matchSrc + i]
                }
                dstIdx += matchLen
                updateTable(result, table, lastHashed, (dstIdx + 1) - matchLen)
                lastHashed = dstIdx
            } else {
                if (dstIdx >= Math.max(uncompressedSize, 10) - 10) break
                result[dstIdx] = source[srcIdx]
                control = control ushr 1
                val hPos = Math.max(dstIdx - 1, 0)
                updateTable(result, table, lastHashed, hPos)
                lastHashed = Math.max(lastHashed, hPos)
                dstIdx++
                srcIdx++
            }
        }
        while (dstIdx < uncompressedSize) {
            if (control == 1L) srcIdx += 4
            control = control ushr 1
            result[dstIdx++] = source[srcIdx++]
        }
        return result
    }

    private fun updateTable(data: ByteArray, table: IntArray, start: Int, end: Int) {
        var s = start
        if (s >= end) return
        var i = read24(data, s)
        table[hash(i)] = s
        while (true) {
            s++
            if (s >= end) return
            i = (i ushr 8) or ((data[s + 2].toInt() and 0xFF) shl 16)
            table[hash(i)] = s
        }
    }

    private fun read24(data: ByteArray, offset: Int): Int =
        ((data[offset + 2].toInt() and 0xFF) shl 16) or (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(data: ByteArray, offset: Int): Int =
        ((data[offset + 3].toInt() and 0xFF) shl 24) or (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or ((data[offset + 2].toInt() and 0xFF) shl 16)
}
