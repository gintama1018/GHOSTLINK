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

    enum class SecurityLevel(val label: String) {
        UNAUTHENTICATED_ECDH("ECDH Established (Eavesdrop Proof · Unauthenticated Peer)"),
        CONTACT_BOUND_ECDH("ECDH + Physical Touch Binding (<2cm Contact)"),
        SAS_AUTHENTICATED_ECDH("Peer Authenticated (SAS Transcript Verified)")
    }

    /**
     * Builds domain-separated transcript info to prevent cross-protocol and role confusion attacks.
     */
    fun buildTranscriptInfo(
        protocolVersion: Byte = ProtocolConstants.WIRE_VERSION,
        sessionId: Long,
        senderPubKey: ByteArray,
        receiverPubKey: ByteArray,
        role: String = "TRANSCEIVER",
        bulkMode: String = "OPTICAL"
    ): ByteArray {
        val roleBytes = role.toByteArray(Charsets.UTF_8)
        val modeBytes = bulkMode.toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(1 + 8 + senderPubKey.size + receiverPubKey.size + roleBytes.size + modeBytes.size)
        buf.put(protocolVersion)
        buf.putLong(sessionId)
        buf.put(senderPubKey)
        buf.put(receiverPubKey)
        buf.put(roleBytes)
        buf.put(modeBytes)
        return buf.array()
    }

    /**
     * Computes a 6-digit Short Authentication String (SAS) from the transcript of exchanged public keys.
     * Both Sender and Receiver display this 6-digit code.
     * If an active Man-in-the-Middle (MITM) injected different keys, the SAS codes will NOT match.
     */
    fun computeSasCode(pubKeyA: ByteArray, pubKeyB: ByteArray, sessionId: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec("GhostLink-SAS-Verification".toByteArray(Charsets.UTF_8), "HmacSHA256"))
        // Sort keys lexicographically so both Sender and Receiver get the identical SAS regardless of role
        val (first, second) = if (ByteBuffer.wrap(pubKeyA).compareTo(ByteBuffer.wrap(pubKeyB)) <= 0) {
            Pair(pubKeyA, pubKeyB)
        } else {
            Pair(pubKeyB, pubKeyA)
        }
        mac.update(first)
        mac.update(second)
        val sessionBytes = ByteBuffer.allocate(8).putLong(sessionId).array()
        mac.update(sessionBytes)
        val digest = mac.doFinal()
        val num = ((digest[0].toInt() and 0x7F) shl 24) or
                  ((digest[1].toInt() and 0xFF) shl 16) or
                  ((digest[2].toInt() and 0xFF) shl 8) or
                  (digest[3].toInt() and 0xFF)
        val code = num % 1_000_000
        return String.format("%03d-%03d", code / 1000, code % 1000)
    }

    /**
     * Expands raw ECDH shared secret into SessionKeys via HKDF-SHA256 with transcript binding.
     * Optionally mixes in physical contact salt from magnetic induction.
     */
    fun deriveSessionKeys(
        sharedSecret: ByteArray,
        salt: ByteArray? = null,
        transcriptInfo: ByteArray? = null
    ): SessionKeys {
        // 1. HKDF-Extract(salt ?: HKDF_SALT, sharedSecret) -> PRK
        val effectiveSalt = salt ?: HKDF_SALT
        val hmacExtract = Mac.getInstance("HmacSHA256")
        hmacExtract.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
        val prk = hmacExtract.doFinal(sharedSecret)

        val infoBytes = transcriptInfo ?: "GhostLink-v2-Default-Transcript".toByteArray(Charsets.UTF_8)

        // 2. HKDF-Expand(PRK, info || "AES-GCM-Key", 32) -> AES Key
        val aesInfo = ByteBuffer.allocate(infoBytes.size + 12)
            .put(infoBytes)
            .put("-AES-GCM-Key".toByteArray(Charsets.UTF_8))
            .array()
        val aesKey = hkdfExpand(prk, aesInfo, 32)

        // 3. HKDF-Expand(PRK, info || "Base-IV", 12) -> Base IV
        val ivInfo = ByteBuffer.allocate(infoBytes.size + 8)
            .put(infoBytes)
            .put("-Base-IV".toByteArray(Charsets.UTF_8))
            .array()
        val baseIv = hkdfExpand(prk, ivInfo, 12)

        // 4. HKDF-Expand(PRK, info || "Session-ID", 4) -> Session ID (uint32)
        val sidInfo = ByteBuffer.allocate(infoBytes.size + 11)
            .put(infoBytes)
            .put("-Session-ID".toByteArray(Charsets.UTF_8))
            .array()
        val sessionBytes = hkdfExpand(prk, sidInfo, 4)
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
