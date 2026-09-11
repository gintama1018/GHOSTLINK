package com.ghostlink.zerorf.channels.magnetic

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 8-Byte Streamlined Magnetic Handshake Packet.
 * Total: 64 bits.
 * At 120 ms/bit average duration: ~7.7–10 seconds contact handshake.
 */
data class MagneticPacket(
    val preamble: Byte = SYNC_BYTE,
    val verCaps: Byte,
    val saltSeed: ByteArray,
    val crc16: Int
) {
    companion object {
        const val PACKET_SIZE = 8
        const val SYNC_BYTE: Byte = 0xA5.toByte()

        fun create(verCaps: Byte, saltSeed: ByteArray): MagneticPacket {
            require(saltSeed.size == 4) { "Seed must be exactly 4 bytes" }
            val dataForCrc = byteArrayOf(
                SYNC_BYTE,
                verCaps,
                saltSeed[0],
                saltSeed[1],
                saltSeed[2],
                saltSeed[3]
            )
            val crc = computeCrc16(dataForCrc)
            return MagneticPacket(SYNC_BYTE, verCaps, saltSeed, crc)
        }

        fun deserialize(bytes: ByteArray): MagneticPacket {
            require(bytes.size == PACKET_SIZE) { "Magnetic packet must be exactly 8 bytes" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val pre = buf.get()
            if (pre != SYNC_BYTE) {
                throw IllegalArgumentException("Invalid sync byte: 0x${Integer.toHexString(pre.toInt() and 0xFF)}")
            }
            val vc = buf.get()
            val seed = ByteArray(4)
            buf.get(seed)
            val rxCrc = buf.short.toInt() and 0xFFFF
            val calcCrc = computeCrc16(byteArrayOf(pre, vc, seed[0], seed[1], seed[2], seed[3]))
            if (rxCrc != calcCrc) {
                throw SecurityException("Magnetic CRC-16 mismatch: received $rxCrc, computed $calcCrc")
            }
            return MagneticPacket(pre, vc, seed, rxCrc)
        }

        fun computeCrc16(bytes: ByteArray): Int {
            var crc = 0xFFFF
            for (b in bytes) {
                crc = crc xor ((b.toInt() and 0xFF) shl 8)
                for (i in 0 until 8) {
                    crc = if ((crc and 0x8000) != 0) {
                        (crc shl 1) xor 0x1021
                    } else {
                        crc shl 1
                    }
                    crc = crc and 0xFFFF
                }
            }
            return crc
        }
    }

    fun serialize(): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
        buf.put(preamble)
        buf.put(verCaps)
        buf.put(saltSeed)
        buf.putShort((crc16 and 0xFFFF).toShort())
        return buf.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MagneticPacket
        if (preamble != other.preamble) return false
        if (verCaps != other.verCaps) return false
        if (!saltSeed.contentEquals(other.saltSeed)) return false
        if (crc16 != other.crc16) return false
        return true
    }

    override fun hashCode(): Int {
        var result = preamble.toInt()
        result = 31 * result + verCaps.toInt()
        result = 31 * result + saltSeed.contentHashCode()
        result = 31 * result + crc16
        return result
    }
}
