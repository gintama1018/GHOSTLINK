package com.ghostlink.zerorf.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * GhostLink Wire Framing Specification v2 (24-Byte Protected Header).
 *
 * Wire Layout (Big-Endian):
 * [ 2 bytes: magic          ('G', 'L' = 0x47, 0x4C) ]
 * [ 1 byte : version        (0x02) ]
 * [ 1 byte : flags          (0x00=Data, 0x01=Handshake, etc.) ]
 * [ 4 bytes: session_id     (uint32) ]
 * [ 4 bytes: chunk_index    (uint32) ]
 * [ 4 bytes: total_chunks   (uint32) ]
 * [ 2 bytes: payload_length (uint16 <= 1024) ]
 * [ 2 bytes: header_crc16   (CRC16-CCITT over preceding 18 bytes) ]
 * [ 4 bytes: payload_crc32  (uint32 CRC32 over payload) ]
 * [ L bytes: payload bytes ]
 */
data class Packet(
    val sessionId: Long,      // uint32 session identifier
    val chunkIndex: Long,     // uint32 chunk index (0-based)
    val totalChunks: Long,    // uint32 total chunks in session
    val flags: Byte = ProtocolConstants.FLAG_DATA,
    val payloadLength: Int,   // uint16 payload length in bytes
    val checksum: Long,       // uint32 CRC32 of payload
    val payload: ByteArray
) {
    companion object {
        const val HEADER_SIZE = 24

        fun computeCrc16(bytes: ByteArray, offset: Int, length: Int): Int {
            var crc = 0xFFFF
            for (i in offset until (offset + length)) {
                crc = crc xor ((bytes[i].toInt() and 0xFF) shl 8)
                for (j in 0 until 8) {
                    crc = if ((crc and 0x8000) != 0) {
                        (crc shl 1) xor 0x1021
                    } else {
                        crc shl 1
                    }
                }
            }
            return crc and 0xFFFF
        }

        fun create(
            chunkIndex: Long,
            totalChunks: Long,
            payload: ByteArray,
            sessionId: Long = 1L,
            flags: Byte = ProtocolConstants.FLAG_DATA
        ): Packet {
            require(payload.size <= ProtocolConstants.MAX_CHUNK_SIZE) {
                "Payload size (${payload.size} B) exceeds MAX_CHUNK_SIZE (${ProtocolConstants.MAX_CHUNK_SIZE} B)"
            }
            require(totalChunks in 1..ProtocolConstants.MAX_TOTAL_CHUNKS) {
                "Total chunks ($totalChunks) outside valid range [1..${ProtocolConstants.MAX_TOTAL_CHUNKS}]"
            }
            require(chunkIndex in 0 until totalChunks) {
                "Chunk index ($chunkIndex) outside valid range [0..${totalChunks - 1}]"
            }

            val crc = CRC32()
            crc.update(payload)
            return Packet(
                sessionId = sessionId and 0xFFFFFFFFL,
                chunkIndex = chunkIndex and 0xFFFFFFFFL,
                totalChunks = totalChunks and 0xFFFFFFFFL,
                flags = flags,
                payloadLength = payload.size,
                checksum = crc.value,
                payload = payload
            )
        }

        fun deserialize(bytes: ByteArray): Packet {
            require(bytes.size >= HEADER_SIZE) {
                "Packet smaller than ${HEADER_SIZE}-byte header: ${bytes.size}"
            }

            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val magic1 = buf.get()
            val magic2 = buf.get()
            require(magic1 == ProtocolConstants.MAGIC_BYTE_1 && magic2 == ProtocolConstants.MAGIC_BYTE_2) {
                "Invalid protocol magic: 0x%02X 0x%02X".format(magic1, magic2)
            }

            val version = buf.get()
            require(version == ProtocolConstants.PROTOCOL_VERSION) {
                "Unsupported protocol version: $version (expected ${ProtocolConstants.PROTOCOL_VERSION})"
            }

            val flags = buf.get()
            val sessionId = buf.int.toLong() and 0xFFFFFFFFL
            val chunkIndex = buf.int.toLong() and 0xFFFFFFFFL
            val totalChunks = buf.int.toLong() and 0xFFFFFFFFL
            val payloadLength = buf.short.toInt() and 0xFFFF
            val headerCrc16 = buf.short.toInt() and 0xFFFF

            // 1. Verify Header Integrity (Pre-allocation gate)
            val computedHeaderCrc = computeCrc16(bytes, 0, 18)
            if (computedHeaderCrc != headerCrc16) {
                throw SecurityException(
                    "Header CRC16 mismatch! Header: 0x%04X, Computed: 0x%04X".format(headerCrc16, computedHeaderCrc)
                )
            }

            // 2. Bound checks
            require(totalChunks in 1..ProtocolConstants.MAX_TOTAL_CHUNKS) {
                "Malformed totalChunks: $totalChunks (exceeds max ${ProtocolConstants.MAX_TOTAL_CHUNKS})"
            }
            require(chunkIndex < totalChunks) {
                "Malformed chunkIndex ($chunkIndex) >= totalChunks ($totalChunks)"
            }
            require(payloadLength <= ProtocolConstants.MAX_CHUNK_SIZE) {
                "Malformed payloadLength: $payloadLength (exceeds max ${ProtocolConstants.MAX_CHUNK_SIZE})"
            }
            require(bytes.size == HEADER_SIZE + payloadLength) {
                "Packet length mismatch: declared ${HEADER_SIZE + payloadLength}, actual ${bytes.size}"
            }

            val payloadChecksum = buf.int.toLong() and 0xFFFFFFFFL
            val payload = ByteArray(payloadLength)
            buf.get(payload)

            // 3. Verify Payload Integrity
            val crc = CRC32()
            crc.update(payload)
            if (crc.value != payloadChecksum) {
                throw SecurityException(
                    "Payload CRC32 mismatch! Header: $payloadChecksum, Computed: ${crc.value}"
                )
            }

            return Packet(sessionId, chunkIndex, totalChunks, flags, payloadLength, payloadChecksum, payload)
        }
    }

    fun serialize(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.put(ProtocolConstants.MAGIC_BYTE_1)
        buf.put(ProtocolConstants.MAGIC_BYTE_2)
        buf.put(ProtocolConstants.PROTOCOL_VERSION)
        buf.put(flags)
        buf.putInt((sessionId and 0xFFFFFFFFL).toInt())
        buf.putInt((chunkIndex and 0xFFFFFFFFL).toInt())
        buf.putInt((totalChunks and 0xFFFFFFFFL).toInt())
        buf.putShort((payloadLength and 0xFFFF).toShort())

        // Compute and put header CRC16 over the first 18 bytes
        val headerBytes = buf.array()
        val headerCrc = computeCrc16(headerBytes, 0, 18)
        buf.putShort((headerCrc and 0xFFFF).toShort())

        buf.putInt((checksum and 0xFFFFFFFFL).toInt())
        buf.put(payload)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Packet
        if (sessionId != other.sessionId) return false
        if (chunkIndex != other.chunkIndex) return false
        if (totalChunks != other.totalChunks) return false
        if (flags != other.flags) return false
        if (payloadLength != other.payloadLength) return false
        if (checksum != other.checksum) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + chunkIndex.hashCode()
        result = 31 * result + totalChunks.hashCode()
        result = 31 * result + flags.toInt()
        result = 31 * result + payloadLength
        result = 31 * result + checksum.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
