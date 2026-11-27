package com.ninjaradio.ultimate.protocol.ts3

import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object Eax {
    data class Sealed(val ciphertext: ByteArray, val tag: ByteArray)

    fun seal(key: ByteArray, nonce: ByteArray, header: ByteArray, plaintext: ByteArray, tagSize: Int): Sealed {
        val nTag = omac(key, 0, nonce)
        val cipher = ctr(key, nTag, plaintext)
        val hTag = omac(key, 1, header)
        val cTag = omac(key, 2, cipher)
        val tag = ByteArray(tagSize)
        for (i in 0 until tagSize) {
            tag[i] = (nTag[i].toInt() xor hTag[i].toInt() xor cTag[i].toInt()).toByte()
        }
        return Sealed(cipher, tag)
    }

    fun open(key: ByteArray, nonce: ByteArray, header: ByteArray, ciphertext: ByteArray, tag: ByteArray): ByteArray {
        val nTag = omac(key, 0, nonce)
        val plaintext = ctr(key, nTag, ciphertext)
        val expected = seal(key, nonce, header, plaintext, tag.size).tag
        if (!MessageDigest.isEqual(expected, tag)) {
            throw IllegalArgumentException("Invalid AES-EAX authentication tag")
        }
        return plaintext
    }

    private fun omac(key: ByteArray, domain: Int, data: ByteArray): ByteArray {
        val pad = ByteArray(16)
        pad[15] = domain.toByte()
        return cmac(key, pad + data)
    }

    private fun cmac(key: ByteArray, message: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val l = cipher.doFinal(ByteArray(16))
        val k1 = dbl(l)
        val k2 = dbl(k1)
        val lastBlock = ByteArray(16)
        val numBlocks = Math.max(1, (message.size + 15) / 16)
        val lastIdx = (numBlocks - 1) * 16

        if (message.isNotEmpty() && message.size % 16 == 0) {
            System.arraycopy(message, lastIdx, lastBlock, 0, 16)
            xor(lastBlock, k1)
        } else {
            val remain = message.size - lastIdx
            System.arraycopy(message, lastIdx, lastBlock, 0, remain)
            lastBlock[remain] = 0x80.toByte()
            xor(lastBlock, k2)
        }

        var x = ByteArray(16)
        for (i in 0 until numBlocks - 1) {
            val block = message.copyOfRange(i * 16, (i + 1) * 16)
            xor(block, x)
            x = cipher.doFinal(block)
        }
        xor(lastBlock, x)
        return cipher.doFinal(lastBlock)
    }

    private fun ctr(key: ByteArray, iv: ByteArray, input: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(input)
    }

    private fun dbl(input: ByteArray): ByteArray {
        val out = ByteArray(16)
        var carry = 0
        for (i in 15 downTo 0) {
            val b = input[i].toInt() and 0xFF
            out[i] = ((b shl 1) or carry).toByte()
            carry = (b ushr 7) and 1
        }
        if ((input[0].toInt() and 0x80) != 0) {
            out[15] = (out[15].toInt() xor 0x87).toByte()
        }
        return out
    }

    private fun xor(target: ByteArray, value: ByteArray) {
        for (i in target.indices) {
            target[i] = (target[i].toInt() xor value[i].toInt()).toByte()
        }
    }
}
