package com.ninjaradio.ultimate.protocol.ts3

import android.content.Context
import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * Manages persistent client identity for TeamSpeak 3.
 * TeamSpeak 3 binds user permissions, server groups, and registration to the
 * client's public key (Unique Identifier / UID). By persisting the keypair and
 * calculated security offset, the user's identity is permanent across app restarts.
 */
object Ts3IdentityManager {
    private const val TAG = "Ts3IdentityManager"
    private const val PREFS_NAME = "ninja_ts3_identity_prefs"
    private const val KEY_PRIV = "ts3_priv_key_pkcs8"
    private const val KEY_PUB = "ts3_pub_key_x509"
    private const val KEY_OFFSET = "ts3_identity_offset"
    private const val KEY_UID = "ts3_client_uid"

    @Volatile private var cachedKeyPair: KeyPair? = null
    @Volatile private var cachedOffset: Long = -1L
    @Volatile private var cachedUid: String = ""

    data class Identity(
        val keyPair: KeyPair,
        val offset: Long,
        val uid: String,
        val pubB64: String
    )

    fun getOrCreateIdentity(context: Context): Identity {
        val kpMem = cachedKeyPair
        val offMem = cachedOffset
        val uidMem = cachedUid
        if (kpMem != null && offMem >= 0L && uidMem.isNotEmpty()) {
            val pubEC = kpMem.public as ECPublicKey
            val encodedPub = Ts3ChannelFetcher.encodeTsPublicKey(pubEC.w)
            val pubB64 = Base64.encodeToString(encodedPub, Base64.NO_WRAP)
            return Identity(kpMem, offMem, uidMem, pubB64)
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val privStr = prefs.getString(KEY_PRIV, null)
        val pubStr = prefs.getString(KEY_PUB, null)
        val offset = prefs.getLong(KEY_OFFSET, -1L)
        val savedUid = prefs.getString(KEY_UID, null)

        if (!privStr.isNullOrEmpty() && !pubStr.isNullOrEmpty() && offset >= 0L && !savedUid.isNullOrEmpty()) {
            try {
                val kf = KeyFactory.getInstance("EC")
                val privBytes = Base64.decode(privStr, Base64.DEFAULT)
                val pubBytes = Base64.decode(pubStr, Base64.DEFAULT)
                val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val pubKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))
                val kp = KeyPair(pubKey, privKey)
                cachedKeyPair = kp
                cachedOffset = offset
                cachedUid = savedUid

                val pubEC = pubKey as ECPublicKey
                val encodedPub = Ts3ChannelFetcher.encodeTsPublicKey(pubEC.w)
                val pubB64 = Base64.encodeToString(encodedPub, Base64.NO_WRAP)
                Log.i(TAG, "Loaded persistent TS3 identity: UID=$savedUid, offset=$offset")
                return Identity(kp, offset, savedUid, pubB64)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore persistent identity: ${e.message}, creating new identity")
            }
        }

        // Generate a new persistent identity
        Log.i(TAG, "Generating new persistent TS3 identity keypair (secp256r1)...")
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val newKp = kpg.generateKeyPair()

        val pubEC = newKp.public as ECPublicKey
        val encodedPub = Ts3ChannelFetcher.encodeTsPublicKey(pubEC.w)
        val pubB64 = Base64.encodeToString(encodedPub, Base64.NO_WRAP)

        // Calculate UID: base64(sha1(encodedPub))
        val sha1 = MessageDigest.getInstance("SHA-1").digest(encodedPub)
        val newUid = Base64.encodeToString(sha1, Base64.NO_WRAP)

        // Calculate identity offset with min level 10
        val newOffset = Ts3ChannelFetcher.findIdentityKeyOffset(pubB64, 10)

        prefs.edit()
            .putString(KEY_PRIV, Base64.encodeToString(newKp.private.encoded, Base64.NO_WRAP))
            .putString(KEY_PUB, Base64.encodeToString(newKp.public.encoded, Base64.NO_WRAP))
            .putLong(KEY_OFFSET, newOffset)
            .putString(KEY_UID, newUid)
            .apply()

        cachedKeyPair = newKp
        cachedOffset = newOffset
        cachedUid = newUid

        Log.i(TAG, "Saved new persistent TS3 identity: UID=$newUid, offset=$newOffset")
        return Identity(newKp, newOffset, newUid, pubB64)
    }
}
