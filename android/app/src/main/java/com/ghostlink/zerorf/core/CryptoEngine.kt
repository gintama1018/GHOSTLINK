package com.ghostlink.zerorf.core

import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * GhostLink Cryptographic Engine (PRD F7 Mandatory Gate).
 *
 * Full-file AES-GCM-256 authenticated encryption using a session key
 * derived via HKDF-SHA256 from the physical contact magnetic handshake seed.
 * Plaintext never enters the chunker, QR generator, or ultrasonic modulator.
 */
object CryptoEngine {
    private const val INFO_LABEL = "GhostLink-Session-v1"
    private val FIXED_SALT = "GhostLinkSalt2026".toByteArray(Charsets.UTF_8)
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH_BITS = 128

    /**
     * Derives a 256-bit AES key from the 4-byte ephemeral magnetic seed.
     */
    fun deriveSessionKey(seed4Bytes: ByteArray): ByteArray {
        require(seed4Bytes.size == 4) { "Handshake seed must be exactly 4 bytes" }

        // HKDF-Extract(salt, seed)
        val hmacExtract = Mac.getInstance("HmacSHA256")
        hmacExtract.init(SecretKeySpec(FIXED_SALT, "HmacSHA256"))
        val prk = hmacExtract.doFinal(seed4Bytes)

        // HKDF-Expand(prk, info, 32)
        val hmacExpand = Mac.getInstance("HmacSHA256")
        hmacExpand.init(SecretKeySpec(prk, "HmacSHA256"))
        val infoBytes = INFO_LABEL.toByteArray(Charsets.UTF_8)
        val infoBuf = ByteBuffer.allocate(infoBytes.size + 1)
        infoBuf.put(infoBytes)
        infoBuf.put(0x01.toByte()) // Counter = 1

        val okm = hmacExpand.doFinal(infoBuf.array())
        return okm.copyOf(32) // 256-bit AES key
    }

    /**
     * Encrypts plaintext bytes using AES-GCM-256.
     * Output format: [12 bytes IV] + [ciphertext with appended 16-byte authentication tag]
     */
    fun encrypt(plaintext: ByteArray, sessionKey: ByteArray): ByteArray {
        require(sessionKey.size == 32) { "Session key must be 32 bytes (256-bit)" }

        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(sessionKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val ciphertextWithTag = cipher.doFinal(plaintext)

        val out = ByteBuffer.allocate(iv.size + ciphertextWithTag.size)
        out.put(iv)
        out.put(ciphertextWithTag)
        return out.array()
    }

    /**
     * Decrypts AES-GCM-256 payload. Validates the 128-bit authentication tag.
     * Throws SecurityException if ciphertext is tampered or authentication fails.
     */
    fun decrypt(ivAndCiphertext: ByteArray, sessionKey: ByteArray): ByteArray {
        require(sessionKey.size == 32) { "Session key must be 32 bytes (256-bit)" }
        require(ivAndCiphertext.size >= GCM_IV_LENGTH + 16) {
            "Ciphertext shorter than minimum IV + GCM tag length"
        }

        val buf = ByteBuffer.wrap(ivAndCiphertext)
        val iv = ByteArray(GCM_IV_LENGTH)
        buf.get(iv)

        val ciphertext = ByteArray(ivAndCiphertext.size - GCM_IV_LENGTH)
        buf.get(ciphertext)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(sessionKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(ciphertext)
    }
}
