package com.ninjaradio.ultimate.protocol.ts3

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays

object Ts3ModernCrypto {
    data class Ephemeral(val privateKey: ByteArray, val publicKey: ByteArray)

    private data class Point(
        val x: BigInteger,
        val y: BigInteger,
        val z: BigInteger = BigInteger.ONE,
        val t: BigInteger = x.multiply(y).mod(p)
    )

    private val zero: BigInteger = BigInteger.ZERO
    private val one: BigInteger = BigInteger.ONE
    private val two: BigInteger = BigInteger.valueOf(2L)
    private val p: BigInteger = one.shiftLeft(255).subtract(BigInteger.valueOf(19L))
    private val d: BigInteger = BigInteger.valueOf(-121665L).mod(p).multiply(BigInteger.valueOf(121666L).modInverse(p)).mod(p)
    private val sqrtM1: BigInteger = two.modPow(p.subtract(one).divide(BigInteger.valueOf(4L)), p)
    private val rootKey: ByteArray = byteArrayOf(
        -51, 13, -30, -82, -44, 99, 69, 80, -102, 126, 60, -3, -113, 104, -77, -36,
        117, 85, -78, -99, -52, -20, 115, -51, 24, 117, 15, -103, 56, 18, 64, -118
    )
    private val identity: Point = Point(zero, one, one, zero)
    private val base: Point = decode(
        byteArrayOf(
            88, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102,
            102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102, 102
        )
    )

    fun generateEphemeral(): Ephemeral {
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        val privateKey = Arrays.copyOf(randomBytes, 32)
        clamp(privateKey)
        val publicKey = encode(multiply(base, littleEndian(privateKey)))
        return Ephemeral(privateKey, publicKey)
    }

    fun sharedSecret(publicKey: ByteArray, privateKey: ByteArray): ByteArray {
        val point = decode(publicKey)
        val privClamped = Arrays.copyOf(privateKey, 32)
        clamp(privClamped)
        return encode(multiply(point, littleEndian(privClamped)))
    }

    fun deriveLicenseKey(license: ByteArray): ByteArray {
        var key = rootKey
        val md = MessageDigest.getInstance("SHA-256")
        var offset = 0
        while (offset < license.size) {
            val end = nullStringLength(license, offset)
            if (end == 0) break
            val part = license.copyOfRange(offset, offset + end)
            offset += end + 1
            md.reset()
            md.update(key)
            md.update(part)
            key = md.digest()
        }
        return key
    }

    private fun nullStringLength(data: ByteArray, start: Int): Int {
        for (i in start until data.size) {
            if (data[i].toInt() == 0) return i - start
        }
        return data.size - start
    }

    private fun clamp(bytes: ByteArray) {
        bytes[0] = (bytes[0].toInt() and 248).toByte()
        bytes[31] = (bytes[31].toInt() and 127).toByte()
        bytes[31] = (bytes[31].toInt() or 64).toByte()
    }

    private fun multiply(point: Point, scalar: BigInteger): Point {
        var result = identity
        var basePoint = point
        var s = scalar
        while (s > BigInteger.ZERO) {
            if (s.testBit(0)) {
                result = add(result, basePoint)
            }
            basePoint = add(basePoint, basePoint)
            s = s.shiftRight(1)
        }
        return result
    }

    private fun add(a: Point, b: Point): Point {
        val aX = a.x; val aY = a.y; val aZ = a.z; val aT = a.t
        val bX = b.x; val bY = b.y; val bZ = b.z; val bT = b.t

        val A = aY.subtract(aX).multiply(bY.subtract(bX)).mod(p)
        val B = aY.add(aX).multiply(bY.add(bX)).mod(p)
        val C = two.multiply(d).multiply(aT).multiply(bT).mod(p)
        val D = two.multiply(aZ).multiply(bZ).mod(p)
        val E = B.subtract(A).mod(p)
        val F = D.subtract(C).mod(p)
        val G = D.add(C).mod(p)
        val H = B.add(A).mod(p)

        val x3 = E.multiply(F).mod(p)
        val y3 = G.multiply(H).mod(p)
        val z3 = F.multiply(G).mod(p)
        val t3 = E.multiply(H).mod(p)
        return Point(x3, y3, z3, t3)
    }

    private fun decode(encoded: ByteArray): Point {
        if (encoded.size != 32) throw IllegalArgumentException("Invalid encoded point size")
        val copy = encoded.copyOf(32)
        val b = copy[31].toInt() and 0xFF
        val sign = (b ushr 7) and 1
        copy[31] = (b and 0x7F).toByte()
        val yVal = littleEndian(copy)
        if (yVal >= p) throw IllegalArgumentException("Invalid Edwards25519 point")

        val y2 = yVal.multiply(yVal).mod(p)
        val u = y2.subtract(one).mod(p)
        val v = d.multiply(y2).add(one).mod(p)
        val vInv = v.modInverse(p)
        val x2 = u.multiply(vInv).mod(p)

        var xVal = x2.modPow(p.add(BigInteger.valueOf(3L)).divide(BigInteger.valueOf(8L)), p)
        if (xVal.multiply(xVal).subtract(x2).mod(p) != zero) {
            xVal = xVal.multiply(sqrtM1).mod(p)
        }
        if (xVal.multiply(xVal).subtract(x2).mod(p) != zero) {
            throw IllegalArgumentException("Invalid Edwards25519 root")
        }
        if (xVal.testBit(0) != (sign == 1)) {
            xVal = p.subtract(xVal)
        }
        return Point(xVal, yVal, one, xVal.multiply(yVal).mod(p))
    }

    private fun encode(point: Point): ByteArray {
        val zInv = point.z.modInverse(p)
        val xVal = point.x.multiply(zInv).mod(p)
        val yVal = point.y.multiply(zInv).mod(p)
        val out = fixedLittleEndian(yVal, 32)
        if (xVal.testBit(0)) {
            out[31] = (out[31].toInt() or 0x80).toByte()
        }
        return out
    }

    private fun littleEndian(bytes: ByteArray): BigInteger {
        val rev = bytes.reversedArray()
        return BigInteger(1, rev)
    }

    private fun fixedLittleEndian(value: BigInteger, size: Int): ByteArray {
        var arr = value.toByteArray()
        if (arr.size > 1 && arr[0].toInt() == 0) {
            arr = arr.copyOfRange(1, arr.size)
        }
        if (arr.size > size) throw IllegalArgumentException("Value exceeds fixed size")
        val out = ByteArray(size)
        val rev = arr.reversedArray()
        System.arraycopy(rev, 0, out, 0, rev.size)
        return out
    }
}
