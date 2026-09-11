package com.ghostlink.zerorf.core

/**
 * Global protocol constants, security thresholds, and bounded memory limits
 * for the GhostLink Zero-RF communication engine.
 */
object ProtocolConstants {
    const val MAGIC_BYTE_1: Byte = 0x47 // 'G'
    const val MAGIC_BYTE_2: Byte = 0x4C // 'L'
    const val PROTOCOL_VERSION: Byte = 0x02
    const val WIRE_VERSION: Byte = 0x02

    // Memory and chunk limits to prevent allocation abuse and OOM attacks
    const val MAX_CHUNK_SIZE = 1024 // Maximum payload bytes per physical chunk
    const val MIN_CHUNK_SIZE = 8   // Minimum payload bytes per physical chunk
    const val MAX_TOTAL_CHUNKS = 50_000L // 50,000 * 1024 B = ~50 MB maximum transfer
    const val MAX_TRANSFER_BYTES = 50L * 1024 * 1024 // 50 MB safety ceiling
    const val MAX_REASSEMBLY_TIMEOUT_MS = 180_000L // 3 minutes session expiration

    // Physical default chunk sizes
    const val OPTICAL_CHUNK_SIZE = 250 // Balanced for QR Version 10 Level-L
    const val ACOUSTIC_CHUNK_SIZE = 32 // Balanced for ultrasonic packet duration
    const val ACOUSTIC_MAX_FILE_SIZE = 128 * 1024 // 128 KB acoustic safety cap

    // Packet Flags
    const val FLAG_DATA: Byte = 0x00
    const val FLAG_HANDSHAKE_INIT: Byte = 0x01
    const val FLAG_HANDSHAKE_REPLY: Byte = 0x02
    const val FLAG_RETRANSMIT_REQ: Byte = 0x04
}
