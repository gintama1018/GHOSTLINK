package com.ghostlink.zerorf.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * GhostLink 14-Byte Binary Wire Framing Specification.
 *
 * Wire Layout (Big-Endian):
 * [ 4 bytes: chunk_index   (uint32) ]
 * [ 4 bytes: total_chunks  (uint32) ]
 * [ 2 bytes: payload_length (uint16) ]
 * [ 4 bytes: chunk_checksum (uint32 CRC32) ]
 * [ L bytes: payload bytes ]
 */
data class Packet(
    val chunkIndex: Long,     // uint32 represented as Long
    val totalChunks: Long,    // uint32 represented as Long
    val payloadLength: Int,   // uint16 represented as Int
    val checksum: Long,       // uint32 CRC32 represented as Long
    val payload: ByteArray
) {
    companion object {
        const val HEADER_SIZE = 14

        fun create(chunkIndex: Long, totalChunks: Long, payload: ByteArray): Packet {
            val crc = CRC32()
            crc.update(payload)
            return Packet(
                chunkIndex = chunkIndex,
                totalChunks = totalChunks,
                payloadLength = payload.size,
                checksum = crc.value,
                payload = payload
            )
        }

        fun deserialize(bytes: ByteArray): Packet {
            require(bytes.size >= HEADER_SIZE) { "Packet smaller than 14-byte header: ${bytes.size}" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val chunkIndex = buf.int.toLong() and 0xFFFFFFFFL
            val totalChunks = buf.int.toLong() and 0xFFFFFFFFL
            val payloadLength = buf.short.toInt() and 0xFFFF
            val checksum = buf.int.toLong() and 0xFFFFFFFFL

            require(bytes.size == HEADER_SIZE + payloadLength) {
                "Payload length mismatch. Header: $payloadLength, Total: ${bytes.size}"
            }

            val payload = ByteArray(payloadLength)
            buf.get(payload)

            val crc = CRC32()
            crc.update(payload)
            if (crc.value != checksum) {
                throw SecurityException("CRC32 mismatch! Header: $checksum, Computed: ${crc.value}")
            }

            return Packet(chunkIndex, totalChunks, payloadLength, checksum, payload)
        }
    }

    fun serialize(): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_SIZE + payload.size).order(ByteOrder.BIG_ENDIAN)
        buf.putInt((chunkIndex and 0xFFFFFFFFL).toInt())
        buf.putInt((totalChunks and 0xFFFFFFFFL).toInt())
        buf.putShort((payloadLength and 0xFFFF).toShort())
        buf.putInt((checksum and 0xFFFFFFFFL).toInt())
        buf.put(payload)
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as Packet
        if (chunkIndex != other.chunkIndex) return false
        if (totalChunks != other.totalChunks) return false
        if (payloadLength != other.payloadLength) return false
        if (checksum != other.checksum) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = chunkIndex.hashCode()
        result = 31 * result + totalChunks.hashCode()
        result = 31 * result + payloadLength
        result = 31 * result + checksum.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
