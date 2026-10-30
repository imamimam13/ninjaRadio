package com.ninjadmr.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts profile passwords at rest using an AES-256/GCM key held in the
 * Android Keystore. Values are keyed by profile name.
 */
class CredentialsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun put(profileName: String, password: String) {
        val key = key()
        if (password.isEmpty()) {
            prefs.edit().remove(profileName).apply()
            return
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val payload = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + payload.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(payload, 0, combined, iv.size, payload.size)
        prefs.edit().putString(profileName, Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }

    fun get(profileName: String): String {
        val stored = prefs.getString(profileName, null) ?: return if (profileName == "default" || profileName == "BRIDI") "ninjaradio_pass" else ""
        return try {
            val combined = Base64.decode(stored, Base64.NO_WRAP)
            val iv = combined.copyOfRange(0, IV_LEN)
            val payload = combined.copyOfRange(IV_LEN, combined.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            val res = String(cipher.doFinal(payload), Charsets.UTF_8)
            if (res.isBlank() && (profileName == "default" || profileName == "BRIDI")) "ninjaradio_pass" else res
        } catch (_: Exception) {
            if (profileName == "default" || profileName == "BRIDI") "ninjaradio_pass" else ""
        }
    }

    fun remove(profileName: String) {
        prefs.edit().remove(profileName).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS = "ninjadmr_credentials"
        private const val ALIAS = "ninjadmr_profile_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val IV_LEN = 12
    }
}
