package com.ghostlink.zerorf.core

import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * GhostLink Cryptographic Engine v2.
 *
 * Implements:
 * 1. Ephemeral Key Agreement (ECDH on NIST P-256)
 * 2. HKDF-SHA256 Key Expansion (Extract + Expand)
 * 3. AES-GCM-256 Authenticated Encryption with Per-Chunk Unique Nonce
 * 4. Header AAD (Additional Authenticated Data) Binding to prevent replay/substitution
 */
object CryptoEngine {

    private const val EC_CURVE = "secp256r1"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128
    private val HKDF_SALT = "GhostLink-ZeroRF-v2-Salt".toByteArray(Charsets.UTF_8)

    data class SessionKeys(
        val sessionId: Long,
        val aesKey: ByteArray,     // 32 bytes (256-bit AES)
        val baseIv: ByteArray      // 12 bytes
    )

    /**
     * Generates an ephemeral NIST P-256 EC KeyPair for session key agreement.
     */
    fun generateEphemeralKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec(EC_CURVE), SecureRandom())
        return kpg.generateKeyPair()
    }

    /**
     * Serializes an ECPublicKey into a 65-byte uncompressed point format (0x04 || X || Y).
     */
    fun serializePublicKey(pubKey: PublicKey): ByteArray {
        val ecPub = pubKey as ECPublicKey
        val w = ecPub.w
        val xBytes = w.affineX.toByteArray().stripLeadingZero()
        val yBytes = w.affineY.toByteArray().stripLeadingZero()

        val out = ByteArray(65)
        out[0] = 0x04.toByte() // Uncompressed point indicator
        System.arraycopy(xBytes, 0, out, 1 + (32 - xBytes.size), xBytes.size)
        System.arraycopy(yBytes, 0, out, 33 + (32 - yBytes.size), yBytes.size)
        return out
    }

    /**
     * Deserializes a 65-byte uncompressed point into an ECPublicKey.
     */
    fun deserializePublicKey(bytes: ByteArray): PublicKey {
        require(bytes.size == 65 && bytes[0] == 0x04.toByte()) {
            "Invalid uncompressed EC point format (expected 65 bytes starting with 0x04)"
        }
        val xBytes = bytes.copyOfRange(1, 33)
        val yBytes = bytes.copyOfRange(33, 65)
        val x = BigInteger(1, xBytes)
        val y = BigInteger(1, yBytes)
        val point = ECPoint(x, y)

        val dummyPub = generateEphemeralKeyPair().public as ECPublicKey
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(ECPublicKeySpec(point, dummyPub.params))
    }

    /**
     * Computes ECDH shared secret point Z between privateKey and peerPublicKey.
     */
    fun computeSharedSecret(myPrivate: PrivateKey, peerPublic: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPrivate)
        ka.doPhase(peerPublic, true)
        return ka.generateSecret()
    }

    /**
     * Expands raw shared secret into SessionKeys via HKDF-SHA256.
     */
    fun deriveSessionKeys(sharedSecret: ByteArray): SessionKeys {
        // 1. HKDF-Extract(salt, sharedSecret) -> PRK
        val hmacExtract = Mac.getInstance("HmacSHA256")
        hmacExtract.init(SecretKeySpec(HKDF_SALT, "HmacSHA256"))
        val prk = hmacExtract.doFinal(sharedSecret)

        // 2. HKDF-Expand(PRK, info, 32) -> AES Key
        val aesKey = hkdfExpand(prk, "GhostLink-v2-AES-GCM-Key".toByteArray(Charsets.UTF_8), 32)

        // 3. HKDF-Expand(PRK, info, 12) -> Base IV
        val baseIv = hkdfExpand(prk, "GhostLink-v2-Base-IV".toByteArray(Charsets.UTF_8), 12)

        // 4. HKDF-Expand(PRK, info, 4) -> Session ID (uint32)
        val sessionBytes = hkdfExpand(prk, "GhostLink-v2-Session-ID".toByteArray(Charsets.UTF_8), 4)
        val sessionId = (ByteBuffer.wrap(sessionBytes).int.toLong() and 0xFFFFFFFFL).coerceAtLeast(1L)

        return SessionKeys(sessionId, aesKey, baseIv)
    }

    /**
     * Computes unique deterministic 12-byte IV for chunkIndex: baseIv XOR chunkIndex.
     */
    fun computeChunkIv(baseIv: ByteArray, chunkIndex: Long): ByteArray {
        val iv = baseIv.copyOf()
        val indexBuf = ByteBuffer.allocate(8).putLong(chunkIndex).array()
        for (i in 0 until 8) {
            iv[4 + i] = (iv[4 + i].toInt() xor indexBuf[i].toInt()).toByte()
        }
        return iv
    }

    /**
     * Encrypts plaintext chunk using AES-GCM-256 with per-chunk IV.
     */
    fun encrypt(plaintext: ByteArray, keys: SessionKeys, chunkIndex: Long, aad: ByteArray? = null): ByteArray {
        val iv = computeChunkIv(keys.baseIv, chunkIndex)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keys.aesKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        if (aad != null) {
            cipher.updateAAD(aad)
        }

        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypts ciphertext chunk using AES-GCM-256, verifying authentication tag and AAD.
     */
    fun decrypt(ciphertextWithTag: ByteArray, keys: SessionKeys, chunkIndex: Long, aad: ByteArray? = null): ByteArray {
        val iv = computeChunkIv(keys.baseIv, chunkIndex)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(keys.aesKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        if (aad != null) {
            cipher.updateAAD(aad)
        }

        return cipher.doFinal(ciphertextWithTag)
    }

    private fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val hmac = Mac.getInstance("HmacSHA256")
        hmac.init(SecretKeySpec(prk, "HmacSHA256"))

        val out = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1.toByte()

        while (offset < length) {
            val buf = ByteBuffer.allocate(t.size + info.size + 1)
            buf.put(t)
            buf.put(info)
            buf.put(counter)
            t = hmac.doFinal(buf.array())
            val toCopy = minOf(t.size, length - offset)
            System.arraycopy(t, 0, out, offset, toCopy)
            offset += toCopy
            counter++
        }
        return out
    }

    private fun ByteArray.stripLeadingZero(): ByteArray {
        return if (this.size == 33 && this[0] == 0.toByte()) {
            this.copyOfRange(1, 33)
        } else {
            this
        }
    }
}
